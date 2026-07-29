[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet("Red", "Green")] [string] $Mode,
    [Parameter(Mandatory)] [ValidateScript({ $_ -eq 3 })] [int] $WorktreeCount,
    [Parameter(Mandatory)] [ValidateScript({ $_ -eq 2 })] [int] $JobsPerWorktree,
    [Parameter(Mandatory)] [ValidateScript({ $_ -eq 1 })] [int] $MinecraftCount,
    [ValidateRange(1, 900)] [int] $TimeoutSeconds = 600
)

. (Join-Path $PSScriptRoot "stdio-job-probe-common.ps1")

$fixturePath = Join-Path $script:VibrisRoot `
    "integration-tests\fixtures\acceptance\three-worktree-six-jobs.json"
$sourcePath = Join-Path $script:VibrisRoot `
    "integration-tests\fixtures\shaderpacks\capture-known-resources\shaders"
$sourceExe = Join-Path $script:VibrisRoot "mcp\out\build\Release\vibris-mcp.exe"
$sourceJar = Join-Path $script:IrisRoot "build\libs\iris-fabric-*-local.jar"
$scope = $null
$repository = $null
$worktrees = @{}
$workspaceIds = @{}
$jobEntries = @{}
$failure = $null
$summary = $null

function Wait-AcceptancePendingSources
{
    param([Parameter(Mandatory)] [object] $Scope, [Parameter(Mandatory)] [int] $Minimum)

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([datetime]::UtcNow -lt $deadline)
    {
        $count = @(Get-ChildItem -LiteralPath $Scope.PendingRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object {
                $parsed = [guid]::Empty
                [guid]::TryParse($_.Name, [ref] $parsed)
            }).Count
        if ($count -ge $Minimum) { return }
        if ($Scope.Wrapper.Process.HasExited) { throw "Packaged client exited while jobs were queued." }
        Start-Sleep -Milliseconds 50
    }
    throw "Pending source count did not reach $Minimum within $TimeoutSeconds seconds."
}

function New-AcceptanceMessages
{
    param([Parameter(Mandatory)] [object] $Client)

    $messages = [System.Collections.Generic.List[object]]::new()
    $messages.Add([ordered] @{
        jsonrpc = "2.0"
        id = 10
        method = "initialize"
        params = [ordered] @{
            protocolVersion = "2024-11-05"
            capabilities = @{}
            clientInfo = @{ name = "vibris-final-acceptance-$($Client.label)"; version = "1.0" }
        }
    })
    $messages.Add([ordered] @{
        jsonrpc = "2.0"
        id = 11
        method = "tools/call"
        params = @{ name = "vibris_get_config"; arguments = @{} }
    })
    foreach ($job in @($Client.jobs))
    {
        $messages.Add([ordered] @{
            jsonrpc = "2.0"
            id = [int] $job.message_id
            method = "tools/call"
            params = [ordered] @{
                name = "vibris_run_recipe"
                arguments = [ordered] @{
                    recipe = "reload_and_capture"
                    source = @{ kind = "workspace" }
                    warmup_frames = [int] $job.warmup_frames
                    screenshot_format = "png"
                }
            }
        })
    }
    return @($messages)
}

function Assert-AcceptanceConfig
{
    param(
        [Parameter(Mandatory)] [object] $Actual,
        [Parameter(Mandatory)] [object] $Expected,
        [Parameter(Mandatory)] [string] $WorkspaceId
    )

    $configProperty = $Actual.PSObject.Properties["config"]
    $config = if ($null -ne $configProperty -and $null -ne $configProperty.Value)
    {
        $configProperty.Value
    }
    else
    {
        $Actual
    }
    if ([string] $config.workspace_id -cne $WorkspaceId -or
        [string] $config.shader_directory -cne "shaders" -or
        [string] $config.save_id -cne [string] $Expected.save_id -or
        [string] $config.dimension_id -cne [string] $Expected.dimension_id -or
        [string] $config.time_preset_id -cne [string] $Expected.time_preset_id -or
        [string] $config.camera_preset_id -cne [string] $Expected.camera_preset_id -or
        [double] $config.fov -ne [double] $Expected.fov -or
        [int] $config.default_warmup_frames -ne [int] $Expected.default_warmup_frames)
    {
        throw "Restarted MCP configuration changed or crossed worktrees: " +
            ($Actual | ConvertTo-Json -Compress -Depth 10)
    }
}

function Assert-AcceptanceContext
{
    param([Parameter(Mandatory)] [object] $Actual, [Parameter(Mandatory)] [object] $Client)

    $configured = $Client.configure
    $expected = $Client.expected
    if ([string] $Actual.save_id -cne [string] $configured.save_id -or
        [string] $Actual.dimension_id -cne [string] $configured.dimension_id -or
        [string] $Actual.time_preset_id -cne [string] $configured.time_preset_id -or
        [string] $Actual.weather_preset_id -cne [string] $expected.weather_preset_id -or
        [string] $Actual.camera_preset_id -cne [string] $configured.camera_preset_id -or
        [string] $Actual.settings_preset_id -cne [string] $expected.settings_preset_id -or
        [double] $Actual.fov -ne [double] $configured.fov -or
        [int] $Actual.resolution.width -lt 1 -or [int] $Actual.resolution.height -lt 1 -or
        [long] $Actual.day_time -ne [long] $expected.day_time -or
        [double] $Actual.rain_level -ne [double] $expected.rain_level -or
        [double] $Actual.thunder_level -ne [double] $expected.thunder_level)
    {
        throw "Minecraft context did not match worktree $($Client.label): " +
            ($Actual | ConvertTo-Json -Compress -Depth 10)
    }
    foreach ($field in @("x", "y", "z", "yaw", "pitch"))
    {
        if ([double] $Actual.camera.$field -ne [double] $expected.camera.$field)
        {
            throw "Minecraft camera field '$field' did not match worktree $($Client.label)."
        }
    }
}

try
{
    $fixture = Get-Content -Raw -LiteralPath $fixturePath | ConvertFrom-Json
    $clients = @($fixture.clients)
    $expectedOrder = @($fixture.expected_execution_order)
    if ($fixture.schema_version -ne 1 -or $clients.Count -ne $WorktreeCount -or
        @($clients | Where-Object { @($_.jobs).Count -ne $JobsPerWorktree }).Count -ne 0 -or
        [string]::Join(",", $expectedOrder) -cne "A1,B1,C1,A2,B2,C2")
    {
        throw "Final acceptance fixture does not encode the frozen 3x2 scenario."
    }

    $exe = Resolve-IrisArtifact -Path $sourceExe -Label "Release native MCP"
    $jar = Resolve-IrisPatchedJar -Path $sourceJar
    $scope = New-IrisProbeScope -Criterion "c001" -Gate "G008" `
        -GameDir (Join-Path $script:VibrisRoot ".omo\tmp\ulw-v1-g008-c001\game")

    $delivery = Join-Path $scope.Root "delivery"
    [void] (New-Item -ItemType Directory -Path $delivery)
    $stagedExe = Join-Path $delivery "vibris-mcp.exe"
    $stagedJar = Join-Path $delivery (Split-Path -Leaf $jar)
    Copy-Item -LiteralPath $exe -Destination $stagedExe
    Copy-Item -LiteralPath $jar -Destination $stagedJar
    [void] (Resolve-IrisPatchedJar -Path $stagedJar)
    $deliveryFiles = @(Get-ChildItem -LiteralPath $delivery -Recurse -File)
    if ($deliveryFiles.Count -ne 2 -or
        @($deliveryFiles | Where-Object { $_.Name -ceq "vibris-mcp.exe" }).Count -ne 1 -or
        @($deliveryFiles | Where-Object { $_.Extension -ceq ".jar" }).Count -ne 1)
    {
        throw "Delivery must contain exactly one vibris-mcp.exe and one patched Iris JAR."
    }

    $repository = Join-Path $scope.Root "repository"
    $worktreeRoot = Join-Path $scope.Root "worktrees"
    [void] (New-Item -ItemType Directory -Path $repository)
    [void] (New-Item -ItemType Directory -Path $worktreeRoot)
    [void] (Invoke-G007Git -WorkspaceRoot $repository -GitArguments @("init", "--quiet"))
    Copy-Item -LiteralPath $sourcePath -Destination (Join-Path $repository "shaders") -Recurse
    [System.IO.File]::WriteAllText((Join-Path $repository ".gitignore"), ".codex/`r`n")
    [void] (Invoke-G007Git -WorkspaceRoot $repository -GitArguments @("add", "--", ".gitignore", "shaders"))
    [void] (Invoke-G007Git -WorkspaceRoot $repository -GitArguments @(
        "-c", "user.name=Vibris Acceptance", "-c", "user.email=vibris@example.invalid",
        "commit", "--quiet", "-m", "acceptance baseline"
    ))
    foreach ($client in $clients)
    {
        $label = [string] $client.label
        $worktree = Join-Path $worktreeRoot $label
        [void] (Invoke-G007Git -WorkspaceRoot $repository -GitArguments @(
            "worktree", "add", "--quiet", "-b", "acceptance-$($label.ToLowerInvariant())", $worktree, "HEAD"
        ))
        $worktrees[$label] = $worktree
        [System.IO.File]::WriteAllText(
            (Join-Path $worktree "shaders\lib\acceptance-$($label.ToLowerInvariant()).glsl"),
            "#define VIBRIS_ACCEPTANCE_$label 1`r`n")
    }

    $presetRoot = Join-Path $scope.GameDir "config\vibris"
    [void] (New-Item -ItemType Directory -Path $presetRoot)
    [System.IO.File]::WriteAllText((Join-Path $presetRoot "presets.json"),
        ($fixture.preset_catalog | ConvertTo-Json -Depth 20))
    Start-IrisPackagedClient -Scope $scope -PatchedJar $stagedJar -Scenario "g008-c001" `
        -TimeoutSeconds $TimeoutSeconds
    Wait-IrisWorldReady -Scope $scope -TimeoutSeconds $TimeoutSeconds

    foreach ($client in $clients)
    {
        $label = [string] $client.label
        $messages = @(
            [ordered] @{
                jsonrpc = "2.0"; id = 1; method = "initialize"
                params = @{
                    protocolVersion = "2024-11-05"; capabilities = @{}
                    clientInfo = @{ name = "vibris-configure-$label"; version = "1.0" }
                }
            },
            [ordered] @{
                jsonrpc = "2.0"; id = 2; method = "tools/call"
                params = @{ name = "vibris_configure"; arguments = $client.configure }
            },
            [ordered] @{
                jsonrpc = "2.0"; id = 3; method = "tools/call"
                params = @{ name = "vibris_get_config"; arguments = @{} }
            }
        )
        $responses = Invoke-G007Mcp -Exe $stagedExe -WorkspaceRoot $worktrees[$label] `
            -Messages $messages -TimeoutSeconds $TimeoutSeconds
        $configured = Get-G007ToolPayload (Get-G007Response -Responses $responses -Id 2)
        $persisted = Get-G007ToolPayload (Get-G007Response -Responses $responses -Id 3)
        $workspaceId = [string] $configured.workspace_id
        $parsed = [guid]::Empty
        if (-not [guid]::TryParse($workspaceId, [ref] $parsed))
        {
            throw "Worktree $label did not receive a UUID workspace_id."
        }
        Assert-AcceptanceConfig -Actual $persisted -Expected $client.configure -WorkspaceId $workspaceId
        $workspaceIds[$label] = $workspaceId
    }
    if (@($workspaceIds.Values | Select-Object -Unique).Count -ne $WorktreeCount)
    {
        throw "Workspace IDs crossed or were reused between worktrees."
    }

    $clientA = @($clients | Where-Object { $_.label -ceq "A" })[0]
    $jobEntries["A"] = Start-G007Mcp -Exe $stagedExe -WorkspaceRoot $worktrees["A"] `
        -Messages (New-AcceptanceMessages -Client $clientA)
    [void] (Wait-IrisEvent -Scope $scope -Type "context_applied" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.context.dimension_id -ceq $clientA.configure.dimension_id -and
            [double] $event.context.fov -eq [double] $clientA.configure.fov })

    $clientB = @($clients | Where-Object { $_.label -ceq "B" })[0]
    $jobEntries["B"] = Start-G007Mcp -Exe $stagedExe -WorkspaceRoot $worktrees["B"] `
        -Messages (New-AcceptanceMessages -Client $clientB)
    Wait-AcceptancePendingSources -Scope $scope -Minimum 2
    $clientC = @($clients | Where-Object { $_.label -ceq "C" })[0]
    $jobEntries["C"] = Start-G007Mcp -Exe $stagedExe -WorkspaceRoot $worktrees["C"] `
        -Messages (New-AcceptanceMessages -Client $clientC)
    Wait-AcceptancePendingSources -Scope $scope -Minimum 3

    foreach ($client in $clients)
    {
        $label = [string] $client.label
        $responses = Complete-G007Mcp -Entry $jobEntries[$label] -TimeoutSeconds $TimeoutSeconds
        $restartConfig = Get-G007ToolPayload (Get-G007Response -Responses $responses -Id 11)
        Assert-AcceptanceConfig -Actual $restartConfig -Expected $client.configure `
            -WorkspaceId $workspaceIds[$label]
        foreach ($job in @($client.jobs))
        {
            $result = Get-G007ToolPayload (Get-G007Response -Responses $responses -Id ([int] $job.message_id))
            Assert-G007CompletedResult -Scope $scope -Payload $result
        }
    }
    foreach ($label in @("A", "B", "C"))
    {
        Stop-G007Mcp -Entry $jobEntries[$label]
        $jobEntries[$label] = $null
        $lockProbe = @([ordered] @{
            jsonrpc = "2.0"; id = 1; method = "initialize"
            params = @{
                protocolVersion = "2024-11-05"; capabilities = @{}
                clientInfo = @{ name = "vibris-lock-probe-$label"; version = "1.0" }
            }
        })
        [void] (Invoke-G007Mcp -Exe $stagedExe -WorkspaceRoot $worktrees[$label] `
            -Messages $lockProbe -TimeoutSeconds $TimeoutSeconds)
    }

    $events = @(Get-Content -LiteralPath $scope.EventFile | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_)
    } | ForEach-Object { $_ | ConvertFrom-Json })
    if (@($events | Where-Object { $_.run_id -cne $scope.RunId }).Count -ne 0)
    {
        throw "Vibris automation event stream contains another run ID."
    }
    $contexts = @($events | Where-Object {
        $_.type -ceq "context_applied" -and -not [string]::IsNullOrWhiteSpace([string] $_.source_uuid)
    })
    if ($contexts.Count -ne ($WorktreeCount * $JobsPerWorktree))
    {
        throw "Expected six attributed context events, observed $($contexts.Count)."
    }
    $occurrences = @{ A = 0; B = 0; C = 0 }
    $actualOrder = [System.Collections.Generic.List[string]]::new()
    $sourceIds = [System.Collections.Generic.HashSet[string]]::new()
    foreach ($event in $contexts)
    {
        $matches = @($clients | Where-Object {
            $_.configure.dimension_id -ceq $event.context.dimension_id -and
            [double] $_.configure.fov -eq [double] $event.context.fov
        })
        if ($matches.Count -ne 1) { throw "Context event did not identify exactly one worktree." }
        $client = $matches[0]
        Assert-AcceptanceContext -Actual $event.context -Client $client
        $label = [string] $client.label
        $occurrences[$label]++
        $actualOrder.Add("$label$($occurrences[$label])")
        if (-not $sourceIds.Add([string] $event.source_uuid))
        {
            throw "Two jobs reused source UUID $($event.source_uuid)."
        }
    }
    if ([string]::Join(",", $actualOrder) -cne [string]::Join(",", $expectedOrder))
    {
        throw "Execution order was $([string]::Join(',', $actualOrder)); expected A1,B1,C1,A2,B2,C2."
    }

    $sourceEvents = @($events | Where-Object {
        $_.type -ceq "source_active" -and $sourceIds.Contains([string] $_.source_uuid)
    })
    $captureEvents = @($events | Where-Object {
        $_.type -ceq "capture_complete" -and $sourceIds.Contains([string] $_.source_uuid)
    })
    if ($sourceEvents.Count -ne 6 -or $captureEvents.Count -ne 6 -or
        [string]::Join(",", @($sourceEvents.source_uuid)) -cne
            [string]::Join(",", @($contexts.source_uuid)) -or
        [string]::Join(",", @($captureEvents.source_uuid)) -cne
            [string]::Join(",", @($contexts.source_uuid)))
    {
        throw "Source, context, and capture events did not form six ordered job intervals."
    }
    foreach ($event in $sourceEvents)
    {
        $target = [System.IO.Path]::GetFullPath([string] $event.link_target)
        if ((Split-Path -Leaf $target) -cne [string] $event.source_uuid -or
            -not $target.StartsWith($scope.PendingRoot + [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase))
        {
            throw "Job activated a source outside the owned pending root: $target"
        }
    }
    $active = [System.Collections.Generic.HashSet[string]]::new()
    $maxConcurrent = 0
    foreach ($event in $events)
    {
        if ($event.type -ceq "source_active")
        {
            $uuid = [string] $event.source_uuid
            if (-not $sourceIds.Contains($uuid)) { continue }
            if ($active.Count -ne 0 -or -not $active.Add($uuid))
            {
                throw "A job source activated before the prior capture completed."
            }
            $maxConcurrent = [math]::Max($maxConcurrent, $active.Count)
        }
        elseif ($event.type -ceq "capture_complete")
        {
            $uuid = [string] $event.source_uuid
            if (-not $sourceIds.Contains($uuid)) { continue }
            if (-not $active.Remove($uuid)) { throw "Capture completed without its active job interval." }
        }
    }
    if ($active.Count -ne 0 -or $maxConcurrent -ne 1)
    {
        throw "Observed max concurrency $maxConcurrent with $($active.Count) unfinished intervals."
    }
    $summary = "PASS criterion=G008-C001 mode=$Mode order=A1,B1,C1,A2,B2,C2 " +
        "max_concurrent_jobs=1 worktrees=3 jobs=6 minecraft=1 restart_persisted=true " +
        "config_crosstalk=false state=source/world/time/camera/fov delivery_files=2"
}
catch
{
    $failure = $_.Exception
}
finally
{
    foreach ($entry in @($jobEntries.Values))
    {
        try { Stop-G007Mcp -Entry $entry }
        catch { $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception }
    }
    if ($null -ne $scope)
    {
        try { Stop-IrisPackagedClient -Scope $scope -TimeoutSeconds $TimeoutSeconds }
        catch { $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception }
    }
    if ($null -ne $repository -and (Test-Path -LiteralPath $repository -PathType Container))
    {
        foreach ($label in @("C", "B", "A"))
        {
            $worktree = $worktrees[$label]
            if ($null -ne $worktree -and (Test-Path -LiteralPath $worktree))
            {
                try
                {
                    [void] (Invoke-G007Git -WorkspaceRoot $repository `
                        -GitArguments @("worktree", "remove", "--force", $worktree))
                }
                catch { $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception }
            }
        }
        try { [void] (Invoke-G007Git -WorkspaceRoot $repository -GitArguments @("worktree", "prune")) }
        catch { $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception }
    }
    if ($null -ne $scope)
    {
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
Write-Output ("CLEANUP criterion=G008-C001 mcps_closed=3 listener_closed=true mutexes_reacquired=true " +
    "worktrees_removed=true root_removed=true multimc_untouched=true")