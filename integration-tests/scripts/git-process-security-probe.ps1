[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Repository,
    [Parameter(Mandatory)] [string] $NativeSecurityFixture
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "..\..\tools\git-process.ps1")
. (Join-Path $PSScriptRoot "stdio-job-probe-common.ps1")

$root = [System.IO.Path]::GetFullPath($Repository)
$fixture = [System.IO.Path]::GetFullPath($NativeSecurityFixture)
$temporary = Join-Path ([System.IO.Path]::GetTempPath()) ("vibris-git-process-" + [guid]::NewGuid().ToString("N"))
$oldPath = $env:PATH
$oldGitDir = $env:GIT_DIR
$oldGitWorkTree = $env:GIT_WORK_TREE
$oldTrusted = $script:TrustedGitExecutable
$hadGitFunction = Test-Path Function:\global:git
$oldGitFunction = if ($hadGitFunction) { (Get-Item Function:\global:git).ScriptBlock } else { $null }

try
{
    [void] (New-Item -ItemType Directory -Path $temporary)
    $fakeBin = Join-Path $temporary "fake-bin"
    [void] (New-Item -ItemType Directory -Path $fakeBin)
    Copy-Item -LiteralPath $PSHOME\pwsh.exe -Destination (Join-Path $fakeBin "git.exe")
    $env:PATH = "$fakeBin;$oldPath"
    $env:GIT_DIR = "I:\nonexistent\poison.git"
    $env:GIT_WORK_TREE = "I:\nonexistent\poison-worktree"
    function global:git { throw "PowerShell Git function decoy ran." }

    $script:TrustedGitExecutable = $null
    $trusted = Resolve-TrustedGitExecutable
    if ([System.StringComparer]::OrdinalIgnoreCase.Equals($trusted, (Join-Path $fakeBin "git.exe")))
    {
        throw "PATH fake git.exe was selected."
    }
    $head = Invoke-TrustedGitText -Root $root -Arguments @("rev-parse", "HEAD") -Label "poisoned provenance"
    if ($head -notmatch '^[0-9a-fA-F]{40,64}$') { throw "Trusted Git returned an invalid HEAD." }

    $metacharRepo = Join-Path $temporary "repo [literal]; argv"
    [void] (New-Item -ItemType Directory -Path $metacharRepo)
    [void] (Invoke-TrustedGitText -Root $metacharRepo -Arguments @("init", "--quiet") -Label "metachar init")
    $specialName = "unicøde ; [literal] .txt"
    [System.IO.File]::WriteAllText((Join-Path $metacharRepo $specialName), "payload")
    [void] (Invoke-TrustedGitText -Root $metacharRepo -Arguments @("add", "--", $specialName) `
        -Label "special path add")
    $tracked = Invoke-TrustedGitText -Root $metacharRepo -Arguments @("ls-files", "-z") `
        -Label "NUL path inventory"
    $trackedPaths = @($tracked -split [char] 0 | Where-Object { $_.Length -ne 0 })
    if ($trackedPaths.Count -ne 1 -or $trackedPaths[0] -cne $specialName)
    {
        throw "NUL-delimited Git inventory did not preserve a Unicode metacharacter filename."
    }
    $fixtureRoot = [string] @(Invoke-G007Git -WorkspaceRoot $metacharRepo `
        -GitArguments @("rev-parse", "--path-format=absolute", "--show-toplevel"))[0]
    if (-not [System.StringComparer]::OrdinalIgnoreCase.Equals(
            [System.IO.Path]::GetFullPath($fixtureRoot), [System.IO.Path]::GetFullPath($metacharRepo)))
    {
        throw "G007 Git fixture did not remain bound under function/PATH/GIT_* spoofing."
    }

    $hangingGit = Join-Path $temporary "git.exe"
    Copy-Item -LiteralPath $fixture -Destination $hangingGit
    $pidFile = Join-Path $temporary "owned-git.pid"
    $env:VIBRIS_GIT_DECOY_MARKER = $pidFile
    $env:VIBRIS_GIT_DECOY_MODE = "hang"
    $script:TrustedGitExecutable = $hangingGit
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $timedOut = $false
    try
    {
        [void] (Invoke-TrustedGitText -Root $root -Arguments @("rev-parse", "HEAD") `
            -Label "hanging provenance" -TimeoutSeconds 5)
    }
    catch
    {
        $timedOut = $_.Exception.Message -like "GIT_TIMEOUT:*"
    }
    $timer.Stop()
    if (-not $timedOut -or $timer.ElapsedMilliseconds -lt 4500 -or $timer.ElapsedMilliseconds -gt 7500)
    {
        throw "Hanging Git did not fail within the monotonic timeout window: $($timer.ElapsedMilliseconds)ms"
    }
    $ownedPid = [int] ((Get-Content -LiteralPath $pidFile -Raw).Trim())
    $residual = Get-Process -Id $ownedPid -ErrorAction SilentlyContinue
    if ($null -ne $residual -and $residual.Path -eq $hangingGit)
    {
        throw "Owned hanging Git child remains after timeout: $ownedPid"
    }
    Write-Output ("PASS trusted_git=$trusted head=$head g007_spoof_safe=true nul_path=true metachar_repo=true " +
        "timeout_ms=$($timer.ElapsedMilliseconds) owned_pid=$ownedPid residual=false")
}
finally
{
    $script:TrustedGitExecutable = $oldTrusted
    $env:PATH = $oldPath
    $env:GIT_DIR = $oldGitDir
    $env:GIT_WORK_TREE = $oldGitWorkTree
    $env:VIBRIS_GIT_DECOY_MARKER = $null
    $env:VIBRIS_GIT_DECOY_MODE = $null
    Remove-Item Function:\global:git -ErrorAction SilentlyContinue
    if ($hadGitFunction) { Set-Item Function:\global:git -Value $oldGitFunction }
    if (Test-Path -LiteralPath $temporary)
    {
        $resolved = [System.IO.Path]::GetFullPath($temporary)
        $expectedParent = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
        if (-not $resolved.StartsWith($expectedParent, [System.StringComparison]::OrdinalIgnoreCase))
        {
            throw "Refusing to clean unexpected Git probe path: $resolved"
        }
        Remove-Item -LiteralPath $resolved -Recurse -Force
    }
}
