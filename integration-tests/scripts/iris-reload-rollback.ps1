[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet("Red", "Green")] [string] $Mode,
    [Parameter(Mandatory)] [string] $PatchedJar,
    [string] $Client = "mcp/out/build/Release/vibris-control-client.exe",
    [Parameter(Mandatory)] [string] $GoodSource,
    [Parameter(Mandatory)] [string] $BadSource,
    [Parameter(Mandatory)] [string] $GameDir,
    [string] $Context = "integration-tests/fixtures/context/overworld-sunset-rooftop.json",
    [ValidateRange(1, 600)] [int] $TimeoutSeconds = 180
)

. (Join-Path $PSScriptRoot "iris-probe-common.ps1")

$scope = $null
$clientEntry = $null
$failure = $null
$summary = $null

try
{
    $jar = Resolve-IrisPatchedJar -Path $PatchedJar
    $clientExe = Resolve-IrisArtifact -Path $Client -Label "native control client"
    $goodRoot = Resolve-IrisDirectory -Path $GoodSource -Label "known-good source"
    $badRoot = Resolve-IrisDirectory -Path $BadSource -Label "compile-error source"
    $contextPath = Resolve-IrisArtifact -Path $Context -Label "context fixture"
    $contextValue = Get-Content -Raw -LiteralPath $contextPath | ConvertFrom-Json
    $badComposite = Join-Path $badRoot "composite.fsh"
    if ((Get-Content -Raw -LiteralPath $badComposite) -notmatch '(?m)^#error ULW_PHASE4_ROLLBACK\r?$')
    {
        throw "C002 bad fixture lacks exact #error ULW_PHASE4_ROLLBACK."
    }
    $scope = New-IrisProbeScope -Criterion "c002" -GameDir $GameDir
    [void] (Write-IrisPresetCatalog -Scope $scope -Context $contextValue)
    Start-IrisPackagedClient -Scope $scope -PatchedJar $jar -Scenario "c002" `
        -TimeoutSeconds $TimeoutSeconds
    $clientEntry = Start-CoreClient -Exe $clientExe -Port $script:IrisPort -WorkspaceId "phase4-c002" `
        -InstanceId ([guid]::NewGuid().ToString()) -WorkingDirectory $scope.Root -Owned `
        ([System.Collections.Generic.List[object]]::new()) -TimeoutSeconds $TimeoutSeconds
    [void] (Get-IrisClientHello -Client $clientEntry -Scope $scope)
    Wait-IrisWorldReady -Scope $scope -TimeoutSeconds $TimeoutSeconds

    $sourceA = New-IrisPreparedSource -Scope $scope -Source $goodRoot
    $jobA = New-CoreSubmitCommand -MessageId "g005-c002-a" -RequestId "g005-c002-a" `
        -Sources @($sourceA) -Context $contextValue -WaitFrames 1 -Timeouts @{
            queue_timeout_ms = 60000
            execution_timeout_ms = 120000
            total_timeout_ms = 180000
        }
    $startA = $clientEntry.Messages.Count
    Send-CoreClientCommand -Entry $clientEntry -Command $jobA
    $terminalA = Wait-CoreClientMessage -Entry $clientEntry -StartIndex $startA `
        -TimeoutSeconds $TimeoutSeconds -Match { param($message) $message.request_id -ceq "g005-c002-a" -and
            $message.type -in @("JobCompleted", "JobFailed") }
    if ($terminalA.type -cne "JobCompleted") { throw "C002 source A did not activate successfully." }
    $activeA = Wait-IrisEvent -Scope $scope -Type "source_active" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.source_uuid -ceq $sourceA.uuid }
    if (-not [string]::Equals((Get-IrisLinkTarget -Scope $scope), $sourceA.directory,
            [System.StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $sourceA.directory -PathType Container))
    {
        throw "C002 source A was not retained as the active source."
    }

    $sourceB = New-IrisPreparedSource -Scope $scope -Source $badRoot
    $jobB = New-CoreSubmitCommand -MessageId "g005-c002-b" -RequestId "g005-c002-b" `
        -Sources @($sourceB) -Context $contextValue -WaitFrames 1 -Timeouts @{
            queue_timeout_ms = 60000
            execution_timeout_ms = 120000
            total_timeout_ms = 180000
        }
    $startB = $clientEntry.Messages.Count
    Send-CoreClientCommand -Entry $clientEntry -Command $jobB
    $terminalB = Wait-CoreClientMessage -Entry $clientEntry -StartIndex $startB `
        -TimeoutSeconds $TimeoutSeconds -Match { param($message) $message.request_id -ceq "g005-c002-b" -and
            $message.type -in @("JobCompleted", "JobFailed") }
    if ($terminalB.type -cne "JobFailed" -or
        (Get-CoreFailureCode -Message $terminalB) -cne "SHADER_COMPILE_FAILED")
    {
        $terminalBJson = $terminalB | ConvertTo-Json -Depth 20 -Compress
        throw "C002 bad source did not return SHADER_COMPILE_FAILED without success. TERMINAL=$terminalBJson"
    }
    $logs = @($terminalB.artifacts | Where-Object { $_.file_name -ceq "shader.log" })
    if ($logs.Count -ne 1) { throw "C002 failure did not return exactly one shader.log artifact." }
    $shaderLog = Resolve-IrisOwnedArtifact -Scope $scope -Path $logs[0].path
    $shaderLogText = if (Test-Path -LiteralPath $shaderLog -PathType Leaf)
    {
        Get-Content -Raw -LiteralPath $shaderLog
    }
    else
    {
        "<missing>"
    }
    if ($shaderLogText -notmatch 'ULW_PHASE4_ROLLBACK')
    {
        throw "C002 shader.log is absent or lacks the frozen compile marker.`nPATH=$shaderLog`nCONTENT:`n$shaderLogText"
    }
    $rollback = Wait-IrisEvent -Scope $scope -Type "source_rollback" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.failed_source_uuid -ceq $sourceB.uuid -and
            $event.restored_source_uuid -ceq $sourceA.uuid }
    $rollbackLinkTarget = Get-IrisLinkTarget -Scope $scope
    $sourceAExists = Test-Path -LiteralPath $sourceA.directory -PathType Container
    $sourceBExists = Test-Path -LiteralPath $sourceB.directory
    if ($rollback.pipeline_id -cne $activeA.pipeline_id -or
        -not [string]::Equals($rollbackLinkTarget, $sourceA.directory,
            [System.StringComparison]::OrdinalIgnoreCase) -or
        -not $sourceAExists -or $sourceBExists)
    {
        throw "C002 rollback did not restore A and delete failed B while preserving the old pipeline. " +
            "ACTIVE_PIPELINE=$($activeA.pipeline_id) ROLLBACK_PIPELINE=$($rollback.pipeline_id) " +
            "EXPECTED_LINK=$($sourceA.directory) ACTUAL_LINK=$rollbackLinkTarget " +
            "SOURCE_A_EXISTS=$sourceAExists SOURCE_B_EXISTS=$sourceBExists"
    }
    [void] (Wait-IrisEvent -Scope $scope -Type "frame_tail" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.pipeline_id -ceq $activeA.pipeline_id -and
            [long] $event.frame_id -gt [long] $rollback.frame_id })
    $summary = "PASS criterion=G005-C002 mode=$Mode error=SHADER_COMPILE_FAILED " +
        "restored=$($sourceA.uuid) deleted=$($sourceB.uuid) pipeline=$($activeA.pipeline_id)"
}
catch
{
    $failure = $_.Exception
}
finally
{
    if ($null -ne $clientEntry -and -not $clientEntry.Graceful)
    {
        try { Stop-CoreClient -Entry $clientEntry -SendClose -TimeoutSeconds $TimeoutSeconds }
        catch { $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception }
    }
    if ($null -ne $scope)
    {
        try { Stop-IrisPackagedClient -Scope $scope -TimeoutSeconds $TimeoutSeconds }
        catch { $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception }
        try { Remove-IrisProbeScope -Scope $scope }
        catch { $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception }
    }
}

if ($null -ne $failure)
{
    Write-Error $failure
    exit 1
}
Write-Output $summary
Write-Output "CLEANUP criterion=G005-C002 listener_closed=true root_removed=true multimc_untouched=true"
