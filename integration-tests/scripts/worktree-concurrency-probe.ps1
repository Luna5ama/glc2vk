[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Exe,
    [Parameter(Mandatory)] [string] $WorkspaceRoot,
    [Parameter(Mandatory)] [string] $MalformedConfig,
    [int] $TimeoutSeconds = 15
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ownedProcesses = [System.Collections.Generic.List[object]]::new()
$ownedPids = [System.Collections.Generic.List[int]]::new()
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
. (Join-Path $repoRoot "tools\git-process.ps1")
$criterionRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ".omo\tmp\ulw-v1-g002-c002"))
$repoA = [System.IO.Path]::GetFullPath($WorkspaceRoot)
$expectedRepoA = [System.IO.Path]::GetFullPath((Join-Path $criterionRoot "worktree"))
$repoB = [System.IO.Path]::GetFullPath((Join-Path $criterionRoot "independent-repo"))
$tempCreated = $false
$forcedStops = 0

function Start-Mcp
{
    param([Parameter(Mandatory)] [string] $WorkingDirectory)

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Exe
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void] $process.Start()
    $owned = [pscustomobject]@{
        Process = $process
        StartTimeUtc = $process.StartTime.ToUniversalTime()
        Stderr = $process.StandardError.ReadToEndAsync()
        WorkingDirectory = $WorkingDirectory
        Arguments = $startInfo.Arguments
        Closed = $false
    }
    $ownedProcesses.Add($owned)
    $ownedPids.Add($process.Id)
    return $owned
}

function Send-Request
{
    param([object] $Owned, [string] $Line)

    $Owned.Process.StandardInput.WriteLine($Line)
    $Owned.Process.StandardInput.Flush()
}

function Read-Response
{
    param([object] $Owned)

    $task = $Owned.Process.StandardOutput.ReadLineAsync()
    if (-not $task.Wait($TimeoutSeconds * 1000))
    {
        throw "MCP process $($Owned.Process.Id) did not return a protocol line within $TimeoutSeconds seconds."
    }
    if ($null -eq $task.Result)
    {
        throw "MCP process $($Owned.Process.Id) closed stdout before returning a protocol line."
    }
    try
    {
        $json = $task.Result | ConvertFrom-Json
    }
    catch
    {
        throw "MCP stdout was not JSON-RPC: $($task.Result)"
    }
    return [pscustomobject]@{ Line = $task.Result; Json = $json }
}

function Assert-Response
{
    param([object] $Response, [string] $Id)

    if ($Response.Json.jsonrpc -ne "2.0" -or $Response.Json.id -ne $Id)
    {
        throw "Expected JSON-RPC response '$Id', got: $($Response.Line)"
    }
}

function Close-Mcp
{
    param([object] $Owned)

    if ($Owned.Closed)
    {
        return
    }
    $Owned.Process.StandardInput.Close()
    if (-not $Owned.Process.WaitForExit($TimeoutSeconds * 1000))
    {
        throw "MCP process $($Owned.Process.Id) did not exit after stdin closed."
    }
    $Owned.Process.WaitForExit()
    if ($Owned.Process.ExitCode -ne 0)
    {
        throw "MCP process $($Owned.Process.Id) exited $($Owned.Process.ExitCode): $($Owned.Stderr.Result)"
    }
    $Owned.Closed = $true
}

function Invoke-Initialize
{
    param([object] $Owned, [string] $Id)

    $request = [ordered]@{
        jsonrpc = "2.0"
        id = $Id
        method = "initialize"
        params = @{
            protocolVersion = "2024-11-05"
            capabilities = @{}
            clientInfo = @{ name = "vibris-worktree-concurrency-probe"; version = "1" }
        }
    } | ConvertTo-Json -Compress -Depth 8
    Send-Request -Owned $Owned -Line $request
    $response = Read-Response -Owned $Owned
    Assert-Response -Response $response -Id $Id
    if ($response.Json.PSObject.Properties.Name -contains "error" -or
        -not ($response.Json.PSObject.Properties.Name -contains "result") -or
        $response.Json.result.protocolVersion -cne "2024-11-05")
    {
        throw "Initialize failed: $($response.Line)"
    }
    $initialized = [ordered]@{
        jsonrpc = "2.0"
        method = "notifications/initialized"
        params = @{}
    } | ConvertTo-Json -Compress
    Send-Request -Owned $Owned -Line $initialized
}

function Assert-ToolList
{
    param([object] $Owned, [string] $Id)

    $request = [ordered]@{
        jsonrpc = "2.0"
        id = $Id
        method = "tools/list"
        params = @{}
    } | ConvertTo-Json -Compress
    Send-Request -Owned $Owned -Line $request
    $response = Read-Response -Owned $Owned
    Assert-Response -Response $response -Id $Id
    $actual = @($response.Json.result.tools | ForEach-Object { $_.name })
    $expected = @(
        "vibris_list_presets",
        "vibris_get_status",
        "vibris_run_recipe",
        "vibris_run_actions",
        "vibris_run_matrix"
    )
    if ([string]::Join("`n", $actual) -cne [string]::Join("`n", $expected))
    {
        throw "MCP process $($Owned.Process.Id) returned an unexpected tool list: $($actual -join ',')"
    }
}

function Invoke-Tool
{
    param(
        [object] $Owned,
        [string] $Id,
        [string] $Name,
        [object] $Arguments = @{}
    )

    $request = [ordered]@{
        jsonrpc = "2.0"
        id = $Id
        method = "tools/call"
        params = @{ name = $Name; arguments = $Arguments }
    } | ConvertTo-Json -Compress -Depth 8
    Send-Request -Owned $Owned -Line $request
    $response = Read-Response -Owned $Owned
    Assert-Response -Response $response -Id $Id
    if ($response.Json.PSObject.Properties.Name -contains "error")
    {
        throw "Tool $Name failed: $($response.Line)"
    }
    $content = @($response.Json.result.content)
    if ($content.Count -ne 1 -or $content[0].type -ne "text")
    {
        throw "Tool $Name must return exactly one text item."
    }
    return $content[0].text | ConvertFrom-Json
}

function Assert-RequestBinding
{
    param(
        [object] $Owned,
        [string] $Id,
        [string] $ExpectedRoot
    )

    $payload = Invoke-Tool -Owned $Owned -Id $Id -Name "vibris_get_status" `
        -Arguments @{ worktree_root = [System.IO.Path]::GetFullPath($ExpectedRoot) }
    $binding = if ($null -ne $payload.PSObject.Properties["success"] -and $payload.success -eq $false) {
        $payload.error.details
    } else {
        $payload
    }
    $actualRoot = [System.IO.Path]::GetFullPath([string] $binding.worktree_root)
    $canonicalExpected = [System.IO.Path]::GetFullPath($ExpectedRoot)
    if (-not [System.StringComparer]::OrdinalIgnoreCase.Equals($actualRoot, $canonicalExpected))
    {
        throw "MCP process $($Owned.Process.Id) bound '$actualRoot', expected '$canonicalExpected'."
    }
    $parsed = [guid]::Empty
    if (-not [guid]::TryParse([string] $binding.workspace_id, [ref] $parsed))
    {
        throw "MCP process $($Owned.Process.Id) returned a non-UUID workspace_id."
    }
    return [string] $binding.workspace_id
}

function Initialize-GitRepository
{
    param([string] $Path)

    [void] (New-Item -ItemType Directory -Path $Path -Force)
    [void] (Invoke-TrustedGitText -Root $Path -Arguments @("init", "--quiet") `
        -Label "Git fixture initialization")
}

function Get-IdentityBytes
{
    param([string] $Repository)

    $identityPath = Join-Path $Repository ".vibris\workspace.json"
    if (-not (Test-Path -LiteralPath $identityPath -PathType Leaf))
    {
        throw "Missing durable identity: $identityPath"
    }
    return [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($identityPath))
}

function Assert-IdentityBytes
{
    param(
        [string] $Repository,
        [string] $Expected,
        [string] $Context
    )

    if ((Get-IdentityBytes -Repository $Repository) -cne $Expected)
    {
        throw "Workspace identity bytes changed after $Context in $Repository."
    }
}

try
{
    if (-not [System.StringComparer]::OrdinalIgnoreCase.Equals($repoA, $expectedRepoA))
    {
        throw "WorkspaceRoot must resolve to the scoped criterion worktree: $expectedRepoA"
    }
    if ($TimeoutSeconds -lt 1 -or $TimeoutSeconds -gt 60)
    {
        throw "TimeoutSeconds must be between 1 and 60."
    }
    if (-not (Test-Path -LiteralPath $Exe -PathType Leaf))
    {
        throw "Missing native MCP executable: $Exe"
    }
    if (-not (Test-Path -LiteralPath $MalformedConfig -PathType Leaf))
    {
        throw "Missing oversized config fixture: $MalformedConfig"
    }
    if (Test-Path -LiteralPath $criterionRoot)
    {
        throw "Scoped criterion temp path already exists: $criterionRoot"
    }

    $fixture = Get-Content -LiteralPath $MalformedConfig -Raw | ConvertFrom-Json
    $oversizedRequest = [ordered] @{
        jsonrpc = "2.0"
        id = "oversize-config"
        method = "tools/call"
        params = [ordered] @{
            name = "vibris_run_recipe"
            arguments = [ordered] @{
                worktree_root = $repoA
                preset_id = "default"
                recipe = "profile"
                source = [ordered] @{ kind = "workspace" }
                config = [ordered] @{ OVERSIZE = [string] $fixture.params.arguments.save_id }
                frames = 1
            }
        }
    }
    $oversizedLine = $oversizedRequest | ConvertTo-Json -Compress -Depth 16
    $oversizedBytes = [System.Text.Encoding]::UTF8.GetByteCount($oversizedLine)
    if ($fixture.jsonrpc -ne "2.0" -or $fixture.id -ne "oversize-config" -or
        -not ($fixture.params.arguments.save_id -is [string]) -or
        $oversizedBytes -le 65536 -or $oversizedBytes -ge 1048576)
    {
        throw "Fixture must preserve the oversize payload and produce a request between 64 KiB and 1 MiB."
    }

    [void] (New-Item -ItemType Directory -Path $criterionRoot)
    $tempCreated = $true
    Initialize-GitRepository -Path $repoA
    Initialize-GitRepository -Path $repoB
    $shared = Start-Mcp -WorkingDirectory $criterionRoot
    Invoke-Initialize -Owned $shared -Id "shared-init"
    Assert-ToolList -Owned $shared -Id "shared-tools"
    $idA1 = Assert-RequestBinding -Owned $shared -Id "repo-a-first" -ExpectedRoot $repoA
    $identityA = Get-IdentityBytes -Repository $repoA
    $idB = Assert-RequestBinding -Owned $shared -Id "repo-b" -ExpectedRoot $repoB
    if ($idB -ceq $idA1)
    {
        throw "Independent repositories returned the same workspace ID."
    }
    $idA2 = Assert-RequestBinding -Owned $shared -Id "repo-a-second" -ExpectedRoot $repoA
    if ($idA2 -cne $idA1)
    {
        throw "Returning to the first worktree changed its workspace ID."
    }
    $identityB = Get-IdentityBytes -Repository $repoB

    Send-Request -Owned $shared -Line $oversizedLine
    $oversizedResponse = Read-Response -Owned $shared
    Assert-Response -Response $oversizedResponse -Id "oversize-config"
    if (-not ($oversizedResponse.Json.PSObject.Properties.Name -contains "error") -or
        $oversizedResponse.Json.error.code -ne -32602 -or
        [System.Text.Encoding]::UTF8.GetByteCount($oversizedResponse.Line) -gt 4096)
    {
        throw "Oversized request-scoped shader config did not return a bounded invalid-arguments error."
    }
    Assert-IdentityBytes -Repository $repoA -Expected $identityA -Context "oversized input"
    Assert-IdentityBytes -Repository $repoB -Expected $identityB -Context "oversized input"
    if ((Assert-RequestBinding -Owned $shared -Id "repo-a-still-responsive" -ExpectedRoot $repoA) -cne $idA1 -or
        (Assert-RequestBinding -Owned $shared -Id "repo-b-still-responsive" -ExpectedRoot $repoB) -cne $idB)
    {
        throw "Request-scoped worktree routing changed identity after bounded failures."
    }

    Close-Mcp -Owned $shared
    Write-Output ("PASS one_mcp_multiworktree=true first_id=$idA1 independent_id=$idB " +
        "oversized_bounded=true identity_preserved=true")
}
finally
{
    foreach ($owned in @($ownedProcesses))
    {
        if (-not $owned.Process.HasExited)
        {
            if ($owned.Process.StartTime.ToUniversalTime() -ne $owned.StartTimeUtc)
            {
                throw "Owned MCP process identity changed before cleanup: $($owned.Process.Id)"
            }
            $owned.Process.Kill($true)
            [void] $owned.Process.WaitForExit(2000)
            $forcedStops++
        }
        $owned.Process.Dispose()
    }
    if ($tempCreated -and (Test-Path -LiteralPath $criterionRoot))
    {
        $resolvedCriterion = [System.IO.Path]::GetFullPath($criterionRoot)
        $expectedCriterion = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ".omo\tmp\ulw-v1-g002-c002"))
        if (-not [System.StringComparer]::OrdinalIgnoreCase.Equals($resolvedCriterion, $expectedCriterion))
        {
            throw "Refusing to clean unexpected criterion root: $resolvedCriterion"
        }
        Remove-Item -LiteralPath $resolvedCriterion -Recurse -Force
    }
    Write-Output ("CLEANUP owned_pids=$($ownedPids -join ',') forced_stops=$forcedStops " +
        "temp_removed=$($tempCreated -and -not (Test-Path -LiteralPath $criterionRoot))")
}
