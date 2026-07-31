[CmdletBinding()]
param(
    [Parameter(Mandatory)] [ValidateSet("Red", "Green")] [string] $Mode,
    [Parameter(Mandatory)] [ValidateScript({ $_ -eq 3 })] [int] $WorktreeCount,
    [Parameter(Mandatory)] [ValidateScript({ $_ -eq 2 })] [int] $JobsPerWorktree,
    [Parameter(Mandatory)] [ValidateScript({ $_ -eq 1 })] [int] $MinecraftCount,
    [Parameter(Mandatory)] [string] $DeliveryRoot,
    [string] $EvidenceDirectory,
    [switch] $InjectSourceCrosstalk,
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
$repositoryOne = $null
$linkedWorktree = $null
$worktrees = @{}
$jobEntries = @{}
$workspaceIds = @{}
$ownedEntries = [System.Collections.Generic.List[object]]::new()
$ownedPids = [System.Collections.Generic.List[int]]::new()
$failure = $null
$summary = $null
$eventSummary = $null

function Test-CorePort
{
    param([Parameter(Mandatory)] [int] $Port)

    return @(Get-NetTCPConnection -LocalPort $Port -State Listen `
        -ErrorAction SilentlyContinue).Count -ne 0
}

function Resolve-AcceptanceDelivery
{
    $delivery = Resolve-IrisDirectory -Path $DeliveryRoot -Label "DeliveryRoot"
    if ((Get-Item -LiteralPath $delivery -Force).Attributes -band
        [System.IO.FileAttributes]::ReparsePoint)
    {
        throw "DeliveryRoot must not be a reparse point: $delivery"
    }
    $entries = @(Get-ChildItem -LiteralPath $delivery -Recurse -Force)
    if ($entries | Where-Object { $_.Attributes -band [System.IO.FileAttributes]::ReparsePoint })
    {
        throw "DeliveryRoot must not contain reparse points: $delivery"
    }
    $files = @($entries | Where-Object { -not $_.PSIsContainer })
    $mcp = @($files | Where-Object { $_.Name -ceq "vibris-mcp.exe" })
    $iris = @($files | Where-Object { $_.Extension -ceq ".jar" })
    if ($files.Count -ne 2 -or $mcp.Count -ne 1 -or $iris.Count -ne 1)
    {
        throw "DeliveryRoot must contain exactly one vibris-mcp.exe and one patched Iris JAR."
    }

    $canonicalMcp = Resolve-IrisArtifact -Path $sourceExe -Label "Release native MCP"
    $canonicalIris = Resolve-IrisPatchedJar -Path $sourceJar
    $deliveredIris = Resolve-IrisPatchedJar -Path $iris[0].FullName
    $mcpHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $mcp[0].FullName).Hash
    $irisHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $deliveredIris).Hash
    if ($mcpHash -cne (Get-FileHash -Algorithm SHA256 -LiteralPath $canonicalMcp).Hash -or
        $irisHash -cne (Get-FileHash -Algorithm SHA256 -LiteralPath $canonicalIris).Hash)
    {
        throw "DeliveryRoot contains a stale or mismatched MCP/Iris artifact."
    }
    return [pscustomobject] @{
        Root = $delivery
        Mcp = $mcp[0].FullName
        Iris = $deliveredIris
        McpSha256 = $mcpHash
        IrisSha256 = $irisHash
    }
}

function Get-AcceptanceIrisPort
{
    $defaultListeners = @(Get-NetTCPConnection -LocalPort $script:IrisPort -State Listen `
        -ErrorAction SilentlyContinue)
    if ($defaultListeners.Count -eq 0) { return $script:IrisPort }
    $reservation = [System.Net.Sockets.TcpListener]::new(
        [System.Net.IPAddress]::Loopback, 0)
    try
    {
        $reservation.Start()
        return ([System.Net.IPEndPoint] $reservation.LocalEndpoint).Port
    }
    finally
    {
        $reservation.Stop()
    }
}

function Wait-AcceptanceOwnedListener
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [Parameter(Mandatory)] [int] $Port,
        [ValidateRange(1, 600)] [int] $TimeoutSeconds
    )

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([datetime]::UtcNow -lt $deadline)
    {
        $listeners = @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue)
        $owned = @($listeners | Where-Object {
            $_.LocalAddress -ceq "127.0.0.1" -and [int] $_.LocalPort -eq $Port -and
            [int] $_.OwningProcess -eq [int] $Scope.RuntimePid
        })
        if ($listeners.Count -eq 1 -and $owned.Count -eq 1)
        {
            Assert-IrisOwnedRuntime -Scope $Scope
            return $owned[0]
        }
        if ($listeners.Count -ne 0)
        {
            throw "Listener ownership mismatch on 127.0.0.1:$Port; expected runtime PID " +
                "$($Scope.RuntimePid), observed " +
                [string]::Join(",", @($listeners | ForEach-Object {
                    "$($_.LocalAddress):$($_.LocalPort)/$($_.OwningProcess)"
                }))
        }
        if ($Scope.Wrapper.Process.HasExited)
        {
            throw "Packaged Iris exited before its exact listener ownership tuple was observed."
        }
        Start-Sleep -Milliseconds 20
    }
    throw "Owned Iris listener tuple was not observed within $TimeoutSeconds seconds."
}

function Start-AcceptancePackagedClient
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [Parameter(Mandatory)] [string] $PatchedJar,
        [ValidateRange(1, 600)] [int] $TimeoutSeconds
    )

    if ($script:IrisPort -eq 50051)
    {
        Start-IrisPackagedClient -Scope $Scope -PatchedJar $PatchedJar -Scenario "g008-c001" `
            -TimeoutSeconds $TimeoutSeconds
        [void] (Wait-AcceptanceOwnedListener -Scope $Scope -Port $script:IrisPort `
            -TimeoutSeconds $TimeoutSeconds)
        return
    }

    $initScript = Join-Path $Scope.Root "alternate-port-$($Scope.RunId).init.gradle"
    $port = $script:IrisPort
    $initText = @"
gradle.projectsEvaluated {
    if (gradle.rootProject.name == 'Iris') {
        def fabric = gradle.rootProject.findProject(':fabric')
        if (fabric == null) {
            throw new GradleException('Iris build did not expose the :fabric project')
        }
        def target = fabric.tasks.getByName('runVibrisAutomationClient')
        if (target.actions.size() != 2) {
            throw new GradleException('runVibrisAutomationClient must have exactly writer and Loom actions')
        }
        def writer = target.actions.get(0)
        def loom = target.actions.get(1)
        def override = { ignored ->
            def game = new File(target.project.findProperty('automationGameDir').toString())
            def config = new File(game, 'config/vibris/server.json')
            def original = '\"listen_address\": \"127.0.0.1:50051\"'
            def replacement = '\"listen_address\": \"127.0.0.1:$port\"'
            def text = config.getText('UTF-8')
            if (!text.contains(original)) {
                throw new GradleException('Vibris automation server config did not contain the default listener')
            }
            config.setText(text.replace(original, replacement), 'UTF-8')
        } as org.gradle.api.Action
        target.actions.add(1, override)
        def injected = target.actions.get(1)
        if (target.actions.size() != 3 || !target.actions.get(0).is(writer) ||
            !target.actions.get(2).is(loom) || injected.is(writer) || injected.is(loom)) {
            throw new GradleException('alternate port action was not injected between writer and Loom actions')
        }
    }
}
"@
    [System.IO.File]::WriteAllText($initScript, $initText)
    $arguments = @(
        "--no-daemon", "--init-script", $initScript, ":fabric:runVibrisAutomationClient",
        "-PautomationPatchedJar=$PatchedJar", "-PautomationGameDir=$($Scope.GameDir)",
        "-PautomationRunId=$($Scope.RunId)", "-PautomationScenario=g008-c001"
    )
    $command = (ConvertTo-CoreArgument (Join-Path $script:IrisRoot "gradlew.bat")) + " " +
        [string]::Join(" ", @($arguments | ForEach-Object { ConvertTo-CoreArgument $_ }))
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = Join-Path ([Environment]::GetFolderPath("System")) "cmd.exe"
    $startInfo.WorkingDirectory = $script:IrisRoot
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.Arguments = "/d /s /c `"$command`""
    $wrapper = [System.Diagnostics.Process]::new()
    $wrapper.StartInfo = $startInfo
    [void] $wrapper.Start()
    $Scope.Wrapper = [pscustomobject] @{
        Process = $wrapper
        Created = $wrapper.StartTime.ToUniversalTime().ToString("O")
        Stdout = $wrapper.StandardOutput.ReadToEndAsync()
        Stderr = $wrapper.StandardError.ReadToEndAsync()
    }
    Wait-IrisOwnedRuntimeProcess -Scope $Scope -TimeoutSeconds $TimeoutSeconds
    Start-IrisWindowGuard -Scope $Scope
    $receipt = Wait-IrisReceipt -Scope $Scope -TimeoutSeconds $TimeoutSeconds
    if ([int] $receipt.pid -ne $Scope.RuntimePid)
    {
        throw "Alternate-port runtime ownership receipt did not match the owned PID."
    }
    Assert-IrisOwnedRuntime -Scope $Scope
    [void] (Wait-AcceptanceOwnedListener -Scope $Scope -Port $script:IrisPort `
        -TimeoutSeconds $TimeoutSeconds)
}

function Wait-AcceptancePendingMarker
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [Parameter(Mandatory)] [string] $Marker,
        [Parameter(Mandatory)] [ValidateScript({ $_ -eq 1 })] [int] $Minimum
    )

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([datetime]::UtcNow -lt $deadline)
    {
        $matches = @(Get-ChildItem -LiteralPath $Scope.PendingRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object {
                $parsed = [guid]::Empty
                $markerPath = Join-Path $_.FullName "lib\vibris-acceptance-source.glsl"
                [guid]::TryParse($_.Name, [ref] $parsed) -and
                    (Test-Path -LiteralPath $markerPath -PathType Leaf) -and
                    (Get-Content -Raw -LiteralPath $markerPath).Trim() -ceq $Marker
            })
        if ($matches.Count -ge $Minimum) { return [string] $matches[0].Name }
        if ($Scope.Wrapper.Process.HasExited) { throw "Packaged client exited while jobs were queued." }
        Start-Sleep -Milliseconds 50
    }
    throw "Pending source marker '$Marker' did not reach $Minimum within $TimeoutSeconds seconds."
}

function Start-AcceptanceMcp
{
    param(
        [Parameter(Mandatory)] [string] $Exe,
        [Parameter(Mandatory)] [string] $WorkingDirectory,
        [Parameter(Mandatory)] [object[]] $Messages
    )

    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = [System.IO.Path]::GetFullPath($Exe)
    $start.WorkingDirectory = [System.IO.Path]::GetFullPath($WorkingDirectory)
    $start.Arguments = "--server-address 127.0.0.1:$script:IrisPort"
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    [void] $process.Start()
    $entry = [pscustomobject] @{
        Process = $process
        Created = $process.StartTime.ToUniversalTime().ToString("O")
        Output = $process.StandardOutput.ReadToEndAsync()
        Error = $process.StandardError.ReadToEndAsync()
        Count = @($Messages | Where-Object { $_ -is [System.Collections.IDictionary] -and $_.Contains("id") }).Count
        WorkingDirectory = $start.WorkingDirectory
        Arguments = $start.Arguments
        Closed = $false
    }
    $ownedEntries.Add($entry)
    $ownedPids.Add($process.Id)
    foreach ($message in $Messages)
    {
        $process.StandardInput.WriteLine(($message | ConvertTo-Json -Compress -Depth 30))
    }
    $process.StandardInput.Close()
    return $entry
}

function Stop-AcceptanceMcp
{
    param([AllowNull()] [object] $Entry)

    if ($null -eq $Entry -or $Entry.Closed) { return }
    if (-not $Entry.Process.HasExited -and
        $Entry.Process.StartTime.ToUniversalTime().ToString("O") -ceq $Entry.Created)
    {
        Stop-Process -Id $Entry.Process.Id -Force
        [void] $Entry.Process.WaitForExit(5000)
    }
    $Entry.Process.Dispose()
    $Entry.Closed = $true
}

function Invoke-AcceptanceMcp
{
    param(
        [Parameter(Mandatory)] [string] $Exe,
        [Parameter(Mandatory)] [string] $WorkingDirectory,
        [Parameter(Mandatory)] [object[]] $Messages
    )

    $entry = Start-AcceptanceMcp -Exe $Exe -WorkingDirectory $WorkingDirectory -Messages $Messages
    try { return Complete-G007Mcp -Entry $entry -TimeoutSeconds $TimeoutSeconds }
    finally { Stop-AcceptanceMcp -Entry $entry }
}

function New-AcceptanceMessages
{
    param([Parameter(Mandatory)] [object] $Client)

    $messages = [System.Collections.Generic.List[object]]::new()
    $messages.Add([ordered] @{
        jsonrpc = "2.0"; id = 10; method = "initialize"
        params = [ordered] @{
            protocolVersion = "2024-11-05"; capabilities = @{}
            clientInfo = @{ name = "vibris-final-acceptance-$($Client.label)"; version = "1.0" }
        }
    })
    $messages.Add([ordered] @{
        jsonrpc = "2.0"; method = "notifications/initialized"; params = @{}
    })
    $messages.Add([ordered] @{
        jsonrpc = "2.0"; id = 11; method = "tools/call"
        params = @{ name = "vibris_get_config"; arguments = @{} }
    })
    $messages.Add([ordered] @{
        jsonrpc = "2.0"; id = 12; method = "tools/call"
        params = @{ name = "vibris_configure"; arguments = $Client.configure }
    })
    $messages.Add([ordered] @{
        jsonrpc = "2.0"; id = 13; method = "tools/call"
        params = @{ name = "vibris_get_config"; arguments = @{} }
    })
    foreach ($job in @($Client.jobs))
    {
        $messages.Add([ordered] @{
            jsonrpc = "2.0"; id = [int] $job.message_id; method = "tools/call"
            params = [ordered] @{
                name = "vibris_run_recipe"
                arguments = [ordered] @{
                    recipe = "reload_and_capture"
                    source = @{ kind = "commit"; revision = [string] $job.revision }
                    warmup_frames = [int] $job.warmup_frames
                    screenshot_format = "png"
                }
            }
        })
    }
    return @($messages)
}

function Assert-AcceptanceInitializeResponse
{
    param(
        [Parameter(Mandatory)] [object[]] $Responses,
        [Parameter(Mandatory)] [int] $Id
    )

    $response = Get-G007Response -Responses $Responses -Id $Id
    if ([string] $response.jsonrpc -cne "2.0" -or
        $null -ne $response.PSObject.Properties["error"] -or
        $null -eq $response.PSObject.Properties["result"] -or
        [string] $response.result.protocolVersion -cne "2024-11-05")
    {
        throw "MCP initialize response $Id was an error or negotiated the wrong protocol: " +
            ($response | ConvertTo-Json -Compress -Depth 10)
    }
    return $response
}

function Assert-AcceptanceBinding
{
    param(
        [Parameter(Mandatory)] [object] $Actual,
        [Parameter(Mandatory)] [string] $ExpectedRoot,
        [string] $ExpectedWorkspaceId,
        [Parameter(Mandatory)] [bool] $Configured
    )

    $actualRoot = [System.IO.Path]::GetFullPath([string] $Actual.worktree_root)
    if (-not [System.StringComparer]::OrdinalIgnoreCase.Equals(
            $actualRoot, [System.IO.Path]::GetFullPath($ExpectedRoot)) -or
        [bool] $Actual.configured -ne $Configured -or
        ($Configured -and $null -eq $Actual.config) -or
        (-not $Configured -and $null -ne $Actual.config))
    {
        throw "MCP binding/configuration state was incorrect: " +
            ($Actual | ConvertTo-Json -Compress -Depth 10)
    }
    $parsed = [guid]::Empty
    if (-not [guid]::TryParse([string] $Actual.workspace_id, [ref] $parsed) -or
        (-not [string]::IsNullOrWhiteSpace($ExpectedWorkspaceId) -and
            [string] $Actual.workspace_id -cne $ExpectedWorkspaceId))
    {
        throw "MCP workspace identity was missing or unstable."
    }
    return [string] $Actual.workspace_id
}

function Assert-AcceptanceConfig
{
    param(
        [Parameter(Mandatory)] [object] $Actual,
        [Parameter(Mandatory)] [object] $Expected,
        [Parameter(Mandatory)] [string] $WorkspaceId
    )

    $config = if ($null -ne $Actual.PSObject.Properties["config"]) { $Actual.config } else { $Actual }
    if ([string] $config.workspace_id -cne $WorkspaceId -or
        [string] $config.shader_directory -cne "shaders" -or
        [string] $config.save_id -cne [string] $Expected.save_id -or
        [string] $config.dimension_id -cne [string] $Expected.dimension_id -or
        [string] $config.time_preset_id -cne [string] $Expected.time_preset_id -or
        [string] $config.camera_preset_id -cne [string] $Expected.camera_preset_id -or
        [double] $config.fov -ne [double] $Expected.fov -or
        [int] $config.default_warmup_frames -ne [int] $Expected.default_warmup_frames)
    {
        throw "MCP process-local configuration changed or crossed worktrees: " +
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

function Initialize-AcceptanceJobSnapshots
{
    param(
        [Parameter(Mandatory)] [string] $Worktree,
        [Parameter(Mandatory)] [object] $Client
    )

    $marker = Join-Path $Worktree "shaders\lib\vibris-acceptance-source.glsl"
    foreach ($job in @($Client.jobs))
    {
        [System.IO.File]::WriteAllText($marker, [string] $job.source_marker + "`r`n")
        [void] (Invoke-G007Git -WorkspaceRoot $Worktree -GitArguments @(
            "add", "--", "shaders/lib/vibris-acceptance-source.glsl"
        ))
        [void] (Invoke-G007Git -WorkspaceRoot $Worktree -GitArguments @(
            "-c", "user.name=Vibris Acceptance", "-c", "user.email=vibris@example.invalid",
            "commit", "--quiet", "-m", "acceptance source $($job.id)"
        ))
        $revision = [string] @(Invoke-G007Git -WorkspaceRoot $Worktree `
            -GitArguments @("rev-parse", "HEAD"))[0]
        $job | Add-Member -NotePropertyName revision -NotePropertyValue $revision
    }
}

function Get-AcceptanceSourceBinding
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [Parameter(Mandatory)] [string] $SourceUuid,
        [Parameter(Mandatory)] [object[]] $Clients
    )

    $parsed = [guid]::Empty
    if (-not [guid]::TryParse($SourceUuid, [ref] $parsed))
    {
        throw "Context event returned a non-UUID source namespace."
    }
    $source = [System.IO.Path]::GetFullPath((Join-Path $Scope.PendingRoot $SourceUuid))
    if (-not $source.StartsWith(
            $Scope.PendingRoot + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "Context event source escaped the owned pending root."
    }
    $marker = Join-Path $source "lib\vibris-acceptance-source.glsl"
    if (-not (Test-Path -LiteralPath $marker -PathType Leaf))
    {
        throw "Active source $SourceUuid omitted its immutable job marker."
    }
    $value = (Get-Content -Raw -LiteralPath $marker).Trim()
    $matches = [System.Collections.Generic.List[object]]::new()
    foreach ($client in $Clients)
    {
        foreach ($job in @($client.jobs))
        {
            if ($value -ceq [string] $job.source_marker)
            {
                $matches.Add([pscustomobject] @{ Client = $client; Job = $job })
            }
        }
    }
    if ($matches.Count -ne 1)
    {
        throw "Active source $SourceUuid did not identify exactly one immutable acceptance job."
    }
    return $matches[0]
}

function Register-AcceptanceContextEvent
{
    param(
        [Parameter(Mandatory)] [object] $Event,
        [Parameter(Mandatory)] [object] $Scope,
        [Parameter(Mandatory)] [object[]] $Clients,
        [Parameter(Mandatory)] [AllowEmptyCollection()]
        [System.Collections.Generic.HashSet[string]] $SeenSources,
        [Parameter(Mandatory)] [AllowEmptyCollection()] [hashtable] $SourceByJob,
        [Parameter(Mandatory)] [AllowEmptyCollection()]
        [System.Collections.Generic.List[string]] $ObservedOrder
    )

    $sourceUuid = [string] $Event.source_uuid
    if (-not $SeenSources.Add($sourceUuid))
    {
        throw "Context event repeated source UUID $sourceUuid."
    }
    $binding = Get-AcceptanceSourceBinding -Scope $Scope -SourceUuid $sourceUuid -Clients $Clients
    $jobKey = [string] $binding.Job.id
    if ($SourceByJob.ContainsKey($jobKey))
    {
        throw "Immutable source snapshot for job $jobKey executed more than once."
    }
    Assert-AcceptanceContext -Actual $Event.context -Client $binding.Client
    $SourceByJob[$jobKey] = $sourceUuid
    $ObservedOrder.Add($jobKey)
    return $binding
}

function Assert-AcceptanceObservedPrefix
{
    param(
        [Parameter(Mandatory)] [AllowEmptyCollection()]
        [System.Collections.Generic.List[string]] $ObservedOrder,
        [Parameter(Mandatory)] [object[]] $ExpectedOrder
    )

    if ($ObservedOrder.Count -gt $ExpectedOrder.Count)
    {
        throw "Observed more acceptance jobs than the frozen execution order permits."
    }
    for ($index = 0; $index -lt $ObservedOrder.Count; $index++)
    {
        if ([string] $ObservedOrder[$index] -cne [string] $ExpectedOrder[$index])
        {
            throw "Actual execution order diverged at index ${index}: observed " +
                "$($ObservedOrder[$index]), expected $($ExpectedOrder[$index])."
        }
    }
}

function Assert-AcceptanceAdmissionBinding
{
    param(
        [Parameter(Mandatory)] [hashtable] $AdmittedSources,
        [Parameter(Mandatory)] [string] $SourceUuid,
        [Parameter(Mandatory)] [string] $JobKey
    )

    $matches = @($AdmittedSources.Keys | Where-Object {
        [string] $AdmittedSources[$_] -ceq $SourceUuid
    })
    if ($matches.Count -ne 1 -or [string] $matches[0] -cne $JobKey)
    {
        throw "Admitted request/source mapping for $SourceUuid resolved to job $JobKey instead of its owner."
    }
}

function Get-AcceptanceCommonGitDirectory
{
    param([Parameter(Mandatory)] [string] $Worktree)

    $value = [string] @(Invoke-G007Git -WorkspaceRoot $Worktree -GitArguments @("rev-parse", "--git-common-dir"))[0]
    if ([System.IO.Path]::IsPathRooted($value)) { return [System.IO.Path]::GetFullPath($value) }
    return [System.IO.Path]::GetFullPath((Join-Path $Worktree $value))
}

function Initialize-AcceptanceRepository
{
    param([Parameter(Mandatory)] [string] $Path, [Parameter(Mandatory)] [string] $Label)

    [void] (New-Item -ItemType Directory -Path $Path)
    [void] (Invoke-G007Git -WorkspaceRoot $Path -GitArguments @("init", "--quiet"))
    Copy-Item -LiteralPath $sourcePath -Destination (Join-Path $Path "shaders") -Recurse
    [System.IO.File]::WriteAllText((Join-Path $Path ".gitignore"), ".codex/`r`n")
    [void] (Invoke-G007Git -WorkspaceRoot $Path -GitArguments @("add", "--", ".gitignore", "shaders"))
    [void] (Invoke-G007Git -WorkspaceRoot $Path -GitArguments @(
        "-c", "user.name=Vibris Acceptance", "-c", "user.email=vibris@example.invalid",
        "commit", "--quiet", "-m", "$Label acceptance baseline"
    ))
}

try
{
    $fixture = Get-Content -Raw -LiteralPath $fixturePath | ConvertFrom-Json
    $clients = @($fixture.clients)
    $jobs = @($clients | ForEach-Object { @($_.jobs) })
    $expectedOrder = @($fixture.expected_execution_order)
    $messageIds = @($jobs | ForEach-Object { [int] $_.message_id })
    if ($fixture.schema_version -ne 3 -or $clients.Count -ne $WorktreeCount -or
        @($clients | Where-Object { @($_.jobs).Count -ne $JobsPerWorktree }).Count -ne 0 -or
        [string]::Join(",", $expectedOrder) -cne "A1,B1,C1,A2,B2,C2" -or
        @($messageIds | Select-Object -Unique).Count -ne 6 -or
        [string]::Join(",", @($clients.repository)) -cne "repository-1,repository-1,repository-2" -or
        @($jobs.id | Select-Object -Unique).Count -ne 6 -or
        [string]::Join(",", @($jobs.id | Sort-Object)) -cne
            [string]::Join(",", @($expectedOrder | Sort-Object)) -or
        @($jobs.source_marker | Select-Object -Unique).Count -ne 6 -or
        @($jobs | Where-Object { [string]::IsNullOrWhiteSpace([string] $_.source_marker) }).Count -ne 0)
    {
        throw "Final acceptance fixture does not encode the frozen linked-plus-independent 3x2 scenario."
    }

    $delivery = Resolve-AcceptanceDelivery
    $script:IrisPort = Get-AcceptanceIrisPort
    $scope = New-IrisProbeScope -Criterion "c001" -Gate "G008" `
        -GameDir (Join-Path $script:VibrisRoot ".omo\tmp\ulw-v1-g008-c001\game")
    $repositoryOne = Join-Path $scope.Root "repository-one"
    $repositoryTwo = Join-Path $scope.Root "repository-two"
    $linkedWorktree = Join-Path $scope.Root "linked-worktree-b"
    Initialize-AcceptanceRepository -Path $repositoryOne -Label "repository-one"
    Initialize-AcceptanceRepository -Path $repositoryTwo -Label "repository-two"
    [void] (Invoke-G007Git -WorkspaceRoot $repositoryOne -GitArguments @(
        "worktree", "add", "--quiet", "-b", "acceptance-b", $linkedWorktree, "HEAD"
    ))
    $worktrees["A"] = $repositoryOne
    $worktrees["B"] = $linkedWorktree
    $worktrees["C"] = $repositoryTwo

    foreach ($client in $clients)
    {
        Initialize-AcceptanceJobSnapshots -Worktree $worktrees[[string] $client.label] -Client $client
    }
    if ($InjectSourceCrosstalk)
    {
        $clientC = @($clients | Where-Object { $_.label -ceq "C" })[0]
        $clientC.jobs[0].revision = [string] $clientC.jobs[1].revision
    }
    $commonA = Get-AcceptanceCommonGitDirectory -Worktree $worktrees["A"]
    $commonB = Get-AcceptanceCommonGitDirectory -Worktree $worktrees["B"]
    $commonC = Get-AcceptanceCommonGitDirectory -Worktree $worktrees["C"]
    if (-not (Test-Path -LiteralPath (Join-Path $worktrees["A"] ".git") -PathType Container) -or
        -not (Test-Path -LiteralPath (Join-Path $worktrees["B"] ".git") -PathType Leaf) -or
        -not (Test-Path -LiteralPath (Join-Path $worktrees["C"] ".git") -PathType Container) -or
        -not [System.StringComparer]::OrdinalIgnoreCase.Equals($commonA, $commonB) -or
        [System.StringComparer]::OrdinalIgnoreCase.Equals($commonA, $commonC))
    {
        throw "Acceptance topology must contain two Git repositories and exactly one linked worktree."
    }

    $presetRoot = Join-Path $scope.GameDir "config\vibris"
    [void] (New-Item -ItemType Directory -Path $presetRoot)
    [System.IO.File]::WriteAllText((Join-Path $presetRoot "presets.json"),
        ($fixture.preset_catalog | ConvertTo-Json -Depth 20))
    Start-AcceptancePackagedClient -Scope $scope -PatchedJar $delivery.Iris `
        -TimeoutSeconds $TimeoutSeconds
    Wait-IrisWorldReady -Scope $scope -TimeoutSeconds $TimeoutSeconds

    foreach ($client in $clients)
    {
        $taskDirectory = Join-Path $worktrees[[string] $client.label] ".codex\tasks\final-acceptance"
        [void] (New-Item -ItemType Directory -Path $taskDirectory -Force)
        $client | Add-Member -NotePropertyName task_directory -NotePropertyValue $taskDirectory
    }

    $seenSources = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    $admittedSources = @{}
    $sourceByJob = @{}
    $observedOrder = [System.Collections.Generic.List[string]]::new()
    $clientA = @($clients | Where-Object { $_.label -ceq "A" })[0]
    $jobEntries["A"] = Start-AcceptanceMcp -Exe $delivery.Mcp `
        -WorkingDirectory $clientA.task_directory -Messages (New-AcceptanceMessages -Client $clientA)
    $firstContext = Wait-IrisEvent -Scope $scope -Type "context_applied" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) -not $seenSources.Contains([string] $event.source_uuid) }
    $firstBinding = Register-AcceptanceContextEvent -Event $firstContext -Scope $scope `
        -Clients $clients -SeenSources $seenSources -SourceByJob $sourceByJob `
        -ObservedOrder $observedOrder
    Assert-AcceptanceObservedPrefix -ObservedOrder $observedOrder -ExpectedOrder $expectedOrder
    if ([string] $firstBinding.Job.id -cne "A1")
    {
        throw "First eligible job was $($firstBinding.Job.id), expected A1."
    }
    $admittedSources["A1"] = [string] $firstContext.source_uuid
    Assert-AcceptanceAdmissionBinding -AdmittedSources $admittedSources `
        -SourceUuid ([string] $firstContext.source_uuid) -JobKey "A1"

    $clientB = @($clients | Where-Object { $_.label -ceq "B" })[0]
    $jobEntries["B"] = Start-AcceptanceMcp -Exe $delivery.Mcp `
        -WorkingDirectory $clientB.task_directory -Messages (New-AcceptanceMessages -Client $clientB)
    $admittedSources["B1"] = Wait-AcceptancePendingMarker -Scope $scope `
        -Marker ([string] $clientB.jobs[0].source_marker) -Minimum 1
    $clientC = @($clients | Where-Object { $_.label -ceq "C" })[0]
    $jobEntries["C"] = Start-AcceptanceMcp -Exe $delivery.Mcp `
        -WorkingDirectory $clientC.task_directory -Messages (New-AcceptanceMessages -Client $clientC)
    if ($InjectSourceCrosstalk)
    {
        $admittedSources["C1"] = Wait-AcceptancePendingMarker -Scope $scope `
            -Marker ([string] $clientC.jobs[1].source_marker) -Minimum 1
    }
    else
    {
        $admittedSources["C1"] = Wait-AcceptancePendingMarker -Scope $scope `
            -Marker ([string] $clientC.jobs[0].source_marker) -Minimum 1
    }

    while ($observedOrder.Count -lt ($WorktreeCount * $JobsPerWorktree))
    {
        $event = Wait-IrisEvent -Scope $scope -Type "context_applied" -TimeoutSeconds $TimeoutSeconds `
            -Match { param($candidate) -not $seenSources.Contains([string] $candidate.source_uuid) }
        $binding = Register-AcceptanceContextEvent -Event $event -Scope $scope -Clients $clients `
            -SeenSources $seenSources -SourceByJob $sourceByJob -ObservedOrder $observedOrder
        Assert-AcceptanceObservedPrefix -ObservedOrder $observedOrder -ExpectedOrder $expectedOrder
        Assert-AcceptanceAdmissionBinding -AdmittedSources $admittedSources `
            -SourceUuid ([string] $event.source_uuid) -JobKey ([string] $binding.Job.id)
        switch ([string] $binding.Job.id)
        {
            "B1"
            {
                $admittedSources["A2"] = Wait-AcceptancePendingMarker -Scope $scope `
                    -Marker ([string] $clientA.jobs[1].source_marker) -Minimum 1
            }
            "C1"
            {
                $admittedSources["B2"] = Wait-AcceptancePendingMarker -Scope $scope `
                    -Marker ([string] $clientB.jobs[1].source_marker) -Minimum 1
            }
            "A2"
            {
                $admittedSources["C2"] = Wait-AcceptancePendingMarker -Scope $scope `
                    -Marker ([string] $clientC.jobs[1].source_marker) -Minimum 1
            }
        }
    }

    $artifactNamespaces = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    $artifactByJob = @{}
    $acceptedJobs = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal)
    foreach ($client in $clients)
    {
        $label = [string] $client.label
        $entry = $jobEntries[$label]
        if ($entry.Arguments -match "workspace-root")
        {
            throw "Normal MCP $label unexpectedly received a workspace-root override."
        }
        $responses = Complete-G007Mcp -Entry $entry -TimeoutSeconds $TimeoutSeconds
        [void] (Assert-AcceptanceInitializeResponse -Responses $responses -Id 10)
        $unconfigured = Get-G007ToolPayload (Get-G007Response -Responses $responses -Id 11)
        $workspaceId = Assert-AcceptanceBinding -Actual $unconfigured -ExpectedRoot $worktrees[$label] `
            -Configured $false
        $configured = Get-G007ToolPayload (Get-G007Response -Responses $responses -Id 12)
        $persistedInProcess = Get-G007ToolPayload (Get-G007Response -Responses $responses -Id 13)
        [void] (Assert-AcceptanceBinding -Actual $persistedInProcess -ExpectedRoot $worktrees[$label] `
            -ExpectedWorkspaceId $workspaceId -Configured $true)
        Assert-AcceptanceConfig -Actual $configured -Expected $client.configure -WorkspaceId $workspaceId
        Assert-AcceptanceConfig -Actual $persistedInProcess -Expected $client.configure -WorkspaceId $workspaceId
        $workspaceIds[$label] = $workspaceId
        foreach ($job in @($client.jobs))
        {
            $jobKey = [string] $job.id
            $result = Get-G007ToolPayload (Get-G007Response -Responses $responses -Id ([int] $job.message_id))
            Assert-G007CompletedResult -Scope $scope -Payload $result
            $namespace = Split-Path -Parent ([System.IO.Path]::GetFullPath([string] $result.manifest_path))
            if (-not $acceptedJobs.Add($jobKey) -or $artifactByJob.ContainsKey($jobKey) -or
                -not $artifactNamespaces.Add($namespace))
            {
                throw "Job $jobKey did not own exactly one unique result/artifact namespace."
            }
            $artifactByJob[$jobKey] = $namespace
        }
        Stop-AcceptanceMcp -Entry $entry
        $jobEntries[$label] = $null
    }
    $expectedJobKeys = [string]::Join(",", @($jobs.id | Sort-Object))
    $admissionMismatch = @($jobs | Where-Object {
        $key = [string] $_.id
        -not $admittedSources.ContainsKey($key) -or -not $sourceByJob.ContainsKey($key) -or
            [string] $admittedSources[$key] -cne [string] $sourceByJob[$key]
    })
    if (@($workspaceIds.Values | Select-Object -Unique).Count -ne $WorktreeCount -or
        $artifactNamespaces.Count -ne ($WorktreeCount * $JobsPerWorktree) -or
        $admittedSources.Count -ne ($WorktreeCount * $JobsPerWorktree) -or
        $sourceByJob.Count -ne ($WorktreeCount * $JobsPerWorktree) -or
        $artifactByJob.Count -ne ($WorktreeCount * $JobsPerWorktree) -or
        $admissionMismatch.Count -ne 0 -or
        [string]::Join(",", @($acceptedJobs | Sort-Object)) -cne $expectedJobKeys -or
        [string]::Join(",", @($admittedSources.Keys | Sort-Object)) -cne $expectedJobKeys -or
        [string]::Join(",", @($sourceByJob.Keys | Sort-Object)) -cne $expectedJobKeys -or
        [string]::Join(",", @($artifactByJob.Keys | Sort-Object)) -cne $expectedJobKeys)
    {
        throw "Workspace/request/source/artifact namespaces were not a one-to-one six-job mapping."
    }

    foreach ($client in $clients)
    {
        $label = [string] $client.label
        $restartMessages = @(
            [ordered] @{
                jsonrpc = "2.0"; id = 1; method = "initialize"
                params = @{ protocolVersion = "2024-11-05"; capabilities = @{};
                    clientInfo = @{ name = "vibris-restart-$label"; version = "1.0" } }
            },
            [ordered] @{
                jsonrpc = "2.0"; method = "notifications/initialized"; params = @{}
            },
            [ordered] @{
                jsonrpc = "2.0"; id = 2; method = "tools/call"
                params = @{ name = "vibris_get_config"; arguments = @{} }
            }
        )
        $restart = Invoke-AcceptanceMcp -Exe $delivery.Mcp `
            -WorkingDirectory $client.task_directory -Messages $restartMessages
        [void] (Assert-AcceptanceInitializeResponse -Responses $restart -Id 1)
        $restartConfig = Get-G007ToolPayload (Get-G007Response -Responses $restart -Id 2)
        [void] (Assert-AcceptanceBinding -Actual $restartConfig -ExpectedRoot $worktrees[$label] `
            -ExpectedWorkspaceId $workspaceIds[$label] -Configured $false)
    }

    $events = @(Read-IrisEventLines -Path $scope.EventFile | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_)
    } | ForEach-Object { $_ | ConvertFrom-Json })
    if (@($events | Where-Object { $_.run_id -cne $scope.RunId }).Count -ne 0)
    {
        throw "Vibris automation event stream contains another run ID."
    }
    $contexts = @($events | Where-Object { $_.type -ceq "context_applied" })
    $sourceEvents = @($events | Where-Object { $_.type -ceq "source_active" })
    $captureEvents = @($events | Where-Object { $_.type -ceq "capture_complete" })
    if ($contexts.Count -ne 6 -or $sourceEvents.Count -ne 6 -or $captureEvents.Count -ne 6 -or
        @($contexts | Where-Object {
            -not $seenSources.Contains([string] $_.source_uuid)
        }).Count -ne 0 -or
        @($sourceEvents | Where-Object {
            -not $seenSources.Contains([string] $_.source_uuid)
        }).Count -ne 0 -or
        @($captureEvents | Where-Object {
            -not $seenSources.Contains([string] $_.source_uuid)
        }).Count -ne 0 -or
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
        $expectedTarget = [System.IO.Path]::GetFullPath(
            (Join-Path $scope.PendingRoot ([string] $event.source_uuid)))
        if (-not [System.StringComparer]::OrdinalIgnoreCase.Equals($target, $expectedTarget))
        {
            throw "Job activated a source outside its unique owned namespace: $target"
        }
    }
    if ([string]::Join(",", $observedOrder) -cne [string]::Join(",", $expectedOrder))
    {
        throw "Execution order was $([string]::Join(',', $observedOrder)); expected A1,B1,C1,A2,B2,C2."
    }
    $active = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    $maxConcurrent = 0
    foreach ($event in $events)
    {
        if ($null -eq $event.PSObject.Properties["source_uuid"]) { continue }
        $uuid = [string] $event.source_uuid
        if (-not $seenSources.Contains($uuid)) { continue }
        if ($event.type -ceq "source_active")
        {
            if ($active.Count -ne 0 -or -not $active.Add($uuid))
            {
                throw "A job source activated before the prior capture completed."
            }
            $maxConcurrent = [math]::Max($maxConcurrent, $active.Count)
        }
        elseif ($event.type -ceq "capture_complete" -and -not $active.Remove($uuid))
        {
            throw "Capture completed without its active job interval."
        }
    }
    if ($active.Count -ne 0 -or $maxConcurrent -ne 1)
    {
        throw "Observed max concurrency $maxConcurrent with $($active.Count) unfinished intervals."
    }

    $summary = "PASS criterion=G008-C001 mode=$Mode order=A1,B1,C1,A2,B2,C2 " +
        "max_concurrent_jobs=1 worktrees=3 repositories=2 linked_worktrees=1 jobs=6 minecraft=1 " +
        "restart_identity_stable=true restart_configured=false config_crosstalk=false " +
        "source_crosstalk=false request_source_artifact_1to1=true artifact_namespaces=6 delivery_files=2"
    $eventSummary = [ordered] @{
        schema_version = 1
        criterion = "G008-C001"
        mode = $Mode
        delivery = @{ root = $delivery.Root; mcp_sha256 = $delivery.McpSha256;
            iris_sha256 = $delivery.IrisSha256; files = 2 }
        topology = @{ repositories = 2; linked_worktrees = 1; worktrees = 3 }
        workspace_ids = $workspaceIds
        execution_order = @($observedOrder)
        accepted_jobs = @($acceptedJobs | Sort-Object)
        admitted_source_by_job = $admittedSources
        source_by_job = $sourceByJob
        artifact_by_job = $artifactByJob
        source_uuids = @($seenSources)
        artifact_namespaces = @($artifactNamespaces)
        max_concurrent_jobs = $maxConcurrent
        iris_port = $script:IrisPort
        iris_runtime_pid = $scope.RuntimePid
        restart = @{ identity_stable = $true; configured = $false; config = $null }
        owned_pids = @($ownedPids)
    }
}
catch
{
    $failure = $_.Exception
}
finally
{
    foreach ($entry in @($ownedEntries))
    {
        try { Stop-AcceptanceMcp -Entry $entry }
        catch { $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception }
    }
    if ($null -ne $scope)
    {
        try { Stop-IrisPackagedClient -Scope $scope -TimeoutSeconds $TimeoutSeconds }
        catch { $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception }
    }
    if ($null -ne $repositoryOne -and $null -ne $linkedWorktree -and
        (Test-Path -LiteralPath $repositoryOne -PathType Container) -and
        (Test-Path -LiteralPath $linkedWorktree))
    {
        try
        {
            [void] (Invoke-G007Git -WorkspaceRoot $repositoryOne `
                -GitArguments @("worktree", "remove", "--force", $linkedWorktree))
            [void] (Invoke-G007Git -WorkspaceRoot $repositoryOne -GitArguments @("worktree", "prune"))
        }
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
if (-not [string]::IsNullOrWhiteSpace($EvidenceDirectory))
{
    $evidence = [System.IO.Path]::GetFullPath($EvidenceDirectory)
    [void] (New-Item -ItemType Directory -Path $evidence -Force)
    [System.IO.File]::WriteAllText(
        (Join-Path $evidence "task-6-event-summary.json"),
        ($eventSummary | ConvertTo-Json -Depth 10))
    [System.IO.File]::WriteAllLines(
        (Join-Path $evidence "task-6-vibris-multi-worktree-mcp.txt"),
        @(
            $summary,
            "MCP_SHA256=$($eventSummary.delivery.mcp_sha256)",
            "IRIS_SHA256=$($eventSummary.delivery.iris_sha256)",
            "WORKSPACE_IDS=$([string]::Join(',', @($workspaceIds.Values)))",
            "OWNED_PIDS=$([string]::Join(',', @($ownedPids)))",
            "CLEANUP listener_closed=true linked_worktree_removed=true root_removed=true protected_processes_unchanged=true"
        ))
}
Write-Output $summary
Write-Output ("CLEANUP owned_pids=$([string]::Join(',', @($ownedPids))) listener_closed=true " +
    "linked_worktree_removed=true root_removed=true protected_processes_unchanged=true")
