[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $PatchedJar,
    [Parameter(Mandatory)] [string] $Client,
    [Parameter(Mandatory)] [string] $GameDir,
    [Parameter(Mandatory)] [string] $Source,
    [Parameter(Mandatory)] [string] $Context,
    [ValidateRange(1, 4096)] [int] $WaitFrames = 32,
    [ValidateRange(1, 600)] [int] $TimeoutSeconds = 180
)

. (Join-Path $PSScriptRoot "iris-probe-common.ps1")

$scope = $null
$clientEntry = $null
$failure = $null
$summary = $null

try
{
    if ($WaitFrames -ne 32) { throw "G005-C001 requires WaitFrames 32." }
    $jar = Resolve-IrisPatchedJar -Path $PatchedJar
    $clientExe = Resolve-IrisArtifact -Path $Client -Label "native control client"
    $sourceRoot = Resolve-IrisDirectory -Path $Source -Label "known-good source"
    $contextPath = Resolve-IrisArtifact -Path $Context -Label "context fixture"
    $contextValue = Get-Content -Raw -LiteralPath $contextPath | ConvertFrom-Json
    $scope = New-IrisProbeScope -Criterion "c001" -GameDir $GameDir
    [void] (Write-IrisPresetCatalog -Scope $scope -Context $contextValue)

    Start-IrisPackagedClient -Scope $scope -PatchedJar $jar -Scenario "c001" `
        -TimeoutSeconds $TimeoutSeconds
    $clientEntry = Start-CoreClient -Exe $clientExe -Port $script:IrisPort -WorkspaceId "phase4-c001" `
        -InstanceId ([guid]::NewGuid().ToString()) -WorkingDirectory $scope.Root -Owned `
        ([System.Collections.Generic.List[object]]::new()) -TimeoutSeconds $TimeoutSeconds
    [void] (Get-IrisClientHello -Client $clientEntry -Scope $scope)
    Wait-IrisWorldReady -Scope $scope -TimeoutSeconds $TimeoutSeconds
    $prepared = New-IrisPreparedSource -Scope $scope -Source $sourceRoot
    $command = New-CoreSubmitCommand -MessageId "g005-c001" -RequestId "g005-c001" `
        -Sources @($prepared) -Context $contextValue -WaitFrames $WaitFrames -Timeouts @{
            queue_timeout_ms = 60000
            execution_timeout_ms = 120000
            total_timeout_ms = 180000
        }
    $start = $clientEntry.Messages.Count
    Send-CoreClientCommand -Entry $clientEntry -Command $command
    $terminal = Wait-CoreClientMessage -Entry $clientEntry -StartIndex $start -TimeoutSeconds $TimeoutSeconds `
        -Match { param($message) $message.request_id -ceq "g005-c001" -and
            $message.type -in @("JobCompleted", "JobFailed") }
    if ($terminal.type -cne "JobCompleted")
    {
        throw "C001 job failed: $($terminal | ConvertTo-Json -Compress -Depth 10)"
    }

    $server = Wait-IrisEvent -Scope $scope -Type "server_ready" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.address -ceq "127.0.0.1:50051" }
    if (-not [string]::Equals([System.IO.Path]::GetFullPath($server.pending_shaders_root),
            $scope.PendingRoot, [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "server_ready reported an unexpected pending root."
    }
    $applied = Wait-IrisEvent -Scope $scope -Type "context_applied" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.context.save_id -ceq $contextValue.save_id }
    $actual = $applied.context
    if ($actual.dimension_id -cne $contextValue.dimension_id -or
        $actual.time_preset_id -cne $contextValue.time_preset_id -or
        $actual.weather_preset_id -cne $contextValue.weather_preset_id -or
        $actual.camera_preset_id -cne $contextValue.camera_preset_id -or
        $actual.settings_preset_id -cne $contextValue.settings_preset_id -or
        [double] $actual.fov -ne [double] $contextValue.fov -or
        [int] $actual.resolution.width -ne [int] $contextValue.resolution.width -or
        [int] $actual.resolution.height -ne [int] $contextValue.resolution.height -or
        [long] $actual.day_time -ne [long] $contextValue.expected.day_time -or
        [double] $actual.rain_level -ne [double] $contextValue.expected.rain_level -or
        [double] $actual.thunder_level -ne [double] $contextValue.expected.thunder_level)
    {
        throw "Minecraft did not apply the exact frozen scene context.`nEXPECTED:`n" +
            ($contextValue | ConvertTo-Json -Depth 10) + "`nACTUAL:`n" + ($actual | ConvertTo-Json -Depth 10)
    }
    foreach ($field in @("x", "y", "z", "yaw", "pitch"))
    {
        if ([double] $actual.camera.$field -ne [double] $contextValue.expected.camera.$field)
        {
            throw "Minecraft camera field '$field' did not match the frozen context."
        }
    }
    $active = Wait-IrisEvent -Scope $scope -Type "source_active" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.source_uuid -ceq $prepared.uuid }
    if (-not [string]::Equals((Get-IrisLinkTarget -Scope $scope), $prepared.directory,
            [System.StringComparison]::OrdinalIgnoreCase) -or
        -not [string]::Equals([System.IO.Path]::GetFullPath($active.link_target), $prepared.directory,
            [System.StringComparison]::OrdinalIgnoreCase) -or
        [string]::IsNullOrWhiteSpace($active.pipeline_id) -or [long] $active.frame_id -lt 0 -or
        -not (Test-Path -LiteralPath $prepared.directory -PathType Container))
    {
        throw "C001 active shader link/source ownership is incorrect."
    }
    $wait = Wait-IrisEvent -Scope $scope -Type "frame_wait_complete" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.source_uuid -ceq $prepared.uuid -and [int] $event.count -eq 32 }
    if ([long] $wait.end_frame - [long] $wait.start_frame -ne 32 -or
        $wait.pipeline_id -cne $active.pipeline_id)
    {
        throw "C001 did not wait exactly 32 render-tail frames on the active pipeline."
    }
    $summary = "PASS criterion=G005-C001 context=exact loopback=true source=$($prepared.uuid) " +
        "frames=$($wait.start_frame)..$($wait.end_frame)"
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
Write-Output "CLEANUP criterion=G005-C001 listener_closed=true root_removed=true multimc_untouched=true"
