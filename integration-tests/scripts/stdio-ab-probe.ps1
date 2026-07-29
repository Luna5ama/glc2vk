[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Exe,
    [Parameter(Mandatory)] [string] $WorkspaceRoot,
    [Parameter(Mandatory)] [string] $Request,
    [Parameter(Mandatory)] [string] $CompetingWorkspace,
    [ValidateRange(1, 600)] [int] $TimeoutSeconds = 180
)

. (Join-Path $PSScriptRoot "stdio-job-probe-common.ps1")

function New-InitializeMessage
{
    param([Parameter(Mandatory)] [int] $Id, [Parameter(Mandatory)] [string] $Name)

    return [ordered] @{
        jsonrpc = "2.0"
        id = $Id
        method = "initialize"
        params = [ordered] @{
            protocolVersion = "2024-11-05"
            capabilities = [ordered] @{}
            clientInfo = [ordered] @{ name = $Name; version = "1.0" }
        }
    }
}

function New-ConfigureMessage
{
    param([Parameter(Mandatory)] [int] $Id)

    return [ordered] @{
        jsonrpc = "2.0"
        id = $Id
        method = "tools/call"
        params = [ordered] @{
            name = "vibris_configure"
            arguments = [ordered] @{
                save_id = "vibris-phase4-world"
                dimension_id = "minecraft:overworld"
                time_preset_id = "sunset"
                camera_preset_id = "rooftop"
                fov = 70.0
                default_warmup_frames = 32
            }
        }
    }
}

function Get-SourceEvents
{
    param([Parameter(Mandatory)] [object] $Scope)

    if (-not (Test-Path -LiteralPath $Scope.EventFile -PathType Leaf)) { return @() }
    return @([System.IO.File]::ReadAllLines($Scope.EventFile) | Where-Object {
            -not [string]::IsNullOrWhiteSpace($_)
        } | ForEach-Object { $_ | ConvertFrom-Json } | Where-Object { $_.type -ceq "source_active" })
}

function Get-Artifact
{
    param([Parameter(Mandatory)] [object] $Payload, [Parameter(Mandatory)] [string] $Name)

    $matches = @($Payload.artifacts | Where-Object { $_.file_name -ceq $Name })
    if ($matches.Count -ne 1) { throw "A/B result omitted unique artifact $Name." }
    return $matches[0]
}

function Assert-PairedPlan
{
    param([Parameter(Mandatory)] [object] $Payload, [Parameter(Mandatory)] [int] $Index)

    $a = @($Payload.artifacts | Where-Object { $_.file_name -match "^a-$Index[.]" })
    $b = @($Payload.artifacts | Where-Object { $_.file_name -match "^b-$Index[.]" })
    if ($a.Count -ne 1 -or $b.Count -ne 1) { throw "A/B capture pair $Index is incomplete." }
    $aPlan = [ordered] @{
        kind = $a[0].kind; format = $a[0].format; media_type = $a[0].media_type
        logical_name = $a[0].resource.logical_name; resource_kind = $a[0].resource.kind
        width = $a[0].resource.width; height = $a[0].resource.height; depth = $a[0].resource.depth
        mip_level = $a[0].resource.mip_level; layer = $a[0].resource.layer
        internal_format = $a[0].resource.internal_format
    } | ConvertTo-Json -Compress
    $bPlan = [ordered] @{
        kind = $b[0].kind; format = $b[0].format; media_type = $b[0].media_type
        logical_name = $b[0].resource.logical_name; resource_kind = $b[0].resource.kind
        width = $b[0].resource.width; height = $b[0].resource.height; depth = $b[0].resource.depth
        mip_level = $b[0].resource.mip_level; layer = $b[0].resource.layer
        internal_format = $b[0].resource.internal_format
    } | ConvertTo-Json -Compress
    if ($aPlan -cne $bPlan) { throw "A/B capture plan $Index is not byte-equal." }
}

$scope = $null
$abEntry = $null
$competitorEntry = $null
$failure = $null
$summary = $null
try
{
    $exePath = Resolve-IrisArtifact -Path $Exe -Label "Release native MCP"
    $requestValue = Get-Content -Raw -LiteralPath $Request | ConvertFrom-Json
    if ($requestValue.params.name -cne "vibris_run_recipe" -or
        $requestValue.params.arguments.recipe -cne "ab_compare")
    {
        throw "G007-C002 fixture is not the frozen A/B recipe request."
    }
    $scope = New-G007ProbeScope -Criterion "c002" -WorkspaceRoot $WorkspaceRoot
    $competitor = [System.IO.Path]::GetFullPath($CompetingWorkspace)
    $expectedCompetitor = Join-Path $scope.Root "competitor"
    if (-not [string]::Equals($competitor, $expectedCompetitor,
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "G007-C002 requires CompetingWorkspace $expectedCompetitor."
    }
    [void] (Initialize-G007Workspace -Scope $scope)
    Set-G007WorkspaceCandidate -Scope $scope
    [void] (New-Item -ItemType Directory -Path $competitor)
    $competitorScope = [pscustomobject] @{ WorkspaceRoot = $competitor }
    [void] (Initialize-G007Workspace -Scope $competitorScope)
    [void] (Start-G007Runtime -Scope $scope -Scenario "g007-c002" -TimeoutSeconds $TimeoutSeconds)

    $mainConfig = @((New-InitializeMessage -Id 1 -Name "vibris-ab-config"), (New-ConfigureMessage -Id 2))
    $otherConfig = @((New-InitializeMessage -Id 11 -Name "vibris-competitor-config"),
        (New-ConfigureMessage -Id 12))
    [void] (Invoke-G007Mcp -Exe $exePath -WorkspaceRoot $scope.WorkspaceRoot `
        -Messages $mainConfig -TimeoutSeconds $TimeoutSeconds)
    [void] (Invoke-G007Mcp -Exe $exePath -WorkspaceRoot $competitor `
        -Messages $otherConfig -TimeoutSeconds $TimeoutSeconds)

    $abEntry = Start-G007Mcp -Exe $exePath -WorkspaceRoot $scope.WorkspaceRoot `
        -Messages @((New-InitializeMessage -Id 1 -Name "vibris-ab"), $requestValue)
    $aEvent = Wait-IrisEvent -Scope $scope -Type "source_active" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) -not [string]::IsNullOrWhiteSpace([string] $event.source_uuid) }
    $aDirectory = Join-Path $scope.PendingRoot $aEvent.source_uuid
    if (-not (Test-Path -LiteralPath $aDirectory -PathType Container))
    {
        throw "Baseline source was reclaimed before candidate reload."
    }
    $transferred = @(Get-ChildItem -LiteralPath $scope.PendingRoot -Directory |
        Where-Object { $_.Name -match '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$' })
    if ($transferred.Count -ne 2 -or @($transferred.Name) -cnotcontains $aEvent.source_uuid)
    {
        throw "A/B SubmitJob did not transfer exactly two prepared sources."
    }
    $candidateUuid = [string] @($transferred.Name | Where-Object { $_ -cne $aEvent.source_uuid })[0]
    $aReset = Wait-IrisEvent -Scope $scope -Type "temporal_reset" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.source_uuid -ceq $aEvent.source_uuid }
    $aCapture = Wait-IrisEvent -Scope $scope -Type "capture_complete" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.source_uuid -ceq $aEvent.source_uuid }

    $competitorAction = [ordered] @{
        jsonrpc = "2.0"; id = 12; method = "tools/call"
        params = [ordered] @{
            name = "vibris_run_actions"
            arguments = [ordered] @{ source = [ordered] @{ kind = "workspace" }; actions = @() }
        }
    }
    $competitorEntry = Start-G007Mcp -Exe $exePath -WorkspaceRoot $competitor `
        -Messages @((New-InitializeMessage -Id 11 -Name "vibris-competitor"), $competitorAction)
    $bEvent = Wait-IrisEvent -Scope $scope -Type "source_active" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.source_uuid -cne $aEvent.source_uuid }
    if ($bEvent.source_uuid -cne $candidateUuid)
    {
        throw "A/B candidate activation did not use the second transferred source."
    }
    $bReset = Wait-IrisEvent -Scope $scope -Type "temporal_reset" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.source_uuid -ceq $bEvent.source_uuid }
    $bCapture = Wait-IrisEvent -Scope $scope -Type "capture_complete" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.source_uuid -ceq $bEvent.source_uuid }

    $reclaimDeadline = [datetime]::UtcNow.AddSeconds(10)
    while ((Test-Path -LiteralPath $aDirectory) -and [datetime]::UtcNow -lt $reclaimDeadline)
    {
        if ((Get-SourceEvents -Scope $scope).Count -gt 2)
        {
            throw "Competing Job activated before the baseline source was reclaimed."
        }
        Start-Sleep -Milliseconds 20
    }
    if (Test-Path -LiteralPath $aDirectory)
    {
        throw "Baseline source was not reclaimed after candidate reload succeeded."
    }

    $abResponses = Complete-G007Mcp -Entry $abEntry -TimeoutSeconds $TimeoutSeconds
    $otherResponses = Complete-G007Mcp -Entry $competitorEntry -TimeoutSeconds $TimeoutSeconds
    $ab = Get-G007ToolPayload (Get-G007Response -Responses $abResponses -Id 3)
    $other = Get-G007ToolPayload (Get-G007Response -Responses $otherResponses -Id 12)
    Assert-G007CompletedResult -Scope $scope -Payload $ab
    Assert-G007CompletedResult -Scope $scope -Payload $other -ArtifactsOptional
    if ($ab.kind -cne "ab_compare" -or @($ab.frame_ids).Count -ne 2 -or
        $ab.comparison.baseline_label -cne "baseline" -or $ab.comparison.candidate_label -cne "candidate")
    {
        throw "A/B synchronous result omitted its paired frames or comparison summary."
    }
    Assert-PairedPlan -Payload $ab -Index 0
    Assert-PairedPlan -Payload $ab -Index 1

    $diff = Get-Artifact -Payload $ab -Name "diff.json"
    $heatmap = Get-Artifact -Payload $ab -Name "diff-heatmap.png"
    $diffPath = Resolve-IrisOwnedArtifact -Scope $scope -Path $diff.path
    $metrics = Get-Content -Raw -LiteralPath $diffPath | ConvertFrom-Json
    if ($metrics.baseline -cne "baseline" -or $metrics.candidate -cne "candidate")
    {
        throw "diff.json labels do not match the A/B request."
    }
    $heatmapPath = Resolve-IrisOwnedArtifact -Scope $scope -Path $heatmap.path
    $signature = [System.IO.File]::ReadAllBytes($heatmapPath)
    if ($signature.Length -lt 8 -or [System.BitConverter]::ToString($signature[0..7]) -cne
        "89-50-4E-47-0D-0A-1A-0A")
    {
        throw "A/B heatmap is not a readable PNG."
    }

    $sourceEvents = Get-SourceEvents -Scope $scope
    if ($sourceEvents.Count -lt 3 -or $sourceEvents[0].source_uuid -cne $aEvent.source_uuid -or
        $sourceEvents[1].source_uuid -cne $bEvent.source_uuid)
    {
        throw "A/B source trace was interleaved by the competitor."
    }
    $events = @([System.IO.File]::ReadAllLines($scope.EventFile) | ForEach-Object { $_ | ConvertFrom-Json })
    $contexts = @($events | Where-Object { $_.type -ceq "context_applied" })
    $waits = @($events | Where-Object { $_.type -ceq "frame_wait_complete" })
    if ($contexts.Count -lt 2 -or
        (($contexts[0].context | ConvertTo-Json -Compress -Depth 20) -cne
            ($contexts[1].context | ConvertTo-Json -Compress -Depth 20)) -or
        $waits.Count -lt 2 -or [long] $waits[0].count -ne 32 -or [long] $waits[1].count -ne 32)
    {
        throw "A/B context, resolution, settings, or warmup were not byte-equal."
    }
    $execution = @($events | Where-Object {
            $_.type -in @("source_active", "temporal_reset", "capture_complete")
        } | ForEach-Object { "$($_.type):$($_.source_uuid)" })
    $expectedTrace = @(
        "source_active:$($aEvent.source_uuid)", "temporal_reset:$($aEvent.source_uuid)",
        "capture_complete:$($aEvent.source_uuid)", "source_active:$($bEvent.source_uuid)",
        "temporal_reset:$($bEvent.source_uuid)", "capture_complete:$($bEvent.source_uuid)",
        "source_active:$($sourceEvents[2].source_uuid)"
    )
    $aTargets = @($aCapture.targets | ForEach-Object { [string] $_ -replace '^a-', '' })
    $bTargets = @($bCapture.targets | ForEach-Object { [string] $_ -replace '^b-', '' })
    if (($execution | ConvertTo-Json -Compress) -cne ($expectedTrace | ConvertTo-Json -Compress) -or
        (($aTargets | ConvertTo-Json -Compress) -cne ($bTargets | ConvertTo-Json -Compress)))
    {
        throw "A/B activate-reset-capture trace was interleaved or used unequal capture plans."
    }
    $summary = "PASS criterion=G007-C002 synchronous_results=1 transferred_sources=2 " +
        "trace=activate-reset-capture-A-B-competitor context_equal=true reset_equal=true warmup_equal=true " +
        "capture_plan_equal=true baseline_reclaimed_after_b=true diff_readable=true heatmap_png=true"
}
catch
{
    $failure = $_.Exception
}
finally
{
    try { Stop-G007Mcp -Entry $abEntry }
    catch { $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception }
    try { Stop-G007Mcp -Entry $competitorEntry }
    catch { $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception }
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
Write-Output "CLEANUP criterion=G007-C002 listener_closed=true root_removed=true multimc_untouched=true"