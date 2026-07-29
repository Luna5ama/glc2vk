[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $ServerJar,
    [Parameter(Mandatory)] [string] $Client,
    [Parameter(Mandatory)] [string] $Scenario,
    [Parameter(Mandatory)] [string] $Listen,
    [ValidateRange(1, 300)] [int] $TimeoutSeconds = 120
)

. (Join-Path $PSScriptRoot "core-probe-common.ps1")

$owned = [System.Collections.Generic.List[object]]::new()
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$expectedRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ".omo\tmp\ulw-v1-g004-c002"))
$expectedScenario = Join-Path $repoRoot `
    "integration-tests\fixtures\core\queue33-duplicate-cancel-timeout-resume.json"
$criterionRoot = $expectedRoot
$pendingRoot = Join-Path $criterionRoot "pending"
$artifactRoot = Join-Path $criterionRoot "artifacts"
$criterionCreated = $false
$failure = $null
$summaryLine = $null
$cleanupLine = $null
$port = 0
$server = $null
$serverMessages = @()

function Get-QueuedRequestId
{
    param([int] $Index)

    return "queued-{0:D2}" -f $Index
}

function Get-QueuedSourceUuid
{
    param([int] $Index)

    return "40000000-0000-4000-8000-{0:D12}" -f ($Index + 1)
}

function Wait-RequestTerminal
{
    param(
        [object] $Entry,
        [string] $RequestId,
        [int] $StartIndex,
        [int] $Timeout
    )

    return Wait-CoreClientMessage -Entry $Entry -StartIndex $StartIndex -TimeoutSeconds $Timeout `
        -Match {
            param($message)
            $message.request_id -eq $RequestId -and
                $message.type -in @("JobCompleted", "JobFailed")
        }
}

function Assert-SourceDeleted
{
    param(
        [object[]] $Messages,
        [string] $Uuid,
        [string] $Pending
    )

    $transitions = @($Messages | Where-Object {
        $_.type -eq "SourceTransition" -and $_.uuid -eq $Uuid
    })
    if ($transitions.Count -eq 0 -or [string] $transitions[-1].to -notmatch 'DELETED$' -or
        (Test-Path -LiteralPath (Join-Path $Pending $Uuid)))
    {
        throw "Source $Uuid was not deleted after server ownership ended."
    }
}

try
{
    $port = Resolve-CoreListen -Listen $Listen
    if ($port -ne 55072)
    {
        throw "G004-C002 requires literal listen endpoint 127.0.0.1:55072."
    }
    $resolvedScenario = [System.IO.Path]::GetFullPath($Scenario)
    if (-not [string]::Equals($resolvedScenario, $expectedScenario,
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "G004-C002 requires scenario $expectedScenario."
    }
    Assert-CoreArtifact -Path $ServerJar -Label "fake server JAR"
    Assert-CoreArtifact -Path $Client -Label "native control client"
    Assert-CoreArtifact -Path $resolvedScenario -Label "adversarial scenario"
    $scenarioData = Get-Content -LiteralPath $resolvedScenario -Raw | ConvertFrom-Json
    if ($scenarioData.schema_version -ne 1 -or $scenarioData.queue_limit -ne 32 -or
        $scenarioData.queued_request_count -ne 33 -or
        $scenarioData.overflow_request_id -cne "queued-33" -or
        $scenarioData.cancel_request_id -cne "queued-01" -or
        $scenarioData.duplicate_request_id -cne "queued-02")
    {
        throw "Adversarial fixture does not encode the frozen queue-33 scenario."
    }
    [void] (Get-CoreContextSignature -Context $scenarioData.context)

    $criterionRoot = Assert-CoreCriterionRoot -Root $criterionRoot -ExpectedRoot $expectedRoot
    $criterionCreated = $true
    [void] (New-Item -ItemType Directory -Path $pendingRoot)
    [void] (New-Item -ItemType Directory -Path $artifactRoot)
    $server = Start-CoreServer -Jar $ServerJar -Port $port -WorkRoot $criterionRoot `
        -PendingRoot $pendingRoot -ArtifactRoot $artifactRoot -Owned $owned `
        -TimeoutSeconds $TimeoutSeconds

    $sources = @{}
    $blocker = $scenarioData.blocker
    $sources[[string] $blocker.request_id] = New-CoreSource -PendingRoot $pendingRoot `
        -Uuid ([string] $blocker.source_uuid) -Content "active-timeout"
    for ($index = 1; $index -le $scenarioData.queued_request_count; $index++)
    {
        $requestId = Get-QueuedRequestId -Index $index
        $sources[$requestId] = New-CoreSource -PendingRoot $pendingRoot `
            -Uuid (Get-QueuedSourceUuid -Index $index) -Content $requestId
    }
    foreach ($resumeJob in @($scenarioData.resume_running, $scenarioData.resume_queued))
    {
        $sources[[string] $resumeJob.request_id] = New-CoreSource -PendingRoot $pendingRoot `
            -Uuid ([string] $resumeJob.source_uuid) -Content ([string] $resumeJob.request_id)
    }

    $clientEntry = Start-CoreClient -Exe $Client -Port $port `
        -WorkspaceId ([string] $scenarioData.workspace_id) `
        -InstanceId ([string] $scenarioData.instance_id) -WorkingDirectory $criterionRoot `
        -Owned $owned -TimeoutSeconds $TimeoutSeconds
    $blockerCommand = New-CoreSubmitCommand -MessageId "submit-active-timeout" `
        -RequestId ([string] $blocker.request_id) -Sources @($sources[[string] $blocker.request_id]) `
        -Context $scenarioData.context -WaitFrames $blocker.wait_frames -Timeouts $blocker.timeouts
    Send-CoreClientCommand -Entry $clientEntry -Command $blockerCommand
    [void] (Wait-CoreClientMessage -Entry $clientEntry -StartIndex 0 `
        -TimeoutSeconds $TimeoutSeconds -Match {
            param($message)
            $message.type -eq "JobProgress" -and
                $message.request_id -eq "active-timeout" -and
                [string] $message.stage -match 'WARMING_UP$'
        })

    for ($index = 1; $index -le $scenarioData.queued_request_count; $index++)
    {
        $requestId = Get-QueuedRequestId -Index $index
        $command = New-CoreSubmitCommand -MessageId "submit-$requestId" -RequestId $requestId `
            -Sources @($sources[$requestId]) -Context $scenarioData.context -WaitFrames 1 `
            -Timeouts $scenarioData.normal_timeouts
        Send-CoreClientCommand -Entry $clientEntry -Command $command
    }
    $overflow = Wait-RequestTerminal -Entry $clientEntry -RequestId "queued-33" `
        -StartIndex 0 -Timeout $TimeoutSeconds
    if ($overflow.type -ne "JobFailed" -or (Get-CoreFailureCode -Message $overflow) -ne "QUEUE_FULL")
    {
        throw "The 33rd queued request did not return QUEUE_FULL."
    }
    if (-not (Test-Path -LiteralPath (Join-Path $pendingRoot $sources["queued-33"].uuid)))
    {
        throw "The rejected 33rd source did not remain client-owned."
    }
    Send-CoreClientCommand -Entry $clientEntry -Command ([ordered] @{
        op = "cancel"
        message_id = "cancel-queued-01"
        request_id = "queued-01"
        reason = "probe queued cancellation"
    })

    $activeTimeout = Wait-RequestTerminal -Entry $clientEntry -RequestId "active-timeout" `
        -StartIndex 0 -Timeout $TimeoutSeconds
    if ($activeTimeout.type -ne "JobFailed" -or
        (Get-CoreFailureCode -Message $activeTimeout) -ne "EXECUTION_TIMEOUT")
    {
        throw "Active timeout did not stop with EXECUTION_TIMEOUT at a safe point."
    }
    if (Test-Path -LiteralPath (Join-Path $pendingRoot $sources["active-timeout"].uuid))
    {
        throw "Timed-out source remained after its terminal response."
    }
    $cancelled = Wait-RequestTerminal -Entry $clientEntry -RequestId "queued-01" `
        -StartIndex 0 -Timeout $TimeoutSeconds
    if ($cancelled.type -ne "JobFailed" -or (Get-CoreFailureCode -Message $cancelled) -ne "CANCELLED")
    {
        throw "Queued cancellation did not return CANCELLED."
    }
    if (Test-Path -LiteralPath (Join-Path $pendingRoot $sources["queued-01"].uuid))
    {
        throw "Queued-cancel source remained after its terminal response."
    }
    $firstDuplicateResult = $null
    for ($index = 2; $index -le 32; $index++)
    {
        $requestId = Get-QueuedRequestId -Index $index
        $terminal = Wait-RequestTerminal -Entry $clientEntry -RequestId $requestId `
            -StartIndex 0 -Timeout $TimeoutSeconds
        if ($terminal.type -ne "JobCompleted")
        {
            throw "Queued request $requestId did not drain successfully."
        }
        if ($requestId -eq "queued-02")
        {
            $firstDuplicateResult = $terminal
        }
    }

    $replayStart = $clientEntry.Messages.Count
    $replay = New-CoreSubmitCommand -MessageId "duplicate-replay" -RequestId "queued-02" `
        -Sources @($sources["queued-02"]) -Context $scenarioData.context -WaitFrames 1 `
        -Timeouts $scenarioData.normal_timeouts
    Send-CoreClientCommand -Entry $clientEntry -Command $replay
    $replayed = Wait-RequestTerminal -Entry $clientEntry -RequestId "queued-02" `
        -StartIndex $replayStart -Timeout $TimeoutSeconds
    $firstJson = $firstDuplicateResult.result | ConvertTo-Json -Compress -Depth 30
    $replayJson = $replayed.result | ConvertTo-Json -Compress -Depth 30
    if ($replayed.type -ne "JobCompleted" -or $replayed.message_id -ne "duplicate-replay" -or
        $replayJson -cne $firstJson)
    {
        throw "Duplicate request_id did not return the same cached result."
    }
    Remove-CoreUnownedSource -PendingRoot $pendingRoot -Uuid $sources["queued-33"].uuid

    $running = $scenarioData.resume_running
    $runningCommand = New-CoreSubmitCommand -MessageId "submit-resume-running" `
        -RequestId ([string] $running.request_id) -Sources @($sources[[string] $running.request_id]) `
        -Context $scenarioData.context -WaitFrames $running.wait_frames `
        -Timeouts $scenarioData.normal_timeouts
    Send-CoreClientCommand -Entry $clientEntry -Command $runningCommand
    [void] (Wait-CoreClientMessage -Entry $clientEntry -StartIndex 0 `
        -TimeoutSeconds $TimeoutSeconds -Match {
            param($message)
            $message.type -eq "JobProgress" -and $message.request_id -eq "resume-running" -and
                [string] $message.stage -match 'WARMING_UP$'
        })
    $queued = $scenarioData.resume_queued
    $queuedCommand = New-CoreSubmitCommand -MessageId "submit-resume-queued" `
        -RequestId ([string] $queued.request_id) -Sources @($sources[[string] $queued.request_id]) `
        -Context $scenarioData.context -WaitFrames $queued.wait_frames `
        -Timeouts $scenarioData.normal_timeouts
    $queuedStart = $clientEntry.Messages.Count
    Send-CoreClientCommand -Entry $clientEntry -Command $queuedCommand
    [void] (Wait-CoreClientMessage -Entry $clientEntry -StartIndex $queuedStart `
        -TimeoutSeconds $TimeoutSeconds -Match {
            param($message)
            $message.type -eq "JobAccepted" -and $message.request_id -eq "resume-queued"
        })
    Stop-CoreClient -Entry $clientEntry -TimeoutSeconds $TimeoutSeconds

    $resumeClient = Start-CoreClient -Exe $Client -Port $port `
        -WorkspaceId ([string] $scenarioData.workspace_id) `
        -InstanceId ([string] $scenarioData.resume_instance_id) -WorkingDirectory $criterionRoot `
        -Owned $owned -TimeoutSeconds $TimeoutSeconds
    $resumeStart = $resumeClient.Messages.Count
    Send-CoreClientCommand -Entry $resumeClient -Command ([ordered] @{
        op = "resume"
        message_id = "resume-state"
        request_ids = @($scenarioData.resume_request_ids)
    })
    $resumeState = Wait-CoreClientMessage -Entry $resumeClient -StartIndex $resumeStart `
        -TimeoutSeconds $TimeoutSeconds -Match { param($message) $message.type -eq "ResumeState" }
    $stateByRequest = @{}
    foreach ($job in @($resumeState.jobs))
    {
        $stateByRequest[[string] $job.request_id] = [string] $job.state
    }
    if ($stateByRequest["resume-running"] -notmatch 'RUNNING$' -or
        $stateByRequest["resume-queued"] -notmatch '(QUEUED|ACCEPTED)$' -or
        $stateByRequest["queued-02"] -notmatch 'COMPLETED$')
    {
        throw "Resume did not return the accepted/running/completed registry states."
    }
    $completedReplay = Wait-RequestTerminal -Entry $resumeClient -RequestId "queued-02" `
        -StartIndex $resumeStart -Timeout $TimeoutSeconds
    if ($completedReplay.type -ne "JobCompleted")
    {
        throw "Completed resume did not replay its cached terminal result."
    }
    foreach ($requestId in @("resume-queued", "resume-running"))
    {
        Send-CoreClientCommand -Entry $resumeClient -Command ([ordered] @{
            op = "cancel"
            message_id = "cancel-$requestId"
            request_id = $requestId
            reason = "probe drain after resume"
        })
    }
    foreach ($requestId in @("resume-queued", "resume-running"))
    {
        $terminal = Wait-RequestTerminal -Entry $resumeClient -RequestId $requestId `
            -StartIndex 0 -Timeout $TimeoutSeconds
        if ($terminal.type -ne "JobFailed" -or (Get-CoreFailureCode -Message $terminal) -ne "CANCELLED")
        {
            throw "Resumed request $requestId did not cancel during drain."
        }
    }
    Stop-CoreClient -Entry $resumeClient -SendClose -TimeoutSeconds $TimeoutSeconds
    $serverMessages = @(Stop-CoreServer -Entry $server -TimeoutSeconds $TimeoutSeconds)
    Wait-CorePort -Port $port -Open $false -TimeoutSeconds $TimeoutSeconds

    $serverSummary = Get-CoreSummary -Messages $serverMessages
    if ([int] $serverSummary.queue_limit -ne 32 -or [int] $serverSummary.queue_peak -ne 32 -or
        [int] $serverSummary.request_registry_peak -gt [int] $serverSummary.request_registry_limit -or
        [int] $serverSummary.request_registry_size -gt [int] $serverSummary.request_registry_limit -or
        [int] $serverSummary.source_registry_limit -ne 128 -or
        [int] $serverSummary.source_registry_peak -gt [int] $serverSummary.source_registry_limit -or
        [int] $serverSummary.source_registry_size -ne 0)
    {
        throw "Server registries exceeded their declared bounds or retained a source."
    }
    if ([int] $serverSummary.max_concurrent_jobs -ne 1 -or [int] $serverSummary.max_runtime_operations -ne 1 -or
        "active-timeout:SAFE_POINT_TIMEOUT" -notin @($serverSummary.execution_events))
    {
        throw "Execution timeout was not observed at a non-overlapping runtime safe point."
    }
    if ((Get-CoreExecutionCount -Summary $serverSummary -RequestId "queued-02") -ne 1 -or
        (Get-CoreExecutionCount -Summary $serverSummary -RequestId "queued-33") -ne 0 -or
        (Get-CoreExecutionCount -Summary $serverSummary -RequestId "resume-running") -gt 1 -or
        (Get-CoreExecutionCount -Summary $serverSummary -RequestId "resume-queued") -gt 1)
    {
        throw "Duplicate, rejected, or resumed requests were resubmitted."
    }
    Assert-SourceDeleted -Messages $serverMessages -Uuid $sources["queued-01"].uuid `
        -Pending $pendingRoot
    Assert-SourceDeleted -Messages $serverMessages -Uuid $sources["active-timeout"].uuid `
        -Pending $pendingRoot
    Assert-CoreRootEmpty -Root $pendingRoot -Label "pending source"
    Assert-CoreRootEmpty -Root $artifactRoot -Label "artifact"
    $summaryLine = "PASS criterion=G004-C002 queue_full=33rd duplicate_once=true queued_cancel=true " +
        "active_timeout=true resume=accepted-running-completed registries=bounded"
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
        Stop-CoreOwnedProcesses -Owned $owned -TimeoutSeconds 3
    }
    catch
    {
        $cleanupErrors.Add($_.Exception.Message)
    }
    try
    {
        if ($port -ne 0)
        {
            Wait-CorePort -Port $port -Open $false -TimeoutSeconds 3
        }
    }
    catch
    {
        $cleanupErrors.Add($_.Exception.Message)
    }
    try
    {
        if ($criterionCreated)
        {
            Remove-CoreCriterionRoot -Root $criterionRoot -ExpectedRoot $expectedRoot
        }
    }
    catch
    {
        $cleanupErrors.Add($_.Exception.Message)
    }
    if ($cleanupErrors.Count -ne 0)
    {
        $cleanupFailure = [string]::Join('; ', $cleanupErrors)
        $failure = if ($null -eq $failure) { $cleanupFailure } else { "$failure; cleanup: $cleanupFailure" }
    }
    $cleanupLine = "CLEANUP criterion=G004-C002 owned_pids=$($owned.Count) " +
        "listener_closed=$(-not (Test-CorePort -Port 55072)) root_removed=$(-not (Test-Path $expectedRoot))"
}

if ($null -ne $failure)
{
    Write-Output ("FAIL criterion=G004-C002 reason=" + ($failure -replace '[\r\n]+', ' '))
    Write-Output $cleanupLine
    exit 1
}
Write-Output $summaryLine
Write-Output $cleanupLine