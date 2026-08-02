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

function ConvertTo-QuotedArgument
{
    param([string] $Value)

    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

function Start-Mcp
{
    param(
        [Parameter(Mandatory)] [string] $WorkingDirectory,
        [string] $WorkspaceOverride
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Exe
    $startInfo.WorkingDirectory = $WorkingDirectory
    if (-not [string]::IsNullOrWhiteSpace($WorkspaceOverride))
    {
        $startInfo.Arguments = "--workspace-root $(ConvertTo-QuotedArgument $WorkspaceOverride)"
    }
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
        "vibris_get_config",
        "vibris_list_presets",
        "vibris_configure",
        "vibris_get_status",
        "vibris_profile",
        "vibris_run_recipe",
        "vibris_run_actions"
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
        [string] $Name
    )

    $request = [ordered]@{
        jsonrpc = "2.0"
        id = $Id
        method = "tools/call"
        params = @{ name = $Name; arguments = @{} }
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

function Assert-UnconfiguredBinding
{
    param(
        [object] $Owned,
        [string] $Id,
        [string] $ExpectedRoot
    )

    $payload = Invoke-Tool -Owned $Owned -Id $Id -Name "vibris_get_config"
    if ($payload.configured -ne $false -or $null -ne $payload.config)
    {
        throw "MCP process $($Owned.Process.Id) unexpectedly loaded a process-local scene."
    }
    $actualRoot = [System.IO.Path]::GetFullPath([string] $payload.worktree_root)
    $canonicalExpected = [System.IO.Path]::GetFullPath($ExpectedRoot)
    if (-not [System.StringComparer]::OrdinalIgnoreCase.Equals($actualRoot, $canonicalExpected))
    {
        throw "MCP process $($Owned.Process.Id) bound '$actualRoot', expected '$canonicalExpected'."
    }
    $parsed = [guid]::Empty
    if (-not [guid]::TryParse([string] $payload.workspace_id, [ref] $parsed))
    {
        throw "MCP process $($Owned.Process.Id) returned a non-UUID workspace_id."
    }
    return [string] $payload.workspace_id
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

    $identityPath = Join-Path $Repository ".codex\vibris-workspace.json"
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
    $oversizedLine = $fixture | ConvertTo-Json -Compress -Depth 16
    $oversizedBytes = [System.Text.Encoding]::UTF8.GetByteCount($oversizedLine)
    $argumentNames = @($fixture.params.arguments.PSObject.Properties.Name | Sort-Object)
    $expectedNames = @(
        "camera_preset_id", "default_warmup_frames", "dimension_id",
        "fov", "save_id", "time_preset_id"
    )
    if ($fixture.jsonrpc -ne "2.0" -or $fixture.id -ne "oversize-config" -or
        $fixture.method -ne "tools/call" -or $fixture.params.name -ne "vibris_configure" -or
        -not ($fixture.params.arguments.save_id -is [string]) -or
        [string]::Join(",", $argumentNames) -cne [string]::Join(",", $expectedNames) -or
        $oversizedBytes -le 65536 -or $oversizedBytes -ge 1048576)
    {
        throw "Fixture must be schema-valid, preserve id oversize-config, and be between 64 KiB and 1 MiB."
    }

    [void] (New-Item -ItemType Directory -Path $criterionRoot)
    $tempCreated = $true
    Initialize-GitRepository -Path $repoA
    Initialize-GitRepository -Path $repoB
    $nestedA1 = Join-Path $repoA "nested\task-one"
    $nestedA2 = Join-Path $repoA "nested\task-two"
    $nestedB = Join-Path $repoB "nested\task-independent"
    $invalidRoot = Join-Path $criterionRoot "not-a-worktree"
    foreach ($directory in @($nestedA1, $nestedA2, $nestedB, $invalidRoot))
    {
        [void] (New-Item -ItemType Directory -Path $directory -Force)
    }

    $first = Start-Mcp -WorkingDirectory $nestedA1
    $second = Start-Mcp -WorkingDirectory $nestedA2
    Invoke-Initialize -Owned $first -Id "same-a-1-init"
    Invoke-Initialize -Owned $second -Id "same-a-2-init"
    Assert-ToolList -Owned $first -Id "same-a-1-tools"
    Assert-ToolList -Owned $second -Id "same-a-2-tools"
    $idA1 = Assert-UnconfiguredBinding -Owned $first -Id "same-a-1-config" -ExpectedRoot $repoA
    $idA2 = Assert-UnconfiguredBinding -Owned $second -Id "same-a-2-config" -ExpectedRoot $repoA
    if ($idA1 -cne $idA2)
    {
        throw "Same-worktree processes returned different workspace IDs."
    }
    if (-not [string]::IsNullOrEmpty($first.Arguments) -or -not [string]::IsNullOrEmpty($second.Arguments))
    {
        throw "Normal same-worktree instances unexpectedly received command-line arguments."
    }
    $identityA = Get-IdentityBytes -Repository $repoA

    $independent = Start-Mcp -WorkingDirectory $nestedB
    Invoke-Initialize -Owned $independent -Id "repo-b-init"
    Assert-ToolList -Owned $independent -Id "repo-b-tools"
    $idB = Assert-UnconfiguredBinding -Owned $independent -Id "repo-b-config" -ExpectedRoot $repoB
    if ($idB -ceq $idA1)
    {
        throw "Independent repositories returned the same workspace ID."
    }
    if (-not [string]::IsNullOrEmpty($independent.Arguments))
    {
        throw "Normal independent-repository instance unexpectedly received command-line arguments."
    }
    $identityB = Get-IdentityBytes -Repository $repoB

    $override = Start-Mcp -WorkingDirectory $nestedB -WorkspaceOverride $repoA
    Invoke-Initialize -Owned $override -Id "override-init"
    Assert-ToolList -Owned $override -Id "override-tools"
    $overrideId = Assert-UnconfiguredBinding -Owned $override -Id "override-config" -ExpectedRoot $repoA
    if ($overrideId -cne $idA1)
    {
        throw "Explicit workspace override did not take precedence over cwd."
    }

    $invalid = Start-Mcp -WorkingDirectory $nestedB -WorkspaceOverride $invalidRoot
    $invalidStdout = $invalid.Process.StandardOutput.ReadToEndAsync()
    if (-not $invalid.Process.WaitForExit($TimeoutSeconds * 1000))
    {
        throw "Invalid explicit root did not fail within $TimeoutSeconds seconds."
    }
    $invalid.Process.WaitForExit()
    $invalid.Closed = $true
    if ($invalid.Process.ExitCode -eq 0 -or -not [string]::IsNullOrWhiteSpace($invalidStdout.Result) -or
        $invalid.Stderr.Result -notmatch "INVALID_WORKTREE" -or $invalid.Stderr.Result.Length -gt 4096)
    {
        throw "Invalid explicit root did not produce a bounded nonzero INVALID_WORKTREE failure."
    }
    Assert-IdentityBytes -Repository $repoA -Expected $identityA -Context "invalid explicit root"
    Assert-IdentityBytes -Repository $repoB -Expected $identityB -Context "invalid explicit root"

    Send-Request -Owned $first -Line $oversizedLine
    $oversizedResponse = Read-Response -Owned $first
    Assert-Response -Response $oversizedResponse -Id "oversize-config"
    if (-not ($oversizedResponse.Json.PSObject.Properties.Name -contains "error") -or
        $oversizedResponse.Json.error.data.code -ne "REQUEST_TOO_LARGE" -or
        $oversizedResponse.Json.error.data.retryable -ne $false -or
        [System.Text.Encoding]::UTF8.GetByteCount($oversizedResponse.Line) -gt 4096)
    {
        throw "Oversized configure request did not return a bounded structured REQUEST_TOO_LARGE error."
    }
    Assert-IdentityBytes -Repository $repoA -Expected $identityA -Context "oversized input"
    Assert-IdentityBytes -Repository $repoB -Expected $identityB -Context "oversized input"
    if ((Assert-UnconfiguredBinding -Owned $first -Id "same-a-1-still-responsive" -ExpectedRoot $repoA) -cne $idA1 -or
        (Assert-UnconfiguredBinding -Owned $second -Id "same-a-2-still-responsive" -ExpectedRoot $repoA) -cne $idA2)
    {
        throw "Same-worktree processes changed identity after bounded failures."
    }

    foreach ($owned in @($override, $independent, $second, $first))
    {
        Close-Mcp -Owned $owned
    }
    Write-Output ("PASS same_root_concurrent=true same_id=$idA1 independent_id=$idB " +
        "override_precedence=true invalid_root_bounded=true oversized_bounded=true identity_preserved=true")
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
