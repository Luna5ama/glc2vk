[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $PatchedJar,
    [string] $Client = "mcp/out/build/Release/vibris-control-client.exe",
    [Parameter(Mandatory)] [string] $GameDir,
    [string] $Source = "integration-tests/fixtures/shaderpacks/capture-known-resources/shaders",
    [string] $Context = "integration-tests/fixtures/context/overworld-sunset-rooftop.json",
    [string[]] $Textures = @("colortex0.main", "depthtex0"),
    [string[]] $Buffers = @("iris_ssbo_6"),
    [string] $AlsoRequest = "missing_resource",
    [ValidateRange(1, 600)] [int] $TimeoutSeconds = 180
)

. (Join-Path $PSScriptRoot "iris-probe-common.ps1")

function Get-BundleArtifact
{
    param([Parameter(Mandatory)] [object] $Terminal, [Parameter(Mandatory)] [string] $FileName)

    $groupArtifacts = @($Terminal.result.artifact_groups | ForEach-Object { @($_.artifacts) })
    $matches = @(@($Terminal.result.artifacts) + $groupArtifacts | Where-Object { $_.file_name -ceq $FileName })
    if ($matches.Count -ne 1) { throw "Expected exactly one '$FileName' artifact; found $($matches.Count)." }
    return $matches[0]
}

function Resolve-BundleArtifactFile
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [Parameter(Mandatory)] [object] $Artifact,
        [Parameter(Mandatory)] [string] $ExpectedName
    )

    if ($Artifact.file_name -cne $ExpectedName -or
        -not [System.IO.Path]::IsPathRooted([string] $Artifact.path))
    {
        throw "Artifact '$ExpectedName' did not return its expected absolute path."
    }
    $path = Resolve-IrisOwnedArtifact -Scope $Scope -Path $Artifact.path
    if (-not (Test-Path -LiteralPath $path -PathType Leaf) -or
        (Get-Item -LiteralPath $path).Length -ne [long] $Artifact.byte_size)
    {
        throw "Artifact '$ExpectedName' is unreadable or has the wrong byte count."
    }
    return $path
}

function Assert-BundleJsonHasNoRawGlIds
{
    param([Parameter(Mandatory)] [AllowNull()] [object] $Value, [Parameter(Mandatory)] [string] $Location)

    $json = $Value | ConvertTo-Json -Compress -Depth 30
    if ($json -match '"(?i:gl[_-]?id|opengl[_-]?id|texture[_-]?id|buffer[_-]?id|object[_-]?id|handle)"\s*:')
    {
        throw "Raw GL identifier field appeared in $Location."
    }
}

function Get-ArtifactTreeSnapshot
{
    param([Parameter(Mandatory)] [string] $Root)

    if (-not (Test-Path -LiteralPath $Root -PathType Container)) { return @() }
    $prefixLength = $Root.TrimEnd('\', '/').Length + 1
    return @(Get-ChildItem -LiteralPath $Root -Recurse -Force | ForEach-Object {
        $_.FullName.Substring($prefixLength)
    } | Sort-Object)
}

function Assert-NoTemporaryArtifactDirectories
{
    param([Parameter(Mandatory)] [string] $Root)

    if (Test-Path -LiteralPath $Root -PathType Container)
    {
        $temporary = @(Get-ChildItem -LiteralPath $Root -Directory -Recurse -Force |
            Where-Object { $_.Name.EndsWith(".tmp", [System.StringComparison]::Ordinal) })
        if ($temporary.Count -ne 0)
        {
            throw "Artifact root contains a temporary job directory: $($temporary[0].FullName)"
        }
    }
}

$scope = $null
$clientEntry = $null
$failure = $null
$summary = $null

try
{
    if ($Textures.Count -ne 2 -or $Textures[0] -cne "colortex0.main" -or $Textures[1] -cne "depthtex0" -or
        $Buffers.Count -ne 1 -or $Buffers[0] -cne "iris_ssbo_6" -or
        $AlsoRequest -cne "missing_resource")
    {
        throw "G006-C002 requires Textures colortex0.main,depthtex0, Buffers iris_ssbo_6, " +
            "and AlsoRequest missing_resource."
    }
    $jar = Resolve-IrisPatchedJar -Path $PatchedJar
    $clientExe = Resolve-IrisArtifact -Path $Client -Label "Release native control client"
    $sourceRoot = Resolve-IrisDirectory -Path $Source -Label "capture-known-resources source"
    if (-not [string]::Equals($sourceRoot,
            [System.IO.Path]::GetFullPath((Join-Path $script:VibrisRoot `
                "integration-tests\fixtures\shaderpacks\capture-known-resources\shaders")),
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "G006-C002 requires the capture-known-resources fixture."
    }
    $contextPath = Resolve-IrisArtifact -Path $Context -Label "context fixture"
    $contextValue = Get-Content -Raw -LiteralPath $contextPath | ConvertFrom-Json
    $scope = New-IrisProbeScope -Criterion "c002" -GameDir $GameDir -Gate "G006"
    [void] (Write-IrisPresetCatalog -Scope $scope -Context $contextValue)

    Start-IrisPackagedClient -Scope $scope -PatchedJar $jar -Scenario "g006-c002" `
        -TimeoutSeconds $TimeoutSeconds
    $clientEntry = Start-CoreClient -Exe $clientExe -Port $script:IrisPort -WorkspaceId "g006-c002" `
        -InstanceId ([guid]::NewGuid().ToString()) -WorkingDirectory $scope.Root -Owned `
        ([System.Collections.Generic.List[object]]::new()) -TimeoutSeconds $TimeoutSeconds
    [void] (Get-IrisClientHello -Client $clientEntry -Scope $scope)
    Wait-IrisWorldReady -Scope $scope -TimeoutSeconds $TimeoutSeconds

    $sourceA = New-IrisPreparedSource -Scope $scope -Source $sourceRoot
    $bundleActions = @(
        [ordered] @{ type = "wait_frames"; frames = 2 },
        [ordered] @{ type = "take_screenshot"; format = "png"; artifact_name = "beauty" },
        [ordered] @{ type = "dump_texture"; name = "colortex0.main"; format = "bin"; artifact_name = "colortex0.main" },
        [ordered] @{ type = "dump_texture"; name = "depthtex0"; format = "bin"; artifact_name = "depthtex0" },
        [ordered] @{ type = "dump_buffer"; name = "iris_ssbo_6"; artifact_name = "iris_ssbo_6" }
    )
    $bundleCommand = New-CoreSubmitCommand -MessageId "g006-c002-bundle" `
        -RequestId "g006-c002-bundle" -Sources @($sourceA) -Context $contextValue `
        -Actions $bundleActions -Timeouts @{
            queue_timeout_ms = 60000
            execution_timeout_ms = 120000
            total_timeout_ms = 180000
        }
    $bundleStart = $clientEntry.Messages.Count
    Send-CoreClientCommand -Entry $clientEntry -Command $bundleCommand
    $bundleTerminal = Wait-CoreClientMessage -Entry $clientEntry -StartIndex $bundleStart `
        -TimeoutSeconds $TimeoutSeconds -Match { param($message)
            $message.request_id -ceq "g006-c002-bundle" -and
                $message.type -in @("JobCompleted", "JobFailed") }
    if ($bundleTerminal.type -cne "JobCompleted")
    {
        throw "G006-C002 valid bundle failed: $($bundleTerminal | ConvertTo-Json -Compress -Depth 20)"
    }

    if (@($bundleTerminal.result.frame_ids).Count -ne 1 -or
        @($bundleTerminal.result.artifacts).Count -ne 2 -or
        @($bundleTerminal.result.artifact_groups).Count -ne 4)
    {
        throw "G006-C002 valid bundle did not return one frame and six protocol artifacts."
    }
    $frameId = [long] $bundleTerminal.result.frame_ids[0]
    $beauty = Get-BundleArtifact -Terminal $bundleTerminal -FileName "beauty.png"
    $textureArtifact = Get-BundleArtifact -Terminal $bundleTerminal -FileName "colortex0.main.bin"
    $depth = Get-BundleArtifact -Terminal $bundleTerminal -FileName "depthtex0.bin"
    $bufferArtifact = Get-BundleArtifact -Terminal $bundleTerminal -FileName "iris_ssbo_6.bin"
    $shaderLog = Get-BundleArtifact -Terminal $bundleTerminal -FileName "shader.log"
    $manifest = Get-BundleArtifact -Terminal $bundleTerminal -FileName "manifest.json"
    foreach ($artifact in @($beauty, $textureArtifact, $depth, $bufferArtifact))
    {
        if ([long] $artifact.resource.frame_id -ne $frameId)
        {
            throw "beauty/colortex0/radiance_cache do not share one frame_id."
        }
    }
    $manifestPath = Resolve-BundleArtifactFile -Scope $scope -Artifact $manifest -ExpectedName "manifest.json"
    $jobDirectory = Split-Path -Parent $manifestPath
    foreach ($pair in @(
            @($beauty, "beauty.png"), @($textureArtifact, "colortex0.main.bin"), @($depth, "depthtex0.bin"),
            @($bufferArtifact, "iris_ssbo_6.bin"), @($shaderLog, "shader.log")))
    {
        [void] (Resolve-BundleArtifactFile -Scope $scope -Artifact $pair[0] -ExpectedName $pair[1])
    }
    foreach ($logicalName in @("colortex0.main", "depthtex0", "iris_ssbo_6"))
    {
        $sidecarPath = Resolve-IrisOwnedArtifact -Scope $scope -Path (Join-Path $jobDirectory "$logicalName.json")
        if (-not (Test-Path -LiteralPath $sidecarPath -PathType Leaf))
        {
            throw "G006-C002 sidecar is missing: $sidecarPath"
        }
        $sidecar = Get-Content -Raw -LiteralPath $sidecarPath | ConvertFrom-Json
        if ([long] $sidecar.frame_id -ne $frameId)
        {
            throw "$logicalName sidecar does not carry the bundle frame_id."
        }
        Assert-BundleJsonHasNoRawGlIds -Value $sidecar -Location "$logicalName.json"
    }
    $manifestJson = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    Assert-BundleJsonHasNoRawGlIds -Value $bundleTerminal -Location "result DTO"
    Assert-BundleJsonHasNoRawGlIds -Value $manifestJson -Location "manifest.json"
    Assert-NoTemporaryArtifactDirectories -Root $scope.ArtifactRoot
    $beforeFailure = @(Get-ArtifactTreeSnapshot -Root $scope.ArtifactRoot)

    $sourceB = New-IrisPreparedSource -Scope $scope -Source $sourceRoot
    $failureActions = @(
        [ordered] @{ type = "dump_texture"; name = $AlsoRequest; format = "bin"; artifact_name = $AlsoRequest }
    )
    $failureCommand = New-CoreSubmitCommand -MessageId "g006-c002-missing" `
        -RequestId "g006-c002-missing" -Sources @($sourceB) -Context $contextValue `
        -Actions $failureActions -Timeouts @{
            queue_timeout_ms = 60000
            execution_timeout_ms = 120000
            total_timeout_ms = 180000
        }
    $failureStart = $clientEntry.Messages.Count
    Send-CoreClientCommand -Entry $clientEntry -Command $failureCommand
    $failureTerminal = Wait-CoreClientMessage -Entry $clientEntry -StartIndex $failureStart `
        -TimeoutSeconds $TimeoutSeconds -Match { param($message)
            $message.request_id -ceq "g006-c002-missing" -and
                $message.type -in @("JobCompleted", "JobFailed") }
    if ($failureTerminal.type -cne "JobFailed" -or
        (Get-CoreFailureCode -Message $failureTerminal) -cne "CAPTURE_RESOURCE_NOT_FOUND")
    {
        throw "Unknown texture did not return CAPTURE_RESOURCE_NOT_FOUND: " +
            ($failureTerminal | ConvertTo-Json -Compress -Depth 20)
    }
    if (@($failureTerminal.artifacts).Count -ne 0)
    {
        throw "Unknown texture failure exposed partial artifacts."
    }
    Assert-NoTemporaryArtifactDirectories -Root $scope.ArtifactRoot
    $afterFailure = @(Get-ArtifactTreeSnapshot -Root $scope.ArtifactRoot)
    if ($beforeFailure.Count -ne $afterFailure.Count -or
        [string]::Join("`n", $beforeFailure) -cne [string]::Join("`n", $afterFailure))
    {
        throw "Unknown texture created or changed a finalized/partial artifact path."
    }
    $summary = "PASS criterion=G006-C002 frame_id=$frameId bundle=beauty,colortex0,depthtex0,radiance_cache " +
        "unknown=CAPTURE_RESOURCE_NOT_FOUND partial_dir=false tmp=false"
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
Write-Output "CLEANUP criterion=G006-C002 listener_closed=true root_removed=true multimc_untouched=true"
