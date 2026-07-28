[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Exe,
    [Parameter(Mandatory)] [string] $WorkspaceRoot,
    [Parameter(Mandatory)] [string] $MalformedConfig,
    [int] $TimeoutSeconds = 10
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ownedProcesses = [System.Collections.Generic.List[object]]::new()
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$criterionRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ".omo\tmp\ulw-v1-g002-c002"))
$expectedWorkspace = [System.IO.Path]::GetFullPath((Join-Path $criterionRoot "worktree"))
$resolvedWorkspace = [System.IO.Path]::GetFullPath($WorkspaceRoot)
$tempCreated = $false
$mutexReacquired = $false

function Start-Mcp
{
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Exe
    $startInfo.Arguments = "--workspace-root `"$resolvedWorkspace`""
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
        Stderr = $process.StandardError.ReadToEndAsync()
    }
    $ownedProcesses.Add($owned)
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
}

function Invoke-Initialize
{
    param([object] $Owned, [string] $Id)

    $request = [ordered]@{
        jsonrpc = "2.0"
        id = $Id
        method = "initialize"
        params = @{}
    } | ConvertTo-Json -Compress
    Send-Request -Owned $Owned -Line $request
    $response = Read-Response -Owned $Owned
    Assert-Response -Response $response -Id $Id
    if (-not ($response.Json.PSObject.Properties.Name -contains "result"))
    {
        throw "Initialize failed: $($response.Line)"
    }
}

try
{
    if (-not [System.StringComparer]::OrdinalIgnoreCase.Equals($resolvedWorkspace, $expectedWorkspace))
    {
        throw "WorkspaceRoot must resolve to the scoped criterion worktree: $expectedWorkspace"
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

    [void] (New-Item -ItemType Directory -Path $resolvedWorkspace -Force)
    $tempCreated = $true
    & git.exe -C $resolvedWorkspace init --quiet
    if ($LASTEXITCODE -ne 0)
    {
        throw "git init failed for the criterion worktree."
    }
    $configDirectory = Join-Path $resolvedWorkspace ".codex"
    [void] (New-Item -ItemType Directory -Path $configDirectory)
    $configPath = Join-Path $configDirectory "vibris-session.json"
    $lastGood = [ordered]@{
        schema_version = 1
        workspace_id = "11111111-1111-4111-8111-111111111111"
        shader_directory = "shaders"
        save_id = "shader-test-world"
        dimension_id = "minecraft:overworld"
        time_preset_id = "sunset"
        camera_preset_id = "village-rooftop"
        fov = 70.0
        default_warmup_frames = 32
    } | ConvertTo-Json -Compress
    [System.IO.File]::WriteAllText($configPath, $lastGood, [System.Text.UTF8Encoding]::new($false))
    $lastGoodBase64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($configPath))

    $first = Start-Mcp
    Invoke-Initialize -Owned $first -Id "owner-1"

    $second = Start-Mcp
    $secondStdout = $second.Process.StandardOutput.ReadToEndAsync()
    if (-not $second.Process.WaitForExit($TimeoutSeconds * 1000))
    {
        throw "Second MCP process did not reject duplicate worktree ownership."
    }
    $second.Process.WaitForExit()
    if ($second.Process.ExitCode -eq 0 -or -not [string]::IsNullOrWhiteSpace($secondStdout.Result))
    {
        throw "Second MCP process must exit nonzero with no protocol stdout."
    }
    if ($second.Stderr.Result -notmatch "WORKTREE_ALREADY_OWNED" -or $second.Stderr.Result.Length -gt 4096)
    {
        throw "Second MCP stderr must contain a bounded WORKTREE_ALREADY_OWNED error."
    }

    $statusRequest = [ordered]@{
        jsonrpc = "2.0"
        id = "owner-still-responsive"
        method = "tools/call"
        params = @{ name = "vibris_get_config"; arguments = @{} }
    } | ConvertTo-Json -Compress -Depth 8
    Send-Request -Owned $first -Line $statusRequest
    $statusResponse = Read-Response -Owned $first
    Assert-Response -Response $statusResponse -Id "owner-still-responsive"

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
    $afterBase64 = [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($configPath))
    if ($afterBase64 -cne $lastGoodBase64)
    {
        throw ".codex/vibris-session.json changed after oversized input."
    }

    Close-Mcp -Owned $first
    $reacquired = Start-Mcp
    Invoke-Initialize -Owned $reacquired -Id "owner-reacquired"
    $mutexReacquired = $true
    Close-Mcp -Owned $reacquired
    Write-Output "PASS owner_responsive=true duplicate_rejected=true config_preserved=true error_bounded=true"
}
finally
{
    foreach ($owned in @($ownedProcesses))
    {
        if (-not $owned.Process.HasExited)
        {
            Stop-Process -Id $owned.Process.Id -Force
            [void] $owned.Process.WaitForExit(2000)
        }
        $owned.Process.Dispose()
    }
    if ($tempCreated -and (Test-Path -LiteralPath $criterionRoot))
    {
        Remove-Item -LiteralPath $criterionRoot -Recurse -Force
    }
    Write-Output ("CLEANUP owned_processes=$($ownedProcesses.Count) temp_removed=$tempCreated " +
        "mutex_reacquired=$mutexReacquired")
}