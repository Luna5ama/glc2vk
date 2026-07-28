[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Exe,
    [Parameter(Mandatory)] [string] $Repo,
    [Parameter(Mandatory)] [string] $Revision,
    [Parameter(Mandatory)] [string] $PendingRoot,
    [int] $MutationCount = 2,
    [int] $TimeoutSeconds = 30
)

. (Join-Path $PSScriptRoot "source-probe-common.ps1")

function Assert-SourceProbeError
{
    param(
        [Parameter(Mandatory)] [object] $Response,
        [Parameter(Mandatory)] [string] $ExpectedCode
    )

    $payload = Get-ProbeToolPayload -Response $Response
    if (-not $Response.result.isError -or $payload.error.code -cne $ExpectedCode)
    {
        throw "Expected $ExpectedCode, received " + ($payload | ConvertTo-Json -Compress -Depth 20)
    }
}

$owned = [System.Collections.Generic.List[object]]::new()
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$criterionRoot = [System.IO.Path]::GetFullPath((Split-Path -Parent $PendingRoot))
$expectedRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ".omo\tmp\ulw-v1-g003-c002"))
$worktree = Join-Path $criterionRoot "repo"
$serverJar = Join-Path $repoRoot "test-runtime\build\libs\vibris-test-runtime.jar"
$advertisedPending = [System.IO.Path]::GetFullPath($PendingRoot)
$outsideRoot = Join-Path $criterionRoot "outside-target"
$outsideSentinel = Join-Path $outsideRoot "sentinel.txt"
$escapePath = Join-Path $criterionRoot "outside-created.txt"
$port = 55066
$tempCreated = $false
$mcp = $null
$mcpClosed = $false
$junction = $null
$failure = $null
$summary = $null

try
{
    if (-not [string]::Equals($criterionRoot, $expectedRoot,
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "PendingRoot must be scoped below $expectedRoot."
    }
    if ($Revision -cne "refs/tags/traversal" -or $MutationCount -ne 2)
    {
        throw "G003-C002 requires traversal tag input and exactly two mutations."
    }
    Assert-ProbeMcpExecutable -Exe $Exe
    foreach ($path in @($Exe, $serverJar))
    {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf))
        {
            throw "Missing required executable artifact: $path"
        }
    }
    $fixtureRoot = [System.IO.Path]::GetFullPath($Repo)
    if (-not (Test-Path -LiteralPath $fixtureRoot -PathType Container))
    {
        throw "Missing malicious source fixture: $fixtureRoot"
    }
    if (Test-Path -LiteralPath (Join-Path $fixtureRoot ".git"))
    {
        throw "Malicious fixture must not contain Git metadata."
    }
    if (Test-Path -LiteralPath $criterionRoot)
    {
        throw "Scoped criterion root already exists: $criterionRoot"
    }

    [void] (New-Item -ItemType Directory -Path $worktree -Force)
    [void] (New-Item -ItemType Directory -Path $outsideRoot -Force)
    $tempCreated = $true
    Get-ChildItem -LiteralPath $fixtureRoot -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $worktree -Recurse -Force
    }
    [System.IO.File]::WriteAllText($outsideSentinel, "unchanged", [System.Text.UTF8Encoding]::new($false))
    $sentinelHash = Get-ProbeFileHash -Path $outsideSentinel

    [void] (Invoke-ProbeProcess -FileName "git.exe" -Arguments @("init", "--quiet") `
        -WorkingDirectory $worktree -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("config", "user.name", "Vibris Probe") -WorkingDirectory $worktree `
        -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("config", "user.email", "probe@invalid.local") -WorkingDirectory $worktree `
        -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("add", ".gitignore", "README.txt") -WorkingDirectory $worktree `
        -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("commit", "--quiet", "-m", "no shaders") -WorkingDirectory $worktree `
        -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" -Arguments @("tag", "no-shaders") `
        -WorkingDirectory $worktree -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" -Arguments @("add", "shaders") `
        -WorkingDirectory $worktree -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("commit", "--quiet", "-m", "normal shaders") -WorkingDirectory $worktree `
        -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" -Arguments @("tag", "normal-shaders") `
        -WorkingDirectory $worktree -TimeoutSeconds $TimeoutSeconds)
    $blob = Invoke-ProbeProcess -FileName "git.exe" -Arguments @("hash-object", "-w", "--stdin") `
        -WorkingDirectory $worktree -InputText "../../outside-created.txt" `
        -TimeoutSeconds $TimeoutSeconds
    $blobId = $blob.Stdout.Trim()
    [void] (Invoke-ProbeProcess -FileName "git.exe" -Arguments @(
            "update-index", "--add", "--cacheinfo", "120000,$blobId,shaders/traversal-link"
        ) -WorkingDirectory $worktree -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("commit", "--quiet", "-m", "archive symlink") -WorkingDirectory $worktree `
        -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" -Arguments @("tag", "traversal") `
        -WorkingDirectory $worktree -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" -Arguments @("read-tree", "refs/tags/normal-shaders") `
        -WorkingDirectory $worktree -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("config", "core.protectNTFS", "false") -WorkingDirectory $worktree `
        -TimeoutSeconds $TimeoutSeconds)
    $deviceBlob = Invoke-ProbeProcess -FileName "git.exe" -Arguments @("hash-object", "-w", "--stdin") `
        -WorkingDirectory $worktree -InputText "device-name-content" -TimeoutSeconds $TimeoutSeconds
    [void] (Invoke-ProbeProcess -FileName "git.exe" -Arguments @(
            "-c", "core.protectNTFS=false", "update-index", "--add", "--cacheinfo",
            "100644,$($deviceBlob.Stdout.Trim()),shaders/NUL"
        ) -WorkingDirectory $worktree -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" `
        -Arguments @("commit", "--quiet", "-m", "device name") -WorkingDirectory $worktree `
        -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-ProbeProcess -FileName "git.exe" -Arguments @("tag", "device-name") `
        -WorkingDirectory $worktree -TimeoutSeconds $TimeoutSeconds)

    $livePath = Join-Path $worktree "shaders\lib\live.glsl"
    Expand-ProbeFile -Path $livePath -Length (128MB)
    [void] (New-Item -ItemType Directory -Path (Join-Path $advertisedPending ".staging") -Force)
    [void] (Start-ProbeServer -Jar $serverJar -Port $port -WorkRoot $criterionRoot `
        -PendingRoot $advertisedPending -Owned $owned -TimeoutSeconds $TimeoutSeconds)
    $mcp = Start-ProbeMcp -Exe $Exe -Workspace $worktree -Port $port -Owned $owned
    Initialize-ProbeMcp -Process $mcp.Process -TimeoutSeconds $TimeoutSeconds
    Set-ProbeConfig -Process $mcp.Process -TimeoutSeconds $TimeoutSeconds
    Assert-ProbeServerPendingRoot -Process $mcp.Process -PendingRoot $advertisedPending `
        -TimeoutSeconds $TimeoutSeconds

    $traversal = Invoke-ProbeMcpTool -Process $mcp.Process -Id "commit-traversal" `
        -Name "vibris_run_actions" -Arguments @{
            source = @{ kind = "commit"; revision = $Revision }
            actions = @()
        } -TimeoutSeconds $TimeoutSeconds
    Assert-SourceProbeError -Response $traversal -ExpectedCode "SOURCE_CONTAINS_REPARSE_POINT"
    Assert-ProbePendingClean -PendingRoot $advertisedPending

    $noShaders = Invoke-ProbeMcpTool -Process $mcp.Process -Id "commit-no-shaders" `
        -Name "vibris_run_actions" -Arguments @{
            source = @{ kind = "commit"; revision = "refs/tags/no-shaders" }
            actions = @()
        } -TimeoutSeconds $TimeoutSeconds
    Assert-SourceProbeError -Response $noShaders -ExpectedCode "COMMIT_HAS_NO_SHADERS"
    Assert-ProbePendingClean -PendingRoot $advertisedPending

    $deviceName = Invoke-ProbeMcpTool -Process $mcp.Process -Id "commit-device-name" `
        -Name "vibris_run_actions" -Arguments @{
            source = @{ kind = "commit"; revision = "refs/tags/device-name" }
            actions = @()
        } -TimeoutSeconds $TimeoutSeconds
    Assert-SourceProbeError -Response $deviceName -ExpectedCode "SOURCE_CONTAINS_REPARSE_POINT"
    Assert-ProbePendingClean -PendingRoot $advertisedPending

    $junction = Join-Path $worktree "shaders\reparse"
    [void] (Invoke-ProbeProcess -FileName "cmd.exe" -Arguments @(
            "/d", "/c", "mklink", "/J", $junction, $outsideRoot
        ) -WorkingDirectory $worktree -TimeoutSeconds $TimeoutSeconds)
    if (-not ((Get-Item -LiteralPath $junction -Force).Attributes -band
            [System.IO.FileAttributes]::ReparsePoint))
    {
        throw "Failed to create the workspace reparse-point fixture."
    }
    $reparse = Invoke-ProbeMcpTool -Process $mcp.Process -Id "workspace-reparse" `
        -Name "vibris_run_actions" -Arguments @{
            source = @{ kind = "workspace" }
            actions = @()
        } -TimeoutSeconds $TimeoutSeconds
    Assert-SourceProbeError -Response $reparse -ExpectedCode "SOURCE_CONTAINS_REPARSE_POINT"
    Assert-ProbePendingClean -PendingRoot $advertisedPending
    [System.IO.Directory]::Delete($junction)
    $junction = $null

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
            if ($mutationState.Count -lt $MutationCount -and $seenStages.Add($key))
            {
                $next = $mutationState.Count + 1
                [System.IO.File]::AppendAllText($livePath, "`r`n// mutation $next`r`n", $encoding)
                $mutationState.Count = $next
            }
        }
    }
    $mutated = Invoke-ProbeMcpTool -Process $mcp.Process -Id "workspace-mutates-twice" `
        -Name "vibris_run_actions" -Arguments @{
            source = @{ kind = "workspace" }
            actions = @()
        } -TimeoutSeconds $TimeoutSeconds -Poll $poll
    Assert-SourceProbeError -Response $mutated -ExpectedCode "SOURCE_CHANGED_DURING_SNAPSHOT"
    if ($mutationState.Count -ne $MutationCount)
    {
        throw "Expected two deterministic workspace mutations, observed $($mutationState.Count)."
    }
    Assert-ProbePendingClean -PendingRoot $advertisedPending
    if ((Get-ProbeFileHash -Path $outsideSentinel) -cne $sentinelHash -or
        (Test-Path -LiteralPath $escapePath))
    {
        throw "Adversarial source input changed a path outside pending root."
    }

    Stop-ProbeMcp -Entry $mcp -TimeoutSeconds $TimeoutSeconds
    $mcpClosed = $true
    Assert-ProbePendingClean -PendingRoot $advertisedPending
    $summary = "PASS criterion=G003-C002 traversal=rejected device_name=rejected reparse=rejected " +
        "mutations=2 staging_empty=true outside_unchanged=true"
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
        if ($null -ne $junction -and (Test-Path -LiteralPath $junction))
        {
            [System.IO.Directory]::Delete($junction)
        }
    }
    catch
    {
        $cleanupErrors.Add($_.Exception.Message)
    }
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
    Write-Output ("FAIL criterion=G003-C002 reason=" + ($failure -replace '[\r\n]+', ' '))
    exit 1
}
Write-Output $summary