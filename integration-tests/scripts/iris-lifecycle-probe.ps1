[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet("Baseline", "StartSwitchClose")] [string] $Mode,
    [string] $PatchedJar = "../Iris/build/libs/iris-fabric-*-local.jar",
    [string] $Client = "mcp/out/build/Release/vibris-control-client.exe",
    [Parameter(Mandatory)] [string] $GameDir,
    [string] $SourceA,
    [string] $SourceB,
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
    if ($Mode -ceq "StartSwitchClose" -and
        ([string]::IsNullOrWhiteSpace($SourceA) -or [string]::IsNullOrWhiteSpace($SourceB)))
    {
        throw "StartSwitchClose requires SourceA and SourceB."
    }
    $jar = Resolve-IrisPatchedJar -Path $PatchedJar
    $contextPath = Resolve-IrisArtifact -Path $Context -Label "context fixture"
    $contextValue = Get-Content -Raw -LiteralPath $contextPath | ConvertFrom-Json
    $scope = New-IrisProbeScope -Criterion "c003" -GameDir $GameDir
    [void] (Write-IrisPresetCatalog -Scope $scope -Context $contextValue)
    $stale = Join-Path $scope.PendingRoot "stale-before-listen"
    [void] (New-Item -ItemType Directory -Path $stale)
    [System.IO.File]::WriteAllText((Join-Path $stale "sentinel.txt"), "stale")

    Start-IrisPackagedClient -Scope $scope -PatchedJar $jar -Scenario "c003" `
        -TimeoutSeconds $TimeoutSeconds
    $server = Wait-IrisEvent -Scope $scope -Type "server_ready" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.address -ceq "127.0.0.1:50051" }
    if ((Test-Path -LiteralPath $stale) -or
        -not [string]::Equals([System.IO.Path]::GetFullPath($server.pending_shaders_root),
            $scope.PendingRoot, [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "C003 did not clear stale pending state before opening the listener."
    }

    if ($Mode -ceq "StartSwitchClose")
    {
        $clientExe = Resolve-IrisArtifact -Path $Client -Label "native control client"
        $sourceRootA = Resolve-IrisDirectory -Path $SourceA -Label "source A"
        $sourceRootB = Resolve-IrisDirectory -Path $SourceB -Label "source B"
        $clientEntry = Start-CoreClient -Exe $clientExe -Port $script:IrisPort -WorkspaceId "automation-c003" `
            -InstanceId ([guid]::NewGuid().ToString()) -WorkingDirectory $scope.Root -Owned `
            ([System.Collections.Generic.List[object]]::new()) -TimeoutSeconds $TimeoutSeconds
        [void] (Get-IrisClientHello -Client $clientEntry -Scope $scope)
        Wait-IrisWorldReady -Scope $scope -TimeoutSeconds $TimeoutSeconds
        $preparedA = New-IrisPreparedSource -Scope $scope -Source $sourceRootA
        $jobA = New-CoreSubmitCommand -MessageId "g005-c003-a" -RequestId "g005-c003-a" `
            -Sources @($preparedA) -Context $contextValue -WaitFrames 1 -Timeouts @{
                queue_timeout_ms = 60000
                execution_timeout_ms = 120000
                total_timeout_ms = 180000
            }
        $startA = $clientEntry.Messages.Count
        Send-CoreClientCommand -Entry $clientEntry -Command $jobA
        $terminalA = Wait-CoreClientMessage -Entry $clientEntry -StartIndex $startA `
            -TimeoutSeconds $TimeoutSeconds -Match { param($message) $message.request_id -ceq "g005-c003-a" `
                -and $message.type -in @("JobCompleted", "JobFailed") }
        if ($terminalA.type -cne "JobCompleted") { throw "C003 source A did not complete." }
        [void] (Wait-IrisEvent -Scope $scope -Type "source_active" -TimeoutSeconds $TimeoutSeconds `
            -Match { param($event) $event.source_uuid -ceq $preparedA.uuid })
        if (-not (Test-Path -LiteralPath $preparedA.directory -PathType Container))
        {
            throw "C003 source A was deleted while still active."
        }

        $preparedB = New-IrisPreparedSource -Scope $scope -Source $sourceRootB
        $jobB = New-CoreSubmitCommand -MessageId "g005-c003-b" -RequestId "g005-c003-b" `
            -Sources @($preparedB) -Context $contextValue -WaitFrames 1 -Timeouts @{
                queue_timeout_ms = 60000
                execution_timeout_ms = 120000
                total_timeout_ms = 180000
            }
        $startB = $clientEntry.Messages.Count
        Send-CoreClientCommand -Entry $clientEntry -Command $jobB
        $terminalB = Wait-CoreClientMessage -Entry $clientEntry -StartIndex $startB `
            -TimeoutSeconds $TimeoutSeconds -Match { param($message) $message.request_id -ceq "g005-c003-b" `
                -and $message.type -in @("JobCompleted", "JobFailed") }
        if ($terminalB.type -cne "JobCompleted") { throw "C003 source B did not complete." }
        [void] (Wait-IrisEvent -Scope $scope -Type "source_active" -TimeoutSeconds $TimeoutSeconds `
            -Match { param($event) $event.source_uuid -ceq $preparedB.uuid })
        if ((Test-Path -LiteralPath $preparedA.directory) -or
            -not (Test-Path -LiteralPath $preparedB.directory -PathType Container) -or
            -not [string]::Equals((Get-IrisLinkTarget -Scope $scope), $preparedB.directory,
                [System.StringComparison]::OrdinalIgnoreCase))
        {
            throw "C003 successful switch did not delete A and retain active B."
        }
        $summary = "PASS criterion=G005-C003 mode=StartSwitchClose active_a_retained=true " +
            "old_a_deleted=true active_b_retained=true"
    }
    else
    {
        $summary = "PASS criterion=G005-C003 mode=Baseline startup_cleanup=true no_jobs=true"
    }
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
        try
        {
            $link = Get-Item -LiteralPath (Join-Path $scope.ShaderpackRoot "shaders") -Force `
                -ErrorAction SilentlyContinue
            if ($null -ne $link)
            {
                throw "C003 graceful close left the active shaders link."
            }
            if (Test-Path -LiteralPath $scope.PendingRoot)
            {
                $remaining = @(Get-ChildItem -LiteralPath $scope.PendingRoot -Force)
                if ($remaining.Count -ne 0) { throw "C003 graceful close left pending sources." }
            }
        }
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
Write-Output "CLEANUP criterion=G005-C003 link_removed=true pending_empty=true listener_closed=true " +
    "root_removed=true multimc_untouched=true"