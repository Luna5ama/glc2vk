[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $PatchedJar,
    [string] $Client = "mcp/out/build/Release/vibris-control-client.exe",
    [Parameter(Mandatory)] [string] $GameDir,
    [string] $Source = "integration-tests/fixtures/shaderpacks/capture-known-resources/shaders",
    [string] $Context = "integration-tests/fixtures/context/overworld-sunset-rooftop.json",
    [ValidateSet("png")] [string] $Screenshot = "png",
    [string] $Texture = "colortex0",
    [string] $Buffer = "radiance_cache",
    [ValidateRange(1, 600)] [int] $TimeoutSeconds = 180
)

. (Join-Path $PSScriptRoot "iris-probe-common.ps1")

function Get-CaptureArtifact
{
    param([Parameter(Mandatory)] [object] $Terminal, [Parameter(Mandatory)] [string] $FileName)

    $matches = @($Terminal.result.artifacts | Where-Object { $_.file_name -ceq $FileName })
    if ($matches.Count -ne 1) { throw "Expected exactly one '$FileName' artifact; found $($matches.Count)." }
    return $matches[0]
}

function Assert-JsonHasNoRawGlIds
{
    param([Parameter(Mandatory)] [AllowNull()] [object] $Value, [Parameter(Mandatory)] [string] $Location)

    $json = $Value | ConvertTo-Json -Compress -Depth 30
    if ($json -match '"(?i:gl[_-]?id|opengl[_-]?id|texture[_-]?id|buffer[_-]?id|object[_-]?id|handle)"\s*:')
    {
        throw "Raw GL identifier field appeared in $Location."
    }
}

function Resolve-CaptureArtifactFile
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
    if (-not (Test-Path -LiteralPath $path -PathType Leaf))
    {
        throw "Artifact '$ExpectedName' is not a readable file: $path"
    }
    $bytes = (Get-Item -LiteralPath $path).Length
    if ($bytes -ne [long] $Artifact.byte_size)
    {
        throw "Artifact '$ExpectedName' byte count differs from its result metadata."
    }
    return $path
}

function Assert-RawCapture
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [Parameter(Mandatory)] [object] $Artifact,
        [Parameter(Mandatory)] [string] $JobDirectory,
        [Parameter(Mandatory)] [string] $LogicalName,
        [Parameter(Mandatory)] [string] $Kind,
        [Parameter(Mandatory)] [string] $Extension
    )

    $fileName = "$LogicalName.$Extension"
    $path = Resolve-CaptureArtifactFile -Scope $Scope -Artifact $Artifact -ExpectedName $fileName
    $metadataPath = Resolve-IrisOwnedArtifact -Scope $Scope -Path (Join-Path $JobDirectory "$LogicalName.json")
    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf))
    {
        throw "Capture sidecar is missing: $metadataPath"
    }
    $metadata = Get-Content -Raw -LiteralPath $metadataPath | ConvertFrom-Json
    $fileBytes = (Get-Item -LiteralPath $path).Length
    if ($Artifact.resource.logical_name -cne $LogicalName -or
        $Artifact.resource.kind -cne "RESOURCE_KIND_$Kind" -or
        [long] $Artifact.resource.byte_size -ne $fileBytes -or
        $metadata.logical_name -cne $LogicalName -or $metadata.kind -cne $Kind -or
        [long] $metadata.byte_size -ne $fileBytes -or
        [long] $metadata.frame_id -ne [long] $Artifact.resource.frame_id -or
        [int] $metadata.width -ne [int] $Artifact.resource.width -or
        [int] $metadata.height -ne [int] $Artifact.resource.height -or
        [int] $metadata.depth -ne [int] $Artifact.resource.depth -or
        [int] $metadata.channel_count -ne [int] $Artifact.resource.channel_count -or
        $metadata.internal_format -cne $Artifact.resource.internal_format -or
        "SCALAR_TYPE_$($metadata.scalar_type)" -cne $Artifact.resource.scalar_type)
    {
        throw "Capture '$LogicalName' bytes/resource/sidecar metadata are inconsistent."
    }
    Assert-JsonHasNoRawGlIds -Value $metadata -Location "$LogicalName.json"
    return $fileBytes
}

$scope = $null
$clientEntry = $null
$failure = $null
$summary = $null

try
{
    if ($Texture -cne "colortex0" -or $Buffer -cne "radiance_cache")
    {
        throw "G006-C001 requires Texture colortex0 and Buffer radiance_cache."
    }
    $jar = Resolve-IrisPatchedJar -Path $PatchedJar
    $clientExe = Resolve-IrisArtifact -Path $Client -Label "Release native control client"
    $sourceRoot = Resolve-IrisDirectory -Path $Source -Label "capture-known-resources source"
    if (-not [string]::Equals($sourceRoot,
            [System.IO.Path]::GetFullPath((Join-Path $script:VibrisRoot `
                "integration-tests\fixtures\shaderpacks\capture-known-resources\shaders")),
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "G006-C001 requires the capture-known-resources fixture."
    }
    $contextPath = Resolve-IrisArtifact -Path $Context -Label "context fixture"
    $contextValue = Get-Content -Raw -LiteralPath $contextPath | ConvertFrom-Json
    $scope = New-IrisProbeScope -Criterion "c001" -GameDir $GameDir -Gate "G006"
    [void] (Write-IrisPresetCatalog -Scope $scope -Context $contextValue)

    Start-IrisPackagedClient -Scope $scope -PatchedJar $jar -Scenario "g006-c001" `
        -TimeoutSeconds $TimeoutSeconds
    $clientEntry = Start-CoreClient -Exe $clientExe -Port $script:IrisPort -WorkspaceId "g006-c001" `
        -InstanceId ([guid]::NewGuid().ToString()) -WorkingDirectory $scope.Root -Owned `
        ([System.Collections.Generic.List[object]]::new()) -TimeoutSeconds $TimeoutSeconds
    [void] (Get-IrisClientHello -Client $clientEntry -Scope $scope)
    Wait-IrisWorldReady -Scope $scope -TimeoutSeconds $TimeoutSeconds

    $prepared = New-IrisPreparedSource -Scope $scope -Source $sourceRoot
    $actions = @(
        [ordered] @{ type = "wait_frames"; frames = 2 },
        [ordered] @{ type = "take_screenshot"; format = $Screenshot; artifact_name = "beauty" },
        [ordered] @{ type = "capture_texture"; name = $Texture; format = "raw"; artifact_name = $Texture },
        [ordered] @{ type = "capture_buffer"; name = $Buffer; format = "bin"; artifact_name = $Buffer }
    )
    $command = New-CoreSubmitCommand -MessageId "g006-c001" -RequestId "g006-c001" `
        -Sources @($prepared) -Context $contextValue -Actions $actions -Timeouts @{
            queue_timeout_ms = 60000
            execution_timeout_ms = 120000
            total_timeout_ms = 180000
        }
    $start = $clientEntry.Messages.Count
    Send-CoreClientCommand -Entry $clientEntry -Command $command
    $terminal = Wait-CoreClientMessage -Entry $clientEntry -StartIndex $start `
        -TimeoutSeconds $TimeoutSeconds -Match { param($message)
            $message.request_id -ceq "g006-c001" -and $message.type -in @("JobCompleted", "JobFailed") }
    if ($terminal.type -cne "JobCompleted")
    {
        throw "G006-C001 capture failed: $($terminal | ConvertTo-Json -Compress -Depth 20)"
    }

    $artifacts = @($terminal.result.artifacts)
    if ($artifacts.Count -ne 5 -or @($terminal.result.frame_ids).Count -ne 1)
    {
        throw "G006-C001 did not return one frame and exactly five protocol artifacts."
    }
    $frameId = [long] $terminal.result.frame_ids[0]
    $beauty = Get-CaptureArtifact -Terminal $terminal -FileName "beauty.png"
    $textureArtifact = Get-CaptureArtifact -Terminal $terminal -FileName "colortex0.raw"
    $bufferArtifact = Get-CaptureArtifact -Terminal $terminal -FileName "radiance_cache.bin"
    $shaderLog = Get-CaptureArtifact -Terminal $terminal -FileName "shader.log"
    $manifestArtifact = Get-CaptureArtifact -Terminal $terminal -FileName "manifest.json"
    foreach ($capture in @($beauty, $textureArtifact, $bufferArtifact))
    {
        if ([long] $capture.resource.frame_id -ne $frameId)
        {
            throw "G006-C001 capture resources do not share the completed frame ID."
        }
    }

    $manifestPath = Resolve-CaptureArtifactFile -Scope $scope -Artifact $manifestArtifact `
        -ExpectedName "manifest.json"
    if (-not [System.IO.Path]::IsPathRooted([string] $terminal.result.manifest_path) -or
        -not [string]::Equals($manifestPath,
            (Resolve-IrisOwnedArtifact -Scope $scope -Path $terminal.result.manifest_path),
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "G006-C001 manifest_path is not the returned absolute manifest artifact path."
    }
    $jobDirectory = Split-Path -Parent $manifestPath
    $shaderLogPath = Resolve-CaptureArtifactFile -Scope $scope -Artifact $shaderLog -ExpectedName "shader.log"
    if ((Get-Item -LiteralPath $shaderLogPath).Length -le 0) { throw "shader.log is empty." }

    $beautyPath = Resolve-CaptureArtifactFile -Scope $scope -Artifact $beauty -ExpectedName "beauty.png"
    $png = [System.IO.File]::ReadAllBytes($beautyPath)
    $signature = [byte[]] @(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    if ($png.Length -lt 24 -or -not [System.Linq.Enumerable]::SequenceEqual(
            [byte[]] $png[0..7], $signature) -or
        [System.Text.Encoding]::ASCII.GetString($png, 12, 4) -cne "IHDR")
    {
        throw "beauty.png has an invalid PNG signature or IHDR."
    }
    $pngWidth = [System.Net.IPAddress]::NetworkToHostOrder([System.BitConverter]::ToInt32($png, 16))
    $pngHeight = [System.Net.IPAddress]::NetworkToHostOrder([System.BitConverter]::ToInt32($png, 20))
    if ($pngWidth -ne [int] $contextValue.resolution.width -or
        $pngHeight -ne [int] $contextValue.resolution.height -or
        $pngWidth -ne [int] $beauty.resource.width -or $pngHeight -ne [int] $beauty.resource.height)
    {
        throw "beauty.png dimensions disagree with context/resource metadata."
    }

    $rawBytes = Assert-RawCapture -Scope $scope -Artifact $textureArtifact -JobDirectory $jobDirectory `
        -LogicalName "colortex0" -Kind "TEXTURE" -Extension "raw"
    $binBytes = Assert-RawCapture -Scope $scope -Artifact $bufferArtifact -JobDirectory $jobDirectory `
        -LogicalName "radiance_cache" -Kind "BUFFER" -Extension "bin"

    $manifest = Get-Content -Raw -LiteralPath $manifestPath | ConvertFrom-Json
    $expectedFiles = @("beauty.png", "colortex0.raw", "colortex0.json", "radiance_cache.bin",
        "radiance_cache.json", "shader.log")
    if (@($manifest.artifacts).Count -ne $expectedFiles.Count) { throw "Manifest file count is incorrect." }
    foreach ($name in $expectedFiles)
    {
        $entry = @($manifest.artifacts | Where-Object { $_.file_name -ceq $name })
        $path = Join-Path $jobDirectory $name
        if ($entry.Count -ne 1 -or -not (Test-Path -LiteralPath $path -PathType Leaf) -or
            [long] $entry[0].byte_size -ne (Get-Item -LiteralPath $path).Length)
        {
            throw "Manifest entry '$name' is absent or has the wrong byte count."
        }
    }
    Assert-JsonHasNoRawGlIds -Value $terminal -Location "result DTO"
    Assert-JsonHasNoRawGlIds -Value $manifest -Location "manifest.json"
    $summary = "PASS criterion=G006-C001 frame_id=$frameId png=${pngWidth}x${pngHeight} " +
        "raw_bytes=$rawBytes bin_bytes=$binBytes absolute_paths=true metadata=true raw_gl_ids=false"
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
Write-Output "CLEANUP criterion=G006-C001 listener_closed=true root_removed=true multimc_untouched=true"
