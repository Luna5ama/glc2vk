[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Exe,
    [Parameter(Mandatory)] [string] $Repo,
    [Parameter(Mandatory)] [string] $Revision,
    [Parameter(Mandatory)] [int64] $PayloadBytes,
    [Parameter(Mandatory)] [string] $PendingRoot,
    [int] $Runs = 2,
    [int] $TimeoutSeconds = 60
)

. (Join-Path $PSScriptRoot "source-probe-common.ps1")

$owned = [System.Collections.Generic.List[object]]::new()
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$criterionRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PendingRoot))
$expectedRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ".omo\tmp\ulw-v1-g003-c003"))
$worktree = [System.IO.Path]::GetFullPath($Repo)
$serverJar = Join-Path $repoRoot "test-runtime\build\libs\vibris-test-runtime.jar"
$advertisedPending = [System.IO.Path]::GetFullPath($PendingRoot)
$port = 55067
$tempCreated = $false
$mcp = $null
$mcpClosed = $false
$failure = $null
$summary = $null

try
{
    if (-not [string]::Equals($criterionRoot, $expectedRoot,
            [System.StringComparison]::OrdinalIgnoreCase) -or
        -not [string]::Equals($worktree, (Join-Path $expectedRoot "repo"),
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "Repo and PendingRoot must use the G003-C003 scoped criterion root."
    }
    if ($Revision -cne "HEAD" -or $PayloadBytes -ne 52428800 -or $Runs -ne 2)
    {
        throw "G003-C003 requires HEAD, 52428800 payload bytes, and two runs."
    }
    Assert-ProbeMcpExecutable -Exe $Exe
    foreach ($path in @($Exe, $serverJar))
    {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf))
        {
            throw "Missing required executable artifact: $path"
        }
    }
    if (Test-Path -LiteralPath $criterionRoot)
    {
        throw "Scoped criterion root already exists: $criterionRoot"
    }

    [void] (New-Item -ItemType Directory -Path (Join-Path $worktree "shaders\lib") -Force)
    $tempCreated = $true
    $encoding = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText((Join-Path $worktree ".gitignore"), ".codex/`r`n", $encoding)
    [System.IO.File]::WriteAllText((Join-Path $worktree "shaders\composite.fsh"),
        "#version 150`r`nvoid main() {}`r`n", $encoding)
    $payloadPath = Join-Path $worktree "shaders\lib\payload.bin"
    $payload = [System.IO.File]::Create($payloadPath)
    try
    {
        $payload.SetLength($PayloadBytes)
        $payload.Flush()
    }
    finally
    {
        $payload.Dispose()
    }

    [void] (Invoke-ProbeProcess -FileName "git.exe" -Arguments @("init", "--quiet") `
        -WorkingDirectory $worktree -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("config", "user.name", "Vibris Probe") -WorkingDirectory $worktree `
        -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("config", "user.email", "probe@invalid.local") -WorkingDirectory $worktree `
        -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("config", "core.autocrlf", "false") -WorkingDirectory $worktree `
        -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" -Arguments @("add", ".") `
        -WorkingDirectory $worktree -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("commit", "--quiet", "-m", "50 MiB commit source") -WorkingDirectory $worktree `
        -TimeoutSeconds $TimeoutSeconds)
    $headBefore = (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("rev-parse", "--verify", "HEAD^{commit}") -WorkingDirectory $worktree `
        -TimeoutSeconds $TimeoutSeconds).Stdout.Trim()
    foreach ($relative in @("shaders/composite.fsh", "shaders/lib/payload.bin"))
    {
        $workingBlob = (Invoke-ProbeProcess -FileName "git.exe" `
            -Arguments @("hash-object", "--no-filters", $relative) -WorkingDirectory $worktree `
            -TimeoutSeconds $TimeoutSeconds).Stdout.Trim()
        $committedBlob = (Invoke-ProbeProcess -FileName "git.exe" `
            -Arguments @("rev-parse", "HEAD:$relative") -WorkingDirectory $worktree `
            -TimeoutSeconds $TimeoutSeconds).Stdout.Trim()
        if ($workingBlob -cne $committedBlob)
        {
            throw "Fixture working bytes differ from Git tree bytes for $relative."
        }
    }
    $statusBefore = (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("status", "--porcelain=v1") -WorkingDirectory $worktree `
        -TimeoutSeconds $TimeoutSeconds).Stdout
    if (-not [string]::IsNullOrWhiteSpace($statusBefore) -or $headBefore -notmatch '^[0-9a-f]{40}$')
    {
        throw "Commit fixture was not clean with one full HEAD SHA."
    }

    [void] (New-Item -ItemType Directory -Path (Join-Path $advertisedPending ".staging") -Force)
    [void] (Start-ProbeServer -Jar $serverJar -Port $port -WorkRoot $criterionRoot `
        -PendingRoot $advertisedPending -Owned $owned -TimeoutSeconds $TimeoutSeconds)
    $mcp = Start-ProbeMcp -Exe $Exe -Workspace $worktree -Port $port -Owned $owned
    Initialize-ProbeMcp -Process $mcp.Process -TimeoutSeconds $TimeoutSeconds
    Set-ProbeConfig -Process $mcp.Process -TimeoutSeconds $TimeoutSeconds
    Assert-ProbeServerPendingRoot -Process $mcp.Process -PendingRoot $advertisedPending `
        -TimeoutSeconds $TimeoutSeconds

    $mcp.Process.Refresh()
    $baselineWorkingSet = $mcp.Process.WorkingSet64
    $memoryState = [pscustomobject] @{ Peak = $mcp.Process.PeakWorkingSet64 }
    $sources = [System.Collections.Generic.List[object]]::new()
    for ($run = 1; $run -le $Runs; $run++)
    {
        $response = Invoke-ProbeMcpTool -Process $mcp.Process -Id "commit-source-$run" `
            -Name "vibris_run_actions" -Arguments @{
                source = @{ kind = "commit"; revision = $Revision }
                actions = @()
            } -TimeoutSeconds $TimeoutSeconds -PeakWorkingSet $memoryState
        if ($response.result.isError)
        {
            throw "Commit source preparation failed: " +
                ((Get-ProbeToolPayload -Response $response) | ConvertTo-Json -Compress -Depth 20)
        }
        $toolPayload = Get-ProbeToolPayload -Response $response
        $prepared = @($toolPayload.prepared_sources)
        if ($toolPayload.phase -ne 2 -or $toolPayload.execution_available -ne $false -or
            $toolPayload.source_prepared -ne $true -or $prepared.Count -ne 1)
        {
            throw "Commit tool result did not match the frozen Phase-2 result shape."
        }
        $source = $prepared[0]
        $parsedUuid = [guid]::Empty
        if (-not [guid]::TryParse([string] $source.uuid, [ref] $parsedUuid) -or
            $source.kind -ne "commit" -or [int] $source.attempts -ne 1 -or
            $source.requested_revision -cne $Revision -or
            $source.resolved_revision -cne $headBefore)
        {
            throw "Commit source metadata did not retain HEAD and its resolved full SHA."
        }
        $finalRoot = Join-Path $advertisedPending ([string] $source.uuid)
        if (Test-Path -LiteralPath (Join-Path $finalRoot "shaders"))
        {
            throw "Commit source retained the outer shaders directory."
        }
        $tree = @(Assert-ProbeTreesEqual -Expected (Join-Path $worktree "shaders") `
            -Actual $finalRoot)
        $totalBytes = [int64] (($tree | Measure-Object -Property Length -Sum).Sum)
        if ([int64] $source.file_count -ne $tree.Count -or
            [int64] $source.total_bytes -ne $totalBytes)
        {
            throw "Commit source metadata does not match Git tree content."
        }
        [void] $sources.Add($source)
    }
    if ($sources[0].uuid -ceq $sources[1].uuid)
    {
        throw "Two identical commit preparations reused one UUID."
    }
    $headAfter = (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("rev-parse", "--verify", "HEAD^{commit}") -WorkingDirectory $worktree `
        -TimeoutSeconds $TimeoutSeconds).Stdout.Trim()
    $statusAfter = (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("status", "--porcelain=v1") -WorkingDirectory $worktree `
        -TimeoutSeconds $TimeoutSeconds).Stdout
    if ($headAfter -cne $headBefore -or $statusAfter -cne $statusBefore)
    {
        throw "Commit preparation changed HEAD or worktree status."
    }
    if (@(Get-ChildItem -LiteralPath $criterionRoot -Filter "*.tar" -File -Recurse -Force).Count -ne 0)
    {
        throw "Commit preparation created an intermediate tar file."
    }
    $workingSetDelta = $memoryState.Peak - $baselineWorkingSet
    if ($workingSetDelta -ge 20MB)
    {
        throw "Peak MCP working-set delta was $workingSetDelta bytes, not below 20 MiB."
    }
    $stagingChildren = @(Get-ChildItem -LiteralPath (Join-Path $advertisedPending ".staging") `
        -Force)
    if ($stagingChildren.Count -ne 0)
    {
        throw "Commit preparation left an intermediate staging child."
    }

    Stop-ProbeMcp -Entry $mcp -TimeoutSeconds $TimeoutSeconds
    $mcpClosed = $true
    Assert-ProbePendingClean -PendingRoot $advertisedPending
    $summary = "PASS criterion=G003-C003 runs=2 distinct_uuids=true sha=$headBefore " +
        "payload_bytes=$PayloadBytes working_set_delta=$workingSetDelta tar_files=0"
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
    Write-Output ("FAIL criterion=G003-C003 reason=" + ($failure -replace '[\r\n]+', ' '))
    exit 1
}
Write-Output $summary