[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $DeliveryRoot,
    [ValidateRange(1, 900)] [int] $TimeoutSeconds = 600,
    [string] $EvidenceDirectory
)

. (Join-Path $PSScriptRoot "stdio-job-probe-common.ps1")

function Test-CorePort
{
    param([Parameter(Mandatory)] [int] $Port)

    return @(Get-NetTCPConnection -LocalPort $Port -State Listen `
        -ErrorAction SilentlyContinue).Count -ne 0
}

$scope = $null
$ownedMcps = [System.Collections.Generic.List[object]]::new()
$ownedPids = [System.Collections.Generic.List[int]]::new()
$failure = $null
$summary = $null
$evidenceRoot = $null
$evidenceSaved = $false
$resultEvidence = [ordered] @{}

function Get-SameDelivery
{
    $root = Resolve-IrisDirectory -Path $DeliveryRoot -Label "DeliveryRoot"
    $files = @(Get-ChildItem -LiteralPath $root -File)
    $executables = @($files | Where-Object { $_.Name -ceq "vibris-mcp.exe" })
    $jars = @($files | Where-Object { $_.Extension -ceq ".jar" })
    if ($files.Count -ne 2 -or $executables.Count -ne 1 -or $jars.Count -ne 1)
    {
        throw "DeliveryRoot must contain exactly vibris-mcp.exe and one patched Iris JAR."
    }
    $exe = Resolve-IrisArtifact -Path $executables[0].FullName -Label "delivered MCP"
    $jar = Resolve-IrisPatchedJar -Path $jars[0].FullName
    $canonicalExe = Resolve-IrisArtifact `
        -Path (Join-Path $script:VibrisRoot "mcp\out\build\Release\vibris-mcp.exe") `
        -Label "canonical Release MCP"
    $canonicalJar = Resolve-IrisPatchedJar `
        -Path (Join-Path $script:IrisRoot "build\libs\iris-fabric-*-local.jar")
    $exeHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $exe).Hash
    $jarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $jar).Hash
    $canonicalExeHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $canonicalExe).Hash
    $canonicalJarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $canonicalJar).Hash
    if ($exeHash -cne $canonicalExeHash -or $jarHash -cne $canonicalJarHash)
    {
        throw "DeliveryRoot artifacts are not byte-identical to the current canonical MCP and Iris builds."
    }
    return [pscustomobject] @{
        Root = $root
        Exe = $exe
        Jar = $jar
        ExeHash = $exeHash
        JarHash = $jarHash
        CanonicalExe = $canonicalExe
        CanonicalJar = $canonicalJar
    }
}

function Get-SameIrisPort
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

function Wait-SameOwnedListener
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

function Start-SamePackagedClient
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [Parameter(Mandatory)] [string] $PatchedJar,
        [ValidateRange(1, 600)] [int] $TimeoutSeconds
    )

    if ($script:IrisPort -eq 50051)
    {
        Start-IrisPackagedClient -Scope $Scope -PatchedJar $PatchedJar -Scenario "g008-c002" `
            -TimeoutSeconds $TimeoutSeconds
        [void] (Wait-SameOwnedListener -Scope $Scope -Port $script:IrisPort `
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
        "-PautomationRunId=$($Scope.RunId)", "-PautomationScenario=g008-c002"
    )
    $Scope.Wrapper = Start-CoreGradleWrapper -IrisRoot $script:IrisRoot `
        -GradleArguments $arguments
    Wait-IrisOwnedRuntimeProcess -Scope $Scope -TimeoutSeconds $TimeoutSeconds
    Start-IrisWindowGuard -Scope $Scope
    $receipt = Wait-IrisReceipt -Scope $Scope -TimeoutSeconds $TimeoutSeconds
    if ([int] $receipt.pid -ne $Scope.RuntimePid)
    {
        throw "Alternate-port runtime ownership receipt did not match the owned PID."
    }
    Assert-IrisOwnedRuntime -Scope $Scope
    [void] (Wait-SameOwnedListener -Scope $Scope -Port $script:IrisPort `
        -TimeoutSeconds $TimeoutSeconds)
}

function New-SameInitialize
{
    param([Parameter(Mandatory)] [int] $Id, [Parameter(Mandatory)] [string] $Label)

    return [ordered] @{
        jsonrpc = "2.0"
        id = $Id
        method = "initialize"
        params = [ordered] @{
            protocolVersion = "2024-11-05"
            capabilities = [ordered] @{}
            clientInfo = [ordered] @{ name = "vibris-same-worktree-$Label"; version = "1.0" }
        }
    }
}

function New-SameInitializedNotification
{
    return [ordered] @{
        jsonrpc = "2.0"
        method = "notifications/initialized"
        params = [ordered] @{}
    }
}

function New-SameTool
{
    param(
        [Parameter(Mandatory)] [int] $Id,
        [Parameter(Mandatory)] [string] $Name,
        [Parameter(Mandatory)] [object] $Arguments
    )

    return [ordered] @{
        jsonrpc = "2.0"
        id = $Id
        method = "tools/call"
        params = [ordered] @{ name = $Name; arguments = $Arguments }
    }
}

function Start-SameMcp
{
    param(
        [Parameter(Mandatory)] [string] $Exe,
        [Parameter(Mandatory)] [string] $WorkspaceRoot,
        [Parameter(Mandatory)] [string] $Label
    )

    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Exe
    $start.WorkingDirectory = $WorkspaceRoot
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
        Label = $Label
        Process = $process
        Created = $process.StartTime.ToUniversalTime().ToString("O")
        Error = $process.StandardError.ReadToEndAsync()
        Closed = $false
        Forced = $false
    }
    $ownedMcps.Add($entry)
    $ownedPids.Add($process.Id)
    return $entry
}

function Send-SameMessage
{
    param([Parameter(Mandatory)] [object] $Entry, [Parameter(Mandatory)] [object] $Message)

    if ($Entry.Closed) { throw "MCP $($Entry.Label) is already closed." }
    $Entry.Process.StandardInput.WriteLine(($Message | ConvertTo-Json -Compress -Depth 30))
    $Entry.Process.StandardInput.Flush()
}

function Read-SameResponse
{
    param(
        [Parameter(Mandatory)] [object] $Entry,
        [Parameter(Mandatory)] [int] $Id
    )

    $read = $Entry.Process.StandardOutput.ReadLineAsync()
    if (-not $read.Wait($TimeoutSeconds * 1000))
    {
        throw "MCP $($Entry.Label) PID $($Entry.Process.Id) did not return response $Id."
    }
    if ($null -eq $read.Result)
    {
        $stderr = if ($Entry.Error.IsCompleted) { $Entry.Error.Result } else { "<still running>" }
        throw "MCP $($Entry.Label) closed before response $Id. stderr: $stderr"
    }
    $response = $read.Result | ConvertFrom-Json
    if ($response.jsonrpc -cne "2.0" -or [int] $response.id -ne $Id)
    {
        throw "MCP $($Entry.Label) returned an unexpected response: $($read.Result)"
    }
    if ($null -ne $response.PSObject.Properties["error"])
    {
        throw "MCP $($Entry.Label) request $Id failed: " +
            ($response.error | ConvertTo-Json -Compress -Depth 20)
    }
    return $response
}

function Invoke-SameMessage
{
    param(
        [Parameter(Mandatory)] [object] $Entry,
        [Parameter(Mandatory)] [object] $Message
    )

    Send-SameMessage -Entry $Entry -Message $Message
    return Read-SameResponse -Entry $Entry -Id ([int] $Message.id)
}

function Initialize-SameMcp
{
    param(
        [Parameter(Mandatory)] [object] $Entry,
        [Parameter(Mandatory)] [int] $Id,
        [Parameter(Mandatory)] [string] $Label
    )

    $response = Invoke-SameMessage -Entry $Entry -Message (New-SameInitialize -Id $Id -Label $Label)
    if ($null -ne $response.PSObject.Properties["error"])
    {
        throw "MCP $Label initialize returned an error."
    }
    if ($null -eq $response.PSObject.Properties["result"] -or $null -eq $response.result)
    {
        throw "MCP $Label initialize response is missing result."
    }
    if ($null -eq $response.result.PSObject.Properties["protocolVersion"] -or
        [string] $response.result.protocolVersion -cne "2024-11-05")
    {
        throw "MCP $Label initialize returned an unexpected protocolVersion."
    }

    Send-SameMessage -Entry $Entry -Message (New-SameInitializedNotification)
    return $response
}

function Stop-SameMcp
{
    param([AllowNull()] [object] $Entry, [switch] $Force)

    if ($null -eq $Entry -or $Entry.Closed) { return }
    if ($Entry.Process.HasExited)
    {
        $Entry.Closed = $true
        $Entry.Process.Dispose()
        return
    }
    if ($Entry.Process.StartTime.ToUniversalTime().ToString("O") -cne $Entry.Created)
    {
        throw "MCP $($Entry.Label) PID was reused; refusing process control."
    }
    if ($Force)
    {
        Stop-Process -Id $Entry.Process.Id -Force
        $Entry.Forced = $true
    }
    else
    {
        $Entry.Process.StandardInput.Close()
    }
    if (-not $Entry.Process.WaitForExit($TimeoutSeconds * 1000))
    {
        throw "MCP $($Entry.Label) did not exit after owned shutdown."
    }
    $Entry.Process.WaitForExit()
    if (-not $Force -and $Entry.Process.ExitCode -ne 0)
    {
        throw "MCP $($Entry.Label) exited $($Entry.Process.ExitCode): $($Entry.Error.Result)"
    }
    $Entry.Closed = $true
    $Entry.Process.Dispose()
}

function Get-SameToolPayload
{
    param([Parameter(Mandatory)] [object] $Response)

    return Get-G007ToolPayload -Response $Response
}

function Assert-SameUnconfigured
{
    param(
        [Parameter(Mandatory)] [object] $Payload,
        [Parameter(Mandatory)] [string] $WorkspaceRoot,
        [string] $WorkspaceId
    )

    if ($Payload.configured -ne $false -or $null -ne $Payload.config)
    {
        throw "A new MCP process inherited a process-local scene."
    }
    $actualRoot = [System.IO.Path]::GetFullPath([string] $Payload.worktree_root)
    if (-not [string]::Equals($actualRoot, $WorkspaceRoot,
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "MCP cwd discovery bound '$actualRoot', expected '$WorkspaceRoot'."
    }
    $parsed = [guid]::Empty
    if (-not [guid]::TryParse([string] $Payload.workspace_id, [ref] $parsed))
    {
        throw "MCP returned a non-UUID workspace_id."
    }
    if (-not [string]::IsNullOrWhiteSpace($WorkspaceId) -and
        [string] $Payload.workspace_id -cne $WorkspaceId)
    {
        throw "MCP restart changed the durable workspace_id."
    }
    return [string] $Payload.workspace_id
}

function Assert-SameConfigured
{
    param(
        [Parameter(Mandatory)] [object] $Payload,
        [Parameter(Mandatory)] [object] $Expected,
        [Parameter(Mandatory)] [string] $WorkspaceId
    )

    $config = if ($null -ne $Payload.PSObject.Properties["config"]) { $Payload.config } else { $Payload }
    if ($Payload.PSObject.Properties["configured"] -and $Payload.configured -ne $true)
    {
        throw "Configured MCP reported configured=false."
    }
    if ([string] $config.workspace_id -cne $WorkspaceId -or
        [string] $config.save_id -cne [string] $Expected.save_id -or
        [string] $config.dimension_id -cne [string] $Expected.dimension_id -or
        [string] $config.time_preset_id -cne [string] $Expected.time_preset_id -or
        [string] $config.camera_preset_id -cne [string] $Expected.camera_preset_id -or
        [double] $config.fov -ne [double] $Expected.fov -or
        [int] $config.default_warmup_frames -ne [int] $Expected.default_warmup_frames)
    {
        throw "MCP process-local configuration mismatched: " +
            ($Payload | ConvertTo-Json -Compress -Depth 20)
    }
}

function Assert-SameScene
{
    param(
        [Parameter(Mandatory)] [object] $Actual,
        [Parameter(Mandatory)] [object] $Client
    )

    $configured = $Client.configure
    $expected = $Client.expected
    if ([string] $Actual.save_id -cne [string] $configured.save_id -or
        [string] $Actual.dimension_id -cne [string] $configured.dimension_id -or
        [string] $Actual.time_preset_id -cne [string] $configured.time_preset_id -or
        [string] $Actual.weather_preset_id -cne [string] $expected.weather_preset_id -or
        [string] $Actual.camera_preset_id -cne [string] $configured.camera_preset_id -or
        [string] $Actual.settings_preset_id -cne [string] $expected.settings_preset_id -or
        [double] $Actual.fov -ne [double] $configured.fov -or
        [long] $Actual.day_time -ne [long] $expected.day_time -or
        [double] $Actual.rain_level -ne [double] $expected.rain_level -or
        [double] $Actual.thunder_level -ne [double] $expected.thunder_level)
    {
        throw "Iris applied the wrong scene for MCP $($Client.label): " +
            ($Actual | ConvertTo-Json -Compress -Depth 20)
    }
    foreach ($field in @("x", "y", "z", "yaw", "pitch"))
    {
        if ([double] $Actual.camera.$field -ne [double] $expected.camera.$field)
        {
            throw "Iris camera field '$field' crossed MCP scenes."
        }
    }
}

function Get-SamePendingSources
{
    param([Parameter(Mandatory)] [object] $Scope)

    return @(Get-ChildItem -LiteralPath $Scope.PendingRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object {
            $parsed = [guid]::Empty
            [guid]::TryParse($_.Name, [ref] $parsed)
        })
}

function Wait-SamePendingSources
{
    param([Parameter(Mandatory)] [object] $Scope, [Parameter(Mandatory)] [int] $Minimum)

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([datetime]::UtcNow -lt $deadline)
    {
        $sources = @(Get-SamePendingSources -Scope $Scope)
        if ($sources.Count -ge $Minimum) { return $sources }
        if ($Scope.Wrapper.Process.HasExited) { throw "Packaged Iris exited while sources were queued." }
        Start-Sleep -Milliseconds 20
    }
    throw "Pending source count did not reach $Minimum."
}

function Set-SameWorkspaceMarker
{
    param(
        [Parameter(Mandatory)] [string] $WorkspaceRoot,
        [Parameter(Mandatory)] [string] $SceneLabel
    )

    $path = Join-Path $WorkspaceRoot "shaders\lib\vibris_same_worktree_scene.glsl"
    $text = "#define VIBRIS_SAME_WORKTREE_SCENE_$($SceneLabel.ToUpperInvariant()) 1`r`n"
    [System.IO.File]::WriteAllText($path, $text)
    return [pscustomobject] @{ RelativePath = "lib\vibris_same_worktree_scene.glsl"; Text = $text }
}

function Wait-SameSourceMarker
{
    param(
        [Parameter(Mandatory)] [object] $Source,
        [Parameter(Mandatory)] [object] $Marker
    )

    $path = Join-Path $Source.FullName $Marker.RelativePath
    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([datetime]::UtcNow -lt $deadline)
    {
        if ((Test-Path -LiteralPath $path -PathType Leaf) -and
            ([System.IO.File]::ReadAllText($path) -ceq $Marker.Text))
        {
            return
        }
        Start-Sleep -Milliseconds 10
    }
    throw "Prepared source $($Source.Name) did not freeze the expected process marker."
}

function Get-SameEventSnapshot
{
    param([Parameter(Mandatory)] [object] $Scope)

    $lines = if (Test-Path -LiteralPath $Scope.EventFile -PathType Leaf) {
        @(Read-IrisEventLines -Path $Scope.EventFile | Where-Object {
                -not [string]::IsNullOrWhiteSpace($_)
            })
    } else {
        @()
    }
    $events = @($lines | ForEach-Object { $_ | ConvertFrom-Json })
    foreach ($event in $events)
    {
        if ($event.run_id -cne $Scope.RunId)
        {
            throw "Vibris automation event belongs to another runId."
        }
    }
    return [pscustomobject] @{ Lines = $lines; Events = $events }
}

function Get-SameEvents
{
    param([Parameter(Mandatory)] [object] $Scope)

    return @((Get-SameEventSnapshot -Scope $Scope).Events)
}

function Wait-SameManifests
{
    param([Parameter(Mandatory)] [object] $Scope, [Parameter(Mandatory)] [int] $Count)

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([datetime]::UtcNow -lt $deadline)
    {
        $manifests = @(Get-ChildItem -LiteralPath $Scope.ArtifactRoot -Filter "manifest.json" -File -Recurse `
            -ErrorAction SilentlyContinue)
        if ($manifests.Count -eq $Count) { return $manifests }
        if ($manifests.Count -gt $Count) { throw "Observed more than $Count request manifests." }
        Start-Sleep -Milliseconds 20
    }
    throw "Artifact manifest count did not reach $Count."
}

function Save-SameEvidence
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [Parameter(Mandatory)] [System.Collections.IDictionary] $Result
    )

    if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) { return $null }
    $parent = [System.IO.Path]::GetFullPath($EvidenceDirectory)
    [void] (New-Item -ItemType Directory -Path $parent -Force)
    $target = Join-Path $parent "task-5-runtime-$($Scope.RunId)"
    if (Test-Path -LiteralPath $target) { throw "Evidence target already exists: $target" }
    [void] (New-Item -ItemType Directory -Path $target)
    if (Test-Path -LiteralPath $Scope.EventFile -PathType Leaf)
    {
        $snapshot = Get-SameEventSnapshot -Scope $Scope
        $eventText = if ($snapshot.Lines.Count -eq 0) {
            ""
        } else {
            ($snapshot.Lines -join "`n") + "`n"
        }
        [System.IO.File]::WriteAllText(
            (Join-Path $target "events.jsonl"),
            $eventText,
            [System.Text.UTF8Encoding]::new($false, $true))
    }
    if (Test-Path -LiteralPath $Scope.ArtifactRoot -PathType Container)
    {
        Copy-Item -LiteralPath $Scope.ArtifactRoot -Destination (Join-Path $target "artifacts") -Recurse
    }
    [System.IO.File]::WriteAllText(
        (Join-Path $target "result.json"),
        ($Result | ConvertTo-Json -Depth 30))
    return $target
}

try
{
    $delivery = Get-SameDelivery
    $fixturePath = Join-Path $script:VibrisRoot `
        "integration-tests\fixtures\acceptance\three-worktree-six-jobs.json"
    $fixture = Get-Content -Raw -LiteralPath $fixturePath | ConvertFrom-Json
    $clients = @($fixture.clients | Where-Object { $_.label -in @("A", "B") })
    if ($clients.Count -ne 2)
    {
        throw "Acceptance fixture does not expose exactly scenes A and B."
    }
    $clientA = @($clients | Where-Object { $_.label -ceq "B" })[0]
    $clientB = @($clients | Where-Object { $_.label -ceq "A" })[0]

    $script:IrisPort = Get-SameIrisPort
    $scope = New-IrisProbeScope -Criterion "c002" -Gate "G008" `
        -GameDir (Join-Path $script:VibrisRoot ".omo\tmp\ulw-v1-g008-c002\game")
    $workspace = Join-Path $scope.Root "repository"
    [void] (New-Item -ItemType Directory -Path $workspace)
    [void] (Invoke-G007Git -WorkspaceRoot $workspace -GitArguments @("init", "--quiet"))
    $source = Join-Path $script:VibrisRoot `
        "integration-tests\fixtures\shaderpacks\capture-known-resources\shaders"
    Copy-Item -LiteralPath $source -Destination (Join-Path $workspace "shaders") -Recurse
    [void] (Invoke-G007Git -WorkspaceRoot $workspace -GitArguments @("add", "--", "shaders"))
    [void] (Invoke-G007Git -WorkspaceRoot $workspace -GitArguments @(
        "-c", "user.name=Vibris Acceptance", "-c", "user.email=vibris@example.invalid",
        "commit", "--quiet", "-m", "same-worktree baseline"
    ))

    $presetRoot = Join-Path $scope.GameDir "config\vibris"
    [void] (New-Item -ItemType Directory -Path $presetRoot)
    [System.IO.File]::WriteAllText(
        (Join-Path $presetRoot "presets.json"),
        ($fixture.preset_catalog | ConvertTo-Json -Depth 20))
    Start-SamePackagedClient -Scope $scope -PatchedJar $delivery.Jar -TimeoutSeconds $TimeoutSeconds
    Wait-IrisWorldReady -Scope $scope -TimeoutSeconds $TimeoutSeconds

    $first = Start-SameMcp -Exe $delivery.Exe -WorkspaceRoot $workspace -Label "A"
    [void] (Initialize-SameMcp -Entry $first -Id 1 -Label "A")
    $firstInitial = Get-SameToolPayload (Invoke-SameMessage -Entry $first `
        -Message (New-SameTool -Id 2 -Name "vibris_get_config" -Arguments @{}))
    $workspaceId = Assert-SameUnconfigured -Payload $firstInitial -WorkspaceRoot $workspace
    $firstConfigured = Get-SameToolPayload (Invoke-SameMessage -Entry $first `
        -Message (New-SameTool -Id 3 -Name "vibris_configure" -Arguments $clientA.configure))
    Assert-SameConfigured -Payload $firstConfigured -Expected $clientA.configure -WorkspaceId $workspaceId
    $firstConfig = Get-SameToolPayload (Invoke-SameMessage -Entry $first `
        -Message (New-SameTool -Id 4 -Name "vibris_get_config" -Arguments @{}))
    Assert-SameConfigured -Payload $firstConfig -Expected $clientA.configure -WorkspaceId $workspaceId

    $second = Start-SameMcp -Exe $delivery.Exe -WorkspaceRoot $workspace -Label "B"
    [void] (Initialize-SameMcp -Entry $second -Id 11 -Label "B")
    $secondInitial = Get-SameToolPayload (Invoke-SameMessage -Entry $second `
        -Message (New-SameTool -Id 12 -Name "vibris_get_config" -Arguments @{}))
    $secondId = Assert-SameUnconfigured -Payload $secondInitial -WorkspaceRoot $workspace
    if ($secondId -cne $workspaceId) { throw "Same worktree MCPs received different workspace IDs." }
    $secondConfigured = Get-SameToolPayload (Invoke-SameMessage -Entry $second `
        -Message (New-SameTool -Id 13 -Name "vibris_configure" -Arguments $clientB.configure))
    Assert-SameConfigured -Payload $secondConfigured -Expected $clientB.configure -WorkspaceId $workspaceId
    $secondConfig = Get-SameToolPayload (Invoke-SameMessage -Entry $second `
        -Message (New-SameTool -Id 14 -Name "vibris_get_config" -Arguments @{}))
    Assert-SameConfigured -Payload $secondConfig -Expected $clientB.configure -WorkspaceId $workspaceId
    if ([string] $firstConfig.config.dimension_id -ceq [string] $secondConfig.config.dimension_id -or
        [double] $firstConfig.config.fov -eq [double] $secondConfig.config.fov)
    {
        throw "Distinct MCP processes did not retain distinct process-local scenes."
    }

    $firstJob = New-SameTool -Id 5 -Name "vibris_run_recipe" -Arguments ([ordered] @{
        recipe = "load_and_screenshot"
        source = [ordered] @{ kind = "workspace" }
        warmup_frames = 32
        screenshot_format = "png"
    })
    $secondJob = New-SameTool -Id 15 -Name "vibris_run_recipe" -Arguments ([ordered] @{
        recipe = "load_and_screenshot"
        source = [ordered] @{ kind = "workspace" }
        warmup_frames = 2
        screenshot_format = "png"
    })
    $markerA = Set-SameWorkspaceMarker -WorkspaceRoot $workspace -SceneLabel $clientA.label
    Send-SameMessage -Entry $first -Message $firstJob
    $firstPending = @(Wait-SamePendingSources -Scope $scope -Minimum 1)
    if ($firstPending.Count -ne 1) { throw "First MCP did not prepare exactly one isolated source." }
    $sourceA = $firstPending[0]
    Wait-SameSourceMarker -Source $sourceA -Marker $markerA
    $activeA = Wait-IrisEvent -Scope $scope -Type "source_active" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.source_uuid -ceq $sourceA.Name }

    $markerB = Set-SameWorkspaceMarker -WorkspaceRoot $workspace -SceneLabel $clientB.label
    Send-SameMessage -Entry $second -Message $secondJob
    $pending = @(Wait-SamePendingSources -Scope $scope -Minimum 2)
    $sourceB = @($pending | Where-Object { $_.Name -cne $sourceA.Name })
    if ($sourceB.Count -ne 1)
    {
        throw "The two accepted jobs did not own two unique source directories."
    }
    Wait-SameSourceMarker -Source $sourceB[0] -Marker $markerB
    $beforeClose = @(Get-SameEvents -Scope $scope | Where-Object { $_.type -ceq "source_active" })
    if ($beforeClose.Count -ne 1 -or $beforeClose[0].source_uuid -cne $sourceA.Name)
    {
        throw "Second same-worktree job was not queued behind the first."
    }
    Stop-SameMcp -Entry $first -Force
    if (-not (Test-Path -LiteralPath $sourceB[0].FullName -PathType Container))
    {
        throw "Closing MCP A deleted MCP B's queued accepted source."
    }

    $captureA = Wait-IrisEvent -Scope $scope -Type "capture_complete" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.source_uuid -ceq $sourceA.Name }
    $activeB = Wait-IrisEvent -Scope $scope -Type "source_active" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.source_uuid -ceq $sourceB[0].Name }
    $captureB = Wait-IrisEvent -Scope $scope -Type "capture_complete" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.source_uuid -ceq $sourceB[0].Name }
    $secondResult = Get-SameToolPayload (Read-SameResponse -Entry $second -Id 15)
    Assert-G007CompletedResult -Scope $scope -Payload $secondResult

    $contexts = @(Get-SameEvents -Scope $scope | Where-Object {
        $_.type -ceq "context_applied" -and
        $_.source_uuid -in @($sourceA.Name, $sourceB[0].Name)
    })
    if ($contexts.Count -ne 2) { throw "Expected exactly two attributed context events." }
    $contextA = @($contexts | Where-Object { $_.source_uuid -ceq $sourceA.Name })
    $contextB = @($contexts | Where-Object { $_.source_uuid -ceq $sourceB[0].Name })
    if ($contextA.Count -ne 1 -or $contextB.Count -ne 1)
    {
        throw "Context events did not map one-to-one to same-worktree sources."
    }
    Assert-SameScene -Actual $contextA[0].context -Client $clientA
    Assert-SameScene -Actual $contextB[0].context -Client $clientB

    $events = @(Get-SameEvents -Scope $scope)
    $active = [System.Collections.Generic.HashSet[string]]::new()
    $maxConcurrent = 0
    foreach ($event in $events)
    {
        if ($event.type -ceq "source_active")
        {
            if (-not $active.Add([string] $event.source_uuid) -or $active.Count -ne 1)
            {
                throw "Iris activated overlapping Minecraft jobs."
            }
            $maxConcurrent = [math]::Max($maxConcurrent, $active.Count)
        }
        elseif ($event.type -ceq "capture_complete")
        {
            if (-not $active.Remove([string] $event.source_uuid))
            {
                throw "Iris completed a capture outside its active interval."
            }
        }
    }
    if ($active.Count -ne 0 -or $maxConcurrent -ne 1)
    {
        throw "Maximum active Minecraft job count was not exactly one."
    }

    $manifests = @(Wait-SameManifests -Scope $scope -Count 2)
    $requestDirectories = @($manifests | ForEach-Object { $_.Directory.FullName } | Select-Object -Unique)
    if ($requestDirectories.Count -ne 2 -or
        @($requestDirectories | ForEach-Object { Split-Path -Leaf $_ } | Select-Object -Unique).Count -ne 2)
    {
        throw "Same-worktree jobs reused an artifact request directory."
    }
    $secondManifest = [System.IO.Path]::GetFullPath([string] $secondResult.manifest_path)
    if (@($manifests.FullName | Where-Object {
            [string]::Equals($_, $secondManifest, [System.StringComparison]::OrdinalIgnoreCase)
        }).Count -ne 1)
    {
        throw "MCP B result did not reference exactly one owned request manifest."
    }

    Stop-SameMcp -Entry $second
    $restart = Start-SameMcp -Exe $delivery.Exe -WorkspaceRoot $workspace -Label "restart"
    [void] (Initialize-SameMcp -Entry $restart -Id 21 -Label "restart")
    $restartConfig = Get-SameToolPayload (Invoke-SameMessage -Entry $restart `
        -Message (New-SameTool -Id 22 -Name "vibris_get_config" -Arguments @{}))
    $restartId = Assert-SameUnconfigured -Payload $restartConfig -WorkspaceRoot $workspace `
        -WorkspaceId $workspaceId
    Stop-SameMcp -Entry $restart

    $deliveryAfter = Get-SameDelivery
    if ($deliveryAfter.ExeHash -cne $delivery.ExeHash -or $deliveryAfter.JarHash -cne $delivery.JarHash)
    {
        throw "Acceptance mutated the explicit delivery pair."
    }
    $resultEvidence = [ordered] @{
        same_workspace_id = $true
        workspace_id = $workspaceId
        scene_crosstalk = $false
        max_concurrent_jobs = $maxConcurrent
        restart_configured = $false
        restart_workspace_id = $restartId
        first_mcp_forced_after_accept = $true
        second_completed_after_first_exit = $true
        source_ownership_transfer = $true
        source_paths = @($sourceA.FullName, $sourceB[0].FullName)
        source_markers = @($markerA.Text.Trim(), $markerB.Text.Trim())
        artifact_request_paths = $requestDirectories
        second_manifest_path = $secondManifest
        event_path = $scope.EventFile
        owned_mcp_pids = @($ownedPids)
        iris_runtime_pid = $scope.RuntimePid
        iris_port = $script:IrisPort
        delivery_mcp_sha256 = $delivery.ExeHash
        delivery_iris_sha256 = $delivery.JarHash
        canonical_mcp_path = $delivery.CanonicalExe
        canonical_iris_path = $delivery.CanonicalJar
        context_a_source = [string] $contextA[0].source_uuid
        context_b_source = [string] $contextB[0].source_uuid
        capture_a_targets = @($captureA.targets)
        capture_b_targets = @($captureB.targets)
    }
    $evidenceRoot = Save-SameEvidence -Scope $scope -Result $resultEvidence
    $evidenceSaved = $true
    $summary = "PASS same_workspace_id=true scene_crosstalk=false max_concurrent_jobs=1 " +
        "restart_configured=false first_closed_after_accept=true second_completed=true " +
        "unique_sources=2 unique_request_artifacts=2 minecraft=1 cwd_discovery=true"
}
catch
{
    $failure = $_.Exception
}
finally
{
    foreach ($entry in @($ownedMcps))
    {
        try { Stop-SameMcp -Entry $entry -Force }
        catch { $failure = Merge-IrisProbeFailure -Primary $failure -Additional $_.Exception }
    }
    if ($null -ne $scope -and -not $evidenceSaved -and
        -not [string]::IsNullOrWhiteSpace($EvidenceDirectory))
    {
        try
        {
            $resultEvidence["failure"] = if ($null -eq $failure) { "unknown" } else { $failure.ToString() }
            $evidenceRoot = Save-SameEvidence -Scope $scope -Result $resultEvidence
            $evidenceSaved = $true
        }
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
Write-Output "EVIDENCE root=$evidenceRoot event=events.jsonl artifacts=artifacts result=result.json"
Write-Output ("CLEANUP owned_mcp_pids=$($ownedPids -join ',') iris_runtime_stopped=true " +
    "listener_closed=true root_removed=true delivery_retained=true multimc_untouched=true")
