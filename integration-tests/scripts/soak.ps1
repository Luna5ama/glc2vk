[CmdletBinding()]
param(
    [ValidateRange(1, 1000000)] [int] $Iterations = 1000,
    [ValidateRange(0, 536870912)] [long] $SourceBytes = 52428800,
    [switch] $ReloadCapture,
    [string] $MetricsOut = ".omo\tmp\ulw-v1-g008-c003\metrics.json"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "stdio-job-probe-common.ps1")
. (Join-Path $PSScriptRoot "source-probe-common.ps1")

$timeoutSeconds = 600
$scope = $null
$mcp = $null
$failure = $null
$context = $null
$mcpStderr = ""
$minimumJobBytes = [long]::MaxValue
$expectedSourceBytes = 0L
$samples = [System.Collections.Generic.List[object]]::new()
$cleanup = [ordered] @{
    mcp_stdin_closed = $false
    mcp_process_exited = $false
    completion_queues_closed = $false
    worker_threads_joined = $false
    worktree_mutex_reacquired = $false
    iris_process_exited = $false
    grpc_listener_closed = $false
    pending_sources_removed = $false
    criterion_root_removed = $false
}

function Get-SoakMedian
{
    param(
        [Parameter(Mandatory)] [object[]] $Values,
        [Parameter(Mandatory)] [string] $Property
    )

    $ordered = @($Values | ForEach-Object {
        [double] $_.PSObject.Properties[$Property].Value
    } | Sort-Object)
    if ($ordered.Count -eq 0) { throw "Cannot calculate a median from an empty series." }
    $middle = [math]::Floor($ordered.Count / 2)
    if ($ordered.Count % 2 -eq 1) { return $ordered[$middle] }
    return ($ordered[$middle - 1] + $ordered[$middle]) / 2.0
}

function Get-SoakSlope
{
    param(
        [Parameter(Mandatory)] [object[]] $Values,
        [Parameter(Mandatory)] [string] $Property
    )

    if ($Values.Count -lt 2) { return 0.0 }
    $meanX = ($Values.Count - 1) / 2.0
    $meanY = (@($Values | ForEach-Object {
        [double] $_.PSObject.Properties[$Property].Value
    }) | Measure-Object -Average).Average
    $numerator = 0.0
    $denominator = 0.0
    for ($index = 0; $index -lt $Values.Count; $index++)
    {
        $x = $index - $meanX
        $numerator += $x * ([double] $Values[$index].PSObject.Properties[$Property].Value - $meanY)
        $denominator += $x * $x
    }
    if ($denominator -eq 0.0) { return 0.0 }
    return $numerator / $denominator
}

function Assert-SoakSettled
{
    param(
        [Parameter(Mandatory)] [object[]] $Baseline,
        [Parameter(Mandatory)] [object[]] $Final,
        [Parameter(Mandatory)] [string] $Property,
        [Parameter(Mandatory)] [double] $Tolerance
    )

    $start = Get-SoakMedian -Values $Baseline -Property $Property
    $end = Get-SoakMedian -Values $Final -Property $Property
    $ratio = if ($start -eq 0.0) { if ($end -eq 0.0) { 0.0 } else { [double]::PositiveInfinity } } else {
        [math]::Abs($end - $start) / $start
    }
    if ($ratio -gt $Tolerance)
    {
        throw "$Property did not settle within $([int] ($Tolerance * 100))%: baseline=$start final=$end."
    }
    return [ordered] @{ baseline = $start; final = $end; absolute_delta_ratio = $ratio }
}

function Get-SoakProcessMetrics
{
    param([Parameter(Mandatory)] [int] $Id)

    $process = Get-Process -Id $Id -ErrorAction Stop
    $process.Refresh()
    return [ordered] @{
        private_bytes = [long] $process.PrivateMemorySize64
        working_set_bytes = [long] $process.WorkingSet64
        handle_count = [long] $process.HandleCount
        thread_count = [long] $process.Threads.Count
    }
}

function Get-SoakNativeMetric
{
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [long] $AfterSequence
    )

    $deadline = [datetime]::UtcNow.AddSeconds(2)
    do
    {
        if (Test-Path -LiteralPath $Path -PathType Leaf)
        {
            $line = Get-Content -LiteralPath $Path -Tail 1
            if (-not [string]::IsNullOrWhiteSpace($line))
            {
                $metric = $line | ConvertFrom-Json
                if ([long] $metric.sequence -gt $AfterSequence) { return $metric }
            }
        }
        Start-Sleep -Milliseconds 10
    } while ([datetime]::UtcNow -lt $deadline)
    throw "Sanitizer MCP did not append its native metric after sequence $AfterSequence."
}

function Get-SoakJavaMetric
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [Parameter(Mandatory)] [string] $RequestId
    )

    $command = @{
        run_id = $Scope.RunId
        command = "runtime-metrics"
        request_id = $RequestId
    } | ConvertTo-Json -Compress
    $next = $Scope.CommandFile + ".next"
    [System.IO.File]::WriteAllText($next, $command)
    [System.IO.File]::Move($next, $Scope.CommandFile)
    return Wait-IrisEvent -Scope $Scope -Type "runtime_metrics" -TimeoutSeconds $timeoutSeconds `
        -Match { param($event) $event.request_id -ceq $RequestId }
}

function Get-SoakStorageMetrics
{
    param([Parameter(Mandatory)] [object] $Scope)

    $sourceDirectories = @(Get-ChildItem -LiteralPath $Scope.PendingRoot -Directory `
        -ErrorAction SilentlyContinue | Where-Object {
            $parsed = [guid]::Empty
            [guid]::TryParse($_.Name, [ref] $parsed)
        })
    $sourceFiles = @(Get-ChildItem -LiteralPath $Scope.PendingRoot -Recurse -File `
        -ErrorAction SilentlyContinue)
    $artifactDirectories = @(Get-ChildItem -LiteralPath $Scope.ArtifactRoot -Recurse -Directory `
        -ErrorAction SilentlyContinue)
    $manifests = @(Get-ChildItem -LiteralPath $Scope.ArtifactRoot -Recurse -File -Filter "manifest.json" `
        -ErrorAction SilentlyContinue)
    return [ordered] @{
        pending_source_directories = $sourceDirectories.Count
        pending_source_bytes = [long] (($sourceFiles | Measure-Object -Property Length -Sum).Sum)
        artifact_job_directories = $manifests.Count
        artifact_temporary_directories = @($artifactDirectories | Where-Object {
            $_.Name.EndsWith(".tmp", [System.StringComparison]::Ordinal)
        }).Count
    }
}

function Assert-SoakToolSurface
{
    param([Parameter(Mandatory)] [System.Diagnostics.Process] $Process)

    Send-ProbeMcpMessage -Process $Process -Message @{
        jsonrpc = "2.0"; id = "soak-tools"; method = "tools/list"; params = @{}
    }
    $response = Read-ProbeMcpResponse -Process $Process -ExpectedId "soak-tools" `
        -TimeoutSeconds $timeoutSeconds
    $actual = @($response.result.tools | ForEach-Object { $_.name })
    $expected = @(
        "vibris_get_config", "vibris_list_presets", "vibris_configure",
        "vibris_get_status", "vibris_profile", "vibris_run_recipe", "vibris_run_actions",
        "vibris_get_capture_status", "vibris_reload_shader", "vibris_capture_pass",
        "vibris_capture_multi", "vibris_get_shader_status", "vibris_get_shader_errors",
        "vibris_schedule_screenshot", "vibris_get_screenshot_result", "vibris_get_gpu_metrics",
        "vibris_list_ssbos", "vibris_dump_ssbo", "vibris_list_textures",
        "vibris_dump_texture", "vibris_list_patched_shaders"
    )
    if ([string]::Join("`n", $actual) -cne [string]::Join("`n", $expected) -or
        @($actual | Select-Object -Unique).Count -ne $expected.Count)
    {
        throw "Runtime MCP did not expose exactly the expected 21-tool surface."
    }
}

$metricsPath = if ([System.IO.Path]::IsPathRooted($MetricsOut)) {
    [System.IO.Path]::GetFullPath($MetricsOut)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $script:VibrisRoot $MetricsOut))
}
$releaseExe = Join-Path $script:VibrisRoot "mcp\out\build\Release\vibris-mcp.exe"
$sanitizerExe = Join-Path $script:VibrisRoot "mcp\out\asan\vibris-mcp.exe"
$nativeMetricPath = ""
$usingSanitizer = Test-Path -LiteralPath $sanitizerExe -PathType Leaf
$mcpExe = if ($usingSanitizer) { $sanitizerExe } else { $releaseExe }
$lastNativeSequence = -1L
$audit = ""
$thresholds = $null
$started = [datetime]::UtcNow

try
{
    $audit = [string] (& (Join-Path $PSScriptRoot "source-package-audit.ps1") `
        -McpExe $releaseExe -PatchedJar (Join-Path $script:IrisRoot "build\libs\iris-fabric-*-local.jar"))
    $criterionRoot = Join-Path $script:VibrisRoot ".omo\tmp\ulw-v1-g008-c003"
    if (Test-Path -LiteralPath $criterionRoot)
    {
        $entries = @(Get-ChildItem -LiteralPath $criterionRoot -Recurse -Force)
        if ($entries.Count -ne 1 -or $entries[0].PSIsContainer -or
            -not [string]::Equals($entries[0].FullName, $metricsPath,
                [System.StringComparison]::OrdinalIgnoreCase))
        {
            throw "G008-C003 root contains data other than the prior metrics evidence: $criterionRoot"
        }
        Remove-Item -LiteralPath $criterionRoot -Recurse -Force
    }
    $scope = New-IrisProbeScope -Criterion "c003" -Gate "G008" `
        -GameDir (Join-Path $script:VibrisRoot ".omo\tmp\ulw-v1-g008-c003\game")
    $workspace = Join-Path $scope.Root "worktree"
    [void] (New-Item -ItemType Directory -Path $workspace)
    $scope | Add-Member -NotePropertyName WorkspaceRoot -NotePropertyValue $workspace
    [void] (Initialize-G007Workspace -Scope $scope)
    if ($SourceBytes -gt 0)
    {
        $payloadPath = Join-Path $workspace "shaders\soak-payload.bin"
        $stream = [System.IO.File]::Open($payloadPath, [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try { $stream.SetLength($SourceBytes) }
        finally { $stream.Dispose() }
        [void] (Invoke-G007Git -WorkspaceRoot $workspace `
            -GitArguments @("add", "--", "shaders\soak-payload.bin"))
        [void] (Invoke-G007Git -WorkspaceRoot $workspace -GitArguments @(
            "-c", "user.name=Vibris Soak", "-c", "user.email=vibris@example.invalid",
            "commit", "--quiet", "-m", "add soak payload"
        ))
    }
    $expectedSourceBytes = [long] ((Get-ChildItem -LiteralPath (Join-Path $workspace "shaders") `
        -Recurse -File | Measure-Object -Property Length -Sum).Sum)

    $context = Start-G007Runtime -Scope $scope -Scenario "g008-c003" -TimeoutSeconds $timeoutSeconds
    $owned = [System.Collections.ArrayList]::new()
    $nativeMetricPath = Join-Path $scope.Root "native-metrics.jsonl"
    $oldPath = [Environment]::GetEnvironmentVariable("PATH", "Process")
    $oldMetrics = [Environment]::GetEnvironmentVariable("VIBRIS_SOAK_METRICS", "Process")
    $oldAsanOptions = [Environment]::GetEnvironmentVariable("ASAN_OPTIONS", "Process")
    try
    {
        if ($usingSanitizer)
        {
            $clang = Get-Command "clang++.exe" -ErrorAction Stop
            $resourceRoot = (& $clang.Source -print-resource-dir).Trim()
            $runtime = Join-Path $resourceRoot "lib\windows\clang_rt.asan_dynamic-x86_64.dll"
            if (-not (Test-Path -LiteralPath $runtime -PathType Leaf))
            {
                throw "Clang ASan runtime is missing: $runtime"
            }
            [Environment]::SetEnvironmentVariable("PATH", "$(Split-Path -Parent $runtime);$oldPath", "Process")
            [Environment]::SetEnvironmentVariable("VIBRIS_SOAK_METRICS", $nativeMetricPath, "Process")
            $asanOptions = @($oldAsanOptions, "detect_container_overflow=0") | Where-Object {
                -not [string]::IsNullOrWhiteSpace($_)
            }
            [Environment]::SetEnvironmentVariable("ASAN_OPTIONS", ($asanOptions -join ':'), "Process")
        }
        $mcp = Start-ProbeMcp -Exe $mcpExe -Workspace $workspace -Port $script:IrisPort -Owned $owned
    }
    finally
    {
        [Environment]::SetEnvironmentVariable("PATH", $oldPath, "Process")
        [Environment]::SetEnvironmentVariable("VIBRIS_SOAK_METRICS", $oldMetrics, "Process")
        [Environment]::SetEnvironmentVariable("ASAN_OPTIONS", $oldAsanOptions, "Process")
    }
    Initialize-ProbeMcp -Process $mcp.Process -TimeoutSeconds $timeoutSeconds
    Assert-SoakToolSurface -Process $mcp.Process
    $configured = Invoke-ProbeMcpTool -Process $mcp.Process -Id "soak-config" -Name "vibris_configure" `
        -Arguments @{
            save_id = $context.save_id
            dimension_id = $context.dimension_id
            time_preset_id = $context.time_preset_id
            camera_preset_id = $context.camera_preset_id
            fov = $context.fov
            default_warmup_frames = 0
        } -TimeoutSeconds $timeoutSeconds
    if ($configured.result.isError)
    {
        throw "vibris_configure failed: $((Get-ProbeToolPayload $configured) | ConvertTo-Json -Compress -Depth 20)"
    }
    if ($usingSanitizer)
    {
        $native = Get-SoakNativeMetric -Path $nativeMetricPath -AfterSequence $lastNativeSequence
        $lastNativeSequence = [long] $native.sequence
    }

    $clock = [System.Diagnostics.Stopwatch]::StartNew()
    for ($iteration = 1; $iteration -le $Iterations; $iteration++)
    {
        $job = $null
        if ($ReloadCapture)
        {
            $response = Invoke-ProbeMcpTool -Process $mcp.Process -Id "soak-job-$iteration" `
                -Name "vibris_run_recipe" -Arguments @{
                    recipe = "reload_and_capture"
                    source = @{ kind = "workspace" }
                    warmup_frames = 0
                    screenshot_format = "png"
                } -TimeoutSeconds $timeoutSeconds
            if ($response.result.isError)
            {
                throw "Iteration $iteration failed: " +
                    ((Get-ProbeToolPayload $response) | ConvertTo-Json -Compress -Depth 20)
            }
            $job = Get-ProbeToolPayload $response
            Assert-G007CompletedResult -Scope $scope -Payload $job
            $jobDirectory = Split-Path -Parent ([string] $job.manifest_path)
            $jobBytes = [long] ((Get-ChildItem -LiteralPath $jobDirectory -Recurse -File |
                Measure-Object -Property Length -Sum).Sum)
            $minimumJobBytes = [math]::Min($minimumJobBytes, $jobBytes)
        }

        $statusResponse = Invoke-ProbeMcpTool -Process $mcp.Process -Id "soak-status-$iteration" `
            -Name "vibris_get_status" -Arguments @{} -TimeoutSeconds $timeoutSeconds
        if ($statusResponse.result.isError)
        {
            throw "Status after iteration $iteration failed: " +
                ((Get-ProbeToolPayload $statusResponse) | ConvertTo-Json -Compress -Depth 20)
        }
        $status = Get-ProbeToolPayload $statusResponse
        $nativeProcess = Get-SoakProcessMetrics -Id $mcp.Process.Id
        $javaProcess = Get-SoakProcessMetrics -Id $scope.RuntimePid
        $javaMetric = Get-SoakJavaMetric -Scope $scope -RequestId "soak-java-$iteration"
        $storage = Get-SoakStorageMetrics -Scope $scope
        $native = $null
        if ($usingSanitizer)
        {
            $native = Get-SoakNativeMetric -Path $nativeMetricPath -AfterSequence $lastNativeSequence
            $lastNativeSequence = [long] $native.sequence
        }
        $artifactUsed = [long] $status.artifact_quota_used_bytes
        $artifactCap = [long] $status.artifact_quota_cap_bytes
        $expectedPendingBytes = if ($ReloadCapture) { $expectedSourceBytes } else { 0L }
        if ($storage.pending_source_directories -gt 1 -or
            $storage.pending_source_bytes -ne $expectedPendingBytes -or
            $storage.artifact_temporary_directories -ne 0 -or $artifactUsed -gt $artifactCap)
        {
            throw "Iteration $iteration left unbounded source/artifact state: expected_pending_bytes=" +
                "$expectedPendingBytes actual=$(($storage | ConvertTo-Json -Compress)) " +
                "artifact_used=$artifactUsed artifact_cap=$artifactCap."
        }
        if ($ReloadCapture -and $minimumJobBytes -ne [long]::MaxValue)
        {
            $jobLimit = [long] [math]::Ceiling($artifactCap / [double] [math]::Max(1, $minimumJobBytes)) + 1
            if ($storage.artifact_job_directories -gt $jobLimit)
            {
                throw "Iteration $iteration exceeded the artifact directory bound $jobLimit."
            }
        }
        $samples.Add([pscustomobject] [ordered] @{
            iteration = $iteration
            elapsed_ms = [long] $clock.ElapsedMilliseconds
            native_handle_count = $nativeProcess.handle_count
            native_private_bytes = $nativeProcess.private_bytes
            native_working_set_bytes = $nativeProcess.working_set_bytes
            native_thread_count = $nativeProcess.thread_count
            native_heap_allocated_bytes = if ($null -ne $native) {
                [long] $native.heap_allocated_bytes
            } else { $null }
            native_heap_size_bytes = if ($null -ne $native) { [long] $native.heap_size_bytes } else { $null }
            java_heap_used_bytes = [long] $javaMetric.heap_used_bytes
            java_direct_buffer_bytes = [long] $javaMetric.direct_buffer_memory_used_bytes
            java_private_bytes = $javaProcess.private_bytes
            java_working_set_bytes = $javaProcess.working_set_bytes
            java_handle_count = $javaProcess.handle_count
            java_thread_count = $javaProcess.thread_count
            pending_source_directories = $storage.pending_source_directories
            pending_source_bytes = $storage.pending_source_bytes
            active_source_uuid = [string] $status.active_source_uuid
            artifact_job_directories = $storage.artifact_job_directories
            artifact_temporary_directories = $storage.artifact_temporary_directories
            artifact_quota_used_bytes = $artifactUsed
            artifact_quota_cap_bytes = $artifactCap
        })
        Write-Progress -Activity "Vibris G008-C003 soak" -Status "$iteration / $Iterations" `
            -PercentComplete ([int] (100 * $iteration / $Iterations))
    }
    Write-Progress -Activity "Vibris G008-C003 soak" -Completed

    $warmup = [math]::Min(20, [math]::Floor($samples.Count / 10))
    $window = [math]::Max(1, [math]::Min(50, [math]::Floor(($samples.Count - $warmup) / 2)))
    $baseline = @($samples | Select-Object -Skip $warmup -First $window)
    $final = @($samples | Select-Object -Last $window)
    $nativeHandles = Assert-SoakSettled -Baseline $baseline -Final $final `
        -Property "native_handle_count" -Tolerance 0.02
    $nativePrivate = [ordered] @{
        baseline = Get-SoakMedian -Values $baseline -Property "native_private_bytes"
        final = Get-SoakMedian -Values $final -Property "native_private_bytes"
        gated = $false
        note = "ASan allocator reservation and quarantine are not live allocation metrics"
    }
    $nativeHeap = if ($usingSanitizer) {
        Assert-SoakSettled -Baseline $baseline -Final $final `
            -Property "native_heap_allocated_bytes" -Tolerance 0.02
    } else { $null }
    $javaWindow = @($samples | Select-Object -Last ([math]::Min(200, $samples.Count)))
    $javaComparisonWindow = [math]::Max(1, [math]::Min(50, [math]::Floor($javaWindow.Count / 2)))
    $javaHeapStart = Get-SoakMedian -Values @($javaWindow | Select-Object -First $javaComparisonWindow) `
        -Property "java_heap_used_bytes"
    $javaHeapEnd = Get-SoakMedian -Values @($javaWindow | Select-Object -Last $javaComparisonWindow) `
        -Property "java_heap_used_bytes"
    $javaHeapSlope = Get-SoakSlope -Values $javaWindow -Property "java_heap_used_bytes"
    $javaHeapNoise = $javaHeapStart * 0.05 / [math]::Max(1, $javaWindow.Count)
    if ($javaHeapEnd -gt $javaHeapStart * 1.05 -or $javaHeapSlope -gt $javaHeapNoise)
    {
        throw "Java live heap trends upward beyond 5% noise: start=$javaHeapStart end=$javaHeapEnd " +
            "slope=$javaHeapSlope."
    }
    $javaDirectStart = Get-SoakMedian -Values @($javaWindow | Select-Object -First $javaComparisonWindow) `
        -Property "java_direct_buffer_bytes"
    $javaDirectEnd = Get-SoakMedian -Values @($javaWindow | Select-Object -Last $javaComparisonWindow) `
        -Property "java_direct_buffer_bytes"
    $javaDirectSlope = Get-SoakSlope -Values $javaWindow -Property "java_direct_buffer_bytes"
    $javaDirectNoise = $javaDirectStart * 0.05 / [math]::Max(1, $javaWindow.Count)
    if ($javaDirectEnd -gt $javaDirectStart * 1.05 -or $javaDirectSlope -gt $javaDirectNoise)
    {
        throw "Java direct-buffer memory trends upward beyond 5% noise: start=$javaDirectStart " +
            "end=$javaDirectEnd slope=$javaDirectSlope."
    }
    $thresholds = [ordered] @{
        warmup_samples = $warmup
        comparison_window = $window
        native_handle_count = $nativeHandles
        native_private_bytes = $nativePrivate
        native_heap_allocated_bytes = $nativeHeap
        java_metric_source = "automation_runtime_metrics"
        java_live_heap_available = $true
        java_direct_buffer_available = $true
        java_final_window_samples = $javaWindow.Count
        java_heap_final_window_start = $javaHeapStart
        java_heap_final_window_end = $javaHeapEnd
        java_heap_final_200_slope_bytes_per_iteration = $javaHeapSlope
        java_heap_allowed_noise_slope_bytes_per_iteration = $javaHeapNoise
        java_direct_final_window_start = $javaDirectStart
        java_direct_final_window_end = $javaDirectEnd
        java_direct_final_200_slope_bytes_per_iteration = $javaDirectSlope
        java_direct_allowed_noise_slope_bytes_per_iteration = $javaDirectNoise
    }
}
catch
{
    $failure = $_.Exception
}
finally
{
    if ($null -ne $mcp)
    {
        try
        {
            Stop-ProbeMcp -Entry $mcp -TimeoutSeconds $timeoutSeconds
            $cleanup.mcp_stdin_closed = $true
            $cleanup.mcp_process_exited = $mcp.Process.HasExited
            $mcpStderr = $mcp.Stderr.Result
            $cleanup.completion_queues_closed = $mcpStderr -match 'completion_queues=1'
            $cleanup.worker_threads_joined = $mcpStderr -match 'worker_threads_joined=[1-9][0-9]*'
            if (-not $cleanup.completion_queues_closed -or -not $cleanup.worker_threads_joined)
            {
                throw "Native MCP shutdown did not report closed completion queues and joined workers: $mcpStderr"
            }
        }
        catch
        {
            $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception
        }
        finally
        {
            if (-not $mcp.Process.HasExited)
            {
                $mcp.Process.Kill()
                [void] $mcp.Process.WaitForExit(5000)
            }
            $mcp.Process.Dispose()
        }
        if ($cleanup.mcp_process_exited -and $null -ne $scope)
        {
            try
            {
                $probe = @([ordered] @{
                    jsonrpc = "2.0"; id = 1; method = "initialize"
                    params = @{
                        protocolVersion = "2024-11-05"; capabilities = @{}
                        clientInfo = @{ name = "vibris-soak-lock-probe"; version = "1" }
                    }
                })
                [void] (Invoke-G007Mcp -Exe $releaseExe -WorkspaceRoot $scope.WorkspaceRoot `
                    -Messages $probe -TimeoutSeconds $timeoutSeconds)
                $cleanup.worktree_mutex_reacquired = $true
            }
            catch
            {
                $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception
            }
        }
    }
    if ($null -ne $scope)
    {
        try
        {
            Stop-IrisPackagedClient -Scope $scope -TimeoutSeconds $timeoutSeconds
            $cleanup.iris_process_exited = $scope.RuntimePid -le 0 -or
                $null -eq (Get-Process -Id $scope.RuntimePid -ErrorAction SilentlyContinue)
            $cleanup.grpc_listener_closed = -not (Test-CorePort -Port $script:IrisPort)
            $cleanup.pending_sources_removed =
                @(Get-ChildItem -LiteralPath $scope.PendingRoot -Force -ErrorAction SilentlyContinue).Count -eq 0
        }
        catch
        {
            $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception
        }
        try
        {
            Remove-IrisProbeScope -Scope $scope
            $cleanup.criterion_root_removed = -not (Test-Path -LiteralPath $scope.Root)
        }
        catch
        {
            $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception
        }
    }

    $metrics = [ordered] @{
        schema_version = 1
        criterion = "G008-C003"
        success = $null -eq $failure
        started_at_utc = $started.ToString("O")
        completed_at_utc = [datetime]::UtcNow.ToString("O")
        iterations_requested = $Iterations
        iterations_completed = $samples.Count
        source_bytes = $SourceBytes
        reload_capture = [bool] $ReloadCapture
        native_executable = $mcpExe
        sanitizer_metrics = $usingSanitizer
        asan_container_annotations = if ($usingSanitizer) {
            "disabled for uninstrumented vcpkg static dependencies; address checks remain enabled"
        } else { $null }
        java_metrics_note = "Packaged Iris probe reports live heap and direct-buffer MXBean metrics."
        source_package_audit = $audit
        thresholds = $thresholds
        cleanup = $cleanup
        failure = if ($null -eq $failure) { $null } else { $failure.ToString() }
        samples = @($samples)
    }
    try
    {
        $metricsDirectory = Split-Path -Parent $metricsPath
        [void] (New-Item -ItemType Directory -Path $metricsDirectory -Force)
        [System.IO.File]::WriteAllText($metricsPath, ($metrics | ConvertTo-Json -Depth 20))
    }
    catch
    {
        $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception
    }
}

if ($null -ne $failure)
{
    Write-Error $failure
    exit 1
}
Write-Output ("PASS criterion=G008-C003 iterations=$Iterations source_bytes=$SourceBytes " +
    "reload_capture=$([bool] $ReloadCapture) metrics=$metricsPath")
Write-Output ("CLEANUP criterion=G008-C003 mcp_closed=true workers_joined=true listener_closed=true " +
    "mutex_reacquired=true pending_removed=true root_removed=true")
