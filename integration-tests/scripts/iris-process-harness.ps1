function Get-IrisProtectedProcesses
{
    $processes = @(Get-CimInstance Win32_Process)
    $ids = [System.Collections.Generic.HashSet[uint32]]::new()
    foreach ($process in $processes)
    {
        $identity = "$($process.ExecutablePath)`n$($process.CommandLine)"
        if ($process.Name -ieq "MultiMC.exe" -or $identity -match '(?i)[\\/]MultiMC[\\/]')
        {
            [void] $ids.Add([uint32] $process.ProcessId)
        }
    }
    do
    {
        $changed = $false
        foreach ($process in $processes)
        {
            if ($ids.Contains([uint32] $process.ParentProcessId) -and $ids.Add([uint32] $process.ProcessId))
            {
                $changed = $true
            }
        }
    } while ($changed)
    return @($processes | Where-Object { $ids.Contains([uint32] $_.ProcessId) } | ForEach-Object {
        [pscustomobject] @{
            Pid = [int] $_.ProcessId
            Created = $_.CreationDate.ToUniversalTime().ToString("O")
        }
    })
}

function Assert-IrisProtectedProcesses
{
    param([Parameter(Mandatory)] [object[]] $Snapshot)

    foreach ($protected in $Snapshot)
    {
        $current = Get-CimInstance Win32_Process -Filter "ProcessId = $($protected.Pid)"
        if ($null -eq $current -or
            $current.CreationDate.ToUniversalTime().ToString("O") -cne $protected.Created)
        {
            throw "Protected MultiMC process changed during the probe: PID $($protected.Pid)."
        }
    }
}

function Start-IrisPackagedClient
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [Parameter(Mandatory)] [string] $PatchedJar,
        [Parameter(Mandatory)] [string] $Scenario,
        [ValidateRange(1, 600)] [int] $TimeoutSeconds = 180
    )

    $arguments = @(
        "--no-daemon", ":fabric:runVibrisPhase4Client",
        "-Pphase4PatchedJar=$PatchedJar", "-Pphase4GameDir=$($Scope.GameDir)",
        "-Pphase4RunId=$($Scope.RunId)", "-Pphase4Scenario=$Scenario",
        "-Pphase4EventFile=$($Scope.EventFile)", "-Pphase4ReceiptFile=$($Scope.ReceiptFile)",
        "-Pphase4CommandFile=$($Scope.CommandFile)"
    )
    $command = (ConvertTo-CoreArgument (Join-Path $script:IrisRoot "gradlew.bat")) + " " +
        [string]::Join(" ", @($arguments | ForEach-Object { ConvertTo-CoreArgument $_ }))
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = Join-Path ([Environment]::GetFolderPath("System")) "cmd.exe"
    $startInfo.WorkingDirectory = $script:IrisRoot
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.Arguments = "/d /s /c `"$command`""
    $wrapper = [System.Diagnostics.Process]::new()
    $wrapper.StartInfo = $startInfo
    [void] $wrapper.Start()
    $Scope.Wrapper = [pscustomobject] @{
        Process = $wrapper
        Created = $wrapper.StartTime.ToUniversalTime().ToString("O")
        Stdout = $wrapper.StandardOutput.ReadToEndAsync()
        Stderr = $wrapper.StandardError.ReadToEndAsync()
    }
    $receipt = Wait-IrisReceipt -Scope $Scope -TimeoutSeconds $TimeoutSeconds
    $Scope.RuntimePid = [int] $receipt.pid
    $runtime = Get-CimInstance Win32_Process -Filter "ProcessId = $($Scope.RuntimePid)"
    if ($null -eq $runtime) { throw "Runtime PID from the ownership receipt does not exist." }
    $runtimeStart = $runtime.CreationDate.ToUniversalTime()
    $receiptStart = [datetimeoffset]::Parse([string] $receipt.started_at_utc).UtcDateTime
    if ([math]::Abs(($runtimeStart - $receiptStart).TotalSeconds) -gt 1)
    {
        throw "Runtime creation time does not match its ownership receipt."
    }
    $Scope.RuntimeCreated = $runtimeStart.ToString("O")
    Assert-IrisOwnedRuntime -Scope $Scope
    Wait-CorePort -Port $script:IrisPort -Open $true -Process $wrapper -TimeoutSeconds $TimeoutSeconds
}

function Wait-IrisReceipt
{
    param([Parameter(Mandatory)] [object] $Scope, [Parameter(Mandatory)] [int] $TimeoutSeconds)

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([datetime]::UtcNow -lt $deadline)
    {
        if ($Scope.Wrapper.Process.HasExited)
        {
            $stdout = $Scope.Wrapper.Stdout.Result
            $stderr = $Scope.Wrapper.Stderr.Result
            throw "Packaged client wrapper exited $($Scope.Wrapper.Process.ExitCode) before writing its " +
                "ownership receipt.`nSTDOUT:`n$stdout`nSTDERR:`n$stderr"
        }
        if (Test-Path -LiteralPath $Scope.ReceiptFile -PathType Leaf)
        {
            try
            {
                $receipt = Get-Content -Raw -LiteralPath $Scope.ReceiptFile | ConvertFrom-Json
            }
            catch
            {
                Start-Sleep -Milliseconds 50
                continue
            }
            if ($receipt.run_id -cne $Scope.RunId -or
                -not [string]::Equals([System.IO.Path]::GetFullPath($receipt.game_dir), $Scope.GameDir,
                    [System.StringComparison]::OrdinalIgnoreCase))
            {
                throw "Packaged client ownership receipt does not match this probe."
            }
            return $receipt
        }
        Start-Sleep -Milliseconds 50
    }
    throw "Packaged client did not write its ownership receipt within $TimeoutSeconds seconds."
}

function Assert-IrisOwnedRuntime
{
	param([Parameter(Mandatory)] [object] $Scope)

	$deadline = [datetime]::UtcNow.AddSeconds(5)
	while ($true)
	{
		$runtime = Get-CimInstance Win32_Process -Filter "ProcessId = $($Scope.RuntimePid)"
		if ($null -eq $runtime -or $runtime.CreationDate.ToUniversalTime().ToString("O") -cne $Scope.RuntimeCreated)
		{
			throw "Runtime PID/creation identity no longer matches its receipt."
		}
		$command = [string] $runtime.CommandLine
		$hasRunId = $command -match [regex]::Escape("-Dvibris.phase4.runId=$($Scope.RunId)")
		$hasGameDir = $command -match [regex]::Escape($Scope.GameDir)
		if ($hasRunId -and $hasGameDir) { return }
		if ([datetime]::UtcNow -ge $deadline)
		{
			$previewLength = [math]::Min(240, $command.Length)
			$preview = if ($previewLength -eq 0) { "<empty>" } else { $command.Substring(0, $previewLength) }
			throw "Runtime command line ownership mismatch for PID $($Scope.RuntimePid): " +
				"run_id=$hasRunId game_dir=$hasGameDir length=$($command.Length) preview=$preview"
		}
		Start-Sleep -Milliseconds 50
	}
}

function Stop-IrisOwnedWrapper
{
    param([Parameter(Mandatory)] [object] $Scope)

    if ($Scope.Wrapper.Process.HasExited) { return }
    if ($Scope.Wrapper.Process.StartTime.ToUniversalTime().ToString("O") -cne $Scope.Wrapper.Created)
    {
        throw "Wrapper PID was reused; refusing fallback termination."
    }
    $taskkill = Start-Process -FilePath (Join-Path $env:SystemRoot "System32\taskkill.exe") `
        -ArgumentList @("/PID", [string] $Scope.Wrapper.Process.Id, "/T", "/F") `
        -NoNewWindow -Wait -PassThru
    if ($taskkill.ExitCode -ne 0 -and -not $Scope.Wrapper.Process.HasExited)
    {
        throw "Failed to terminate the owned packaged-client process tree."
    }
    if (-not $Scope.Wrapper.Process.WaitForExit(10000))
    {
        throw "Owned packaged-client wrapper did not exit after tree termination."
    }
}

function Stop-IrisPackagedClient
{
    param([Parameter(Mandatory)] [object] $Scope, [ValidateRange(1, 300)] [int] $TimeoutSeconds = 90)

    if ($null -eq $Scope.Wrapper) { return }
    if ($Scope.RuntimePid -eq 0)
    {
        Stop-IrisOwnedWrapper -Scope $Scope
        [void] $Scope.Wrapper.Process.WaitForExit(1000)
        [void] $Scope.Wrapper.Stdout.Result
        [void] $Scope.Wrapper.Stderr.Result
        $Scope.Wrapper.Process.Dispose()
        if (Test-CorePort -Port $script:IrisPort)
        {
            throw "A listener opened without a valid ownership receipt; refusing to stop its owner."
        }
        return
    }
    if (-not $Scope.Wrapper.Process.HasExited)
    {
        $command = @{ run_id = $Scope.RunId; command = "stop" } | ConvertTo-Json -Compress
        [System.IO.File]::WriteAllText($Scope.CommandFile + ".next", $command)
        [System.IO.File]::Move($Scope.CommandFile + ".next", $Scope.CommandFile)
        $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
        while ([datetime]::UtcNow -lt $deadline -and -not $Scope.Wrapper.Process.HasExited)
        {
            Start-Sleep -Milliseconds 50
        }
        if (-not $Scope.Wrapper.Process.HasExited)
        {
            $runtime = Get-CimInstance Win32_Process -Filter "ProcessId = $($Scope.RuntimePid)"
            if ($null -ne $runtime)
            {
                Assert-IrisOwnedRuntime -Scope $Scope
                Stop-Process -Id $Scope.RuntimePid -Force
            }
            if (-not $Scope.Wrapper.Process.WaitForExit(10000)) { Stop-IrisOwnedWrapper -Scope $Scope }
        }
    }
    [void] $Scope.Wrapper.Process.WaitForExit(1000)
    $stdout = $Scope.Wrapper.Stdout.Result
    $stderr = $Scope.Wrapper.Stderr.Result
    $exitCode = $Scope.Wrapper.Process.ExitCode
    $Scope.Wrapper.Process.Dispose()
    Wait-CorePort -Port $script:IrisPort -Open $false -Process $null -TimeoutSeconds $TimeoutSeconds
    if ($exitCode -ne 0)
    {
        throw "Packaged client wrapper exited $exitCode.`nSTDOUT:`n$stdout`nSTDERR:`n$stderr"
    }
}
