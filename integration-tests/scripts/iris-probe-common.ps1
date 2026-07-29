Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

. (Join-Path $PSScriptRoot "core-probe-common.ps1")
. (Join-Path $PSScriptRoot "iris-process-harness.ps1")
. (Join-Path $PSScriptRoot "iris-preset-harness.ps1")

$script:IrisPort = 50051
$script:VibrisRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$script:IrisRoot = [System.IO.Path]::GetFullPath((Join-Path $script:VibrisRoot "..\Iris"))

function Resolve-IrisArtifact
{
    param([Parameter(Mandatory)] [string] $Path, [Parameter(Mandatory)] [string] $Label)

    $items = @(Get-Item -Path $Path -ErrorAction Stop | Where-Object { -not $_.PSIsContainer })
    if ($items.Count -ne 1)
    {
        throw "$Label must resolve to exactly one file; resolved $($items.Count): $Path"
    }
    return $items[0].FullName
}

function Resolve-IrisDirectory
{
    param([Parameter(Mandatory)] [string] $Path, [Parameter(Mandatory)] [string] $Label)

    $item = Get-Item -LiteralPath ([System.IO.Path]::GetFullPath($Path)) -ErrorAction Stop
    if (-not $item.PSIsContainer) { throw "$Label is not a directory: $Path" }
    return $item.FullName
}

function Get-IrisClientHello
{
    param([Parameter(Mandatory)] [object] $Client, [Parameter(Mandatory)] [object] $Scope)

    $hello = @($Client.Messages | Where-Object { $_.type -ceq "ServerHello" })
    if ($hello.Count -ne 1 -or
        -not [string]::Equals([System.IO.Path]::GetFullPath($hello[0].pending_shaders_root),
            $Scope.PendingRoot, [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "ServerHello did not expose this game's exact pending shaders root."
    }
    return $hello[0]
}

function Resolve-IrisPatchedJar
{
    param([Parameter(Mandatory)] [string] $Path)

    $jar = Resolve-IrisArtifact -Path $Path -Label "PatchedJar"
    $archive = [System.IO.Compression.ZipFile]::OpenRead($jar)
    try
    {
        $names = @($archive.Entries | ForEach-Object { $_.FullName })
        $hasApi = @($names | Where-Object {
            $_ -match '^META-INF/jars/vibris-api.*[.]jar$' -or
                $_ -eq 'dev/vibris/api/VibrisRuntimeAdapter.class'
        }).Count -gt 0
        $hasCore = @($names | Where-Object {
            $_ -match '^META-INF/jars/vibris-core.*[.]jar$' -or $_ -eq 'dev/vibris/core/VibrisBootstrap.class'
        }).Count -gt 0
        if (-not $hasApi -or -not $hasCore -or $names -notcontains "fabric.mod.json")
        {
            throw "PatchedJar does not embed Vibris API/core with Iris fabric.mod.json: $jar"
        }
    }
    finally
    {
        $archive.Dispose()
    }
    return $jar
}

function New-IrisProbeScope
{
    param(
        [Parameter(Mandatory)] [ValidateSet("c001", "c002", "c003")] [string] $Criterion,
        [Parameter(Mandatory)] [string] $GameDir
    )

    $root = [System.IO.Path]::GetFullPath((Join-Path $script:VibrisRoot ".omo\tmp\ulw-v1-g005-$Criterion"))
    $game = [System.IO.Path]::GetFullPath($GameDir)
    $expectedGame = Join-Path $root "game"
    if (-not [string]::Equals($game, $expectedGame, [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "G005-$($Criterion.ToUpperInvariant()) requires GameDir $expectedGame."
    }
    if (Test-Path -LiteralPath $root)
    {
        throw "Scoped criterion root already exists: $root"
    }
    if (Test-CorePort -Port $script:IrisPort)
    {
        throw "Criterion port 127.0.0.1:$script:IrisPort is already in use; refusing to stop its owner."
    }
    [void] (New-Item -ItemType Directory -Path $game)
    [System.IO.File]::WriteAllText((Join-Path $game "options.txt"), "onboardAccessibility:false`n")
    return [pscustomobject] @{
        Criterion = $Criterion.ToUpperInvariant()
        Root = $root
        GameDir = $game
        PendingRoot = Join-Path $game "vibris\pending"
        ArtifactRoot = Join-Path $game "vibris\artifacts"
        ShaderpackRoot = Join-Path $game "shaderpacks\vibris"
        EventFile = Join-Path $game "phase4-events.jsonl"
        ReceiptFile = Join-Path $game "phase4-receipt.json"
        CommandFile = Join-Path $game "phase4-command.json"
        RunId = [guid]::NewGuid().ToString()
        Protected = @(Get-IrisProtectedProcesses)
        Wrapper = $null
        RuntimePid = 0
        RuntimeCreated = ""
    }
}

function New-IrisPreparedSource
{
    param([Parameter(Mandatory)] [object] $Scope, [Parameter(Mandatory)] [string] $Source)

    $sourceRoot = [System.IO.Path]::GetFullPath($Source)
    if (-not (Test-Path -LiteralPath $sourceRoot -PathType Container) -or
        (Split-Path -Leaf $sourceRoot) -cne "shaders")
    {
        throw "Source must be a shaderpack shaders directory: $sourceRoot"
    }
    $entries = @(Get-ChildItem -LiteralPath $sourceRoot -Recurse -Force)
    if ($entries | Where-Object { $_.Attributes -band [System.IO.FileAttributes]::ReparsePoint })
    {
        throw "Fixture source contains a reparse point: $sourceRoot"
    }
    $uuid = [guid]::NewGuid().ToString()
    $target = Join-Path $Scope.PendingRoot $uuid
    [void] (New-Item -ItemType Directory -Path $target)
    Get-ChildItem -LiteralPath $sourceRoot -Force | Copy-Item -Destination $target -Recurse
    $files = @(Get-ChildItem -LiteralPath $target -Recurse -Force -File)
    return [pscustomobject] @{
        uuid = $uuid
        directory = $target
        file_count = $files.Count
        total_bytes = [uint64] (($files | Measure-Object -Property Length -Sum).Sum)
    }
}

function Wait-IrisEvent
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [Parameter(Mandatory)] [string] $Type,
        [Parameter(Mandatory)] [scriptblock] $Match,
        [ValidateRange(1, 600)] [int] $TimeoutSeconds = 90
    )

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([datetime]::UtcNow -lt $deadline)
    {
        if (Test-Path -LiteralPath $Scope.EventFile -PathType Leaf)
        {
            foreach ($line in [System.IO.File]::ReadAllLines($Scope.EventFile))
            {
                if ([string]::IsNullOrWhiteSpace($line)) { continue }
                $event = $line | ConvertFrom-Json
                if ($event.run_id -cne $Scope.RunId)
                {
                    throw "Phase-4 event belongs to another runId."
                }
                if ($event.type -ceq $Type -and (& $Match $event)) { return $event }
            }
        }
        if ($null -ne $Scope.Wrapper -and $Scope.Wrapper.Process.HasExited)
        {
            throw "Packaged client exited while waiting for event '$Type'."
        }
        Start-Sleep -Milliseconds 50
    }
    throw "Phase-4 event '$Type' was not observed within $TimeoutSeconds seconds."
}

function Wait-IrisWorldReady
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [ValidateRange(1, 600)] [int] $TimeoutSeconds = 90
    )

    [void] (Wait-IrisEvent -Scope $Scope -Type "frame_tail" -TimeoutSeconds $TimeoutSeconds `
        -Match { param($event) $event.world_ready -eq $true })
}

function Get-IrisLinkTarget
{
    param([Parameter(Mandatory)] [object] $Scope)

    $link = Join-Path $Scope.ShaderpackRoot "shaders"
    $item = Get-Item -LiteralPath $link -Force -ErrorAction SilentlyContinue
    if ($null -eq $item) { return $null }
    if (-not ($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint))
    {
        throw "Active shaders path is not a reparse point: $link"
    }
    $target = $item.Target
    if ($target -is [array]) { $target = $target[0] }
    return [System.IO.Path]::GetFullPath([string] $target)
}

function Resolve-IrisOwnedArtifact
{
    param([Parameter(Mandatory)] [object] $Scope, [Parameter(Mandatory)] [string] $Path)

    $candidate = if ([System.IO.Path]::IsPathRooted($Path)) {
        [System.IO.Path]::GetFullPath($Path)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $Scope.ArtifactRoot $Path))
    }
    $prefix = $Scope.ArtifactRoot.TrimEnd('\', '/') + [System.IO.Path]::DirectorySeparatorChar
    if (-not $candidate.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "Artifact path escapes this criterion's artifact root: $candidate"
    }
    return $candidate
}

function Merge-IrisProbeFailure
{
    param([Exception] $Primary, [Parameter(Mandatory)] [Exception] $Additional)

    if ($null -eq $Primary) { return $Additional }
    return [System.AggregateException]::new($Primary, $Additional)
}

function Remove-IrisProbeScope
{
    param([Parameter(Mandatory)] [object] $Scope)

    Assert-IrisProtectedProcesses -Snapshot $Scope.Protected
    if (Test-CorePort -Port $script:IrisPort)
    {
        throw "Cleanup left listener 127.0.0.1:$script:IrisPort open."
    }
    $child = ".omo\tmp\ulw-v1-g005-$($Scope.Criterion.ToLowerInvariant())"
    $expected = [System.IO.Path]::GetFullPath((Join-Path $script:VibrisRoot $child))
    if (-not [string]::Equals($Scope.Root, $expected, [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "Refusing to remove unexpected criterion root: $($Scope.Root)"
    }
    if (Test-Path -LiteralPath $Scope.Root)
    {
        if ((Get-Item -LiteralPath $Scope.Root -Force).Attributes -band
            [System.IO.FileAttributes]::ReparsePoint)
        {
            throw "Refusing to recurse through a criterion-root reparse point."
        }
        Remove-Item -LiteralPath $Scope.Root -Recurse -Force
    }
}
