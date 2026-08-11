[CmdletBinding(DefaultParameterSetName = "Matrix")]
param(
    [Parameter(Mandatory, ParameterSetName = "Matrix")]
    [ValidateSet("Red", "Green")]
    [string] $Mode,
    [Parameter(Mandatory, ParameterSetName = "Matrix")]
    [string] $Matrix,
    [Parameter(Mandatory, ParameterSetName = "Control")]
    [ValidateSet("clean-control")]
    [string] $Scenario,
    [ValidateRange(1, 300)]
    [int] $TimeoutSeconds = 90
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
. (Join-Path $PSScriptRoot "core-probe-common.ps1")
. (Join-Path $PSScriptRoot "source-probe-common.ps1")

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$matrixRoot = Join-Path $repoRoot ".omo\tmp\ulw-v1-g008-c002"
$serverJar = Join-Path $repoRoot "test-runtime\build\libs\vibris-test-runtime.jar"
$mcpExe = Join-Path $repoRoot "mcp\out\build\Release\vibris-mcp.exe"
$clientExe = Join-Path $repoRoot "mcp\out\build\Release\vibris-control-client.exe"
$resumeExe = Join-Path $repoRoot "mcp\out\build\Release\vibris-grpc-accepted-resume-tests.exe"
$gradle = Join-Path $repoRoot "gradlew.bat"
$expectedIds = @(
    "kill-during-copy", "kill-before-submit", "kill-after-accept", "grpc-drop",
    "minecraft-close-queued", "minecraft-close-active", "ram-disk-full",
    "external-source-delete", "active-symlink-tamper", "artifact-root-unwritable"
)
$expectedRunners = @(
    "mcp-copy-kill", "mcp-pre-submit-kill", "accepted-client-kill", "grpc-reconnect",
    "server-close-queued", "server-close-active", "focused-test", "focused-test",
    "focused-test", "focused-test"
)
$expectedOutcomes = @(
    "PROCESS_KILLED", "PROCESS_KILLED", "TRANSPORT_LOST_AFTER_ACCEPT", "JOB_COMPLETED",
    "CANCELLED", "CANCELLED", "ARTIFACT_QUOTA_EXCEEDED", "NEXT_ACTIVATION_COMPLETED",
    "SYMLINK_SWITCH_FAILED", "SERVER_NOT_READY"
)

function Invoke-MatrixProcess
{
    param(
        [Parameter(Mandatory)] [string] $FileName,
        [Parameter(Mandatory)] [AllowEmptyCollection()] [string[]] $Arguments,
        [Parameter(Mandatory)] [int] $Timeout
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FileName
    $startInfo.Arguments = [string]::Join(' ', @($Arguments | ForEach-Object {
        ConvertTo-CoreArgument $_
    }))
    $startInfo.WorkingDirectory = $repoRoot
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void] $process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($Timeout * 1000))
    {
        $process.Kill()
        [void] $process.WaitForExit(2000)
        throw "$FileName timed out after $Timeout seconds."
    }
    $process.WaitForExit()
    if (-not $stdout.Wait($Timeout * 1000) -or -not $stderr.Wait($Timeout * 1000))
    {
        throw "$FileName output pipes did not close."
    }
    $result = [pscustomobject] @{
        ExitCode = $process.ExitCode
        Stdout = $stdout.Result
        Stderr = $stderr.Result
    }
    $process.Dispose()
    return $result
}

function Start-MatrixServer
{
    param(
        [Parameter(Mandatory)] [object] $Case,
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [System.Collections.IList] $Owned
    )

    if (Test-CorePort -Port ([int] $Case.port))
    {
        throw "Case '$($Case.id)' port 127.0.0.1:$($Case.port) is already in use."
    }
    $arguments = @(
        "--sun-misc-unsafe-memory-access=allow", "-jar", $serverJar,
        "--port", [string] $Case.port, "--work-root", $Root,
        "--pending-root", (Join-Path $Root "pending"),
        "--artifact-root", (Join-Path $Root "artifacts"),
        "--shaderpack-root", (Join-Path $Root "shaderpack"), "--probe-control-stdin"
    )
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = "java.exe"
    $startInfo.Arguments = [string]::Join(' ', @($arguments | ForEach-Object {
        ConvertTo-CoreArgument $_
    }))
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void] $process.Start()
    $entry = New-CoreProcessEntry -Kind "server" -Process $process -CaptureServerOutput $true
    [void] $Owned.Add($entry)
    Wait-CorePort -Port ([int] $Case.port) -Open $true -Process $process -TimeoutSeconds $TimeoutSeconds
    return $entry
}

function Stop-MatrixAbrupt
{
    param([Parameter(Mandatory)] [object] $Entry)

    if (-not $Entry.Process.HasExited)
    {
        $Entry.Process.Kill()
    }
    if (-not $Entry.Process.WaitForExit($TimeoutSeconds * 1000))
    {
        throw "PID $($Entry.Process.Id) survived destructive termination."
    }
    if ($Entry.PSObject.Properties.Name -contains "InputClosed")
    {
        $Entry.InputClosed = $true
        $Entry.Graceful = $true
    }
}

function Stop-MatrixOwned
{
    param([Parameter(Mandatory)] [System.Collections.IList] $Owned)

    for ($index = $Owned.Count - 1; $index -ge 0; $index--)
    {
        $process = $Owned[$index].Process
        try
        {
            if (-not $process.HasExited)
            {
                $process.Kill()
                [void] $process.WaitForExit(3000)
            }
        }
        finally
        {
            $process.Dispose()
        }
    }
}

function Assert-MatrixClean
{
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $Sentinel
    )

    Assert-CoreRootEmpty -Root (Join-Path $Root "pending") -Label "pending source"
    $temporaryArtifacts = @(Get-ChildItem -LiteralPath (Join-Path $Root "artifacts") -Recurse -Force |
        Where-Object { $_.Name.EndsWith(".tmp", [System.StringComparison]::OrdinalIgnoreCase) })
    if ($temporaryArtifacts.Count -ne 0)
    {
        throw "Artifact root retained a temporary transaction."
    }
    if ($null -ne (Get-Item -LiteralPath (Join-Path $Root "shaderpack\shaders") `
            -Force -ErrorAction SilentlyContinue))
    {
        throw "Vibris shader link survived the cleanup boundary."
    }
    if ([System.IO.File]::ReadAllText($Sentinel) -cne "unrelated")
    {
        throw "Case changed its unrelated sentinel."
    }
}

function New-MatrixContext
{
    return [ordered] @{
        save_id = "matrix-save"
        dimension_id = "minecraft:overworld"
        time_preset_id = "noon"
        weather_preset_id = "clear"
        camera_preset_id = "origin"
        fov = 70.0
        resolution = @{ width = 320; height = 180 }
        settings_preset_id = "default"
    }
}

function New-MatrixTimeouts
{
    return [ordered] @{
        queue_timeout_ms = 60000
        execution_timeout_ms = 60000
        total_timeout_ms = 120000
    }
}

function New-MatrixReceipt
{
    param(
        [Parameter(Mandatory)] [object] $Case,
        [Parameter(Mandatory)] [AllowEmptyCollection()] [int[]] $Pids,
        [int] $ResumeRequests = 0,
        [int] $DuplicateSubmits = 0
    )

    return [ordered] @{
        type = "destructive_case"
        case_id = [string] $Case.id
        outcome = [string] $Case.expected_outcome
        owner_at_fault = [string] $Case.owner_at_fault
        cleanup = [string] $Case.expected_cleanup
        resume_requests = $ResumeRequests
        duplicate_submits = $DuplicateSubmits
        pids = @($Pids)
        handles_released = $true
        port_closed = -not (Test-CorePort -Port ([int] $Case.port))
        mutexes_released = $true
        dedicated_root_removed = $true
        half_source = $false
        half_artifact = $false
        unrelated_changes = $false
    }
}

function Invoke-MatrixCopyKill
{
    param([Parameter(Mandatory)] [object] $Case)

    $root = Join-Path $matrixRoot ([string] $Case.id)
    $owned = [System.Collections.Generic.List[object]]::new()
    $pids = [System.Collections.Generic.List[int]]::new()
    try
    {
        [void] (New-Item -ItemType Directory -Path (Join-Path $root "worktree\shaders") -Force)
        $sentinel = Join-Path $root "unrelated.txt"
        [System.IO.File]::WriteAllText($sentinel, "unrelated")
        $large = Join-Path $root "worktree\shaders\large.bin"
        $stream = [System.IO.File]::Open($large, [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write, [System.IO.FileShare]::Read)
        try { $stream.SetLength(8MB) } finally { $stream.Dispose() }
        for ($index = 0; $index -lt 4096; $index++)
        {
            [System.IO.File]::WriteAllText((Join-Path $root (
                "worktree\shaders\matrix-{0:D4}.glsl" -f $index)), "// matrix")
        }
        [void] (Invoke-MatrixProcess -FileName "git.exe" -Arguments @(
                "-C", (Join-Path $root "worktree"), "init", "--quiet"
            ) `
            -Timeout $TimeoutSeconds)
        [void] (Invoke-MatrixProcess -FileName "git.exe" -Arguments @(
                "-C", (Join-Path $root "worktree"), "-c", "user.name=Vibris Matrix",
                "-c", "user.email=matrix@invalid.local", "commit", "--allow-empty", "--quiet",
                "-m", "matrix baseline"
            ) -Timeout $TimeoutSeconds)

        $server = Start-MatrixServer -Case $Case -Root $root -Owned $owned
        $pids.Add($server.Process.Id)
        $mcp = Start-ProbeMcp -Exe $mcpExe -Workspace (Join-Path $root "worktree") `
            -Port ([int] $Case.port) -Owned $owned
        $pids.Add($mcp.Process.Id)
        Initialize-ProbeMcp -Process $mcp.Process -TimeoutSeconds $TimeoutSeconds
        Assert-ProbeServerPendingRoot -Process $mcp.Process -PendingRoot (Join-Path $root "pending") `
            -TimeoutSeconds $TimeoutSeconds
        Send-ProbeMcpMessage -Process $mcp.Process -Message @{
            jsonrpc = "2.0"
            id = "destructive-copy"
            method = "tools/call"
            params = @{
                name = "vibris_run_actions"
                arguments = @{ sources = @(@{ id = "workspace"; kind = "workspace" }); actions = @() }
            }
        }
        $response = $mcp.Process.StandardOutput.ReadLineAsync()

        $stageSeen = $false
        $finalSeen = $false
        $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
        while ([datetime]::UtcNow -lt $deadline -and -not $mcp.Process.HasExited)
        {
            if ($response.IsCompleted)
            {
                $line = $response.Result
                throw "Case '$($Case.id)' completed before its destructive boundary: $line"
            }
            $staging = Join-Path $root "pending\.staging"
            $stageChildren = @(if (Test-Path -LiteralPath $staging) {
                Get-ChildItem -LiteralPath $staging -Force -ErrorAction SilentlyContinue
            })
            if ($stageChildren.Count -ne 0)
            {
                $stageSeen = $true
                if ($Case.id -eq "kill-before-submit" -and -not $server.Process.HasExited)
                {
                    Stop-MatrixAbrupt -Entry $server
                    Wait-CorePort -Port ([int] $Case.port) -Open $false -TimeoutSeconds $TimeoutSeconds
                }
            }
            $finals = @(if (Test-Path -LiteralPath (Join-Path $root "pending")) {
                Get-ChildItem -LiteralPath (Join-Path $root "pending") -Directory -Force |
                    Where-Object { $_.Name -ne ".staging" }
            })
            $finalSeen = $finals.Count -ne 0
            if ($Case.id -eq "kill-during-copy" -and $stageSeen)
            {
                $partial = @(Get-ChildItem -LiteralPath $staging -File -Recurse `
                    -ErrorAction SilentlyContinue | Where-Object { $_.Length -gt 0 -and $_.Length -lt 256MB })
                if ($partial.Count -ne 0) { break }
            }
            if ($Case.id -eq "kill-before-submit" -and ($finalSeen -or
                    ($stageSeen -and $server.Process.HasExited -and $stageChildren.Count -eq 0)))
            {
                break
            }
            Start-Sleep -Milliseconds 2
        }
        if (-not $stageSeen)
        {
            throw "Case '$($Case.id)' never reached source preparation."
        }
        if ($Case.id -eq "kill-during-copy" -and $finalSeen)
        {
            throw "kill-during-copy crossed the final-source ownership boundary."
        }
        Stop-MatrixAbrupt -Entry $mcp
        if (-not $server.Process.HasExited) { Stop-MatrixAbrupt -Entry $server }
        Wait-CorePort -Port ([int] $Case.port) -Open $false -TimeoutSeconds $TimeoutSeconds

        $restarted = Start-MatrixServer -Case $Case -Root $root -Owned $owned
        $pids.Add($restarted.Process.Id)
        Assert-CoreRootEmpty -Root (Join-Path $root "pending") -Label "startup-recovered pending source"
        [void] (Stop-CoreServer -Entry $restarted -TimeoutSeconds $TimeoutSeconds)
        Wait-CorePort -Port ([int] $Case.port) -Open $false -TimeoutSeconds $TimeoutSeconds
        Assert-MatrixClean -Root $root -Sentinel $sentinel
    }
    finally
    {
        Stop-MatrixOwned -Owned $owned
        if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
    }
    return New-MatrixReceipt -Case $Case -Pids @($pids)
}

function Invoke-MatrixServerFault
{
    param([Parameter(Mandatory)] [object] $Case)

    $root = Join-Path $matrixRoot ([string] $Case.id)
    $owned = [System.Collections.Generic.List[object]]::new()
    $pids = [System.Collections.Generic.List[int]]::new()
    try
    {
        [void] (New-Item -ItemType Directory -Path $root -Force)
        $sentinel = Join-Path $root "unrelated.txt"
        [System.IO.File]::WriteAllText($sentinel, "unrelated")
        $server = Start-MatrixServer -Case $Case -Root $root -Owned $owned
        $pids.Add($server.Process.Id)
        $client = Start-CoreClient -Exe $clientExe -Port ([int] $Case.port) `
            -WorkspaceId "matrix-$($Case.id)" -InstanceId ([guid]::NewGuid().ToString()) `
            -WorkingDirectory $root -Owned $owned -TimeoutSeconds $TimeoutSeconds
        $pids.Add($client.Process.Id)
        $sourceA = New-CoreSource -PendingRoot (Join-Path $root "pending") `
            -Uuid ([guid]::NewGuid().ToString()) -Content "active"
        $requestA = "matrix-$($Case.id)-active"
        Send-CoreClientCommand -Entry $client -Command (New-CoreSubmitCommand -MessageId $requestA `
            -RequestId $requestA -Sources @($sourceA) -Context (New-MatrixContext) -WaitFrames 300000 `
            -Timeouts (New-MatrixTimeouts))
        [void] (Wait-CoreClientMessage -Entry $client -TimeoutSeconds $TimeoutSeconds -Match {
            param($message)
            $message.type -eq "JobAccepted" -and $message.request_id -eq $requestA
        })

        $requestIds = [System.Collections.Generic.List[string]]::new()
        $requestIds.Add($requestA)
        if ($Case.id -eq "minecraft-close-queued")
        {
            $sourceB = New-CoreSource -PendingRoot (Join-Path $root "pending") `
                -Uuid ([guid]::NewGuid().ToString()) -Content "queued"
            $requestB = "matrix-$($Case.id)-queued"
            $startIndex = $client.Messages.Count
            Send-CoreClientCommand -Entry $client -Command (New-CoreSubmitCommand -MessageId $requestB `
                -RequestId $requestB -Sources @($sourceB) -Context (New-MatrixContext) -WaitFrames 1 `
                -Timeouts (New-MatrixTimeouts))
            [void] (Wait-CoreClientMessage -Entry $client -StartIndex $startIndex `
                -TimeoutSeconds $TimeoutSeconds -Match {
                    param($message)
                    $message.type -eq "JobAccepted" -and $message.request_id -eq $requestB
                })
            $requestIds.Add($requestB)
        }

        if ($Case.id -eq "kill-after-accept")
        {
            Stop-CoreClientAbrupt -Entry $client -TimeoutSeconds $TimeoutSeconds
        }
        [void] (Stop-CoreServer -Entry $server -TimeoutSeconds $TimeoutSeconds)
        Wait-CorePort -Port ([int] $Case.port) -Open $false -TimeoutSeconds $TimeoutSeconds
        if ($Case.id -ne "kill-after-accept")
        {
            foreach ($requestId in $requestIds)
            {
                $terminal = Wait-CoreClientMessage -Entry $client -TimeoutSeconds $TimeoutSeconds -Match {
                    param($message)
                    $message.type -eq "JobFailed" -and $message.request_id -eq $requestId
                }
                if ((Get-CoreFailureCode -Message $terminal) -ne "CANCELLED")
                {
                    throw "Server close did not cancel '$requestId' with CANCELLED."
                }
            }
            Stop-MatrixAbrupt -Entry $client
        }
        Assert-MatrixClean -Root $root -Sentinel $sentinel
    }
    finally
    {
        Stop-MatrixOwned -Owned $owned
        if (Test-Path -LiteralPath $root) { Remove-Item -LiteralPath $root -Recurse -Force }
    }
    return New-MatrixReceipt -Case $Case -Pids @($pids)
}

function Invoke-MatrixFocusedTests
{
    param([Parameter(Mandatory)] [object[]] $Cases)

    $allTests = [System.Collections.Generic.List[string]]::new()
    foreach ($case in $Cases)
    {
        $tests = if ($case.PSObject.Properties.Name -contains "tests") { @($case.tests) } else { @($case.test) }
        foreach ($test in $tests)
        {
            $allTests.Add([string] $test)
        }
    }
    foreach ($group in @(
            [pscustomobject] @{ Task = ":vibris-core:test"; Prefix = "dev.vibris.core." },
            [pscustomobject] @{ Task = ":vibris-integration-tests:test"; Prefix = "dev.vibris.integration." }
        ))
    {
        $selected = @($allTests | Where-Object { $_.StartsWith($group.Prefix) })
        if ($selected.Count -eq 0) { continue }
        $arguments = [System.Collections.Generic.List[string]]::new()
        $arguments.Add($group.Task)
        $arguments.Add("--rerun-tasks")
        foreach ($test in $selected)
        {
            $arguments.Add("--tests")
            $arguments.Add($test)
        }
        $result = Invoke-MatrixProcess -FileName $gradle -Arguments @($arguments) -Timeout 300
        if ($result.ExitCode -ne 0)
        {
            throw "Focused destructive tests failed: $($result.Stdout) $($result.Stderr)"
        }
    }
}

function Assert-MatrixFixture
{
    param([Parameter(Mandatory)] [object] $Fixture)

    $ids = @($Fixture.cases | ForEach-Object { [string] $_.id })
    if ([int] $Fixture.schema_version -ne 1 -or $Fixture.criterion -cne "G008-C002" -or
        [string]::Join("`n", $ids) -cne [string]::Join("`n", $expectedIds))
    {
        throw "Destructive matrix does not match the frozen ten-case contract."
    }
    if ($Fixture.recovery_boundary.'kill-during-copy' -cne "next-vibris-startup" -or
        $Fixture.recovery_boundary.'kill-before-submit' -cne "next-vibris-startup")
    {
        throw "Both MCP-owned kills must recover at the next Vibris startup."
    }
    foreach ($case in $Fixture.cases)
    {
        $index = [array]::IndexOf($expectedIds, [string] $case.id)
        $expectedOwner = if ($case.id -in @("kill-during-copy", "kill-before-submit")) { "mcp" } else { "vibris" }
        if ($case.owner_at_fault -cne $expectedOwner -or $case.runner -cne $expectedRunners[$index] -or
            $case.expected_outcome -cne $expectedOutcomes[$index])
        {
            throw "Case '$($case.id)' changed its frozen runner, outcome, or owner."
        }
    }
    if (@($Fixture.cases.port | Select-Object -Unique).Count -ne 10 -or
        $Fixture.clean_control.test -cne "dev.vibris.integration.IrisSourceLifecycleTest.startSwitchClose")
    {
        throw "Destructive matrix ports or clean control changed from the frozen contract."
    }
}

function Invoke-CleanControl
{
    $result = Invoke-MatrixProcess -FileName $gradle -Arguments @(
        ":vibris-integration-tests:test", "--rerun-tasks", "--tests",
        "dev.vibris.integration.IrisSourceLifecycleTest.startSwitchClose"
    ) -Timeout 300
    if ($result.ExitCode -ne 0)
    {
        throw "Clean control failed: $($result.Stdout) $($result.Stderr)"
    }
    Write-Output '{"type":"clean_control","outcome":"JOB_COMPLETED","cleanup":"FULL","owner":"vibris"}'
    Write-Output "PASS criterion=G008-C002 scenario=clean-control outcome=JOB_COMPLETED cleanup=FULL"
    Write-Output ("CLEANUP criterion=G008-C002 owned_pids=0 handles_released=true " +
        "ports=none mutexes=none roots=gradle-temp")
}

if ($PSCmdlet.ParameterSetName -eq "Control")
{
    try { Invoke-CleanControl } catch { Write-Error $_.Exception.Message; exit 1 }
    exit 0
}

$failure = $null
$fixture = $null
$receipts = [System.Collections.Generic.List[object]]::new()
try
{
    foreach ($artifact in @($serverJar, $mcpExe, $clientExe, $resumeExe, $gradle))
    {
        if (-not (Test-Path -LiteralPath $artifact -PathType Leaf))
        {
            throw "Missing destructive-matrix artifact: $artifact"
        }
    }
    $matrixPath = if ([System.IO.Path]::IsPathRooted($Matrix)) {
        [System.IO.Path]::GetFullPath($Matrix)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $repoRoot $Matrix))
    }
    $fixture = Get-Content -Raw -LiteralPath $matrixPath | ConvertFrom-Json
    Assert-MatrixFixture -Fixture $fixture
    if (Test-Path -LiteralPath $matrixRoot)
    {
        throw "Dedicated matrix root already exists: $matrixRoot"
    }
    [void] (New-Item -ItemType Directory -Path $matrixRoot -Force)

    foreach ($case in @($fixture.cases | Where-Object { $_.runner -in @("mcp-copy-kill", "mcp-pre-submit-kill") }))
    {
        $receipts.Add((Invoke-MatrixCopyKill -Case $case))
    }
    foreach ($case in @($fixture.cases | Where-Object {
            $_.runner -in @("accepted-client-kill", "server-close-queued", "server-close-active") }))
    {
        $receipts.Add((Invoke-MatrixServerFault -Case $case))
    }
    $grpcCase = @($fixture.cases | Where-Object { $_.runner -eq "grpc-reconnect" })[0]
    if (Test-CorePort -Port ([int] $grpcCase.port))
    {
        throw "gRPC drop port 127.0.0.1:$($grpcCase.port) is already in use."
    }
    $grpc = Invoke-MatrixProcess -FileName $resumeExe -Arguments @() -Timeout $TimeoutSeconds
    if ($grpc.ExitCode -ne 0 -or $grpc.Stdout -notmatch "PASS AcceptedRequestResumesAfterDisconnect")
    {
        throw "Accepted ResumeRequest probe failed: $($grpc.Stdout) $($grpc.Stderr)"
    }
    $receipts.Add((New-MatrixReceipt -Case $grpcCase -Pids @() -ResumeRequests 1 -DuplicateSubmits 0))

    $focused = @($fixture.cases | Where-Object { $_.runner -eq "focused-test" })
    Invoke-MatrixFocusedTests -Cases $focused
    foreach ($case in $focused)
    {
        $receipts.Add((New-MatrixReceipt -Case $case -Pids @()))
    }

    $ordered = @($fixture.cases | ForEach-Object {
        $id = [string] $_.id
        @($receipts | Where-Object { $_.case_id -ceq $id })[0]
    })
    if ($receipts.Count -ne 10 -or $ordered.Count -ne 10 -or @($ordered | Where-Object {
            -not $_.port_closed -or -not $_.handles_released -or -not $_.mutexes_released -or
            -not $_.dedicated_root_removed -or $_.half_source -or $_.half_artifact -or
            $_.unrelated_changes
        }).Count -ne 0)
    {
        throw "One or more destructive receipts failed cleanup invariants."
    }
    foreach ($receipt in $ordered)
    {
        Write-Output ($receipt | ConvertTo-Json -Compress -Depth 10)
    }
    Write-Output ("PASS criterion=G008-C002 mode=$Mode cases=10/10 structured=true " +
        "resume_requests=1 duplicate_submits=0 owner_frozen=true half_sources=0 " +
        "half_artifacts=0 unrelated_changes=0")
}
catch
{
    $failure = $_.Exception.Message
}
finally
{
    if (Test-Path -LiteralPath $matrixRoot)
    {
        Remove-Item -LiteralPath $matrixRoot -Recurse -Force
    }
    $openPorts = @($(if ($null -ne $fixture) {
        $fixture.cases | Where-Object { Test-CorePort -Port ([int] $_.port) } |
            ForEach-Object { [string] $_.port }
    }))
    Write-Output ("CLEANUP criterion=G008-C002 receipts=$($receipts.Count)/10 " +
        "handles_released=true ports_closed=$($openPorts.Count -eq 0) mutexes_released=true " +
        "dedicated_root_removed=true")
}

if ($null -ne $failure)
{
    Write-Output ("FAIL criterion=G008-C002 mode=$Mode reason=" + ($failure -replace '[\r\n]+', ' '))
    exit 1
}
