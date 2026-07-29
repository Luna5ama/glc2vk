[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $ServerJar,
    [Parameter(Mandatory)] [string] $Client,
    [Parameter(Mandatory)] [string] $PendingRoot,
    [Parameter(Mandatory)] [string] $Uuid,
    [Parameter(Mandatory)] [ValidateSet("accepted")] [string] $DisconnectAfter,
    [ValidateRange(1, 300)] [int] $TimeoutSeconds = 90
)

. (Join-Path $PSScriptRoot "core-probe-common.ps1")

$owned = [System.Collections.Generic.List[object]]::new()
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$expectedRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ".omo\tmp\ulw-v1-g004-c003"))
$expectedPending = Join-Path $expectedRoot "pending"
$criterionRoot = $expectedRoot
$artifactRoot = Join-Path $criterionRoot "artifacts"
$criterionCreated = $false
$failure = $null
$summaryLine = $null
$cleanupLine = $null
$port = 55073
$server = $null
$serverMessages = @()

function Assert-NoForbiddenCoreDependency
{
    param([string] $RepositoryRoot)

    $roots = @(
        (Join-Path $RepositoryRoot "api\src\main"),
        (Join-Path $RepositoryRoot "core\src\main"),
        (Join-Path $RepositoryRoot "protocol-java\src\main"),
        (Join-Path $RepositoryRoot "api\build.gradle.kts"),
        (Join-Path $RepositoryRoot "core\build.gradle.kts"),
        (Join-Path $RepositoryRoot "protocol-java\build.gradle.kts")
    )
    foreach ($root in $roots)
    {
        if (-not (Test-Path -LiteralPath $root))
        {
            throw "Missing production dependency-scan root: $root"
        }
    }
    $matches = @(& rg.exe -n -i 'net[.]irisshaders|org[.]lwjgl|org[.]eclipse[.]jgit|\bgit\b' @roots 2>&1)
    $exitCode = $LASTEXITCODE
    if ($exitCode -eq 0)
    {
        throw "Iris, LWJGL, or Git leaked into the independent core: $([string]::Join('; ', $matches))"
    }
    if ($exitCode -ne 1)
    {
        throw "Core dependency scan failed with rg exit code $exitCode."
    }
}

try
{
    $resolvedPending = [System.IO.Path]::GetFullPath($PendingRoot)
    if (-not [string]::Equals($resolvedPending, $expectedPending,
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "G004-C003 requires PendingRoot $expectedPending."
    }
    if ($Uuid.Replace('\', '/') -cne "../outside" -or $DisconnectAfter -cne "accepted")
    {
        throw "G004-C003 requires -Uuid '../outside' -DisconnectAfter accepted."
    }
    Assert-CoreArtifact -Path $ServerJar -Label "fake server JAR"
    Assert-CoreArtifact -Path $Client -Label "native control client"
    if (Test-CorePort -Port $port)
    {
        throw "Criterion port 127.0.0.1:$port is already in use."
    }
    Assert-NoForbiddenCoreDependency -RepositoryRoot $repoRoot

    $criterionRoot = Assert-CoreCriterionRoot -Root $criterionRoot -ExpectedRoot $expectedRoot
    $criterionCreated = $true
    [void] (New-Item -ItemType Directory -Path $resolvedPending)
    [void] (New-Item -ItemType Directory -Path $artifactRoot)
    $outsideRoot = Join-Path $criterionRoot "outside"
    [void] (New-Item -ItemType Directory -Path $outsideRoot)
    $outsideSentinel = Join-Path $outsideRoot "sentinel.txt"
    $outsideBytes = [System.Text.Encoding]::UTF8.GetBytes("outside-must-not-change")
    [System.IO.File]::WriteAllBytes($outsideSentinel, $outsideBytes)

    $server = Start-CoreServer -Jar $ServerJar -Port $port -WorkRoot $criterionRoot `
        -PendingRoot $resolvedPending -ArtifactRoot $artifactRoot -Owned $owned `
        -TimeoutSeconds $TimeoutSeconds
    $clientEntry = Start-CoreClient -Exe $Client -Port $port -WorkspaceId "source-boundary" `
        -InstanceId "50000000-0000-4000-8000-000000000001" `
        -WorkingDirectory $criterionRoot -Owned $owned -TimeoutSeconds $TimeoutSeconds
    Remove-Item -LiteralPath $resolvedPending
    $context = [ordered] @{
        save_id = "source-boundary-save"
        dimension_id = "minecraft:overworld"
        time_preset_id = "noon"
        weather_preset_id = "clear"
        camera_preset_id = "origin"
        fov = 70.0
        resolution = @{ width = 640; height = 360 }
        settings_preset_id = "default"
    }
    $timeouts = [ordered] @{
        queue_timeout_ms = 30000
        execution_timeout_ms = 30000
        total_timeout_ms = 60000
    }
    $invalidStart = $clientEntry.Messages.Count
    $invalid = New-CoreSubmitCommand -MessageId "invalid-uuid" -RequestId "invalid-uuid" `
        -Sources @([pscustomobject] @{ uuid = $Uuid; file_count = 1; total_bytes = 1 }) `
        -Context $context -WaitFrames 1 -Timeouts $timeouts
    Send-CoreClientCommand -Entry $clientEntry -Command $invalid
    $invalidResult = Wait-CoreClientMessage -Entry $clientEntry -StartIndex $invalidStart `
        -TimeoutSeconds $TimeoutSeconds -Match {
            param($message)
            $message.request_id -eq "invalid-uuid" -and
                $message.type -in @("JobAccepted", "JobFailed")
        }
    if ($invalidResult.type -ne "JobFailed" -or
        (Get-CoreFailureCode -Message $invalidResult) -ne "INVALID_SOURCE_UUID")
    {
        throw "Invalid UUID was not rejected before acceptance with INVALID_SOURCE_UUID."
    }
    if (Test-Path -LiteralPath $resolvedPending)
    {
        throw "Invalid UUID accessed or recreated the missing pending root."
    }
    $outsideChildren = @(Get-ChildItem -LiteralPath $outsideRoot -Force)
    if ($outsideChildren.Count -ne 1 -or $outsideChildren[0].Name -cne "sentinel.txt" -or
        [Convert]::ToBase64String([System.IO.File]::ReadAllBytes($outsideSentinel)) -cne
            [Convert]::ToBase64String($outsideBytes))
    {
        throw "Invalid UUID changed the outside path before rejection."
    }

    [void] (New-Item -ItemType Directory -Path $resolvedPending)
    $validUuid = "50000000-0000-4000-8000-000000000101"
    $validSource = New-CoreSource -PendingRoot $resolvedPending -Uuid $validUuid `
        -Content "accepted-disconnect"
    $acceptedStart = $clientEntry.Messages.Count
    $accepted = New-CoreSubmitCommand -MessageId "accepted-disconnect" `
        -RequestId "accepted-disconnect" -Sources @($validSource) -Context $context `
        -WaitFrames 60000 -Timeouts $timeouts
    Send-CoreClientCommand -Entry $clientEntry -Command $accepted
    [void] (Wait-CoreClientMessage -Entry $clientEntry -StartIndex $acceptedStart `
        -TimeoutSeconds $TimeoutSeconds -Match {
            param($message)
            $message.type -eq "JobAccepted" -and $message.request_id -eq "accepted-disconnect"
        })
    Stop-CoreClientAbrupt -Entry $clientEntry -TimeoutSeconds $TimeoutSeconds

    $sourcePath = Join-Path $resolvedPending $validUuid
    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ((Test-Path -LiteralPath $sourcePath) -and [datetime]::UtcNow -lt $deadline)
    {
        if ($server.Process.HasExited)
        {
            throw "Server exited before cleaning its accepted source."
        }
        Start-Sleep -Milliseconds 20
    }
    if (Test-Path -LiteralPath $sourcePath)
    {
        throw "Accepted disconnect source remained owned by the client or leaked on the server."
    }

    $serverMessages = @(Stop-CoreServer -Entry $server -TimeoutSeconds $TimeoutSeconds)
    Wait-CorePort -Port $port -Open $false -TimeoutSeconds $TimeoutSeconds
    $invalidTransitions = @($serverMessages | Where-Object {
        $_.type -eq "SourceTransition" -and $_.uuid -eq $Uuid
    })
    if ($invalidTransitions.Count -ne 0)
    {
        throw "Invalid UUID reached source state or filesystem processing."
    }
    $transitions = @($serverMessages | Where-Object {
        $_.type -eq "SourceTransition" -and $_.uuid -eq $validUuid
    })
    $allowedStates = @(
        "VALIDATED", "QUEUED", "ACTIVATING", "ACTIVE", "RELEASED_ACTIVE",
        "RECLAIMABLE", "DELETING", "DELETED", "FAILED"
    )
    foreach ($transition in $transitions)
    {
        foreach ($state in @([string] $transition.from, [string] $transition.to))
        {
            $normalized = $state -replace '^SOURCE_STATE_', ''
            if (-not [string]::IsNullOrWhiteSpace($normalized) -and $normalized -notin $allowedStates)
            {
                throw "Accepted source used non-frozen state '$state'."
            }
        }
    }
    $allowedEdges = @(
        "->VALIDATED", "VALIDATED->QUEUED", "QUEUED->ACTIVATING", "ACTIVATING->ACTIVE",
        "QUEUED->RECLAIMABLE", "ACTIVE->RELEASED_ACTIVE", "RELEASED_ACTIVE->RECLAIMABLE",
        "RECLAIMABLE->DELETING", "DELETING->DELETED"
    )
    foreach ($transition in $transitions)
    {
        $from = ([string] $transition.from -replace '^SOURCE_STATE_', '')
        $to = ([string] $transition.to -replace '^SOURCE_STATE_', '')
        if (("$from->$to") -notin $allowedEdges)
        {
            throw "Accepted source used illegal frozen transition $from->$to."
        }
    }
    if ($transitions.Count -eq 0 -or
        ([string] $transitions[-1].to -replace '^SOURCE_STATE_', '') -cne "DELETED")
    {
        throw "Server cleanup did not end the accepted source at DELETED."
    }
    $serverSummary = Get-CoreSummary -Messages $serverMessages
    if ([int] $serverSummary.source_registry_size -ne 0)
    {
        throw "Server retained a source registry entry after accepted disconnect cleanup."
    }
    Assert-CoreRootEmpty -Root $resolvedPending -Label "pending source"
    Assert-CoreRootEmpty -Root $artifactRoot -Label "artifact"
    $summaryLine = "PASS criterion=G004-C003 invalid_uuid=pre_filesystem outside_unchanged=true " +
        "accepted_disconnect=server_owned lifecycle=frozen-to-deleted dependencies=independent"
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
        Wait-CorePort -Port $port -Open $false -TimeoutSeconds 3
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
    $cleanupLine = "CLEANUP criterion=G004-C003 owned_pids=$($owned.Count) " +
        "listener_closed=$(-not (Test-CorePort -Port 55073)) root_removed=$(-not (Test-Path $expectedRoot))"
}

if ($null -ne $failure)
{
    Write-Output ("FAIL criterion=G004-C003 reason=" + ($failure -replace '[\r\n]+', ' '))
    Write-Output $cleanupLine
    exit 1
}
Write-Output $summaryLine
Write-Output $cleanupLine