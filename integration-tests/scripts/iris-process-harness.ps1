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

function Wait-IrisOwnedRuntimeProcess
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [ValidateRange(1, 600)] [int] $TimeoutSeconds
    )

    $wrapperPid = $Scope.Wrapper.Process.Id
    $runToken = "-Dvibris.automation.runId=$($Scope.RunId)"
    $gameToken = $Scope.GameDir
    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([datetime]::UtcNow -lt $deadline)
    {
        if ($Scope.Wrapper.Process.HasExited)
        {
            $stdout = $Scope.Wrapper.Stdout.Result
            $stderr = $Scope.Wrapper.Stderr.Result
            throw "Packaged client wrapper exited $($Scope.Wrapper.Process.ExitCode) before starting its owned " +
                "runtime.`nSTDOUT:`n$stdout`nSTDERR:`n$stderr"
        }

        $processes = @(Get-CimInstance Win32_Process)
        $byId = @{}
        foreach ($process in $processes) { $byId[[int] $process.ProcessId] = $process }
        $candidates = @($processes | Where-Object {
            $command = [string] $_.CommandLine
            if ($command.IndexOf($runToken, [System.StringComparison]::Ordinal) -lt 0 -or
                $command.IndexOf($gameToken, [System.StringComparison]::OrdinalIgnoreCase) -lt 0)
            {
                return $false
            }

            $parentId = [int] $_.ParentProcessId
            $seen = [System.Collections.Generic.HashSet[int]]::new()
            while ($parentId -gt 0 -and $seen.Add($parentId))
            {
                if ($parentId -eq $wrapperPid) { return $true }
                if (-not $byId.ContainsKey($parentId)) { break }
                $parentId = [int] $byId[$parentId].ParentProcessId
            }
            return $false
        })
        if ($candidates.Count -gt 1)
        {
            throw "Multiple wrapper descendants claim this probe's runtime ownership tokens."
        }
        if ($candidates.Count -eq 1)
        {
            $candidate = $candidates[0]
            if ($Scope.Protected | Where-Object { $_.Pid -eq [int] $candidate.ProcessId })
            {
                throw "The discovered runtime PID belongs to the protected MultiMC process tree."
            }
            $Scope.RuntimePid = [int] $candidate.ProcessId
            $Scope.RuntimeCreated = $candidate.CreationDate.ToUniversalTime().ToString("O")
            Assert-IrisOwnedRuntime -Scope $Scope
            return
        }
        Start-Sleep -Milliseconds 50
    }
    throw "Packaged client did not start its owned runtime within $TimeoutSeconds seconds."
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
        "--no-daemon", ":fabric:runVibrisAutomationClient",
        "-PautomationPatchedJar=$PatchedJar", "-PautomationGameDir=$($Scope.GameDir)",
        "-PautomationRunId=$($Scope.RunId)", "-PautomationScenario=$Scenario"
    )
    $Scope.Wrapper = Start-CoreGradleWrapper -IrisRoot $script:IrisRoot `
        -GradleArguments $arguments
    Wait-IrisOwnedRuntimeProcess -Scope $Scope -TimeoutSeconds $TimeoutSeconds
    Start-IrisWindowGuard -Scope $Scope
    $receipt = Wait-IrisReceipt -Scope $Scope -TimeoutSeconds $TimeoutSeconds
    if ([int] $receipt.pid -ne $Scope.RuntimePid)
    {
        throw "Runtime PID from the ownership receipt does not match the wrapper-owned process."
    }
    $runtime = Get-CimInstance Win32_Process -Filter "ProcessId = $($Scope.RuntimePid)"
    if ($null -eq $runtime) { throw "Runtime PID from the ownership receipt does not exist." }
    $runtimeStart = $runtime.CreationDate.ToUniversalTime()
    $receiptStart = if ($receipt.started_at_utc -is [datetime]) {
        ([datetime] $receipt.started_at_utc).ToUniversalTime()
    } elseif ($receipt.started_at_utc -is [datetimeoffset]) {
        ([datetimeoffset] $receipt.started_at_utc).UtcDateTime
    } else {
        [datetimeoffset]::Parse(
            [string] $receipt.started_at_utc,
            [Globalization.CultureInfo]::InvariantCulture,
            [Globalization.DateTimeStyles]::RoundtripKind).UtcDateTime
    }
    $receiptDelay = ($receiptStart - $runtimeStart).TotalSeconds
    if ($receiptDelay -lt -1 -or $receiptDelay -gt 60)
    {
        throw "Runtime ownership receipt timestamp is outside its process startup window: " +
            "runtime=$($runtimeStart.ToString('O')) receipt=$($receiptStart.ToString('O')) delay_seconds=$receiptDelay."
    }
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
		$hasRunId = $command -match [regex]::Escape("-Dvibris.automation.runId=$($Scope.RunId)")
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

function Stop-IrisPackagedClientCore
{
    param([Parameter(Mandatory)] [object] $Scope, [ValidateRange(1, 600)] [int] $TimeoutSeconds = 90)

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

function Stop-IrisPackagedClient
{
    param([Parameter(Mandatory)] [object] $Scope, [ValidateRange(1, 600)] [int] $TimeoutSeconds = 90)

    try
    {
        Stop-IrisPackagedClientCore -Scope $Scope -TimeoutSeconds $TimeoutSeconds
    }
    finally
    {
        Stop-IrisWindowGuard -Scope $Scope
    }
}
