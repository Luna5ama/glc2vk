[CmdletBinding()]
param(
    [string] $McpExe,
    [string] $IrisJar,
    [string] $OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
$irisRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "..\Iris"))
$buildRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "build"))
if (-not $McpExe) { $McpExe = Join-Path $repoRoot "mcp\out\build\Release\vibris-mcp.exe" }
if (-not $OutputDirectory) { $OutputDirectory = Join-Path $buildRoot "delivery" }
if (-not $IrisJar)
{
    $IrisJar = Get-ChildItem -LiteralPath (Join-Path $irisRoot "build\libs") `
        -Filter "iris-fabric-*-local.jar" -File |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}

$McpExe = [System.IO.Path]::GetFullPath($McpExe)
$IrisJar = [System.IO.Path]::GetFullPath($IrisJar)
$OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$buildPrefix = $buildRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) +
    [System.IO.Path]::DirectorySeparatorChar
if (-not $OutputDirectory.StartsWith($buildPrefix, [System.StringComparison]::OrdinalIgnoreCase))
{
    throw "OutputDirectory must be below $buildRoot."
}
if (-not (Test-Path -LiteralPath $McpExe -PathType Leaf)) { throw "Missing MCP executable: $McpExe" }
if (-not (Test-Path -LiteralPath $IrisJar -PathType Leaf)) { throw "Missing patched Iris JAR: $IrisJar" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [System.IO.Compression.ZipFile]::OpenRead($IrisJar)
try
{
    $entries = @($archive.Entries | ForEach-Object FullName)
    foreach ($pattern in @(
        "META-INF/jars/vibris-api-*.jar",
        "META-INF/jars/vibris-core-*.jar",
        "META-INF/jars/vibris-protocol-java.jar",
        "META-INF/jars/grpc-*.jar"))
    {
        if (@($entries | Where-Object { $_ -like $pattern }).Count -eq 0)
        {
            throw "Patched Iris JAR is missing embedded dependency $pattern."
        }
    }
}
finally
{
    $archive.Dispose()
}

$stage = Join-Path $buildRoot (".delivery-" + [guid]::NewGuid().ToString("N"))
[void] (New-Item -ItemType Directory -Path $stage)
try
{
    Copy-Item -LiteralPath $McpExe -Destination (Join-Path $stage "vibris-mcp.exe")
    Copy-Item -LiteralPath $IrisJar -Destination (Join-Path $stage (Split-Path -Leaf $IrisJar))
    if (@(Get-ChildItem -LiteralPath $stage -File).Count -ne 2) { throw "Delivery staging is not exact." }
    if (Test-Path -LiteralPath $OutputDirectory)
    {
        Remove-Item -LiteralPath $OutputDirectory -Recurse -Force
    }
    Move-Item -LiteralPath $stage -Destination $OutputDirectory
    $stage = $null
}
finally
{
    if ($stage -and (Test-Path -LiteralPath $stage))
    {
        Remove-Item -LiteralPath $stage -Recurse -Force
    }
}

$files = @(Get-ChildItem -LiteralPath $OutputDirectory -File | Sort-Object Name)
Write-Output "PASS delivery_files=$($files.Count) output=$OutputDirectory"
foreach ($file in $files) { Write-Output "$($file.Name) $($file.Length)" }