[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $ServerJar,
    [Parameter(Mandatory)] [string] $Client,
    [Parameter(Mandatory)] [string] $Scenario,
    [Parameter(Mandatory)] [string] $Listen,
    [ValidateRange(1, 300)] [int] $TimeoutSeconds = 90
)

. (Join-Path $PSScriptRoot "core-probe-common.ps1")

$owned = [System.Collections.Generic.List[object]]::new()
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$expectedRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ".omo\tmp\ulw-v1-g004-c001"))
$expectedScenario = Join-Path $repoRoot "integration-tests\fixtures\core\a1-b1-c1-a2-b2.json"
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

try
{
    $port = Resolve-CoreListen -Listen $Listen
    if ($port -ne 55071)
    {
        throw "G004-C001 requires literal listen endpoint 127.0.0.1:55071."
    }
    $resolvedScenario = [System.IO.Path]::GetFullPath($Scenario)
    if (-not [string]::Equals($resolvedScenario, $expectedScenario,
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "G004-C001 requires scenario $expectedScenario."
    }
    Assert-CoreArtifact -Path $ServerJar -Label "fake server JAR"
    Assert-CoreArtifact -Path $Client -Label "native control client"
    Assert-CoreArtifact -Path $resolvedScenario -Label "multi-client scenario"
    $scenarioData = Get-Content -LiteralPath $resolvedScenario -Raw | ConvertFrom-Json
    $expectedOrder = @($scenarioData.expected_execution_order)
    if ($scenarioData.schema_version -ne 1 -or $scenarioData.clients.Count -ne 3 -or
        [string]::Join(',', $expectedOrder) -cne "A1,B1,C1,A2,B2" -or
        [string]::Join(',', @($scenarioData.submission_order)) -cne "A1,A2,B1,B2,C1")
    {
        throw "Multi-client fixture does not encode the frozen A1,B1,C1,A2,B2 scenario."
    }

    $criterionRoot = Assert-CoreCriterionRoot -Root $criterionRoot -ExpectedRoot $expectedRoot
    $criterionCreated = $true
    [void] (New-Item -ItemType Directory -Path $pendingRoot)
    [void] (New-Item -ItemType Directory -Path $artifactRoot)
    $server = Start-CoreServer -Jar $ServerJar -Port $port -WorkRoot $criterionRoot `
        -PendingRoot $pendingRoot -ArtifactRoot $artifactRoot -Owned $owned `
        -TimeoutSeconds $TimeoutSeconds

    $jobs = @{}
    $sources = @{}
    $clients = @{}
    foreach ($clientData in @($scenarioData.clients))
    {
        foreach ($job in @($clientData.jobs))
        {
            if ($jobs.ContainsKey([string] $job.request_id))
            {
                throw "Fixture repeats request_id $($job.request_id)."
            }
            [void] (Get-CoreContextSignature -Context $job.context)
            $jobs[[string] $job.request_id] = $job
            $sources[[string] $job.request_id] = New-CoreSource -PendingRoot $pendingRoot `
                -Uuid ([string] $job.source_uuid) -Content "source-$($job.request_id)"
        }
        $clients[[string] $clientData.workspace_id] = Start-CoreClient -Exe $Client -Port $port `
            -WorkspaceId ([string] $clientData.workspace_id) -InstanceId ([string] $clientData.instance_id) `
            -WorkingDirectory $criterionRoot -Owned $owned -TimeoutSeconds $TimeoutSeconds
    }

    $clientForRequest = @{}
    foreach ($clientData in @($scenarioData.clients))
    {
        foreach ($job in @($clientData.jobs))
        {
            $clientForRequest[[string] $job.request_id] = $clients[[string] $clientData.workspace_id]
        }
    }
    $submit = {
        param([string] $RequestId)

        $job = $jobs[$RequestId]
        $command = New-CoreSubmitCommand -MessageId "submit-$RequestId" -RequestId $RequestId `
            -Sources @($sources[$RequestId]) -Context $job.context -WaitFrames $job.wait_frames `
            -Timeouts $scenarioData.timeouts
        Send-CoreClientCommand -Entry $clientForRequest[$RequestId] -Command $command
    }

    & $submit "A1"
    [void] (Wait-CoreClientMessage -Entry $clientForRequest["A1"] -StartIndex 0 `
        -TimeoutSeconds $TimeoutSeconds -Match {
            param($message)
            $message.type -eq "JobProgress" -and $message.request_id -eq "A1" -and
                [string] $message.stage -match 'WARMING_UP$'
        })
    foreach ($requestId in @($scenarioData.submission_order | Select-Object -Skip 1))
    {
        & $submit $requestId
    }

    foreach ($requestId in $expectedOrder)
    {
        $terminal = Wait-CoreClientMessage -Entry $clientForRequest[$requestId] -StartIndex 0 `
            -TimeoutSeconds $TimeoutSeconds -Match {
                param($message)
                $message.request_id -eq $requestId -and
                    $message.type -in @("JobCompleted", "JobFailed")
            }
        if ($terminal.type -ne "JobCompleted" -or $terminal.message_id -ne "submit-$requestId")
        {
            throw "Request $requestId did not complete successfully with its submitted message_id."
        }
    }
    foreach ($entry in @($clients.Values))
    {
        Stop-CoreClient -Entry $entry -SendClose -TimeoutSeconds $TimeoutSeconds
    }
    $serverMessages = @(Stop-CoreServer -Entry $server -TimeoutSeconds $TimeoutSeconds)
    Wait-CorePort -Port $port -Open $false -TimeoutSeconds $TimeoutSeconds

    $traces = @($serverMessages | Where-Object { $_.type -eq "JobTrace" })
    $actualOrder = @($traces | ForEach-Object { [string] $_.request_id })
    if ([string]::Join(',', $actualOrder) -cne [string]::Join(',', $expectedOrder))
    {
        throw "Runtime order was $([string]::Join(',', $actualOrder)); expected A1,B1,C1,A2,B2."
    }
    foreach ($trace in $traces)
    {
        $expected = Get-CoreContextSignature -Context $jobs[[string] $trace.request_id].context
        $actual = Get-CoreContextSignature -Context $trace.context
        if ($actual -cne $expected)
        {
            throw "Runtime trace $($trace.request_id) did not retain its full context snapshot."
        }
    }
    $serverSummary = Get-CoreSummary -Messages $serverMessages
    $eventWorkspaces = @($serverSummary.execution_events | ForEach-Object {
        ([string] $_).Split(':', 2)[0].Substring(0, 1)
    })
    $workspaceRuns = [System.Collections.Generic.List[string]]::new()
    foreach ($workspace in $eventWorkspaces)
    {
        if ($workspaceRuns.Count -eq 0 -or $workspaceRuns[$workspaceRuns.Count - 1] -cne $workspace)
        {
            $workspaceRuns.Add($workspace)
        }
    }
    if ([string]::Join(',', $workspaceRuns) -cne "A,B,C,A,B")
    {
        throw "Execution events interleaved workspaces: $([string]::Join(',', $workspaceRuns))."
    }
    if ([int] $serverSummary.max_concurrent_jobs -ne 1)
    {
        throw "Runtime allowed $($serverSummary.max_concurrent_jobs) concurrent jobs."
    }
    foreach ($requestId in $expectedOrder)
    {
        if ((Get-CoreExecutionCount -Summary $serverSummary -RequestId $requestId) -ne 1)
        {
            throw "Request $requestId did not execute exactly once."
        }
    }
    Assert-CoreRootEmpty -Root $pendingRoot -Label "pending source"
    Assert-CoreRootEmpty -Root $artifactRoot -Label "artifact"
    $summaryLine = "PASS criterion=G004-C001 order=A1,B1,C1,A2,B2 max_concurrent_jobs=1 " +
        "contexts=full no_interleave=true"
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
    $cleanupLine = "CLEANUP criterion=G004-C001 owned_pids=$($owned.Count) " +
        "listener_closed=$(-not (Test-CorePort -Port 55071)) root_removed=$(-not (Test-Path $expectedRoot))"
}

if ($null -ne $failure)
{
    Write-Output ("FAIL criterion=G004-C001 reason=" + ($failure -replace '[\r\n]+', ' '))
    Write-Output $cleanupLine
    exit 1
}
Write-Output $summaryLine
Write-Output $cleanupLine