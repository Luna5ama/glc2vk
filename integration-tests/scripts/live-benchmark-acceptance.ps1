[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $McpExe,
    [Parameter(Mandatory)] [string] $WorkspaceRoot,
    [string] $BaselineRevision = "HEAD",
    [ValidateRange(1, 10000)] [int] $WarmupFrames = 4,
    [ValidateRange(1, 10000)] [int] $VisualWarmupFrames = 4,
    [ValidateRange(1, 10000)] [int] $Frames = 4,
    [ValidateRange(2, 20)] [int] $Rounds = 2,
    [ValidateRange(2, 20)] [int] $ControlRounds = 2,
    [string] $VisualPresetId = "spawn",
    [ValidateRange(30, 3600)] [int] $TimeoutSeconds = 900,
    [ValidateRange(2, 10000)] [int] $InterruptionWarmupFrames = 180,
    [string] $MatrixEvidencePath,
    [string] $EvidencePath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "source-probe-common.ps1")

$script:Owned = [System.Collections.Generic.List[object]]::new()
$script:Mcp = $null
$script:RequestId = 0
$script:InterruptedJobId = $null
$script:InterruptedAfterReceipts = 0
$script:ResumeCount = 0
$script:LivePresetId = $null

function Assert-LiveAcceptance
{
    param([Parameter(Mandatory)] [bool] $Condition, [Parameter(Mandatory)] [string] $Message)
    if (-not $Condition) { throw $Message }
}

function Stop-LiveMcp
{
    param([AllowNull()] [object] $Entry, [switch] $Abrupt)

    if ($null -eq $Entry) { return }
    $process = $Entry.Process
    if (-not $process.HasExited)
    {
        if (-not $Abrupt)
        {
            try { $process.StandardInput.Close() } catch { }
            [void] $process.WaitForExit(2000)
        }
        if (-not $process.HasExited)
        {
            $process.Kill()
            [void] $process.WaitForExit(5000)
        }
    }
}

function Start-LiveMcp
{
    $entry = Start-ProbeMcp -Exe $McpExe -Workspace $WorkspaceRoot -Port 50051 -Owned $script:Owned
    Initialize-ProbeMcp -Process $entry.Process -TimeoutSeconds $TimeoutSeconds
    return $entry
}

function Invoke-LiveTool
{
    param(
        [Parameter(Mandatory)] [string] $Name,
        [Parameter(Mandatory)] [object] $Arguments,
        [int] $CallTimeoutSeconds = $TimeoutSeconds
    )

    $hasOperation = if ($Arguments -is [System.Collections.IDictionary]) {
        $Arguments.Contains("operation")
    } else {
        $null -ne $Arguments.PSObject.Properties["operation"]
    }
    $isControl = $Name -ceq "vibris_run_recipe" -and $hasOperation
    if (-not $isControl -and $Name -in @("vibris_run_recipe", "vibris_run_actions", "vibris_run_matrix"))
    {
        if ([string]::IsNullOrWhiteSpace($script:LivePresetId))
        {
            throw "A request-scoped preset must be selected before running '$Name'."
        }
        if ($Arguments -is [System.Collections.IDictionary])
        {
            $Arguments["preset_id"] = $script:LivePresetId
        }
        else
        {
            $Arguments | Add-Member -NotePropertyName "preset_id" `
                -NotePropertyValue $script:LivePresetId -Force
        }
    }

    $script:RequestId++
    $id = "t12-live-$($script:RequestId)"
    $response = Invoke-ProbeMcpTool -Process $script:Mcp.Process -Id $id -Name $Name `
        -Arguments $Arguments -TimeoutSeconds $CallTimeoutSeconds
    if ($null -ne $response.result.PSObject.Properties["isError"] -and [bool] $response.result.isError)
    {
        throw "Tool '$Name' returned isError=true: " +
            ($response.result | ConvertTo-Json -Compress -Depth 30)
    }
    return Get-ProbeToolPayload $response
}

function Set-LivePreset
{
    param([Parameter(Mandatory)] [string] $PresetId)

    $catalog = Invoke-LiveTool -Name "vibris_list_presets" -Arguments ([ordered] @{ filter = $PresetId })
    $matches = @($catalog.presets | Where-Object { [string] $_.preset_id -ceq $PresetId })
    Assert-LiveAcceptance ($matches.Count -eq 1) "Preset '$PresetId' was not uniquely discoverable."
    Assert-LiveAcceptance (-not [string]::IsNullOrWhiteSpace([string] $matches[0].preset_sha256)) `
        "Preset '$PresetId' omitted its SHA-256."
    $script:LivePresetId = $PresetId
    return $matches[0]
}

function New-LiveMatrixArguments
{
    param(
        [int] $CaseWarmupFrames = $WarmupFrames,
        [ValidateSet("sync", "async")] [string] $Execution = "sync"
    )

    return [ordered] @{
        recipe = "profile_matrix"
        sources = @(
            [ordered] @{ id = "baseline"; kind = "commit"; revision = $BaselineRevision },
            [ordered] @{ id = "candidate"; kind = "workspace" }
        )
        configs = @([ordered] @{ id = "preserve"; mode = "preserve" })
        matrix = [ordered] @{
            sources = @("baseline", "candidate")
            configs = @("preserve")
        }
        warmup_frames = $CaseWarmupFrames
        frames = $Frames
        max_retries = 2
        result_detail = "metrics"
        metric_filter = @("begin3_a", "composite13_a", "composite34")
        statistics = @("avg", "p50", "p95")
        execution = $Execution
    }
}

function Test-TerminalMatrix
{
    param([Parameter(Mandatory)] [object] $Value)
    return [string] $Value.workflow_state -in @("completed", "cancelled") -or
        [string] $Value.status -in @("completed", "completed_with_failures", "cancelled")
}

function Wait-LiveMatrix
{
    param(
        [Parameter(Mandatory)] [string] $JobId,
        [switch] $ResumePaused,
        [int] $MinimumCompletedBeforeReturn = -1
    )

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    $lastProgress = ""
    while ([datetime]::UtcNow -lt $deadline)
    {
        $status = Invoke-LiveTool -Name "vibris_run_recipe" -Arguments ([ordered] @{
            recipe = "profile_matrix"
            operation = "status"
            job_id = $JobId
        })
        $progress = "$($status.progress.completed_cases)/$($status.progress.requested_cases) $($status.progress.stage)"
        if ($progress -cne $lastProgress)
        {
            Write-Host "matrix $JobId $progress"
            $lastProgress = $progress
        }
        if ($MinimumCompletedBeforeReturn -ge 0 -and
            [int] $status.progress.completed_cases -ge $MinimumCompletedBeforeReturn -and
            -not (Test-TerminalMatrix $status))
        {
            return $status
        }
        if (Test-TerminalMatrix $status) { return $status }
        if ([string] $status.workflow_state -ceq "paused" -or [string] $status.status -ceq "paused")
        {
            if (-not $ResumePaused) { return $status }
            $script:ResumeCount++
            [void] (Invoke-LiveTool -Name "vibris_run_recipe" -Arguments ([ordered] @{
                recipe = "profile_matrix"
                operation = "resume"
                job_id = $JobId
                execution = "async"
            }))
            Start-Sleep -Milliseconds 500
        }
        Start-Sleep -Milliseconds 100
    }
    throw "Matrix '$JobId' did not finish within $TimeoutSeconds seconds."
}

function Assert-LiveMatrix
{
    param(
        [Parameter(Mandatory)] [object] $Result,
        [Parameter(Mandatory)] [object] $Preset,
        [switch] $Interrupted
    )

    $presetId = [string] $Preset.preset_id
    $cases = @($Result.cases)
    Assert-LiveAcceptance ([string] $Result.gpu_timing_unit -ceq "ns") `
        "Preset '$presetId' did not report gpu_timing_unit=ns."
    Assert-LiveAcceptance ([int] $Result.requested_cases -eq 2 -and
        [int] $Result.completed_cases -eq 2 -and [int] $Result.cases_with_metrics -eq 2 -and
        [int] $Result.missing_cases -eq 0 -and [int] $Result.failed_cases -eq 0 -and
        [int] $Result.passed -eq 2 -and $cases.Count -eq 2) `
        "Preset '$presetId' did not produce exactly two complete metric receipts."
    Assert-LiveAcceptance ([bool] $Result.success) "Preset '$presetId' matrix did not succeed."
    Assert-LiveAcceptance (@($cases.case_id | Select-Object -Unique).Count -eq 2) `
        "Preset '$presetId' duplicated a case receipt."
    if ($Interrupted)
    {
        Assert-LiveAcceptance ([int] $Result.receipt_count -eq 2) `
            "Interrupted preset '$presetId' did not resume to exactly two receipts."
    }

    $receipts = [System.Collections.Generic.List[object]]::new()
    foreach ($case in $cases)
    {
        $programs = @($case.metrics.gpuProgramTimings)
        Assert-LiveAcceptance ([string] $case.status -ceq "passed" -and $null -eq $case.error) `
            "Preset '$presetId' source '$($case.source_id)' did not pass."
        Assert-LiveAcceptance ($null -ne $case.metrics -and $programs.Count -gt 0) `
            "Preset '$presetId' source '$($case.source_id)' passed without exact program metrics."
        Assert-LiveAcceptance ([bool] $case.provenance.complete) `
            "Preset '$presetId' source '$($case.source_id)' has incomplete provenance."
        Assert-LiveAcceptance ([string] $case.provenance.scene.preset_id -ceq $presetId) `
            "Preset '$presetId' source '$($case.source_id)' was measured in another scene."
        Assert-LiveAcceptance (-not [string]::IsNullOrWhiteSpace([string] $case.provenance.case_hash)) `
            "Preset '$presetId' source '$($case.source_id)' omitted its case hash."

        foreach ($program in $programs)
        {
            Assert-LiveAcceptance ([string] $program.kind -ceq "program") `
                "Preset '$presetId' returned a non-program timing in gpuProgramTimings."
            foreach ($field in @("metric", "program", "stage", "source"))
            {
                Assert-LiveAcceptance (-not [string]::IsNullOrWhiteSpace([string] $program.$field)) `
                    "Preset '$presetId' program timing omitted '$field'."
            }
            Assert-LiveAcceptance (@($program.statistics.PSObject.Properties).Count -gt 0) `
                "Preset '$presetId' program '$($program.program)' has no statistics."
        }

        $receipts.Add([ordered] @{
            preset_id = $presetId
            preset_sha256 = [string] $Preset.preset_sha256
            source_id = [string] $case.source_id
            case_id = [string] $case.case_id
            status = [string] $case.status
            attempt_count = [int] $case.attempt_count
            case_hash = [string] $case.provenance.case_hash
            source = $case.provenance.source
            shader = $case.provenance.shader
            scene = $case.provenance.scene
            gpu_timing_unit = [string] $Result.gpu_timing_unit
            gpu_program_timings = $programs
        })
    }
    return @($receipts)
}

function Invoke-LivePresetMatrix
{
    param([Parameter(Mandatory)] [object] $Preset, [switch] $InjectInterruption)

    $presetId = [string] $Preset.preset_id
    Write-Host "preset $presetId selecting request context"
    [void] (Set-LivePreset -PresetId $presetId)
    if (-not $InjectInterruption)
    {
        $result = Invoke-LiveTool -Name "vibris_run_recipe" `
            -Arguments (New-LiveMatrixArguments -Execution "sync")
        return [pscustomobject] @{
            Result = $result
            Receipts = @(Assert-LiveMatrix -Result $result -Preset $Preset)
            Interrupted = $false
        }
    }

    Write-Host "preset $presetId starting asynchronous interruption probe"
    $started = Invoke-LiveTool -Name "vibris_run_recipe" `
        -Arguments (New-LiveMatrixArguments -CaseWarmupFrames $InterruptionWarmupFrames -Execution "async")
    $jobId = [string] $started.job_id
    Assert-LiveAcceptance (-not [string]::IsNullOrWhiteSpace($jobId)) `
        "Interrupted matrix did not return a job_id."
    $beforeInterrupt = Wait-LiveMatrix -JobId $jobId -MinimumCompletedBeforeReturn 1
    Assert-LiveAcceptance ([int] $beforeInterrupt.progress.completed_cases -eq 1) `
        "Interruption probe reached an unexpected receipt count before disconnect."
    $script:InterruptedJobId = $jobId
    $script:InterruptedAfterReceipts = [int] $beforeInterrupt.progress.completed_cases

    Write-Host "matrix $jobId interrupting owned MCP after receipt 1/2"
    Stop-LiveMcp -Entry $script:Mcp -Abrupt
    $script:Mcp = Start-LiveMcp
    [void] (Set-LivePreset -PresetId $presetId)
    [void] (Invoke-LiveTool -Name "vibris_run_recipe" -Arguments ([ordered] @{
        recipe = "profile_matrix"
        operation = "resume"
        job_id = $jobId
        execution = "async"
    }))
    $result = Wait-LiveMatrix -JobId $jobId -ResumePaused
    return [pscustomobject] @{
        Result = $result
        Receipts = @(Assert-LiveMatrix -Result $result -Preset $Preset -Interrupted)
        Interrupted = $true
    }
}

function Assert-LiveVisualBenchmark
{
    param([Parameter(Mandatory)] [object] $Result)

    Assert-LiveAcceptance ([string] $Result.gpu_timing_unit -ceq "ns") `
        "benchmark_ab did not report gpu_timing_unit=ns."
    Assert-LiveAcceptance ([bool] $Result.success -and [string] $Result.status -ceq "completed") `
        "benchmark_ab did not complete successfully."
    Assert-LiveAcceptance ([string] $Result.performance_verdict -in @("stable", "unstable", "inconclusive")) `
        "benchmark_ab omitted its performance verdict."
    Assert-LiveAcceptance ([string] $Result.visual_verdict -ceq "passed" -and
        [string] $Result.visual.status -ceq "passed" -and [bool] $Result.visual.success) `
        "benchmark_ab visual gate did not pass."
    Assert-LiveAcceptance ([bool] $Result.guards.passed -and [bool] $Result.visual.guards.passed) `
        "benchmark_ab performance or visual guards failed."
    Assert-LiveAcceptance ([bool] $Result.visual.comparison.passed) `
        "benchmark_ab visual thresholds did not pass."
    foreach ($field in @(
        "mean_absolute_error", "root_mean_square_error", "p95_absolute_error",
        "max_absolute_error", "threshold_pixel_ratio", "ssim"))
    {
        Assert-LiveAcceptance ($null -ne $Result.visual.comparison.PSObject.Properties[$field]) `
            "benchmark_ab visual comparison omitted '$field'."
    }
    Assert-LiveAcceptance ([bool] $Result.visual.guards.two_distinct_frames -and
        [bool] $Result.visual.guards.diff_metrics_artifact -and
        [bool] $Result.visual.guards.diff_heatmap_artifact -and
        [bool] $Result.visual.guards.two_successful_load_receipts -and
        [bool] $Result.visual.guards.config_hash_match -and
        [bool] $Result.visual.guards.scene_hash_match) `
        "benchmark_ab visual receipt did not prove deterministic state and diff artifacts."
}

Assert-ProbeMcpExecutable -Exe $McpExe
$McpExe = [System.IO.Path]::GetFullPath($McpExe)
$WorkspaceRoot = [System.IO.Path]::GetFullPath($WorkspaceRoot)
Assert-LiveAcceptance (Test-Path -LiteralPath (Join-Path $WorkspaceRoot ".git")) `
    "WorkspaceRoot must be a Git worktree: $WorkspaceRoot"
if ([string]::IsNullOrWhiteSpace($EvidencePath))
{
    $stamp = [datetime]::UtcNow.ToString("yyyyMMdd-HHmmss")
    $EvidencePath = Join-Path $WorkspaceRoot ".vibris\artifact\t12-live-acceptance-$stamp.json"
}
$EvidencePath = [System.IO.Path]::GetFullPath($EvidencePath)
$evidenceDirectory = Split-Path -Parent $EvidencePath
[void] (New-Item -ItemType Directory -Path $evidenceDirectory -Force)

$allReceipts = [System.Collections.Generic.List[object]]::new()
$matrixSummaries = [System.Collections.Generic.List[object]]::new()
$catalog = $null
$prime = $null
$benchmark = $null
$status = $null

try
{
    $script:Mcp = Start-LiveMcp
    $status = Invoke-LiveTool -Name "vibris_get_status" -Arguments @{}
    Assert-LiveAcceptance ([bool] $status.ready -and [bool] $status.runtime_ready -and
        [string] $status.state -ceq "SERVER_STATE_READY" -and
        [string] $status.runtime_state -ceq "RUNTIME_STATE_READY") `
        "Minecraft/Vibris runtime is not ready."

    $catalog = Invoke-LiveTool -Name "vibris_list_presets" -Arguments @{}
    $presets = @($catalog.presets)
    Assert-LiveAcceptance ($presets.Count -eq 19 -and
        @($presets.preset_id | Select-Object -Unique).Count -eq 19) `
        "Live catalog must contain exactly 19 unique presets."
    $spawn = @($presets | Where-Object { [string] $_.preset_id -ceq "spawn" })
    Assert-LiveAcceptance ($spawn.Count -eq 1) "Live catalog does not contain exactly one spawn preset."
    $visualPreset = @($presets | Where-Object { [string] $_.preset_id -ceq $VisualPresetId })
    Assert-LiveAcceptance ($visualPreset.Count -eq 1) `
        "Live catalog does not contain exactly one visual preset '$VisualPresetId'."
    $orderedPresets = @($spawn[0]) + @($presets | Where-Object { [string] $_.preset_id -cne "spawn" })

    [void] (Set-LivePreset -PresetId "spawn")
    $prime = Invoke-LiveTool -Name "vibris_run_recipe" -Arguments ([ordered] @{
        recipe = "load_and_screenshot"
        source = [ordered] @{ kind = "workspace" }
        config = @{}
        warmup_frames = $WarmupFrames
        screenshot_format = "png"
    })
    Assert-LiveAcceptance ([bool] $prime.success) "Explicit-state priming load failed."
    $primeLoad = @($prime.action_results | Where-Object { [string] $_.kind -ceq "load_shader" })
    Assert-LiveAcceptance ($primeLoad.Count -eq 1 -and [bool] $primeLoad[0].result.provenance.complete -and
        [bool] $primeLoad[0].result.provenance.shader.settings_known) `
        "Explicit-state priming load did not establish restorable source/config/scene provenance."

    if (-not [string]::IsNullOrWhiteSpace($MatrixEvidencePath))
    {
        $MatrixEvidencePath = [System.IO.Path]::GetFullPath($MatrixEvidencePath)
        $loadedMatrix = Get-Content -Raw -LiteralPath $MatrixEvidencePath | ConvertFrom-Json
        Assert-LiveAcceptance ([string] $loadedMatrix.phase -ceq "matrix" -and
            [int] $loadedMatrix.summary.completed_cases -eq 38 -and
            [int] $loadedMatrix.summary.unique_receipts -eq 38) `
            "MatrixEvidencePath is not a completed 38-receipt matrix artifact."
        foreach ($receipt in @($loadedMatrix.receipts)) { $allReceipts.Add($receipt) }
        foreach ($summary in @($loadedMatrix.matrix_summaries)) { $matrixSummaries.Add($summary) }
        $script:InterruptedJobId = [string] $loadedMatrix.interruption.job_id
        $script:InterruptedAfterReceipts = [int] $loadedMatrix.interruption.interrupted_after_receipts
        $script:ResumeCount = [math]::Max(0, [int] $loadedMatrix.interruption.resume_requests - 1)
        Write-Host "loaded completed matrix evidence from $MatrixEvidencePath"
    }
    else
    {
        for ($index = 0; $index -lt $orderedPresets.Count; $index++)
        {
            $run = Invoke-LivePresetMatrix -Preset $orderedPresets[$index] -InjectInterruption:($index -eq 0)
            foreach ($receipt in @($run.Receipts)) { $allReceipts.Add($receipt) }
            $matrixSummaries.Add([ordered] @{
                preset_id = [string] $orderedPresets[$index].preset_id
                job_id = [string] $run.Result.job_id
                interrupted = [bool] $run.Interrupted
                requested_cases = [int] $run.Result.requested_cases
                completed_cases = [int] $run.Result.completed_cases
                receipt_count = [int] $run.Result.receipt_count
                total_attempts = [int] $run.Result.total_attempts
                retried_cases = [int] $run.Result.retried_cases
                artifacts = @($run.Result.artifacts)
            })
            Write-Host "preset $($orderedPresets[$index].preset_id) complete $($allReceipts.Count)/38"
        }
    }

    $receiptKeys = @($allReceipts | ForEach-Object { "$($_.preset_id)|$($_.source_id)" })
    Assert-LiveAcceptance ($allReceipts.Count -eq 38 -and
        @($receiptKeys | Select-Object -Unique).Count -eq 38) `
        "Live matrix did not produce exactly 38 unique (preset, source) receipts."
    Assert-LiveAcceptance (@($allReceipts | Where-Object {
        [string] $_.status -cne "passed" -or @($_.gpu_program_timings).Count -eq 0
    }).Count -eq 0) "A live receipt passed without exact program timing metrics."
    $allPrograms = @($allReceipts | ForEach-Object { @($_.gpu_program_timings) })
    $expectedPrograms = [ordered] @{
        begin3_a = "GenerateSkyViewLUT.comp.glsl"
        composite13_a = "DirectLighting.glsl"
        composite34 = "EpipolarScattering.comp.glsl"
    }
    foreach ($expected in $expectedPrograms.GetEnumerator())
    {
        Assert-LiveAcceptance (@($allPrograms | Where-Object {
            [string] $_.program -ceq [string] $expected.Key -and
            [string] $_.source -like "*$($expected.Value)"
        }).Count -gt 0) "Live timings did not map program '$($expected.Key)' to '$($expected.Value)'."
    }

    if ([string]::IsNullOrWhiteSpace($MatrixEvidencePath))
    {
        $MatrixEvidencePath = "$EvidencePath.matrix.json"
        [System.IO.File]::WriteAllText(
            $MatrixEvidencePath,
            ([ordered] @{
                schema_version = 1
                completed_at_utc = [datetime]::UtcNow.ToString("O")
                phase = "matrix"
                catalog = $catalog
                priming_load = $prime
                interruption = [ordered] @{
                    job_id = $script:InterruptedJobId
                    interrupted_after_receipts = $script:InterruptedAfterReceipts
                    resume_requests = $script:ResumeCount + 1
                    completed_without_duplicate_receipts = $true
                }
                matrix_summaries = @($matrixSummaries)
                receipts = @($allReceipts)
                summary = [ordered] @{
                    requested_presets = 19
                    requested_sources_per_preset = 2
                    requested_cases = 38
                    completed_cases = 38
                    unique_receipts = 38
                    gpu_timing_unit = "ns"
                    exact_program_metadata = $true
                    interrupted_resume = $true
                }
            } | ConvertTo-Json -Depth 100),
            [System.Text.UTF8Encoding]::new($false)
        )
        Write-Host "matrix evidence written to $MatrixEvidencePath"
    }

    [void] (Set-LivePreset -PresetId $VisualPresetId)
    Write-Host "running paired performance + deterministic visual gate in preset $VisualPresetId"
    $benchmark = Invoke-LiveTool -Name "vibris_run_recipe" -Arguments ([ordered] @{
        recipe = "benchmark_ab"
        baseline = [ordered] @{ kind = "commit"; revision = $BaselineRevision }
        candidate = [ordered] @{ kind = "workspace" }
        config = @{}
        warmup_frames = $WarmupFrames
        frames = $Frames
        rounds = $Rounds
        control_rounds = $ControlRounds
        order = "abba"
        statistic = "avg"
        metric_filter = @("begin3_a", "composite13_a", "composite34")
        max_retries = 2
        result_detail = "metrics"
        visual = [ordered] @{
            warmup_frames = $VisualWarmupFrames
            pixel_error_threshold = 0.015
            max_mean_absolute_error = 0.002
            max_root_mean_square_error = 0.004
            max_p95_absolute_error = 0.01
            max_absolute_error = 0.10
            max_threshold_pixel_ratio = 0.001
            min_ssim = 0.995
        }
    })
    [System.IO.File]::WriteAllText(
        "$EvidencePath.benchmark.json",
        ($benchmark | ConvertTo-Json -Depth 100),
        [System.Text.UTF8Encoding]::new($false)
    )
    Assert-LiveVisualBenchmark $benchmark

    $evidence = [ordered] @{
        schema_version = 1
        completed_at_utc = [datetime]::UtcNow.ToString("O")
        mcp = [ordered] @{
            path = $McpExe
            sha256 = (Get-FileHash -Algorithm SHA256 -LiteralPath $McpExe).Hash
        }
        workspace_root = $WorkspaceRoot
        baseline_revision = $BaselineRevision
        parameters = [ordered] @{
            warmup_frames = $WarmupFrames
            visual_warmup_frames = $VisualWarmupFrames
            frames = $Frames
            rounds = $Rounds
            control_rounds = $ControlRounds
            visual_preset_id = $VisualPresetId
            max_retries = 2
        }
        initial_status = $status
        catalog = $catalog
        priming_load = $prime
        interruption = [ordered] @{
            job_id = $script:InterruptedJobId
            interrupted_after_receipts = $script:InterruptedAfterReceipts
            resume_requests = $script:ResumeCount + 1
            completed_without_duplicate_receipts = $true
        }
        matrix_summaries = @($matrixSummaries)
        receipts = @($allReceipts)
        benchmark_ab = $benchmark
        summary = [ordered] @{
            requested_presets = 19
            requested_sources_per_preset = 2
            requested_cases = 38
            completed_cases = 38
            unique_receipts = 38
            gpu_timing_unit = "ns"
            exact_program_metadata = $true
            interrupted_resume = $true
            visual_preset_id = $VisualPresetId
            visual_gate = "passed"
            performance_verdict = [string] $benchmark.performance_verdict
            combined_verdict = [string] $benchmark.verdict
        }
    }
    $temporaryEvidence = "$EvidencePath.tmp"
    [System.IO.File]::WriteAllText(
        $temporaryEvidence,
        ($evidence | ConvertTo-Json -Depth 100),
        [System.Text.UTF8Encoding]::new($false)
    )
    Move-Item -LiteralPath $temporaryEvidence -Destination $EvidencePath -Force
    Write-Output "PASS presets=19 sources=2 receipts=38 interrupted_after=1 visual=passed evidence=$EvidencePath"
}
finally
{
    Stop-LiveMcp -Entry $script:Mcp
    foreach ($entry in $script:Owned)
    {
        Stop-LiveMcp -Entry $entry
        try { $entry.Process.Dispose() } catch { }
    }
}
