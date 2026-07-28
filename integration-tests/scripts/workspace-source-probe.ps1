[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Exe,
    [Parameter(Mandatory)] [string] $Fixture,
    [Parameter(Mandatory)] [string] $PendingRoot,
    [Parameter(Mandatory)] [string] $MutateOnce,
    [int] $TimeoutSeconds = 30
)

. (Join-Path $PSScriptRoot "source-probe-common.ps1")

$owned = [System.Collections.Generic.List[object]]::new()
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$criterionRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PendingRoot))
$expectedRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ".omo\tmp\ulw-v1-g003-c001"))
$workspace = Join-Path $criterionRoot "worktree"
$serverJar = Join-Path $repoRoot "test-runtime\build\libs\vibris-test-runtime.jar"
$advertisedPending = [System.IO.Path]::GetFullPath($PendingRoot)
$port = 55065
$tempCreated = $false
$mcp = $null
$mcpClosed = $false
$failure = $null
$summary = $null

try
{
    if (-not [string]::Equals($criterionRoot, $expectedRoot,
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "PendingRoot must be scoped below $expectedRoot."
    }
    if ($MutateOnce.Replace('\', '/') -cne "lib/live.glsl")
    {
        throw "G003-C001 requires -MutateOnce lib/live.glsl."
    }
    Assert-ProbeMcpExecutable -Exe $Exe
    foreach ($path in @($Exe, $serverJar))
    {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf))
        {
            throw "Missing required executable artifact: $path"
        }
    }
    $fixtureRoot = [System.IO.Path]::GetFullPath($Fixture)
    if (-not (Test-Path -LiteralPath $fixtureRoot -PathType Container))
    {
        throw "Missing workspace fixture: $fixtureRoot"
    }
    if (Test-Path -LiteralPath $criterionRoot)
    {
        throw "Scoped criterion root already exists: $criterionRoot"
    }

    [void] (New-Item -ItemType Directory -Path $workspace -Force)
    $tempCreated = $true
    Get-ChildItem -LiteralPath $fixtureRoot -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $workspace -Recurse -Force
    }
    [void] (Invoke-ProbeProcess -FileName "git.exe" -Arguments @("init", "--quiet") `
        -WorkingDirectory $workspace -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("config", "user.name", "Vibris Probe") -WorkingDirectory $workspace `
        -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("config", "user.email", "probe@invalid.local") -WorkingDirectory $workspace `
        -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" -Arguments @(
            "add", ".gitignore", "shaders/composite.fsh", "shaders/lib/live.glsl"
        ) -WorkingDirectory $workspace -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" -Arguments @(
            "commit", "--quiet", "-m", "workspace source fixture"
        ) -WorkingDirectory $workspace -TimeoutSeconds $TimeoutSeconds)

    $livePath = Join-Path $workspace "shaders\lib\live.glsl"
    Expand-ProbeFile -Path $livePath -Length (128MB)
    $status = Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("status", "--porcelain=v1", "--ignored") -WorkingDirectory $workspace `
        -TimeoutSeconds $TimeoutSeconds
    if ($status.Stdout -notmatch 'shaders/ignored[.]properties' -or
        $status.Stdout -notmatch 'shaders/untracked[.]glsl')
    {
        throw "Fixture did not expose both ignored and untracked shader files."
    }
    $expectedHead = (Invoke-ProbeProcess -FileName "git.exe" -Arguments @("rev-parse", "HEAD") `
        -WorkingDirectory $workspace -TimeoutSeconds $TimeoutSeconds).Stdout.Trim()
    Copy-Item -LiteralPath $Exe -Destination (Join-Path $workspace "git.exe")

    [void] (New-Item -ItemType Directory -Path (Join-Path $advertisedPending ".staging") -Force)
    [void] (Start-ProbeServer -Jar $serverJar -Port $port -WorkRoot $criterionRoot `
        -PendingRoot $advertisedPending -Owned $owned -TimeoutSeconds $TimeoutSeconds)
    $mcp = Start-ProbeMcp -Exe $Exe -Workspace $workspace -Port $port -Owned $owned
    Initialize-ProbeMcp -Process $mcp.Process -TimeoutSeconds $TimeoutSeconds
    Set-ProbeConfig -Process $mcp.Process -TimeoutSeconds $TimeoutSeconds
    Assert-ProbeServerPendingRoot -Process $mcp.Process -PendingRoot $advertisedPending `
        -TimeoutSeconds $TimeoutSeconds

    $seenStages = [System.Collections.Generic.HashSet[string]]::new()
    $mutationState = [pscustomobject] @{ Count = 0 }
    $encoding = [System.Text.UTF8Encoding]::new($false)
    $poll = {
        $stageRoot = Join-Path $advertisedPending ".staging"
        if (-not (Test-Path -LiteralPath $stageRoot -PathType Container))
        {
            return
        }
        foreach ($file in @(Get-ChildItem -LiteralPath $stageRoot -Filter "live.glsl" `
                -File -Recurse -ErrorAction SilentlyContinue))
        {
            $key = "$($file.FullName)|$($file.CreationTimeUtc.Ticks)"
            if ($mutationState.Count -eq 0 -and $seenStages.Add($key))
            {
                [System.IO.File]::AppendAllText($livePath, "`r`n// accepted mutation`r`n", $encoding)
                $mutationState.Count++
            }
        }
    }
    $response = Invoke-ProbeMcpTool -Process $mcp.Process -Id "workspace-source" `
        -Name "vibris_run_actions" -Arguments @{
            source = @{ kind = "workspace" }
            actions = @()
        } -TimeoutSeconds $TimeoutSeconds -Poll $poll
    if ($response.result.isError)
    {
        throw "Workspace source preparation failed: " +
            ((Get-ProbeToolPayload -Response $response) | ConvertTo-Json -Compress -Depth 20)
    }
    $payload = Get-ProbeToolPayload -Response $response
    $prepared = @($payload.prepared_sources)
    if ($payload.phase -ne 2 -or $payload.execution_available -ne $false -or
        $payload.source_prepared -ne $true -or $prepared.Count -ne 1)
    {
        throw "Workspace tool result did not match the frozen Phase-2 result shape."
    }
    $source = $prepared[0]
    $parsedUuid = [guid]::Empty
    if (-not [guid]::TryParse([string] $source.uuid, [ref] $parsedUuid) -or
        $source.kind -ne "workspace" -or [int] $source.attempts -ne 2 -or
        $mutationState.Count -ne 1)
    {
        throw "Workspace source did not report exactly one automatic retry."
    }
    if ([string] $source.head_revision -cne $expectedHead)
    {
        throw "Workspace source did not retain the full current HEAD revision."
    }
    $finalRoot = Join-Path $advertisedPending ([string] $source.uuid)
    if (-not (Test-Path -LiteralPath $finalRoot -PathType Container) -or
        (Test-Path -LiteralPath (Join-Path $finalRoot "shaders")))
    {
        throw "Prepared UUID directory is missing or retained a nested shaders directory."
    }
    foreach ($relative in @(
            "composite.fsh", "ignored.properties", "untracked.glsl", "lib\live.glsl"
        ))
    {
        if (-not (Test-Path -LiteralPath (Join-Path $finalRoot $relative) -PathType Leaf))
        {
            throw "Prepared workspace source is missing $relative."
        }
    }
    $tree = @(Assert-ProbeTreesEqual -Expected (Join-Path $workspace "shaders") -Actual $finalRoot)
    $totalBytes = [int64] (($tree | Measure-Object -Property Length -Sum).Sum)
    if ([int64] $source.file_count -ne $tree.Count -or [int64] $source.total_bytes -ne $totalBytes)
    {
        throw "Prepared workspace metadata does not match the accepted second enumeration."
    }
    $stagingChildren = @(Get-ChildItem -LiteralPath (Join-Path $advertisedPending ".staging") `
        -Force)
    if ($stagingChildren.Count -ne 0)
    {
        throw "Workspace preparation left a .staging child."
    }

    Stop-ProbeMcp -Entry $mcp -TimeoutSeconds $TimeoutSeconds
    $mcpClosed = $true
    Assert-ProbePendingClean -PendingRoot $advertisedPending
    $summary = "PASS criterion=G003-C001 attempts=2 files=$($tree.Count) metadata=second-enumeration " +
        "git_decoy_ignored=true staging_empty=true eof_cleanup=true"
}
catch
{
    $failure = $_.Exception.Message
}
finally
{
    $cleanupErrors = [System.Collections.Generic.List[string]]::new()
    try
    {
        if ($null -ne $mcp -and -not $mcpClosed -and -not $mcp.Process.HasExited)
        {
            Stop-ProbeMcp -Entry $mcp -TimeoutSeconds $TimeoutSeconds
            $mcpClosed = $true
        }
    }
    catch
    {
        $cleanupErrors.Add($_.Exception.Message)
    }
    try
    {
        Assert-ProbePendingClean -PendingRoot $advertisedPending
    }
    catch
    {
        $cleanupErrors.Add($_.Exception.Message)
    }
    try
    {
        Stop-ProbeOwnedProcesses -Owned $owned -TimeoutSeconds 2
    }
    catch
    {
        $cleanupErrors.Add($_.Exception.Message)
    }
    try
    {
        Wait-ProbePort -Port $port -Open $false -TimeoutSeconds 2
    }
    catch
    {
        $cleanupErrors.Add($_.Exception.Message)
    }
    try
    {
        if ($tempCreated -and (Test-Path -LiteralPath $criterionRoot))
        {
            Remove-Item -LiteralPath $criterionRoot -Recurse -Force
        }
    }
    catch
    {
        $cleanupErrors.Add($_.Exception.Message)
    }
    if ($cleanupErrors.Count -ne 0)
    {
        $cleanupFailure = [string]::Join('; ', $cleanupErrors)
        $failure = if ($null -eq $failure) { $cleanupFailure } else {
            "$failure; cleanup: $cleanupFailure"
        }
    }
}

if ($null -ne $failure)
{
    Write-Output ("FAIL criterion=G003-C001 reason=" + ($failure -replace '[\r\n]+', ' '))
    exit 1
}
Write-Output $summary