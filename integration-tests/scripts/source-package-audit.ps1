[CmdletBinding()]
param(
    [string] $McpExe = (Join-Path $PSScriptRoot "..\..\mcp\out\build\Release\vibris-mcp.exe"),
    [string] $PatchedJar = (Join-Path $PSScriptRoot "..\..\..\Iris\build\libs\iris-fabric-*-local.jar")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "iris-probe-common.ps1")

function Assert-AuditPatternAbsent
{
    param(
        [Parameter(Mandatory)] [System.IO.FileInfo[]] $Files,
        [Parameter(Mandatory)] [string] $Pattern,
        [Parameter(Mandatory)] [string] $Label
    )

    $matches = @($Files | Select-String -Pattern $Pattern)
    if ($matches.Count -ne 0)
    {
        $locations = @($matches | Select-Object -First 5 | ForEach-Object {
            "$($_.Path):$($_.LineNumber)"
        })
        throw "$Label found at $([string]::Join(', ', $locations))."
    }
}

$productionRoots = @(
    (Join-Path $script:VibrisRoot "api\src\main"),
    (Join-Path $script:VibrisRoot "core\src\main"),
    (Join-Path $script:VibrisRoot "mcp\src\main\cpp"),
    (Join-Path $script:VibrisRoot "protocol-java\src\main")
)
$productionFiles = @($productionRoots | Where-Object { Test-Path -LiteralPath $_ } | ForEach-Object {
    Get-ChildItem -LiteralPath $_ -Recurse -File
})
Assert-AuditPatternAbsent -Files $productionFiles `
    -Pattern '(?i)\b(?:https?|wss?)://|\bHttpClient\b|\bHttpServer\b|\bWebSocket\b' `
    -Label "Production HTTP/WebSocket transport"
Assert-AuditPatternAbsent -Files $productionFiles -Pattern '(?i)renderdoc' `
    -Label "Production RenderDoc dependency"

$irisRuntimeFiles = @(@("common", "fabric", "neoforge") | ForEach-Object {
    Get-ChildItem -LiteralPath (Join-Path $script:IrisRoot "$_\src\main") -Recurse -File
})
$irisRuntimeFiles += @("common", "fabric", "neoforge") | ForEach-Object {
    Get-Item -LiteralPath (Join-Path $script:IrisRoot "$_\build.gradle.kts")
}
Assert-AuditPatternAbsent -Files $irisRuntimeFiles `
    -Pattern '(?i)LD_PRELOAD[^\r\n]*renderdoc|[/\\][^\s"'']*renderdoc' `
    -Label "Iris RenderDoc path"

$trackedMainPaths = @(& git -C $script:VibrisRoot ls-files -- "*/src/main/**")
if ($LASTEXITCODE -ne 0)
{
    throw "Failed to enumerate tracked Vibris production sources."
}
$trackedMainFiles = @($trackedMainPaths | ForEach-Object {
    $path = Join-Path $script:VibrisRoot $_
    if (Test-Path -LiteralPath $path -PathType Leaf) { Get-Item -LiteralPath $path }
})
$trackedJava = @($trackedMainFiles | Where-Object { $_.Extension -ieq ".java" })
if ($trackedJava.Count -ne 0)
{
    throw "Tracked handwritten JVM production source must be Kotlin: $($trackedJava[0].FullName)"
}

$mcpJvmSources = @(Get-ChildItem -LiteralPath (Join-Path $script:VibrisRoot "mcp\src\main") `
    -Recurse -File | Where-Object { $_.Extension -iin @(".java", ".kt") })
if ($mcpJvmSources.Count -ne 0)
{
    throw "Native MCP production source must remain C++: $($mcpJvmSources[0].FullName)"
}

$coreKotlin = @(Get-ChildItem -LiteralPath (Join-Path $script:VibrisRoot "core\src\main") `
    -Recurse -File -Filter "*.kt")
Assert-AuditPatternAbsent -Files $coreKotlin `
    -Pattern '(?m)^\s*import\s+(?:net\.irisshaders|org\.eclipse\.jgit)\.' `
    -Label "Kotlin core Git/Iris import"

$protoPath = Join-Path $script:VibrisRoot "proto\vibris_control.proto"
$proto = Get-Content -Raw -LiteralPath $protoPath
if ($proto -match '(?m)^\s*bytes\s+\w+\s*=')
{
    throw "Protocol must transfer prepared-source references, not source byte fields: $protoPath"
}
foreach ($requiredField in @("string uuid", "uint64 file_count", "uint64 total_bytes"))
{
    if (-not $proto.Contains($requiredField))
    {
        throw "PreparedSourceRef is missing '$requiredField'."
    }
}

$toolSource = (@(
    "mcp\src\main\cpp\tool_registry.cpp",
    "mcp\src\main\cpp\debug_tools.cpp"
) | ForEach-Object {
    Get-Content -Raw -LiteralPath (Join-Path $script:VibrisRoot $_)
}) -join "`n"
$tools = @([regex]::Matches($toolSource, 'definition\("(vibris_[^"]+)"') | ForEach-Object {
    $_.Groups[1].Value
})
$expectedTools = @(
    "vibris_get_config",
    "vibris_list_presets",
    "vibris_configure",
    "vibris_get_status",
    "vibris_profile",
    "vibris_run_recipe",
    "vibris_run_actions",
    "vibris_get_capture_status",
    "vibris_reload_shader",
    "vibris_capture_pass",
    "vibris_capture_multi",
    "vibris_get_shader_status",
    "vibris_get_shader_errors",
    "vibris_schedule_screenshot",
    "vibris_get_screenshot_result",
    "vibris_get_gpu_metrics",
    "vibris_list_ssbos",
    "vibris_dump_ssbo",
    "vibris_list_textures",
    "vibris_dump_texture",
    "vibris_list_patched_shaders"
)
if ([string]::Join("`n", $tools) -cne [string]::Join("`n", $expectedTools) -or
    @($tools | Select-Object -Unique).Count -ne $expectedTools.Count)
{
    throw "Native MCP tool registry does not expose exactly the expected 21-tool surface."
}

$mixinRoots = @("common", "fabric", "neoforge") | ForEach-Object {
    Join-Path $script:IrisRoot "$_\src\main\java"
}
$vibrisMixins = @($mixinRoots | Where-Object { Test-Path -LiteralPath $_ } | ForEach-Object {
    Get-ChildItem -LiteralPath $_ -Recurse -File | Where-Object {
        $_.FullName -match '[\\/]mixin[\\/]' -and $_.Name -match '(?i)vibris'
    }
})
if ($vibrisMixins.Count -ne 0)
{
    throw "Iris contains a Vibris-specific mixin: $($vibrisMixins[0].FullName)"
}

$exe = Resolve-IrisArtifact -Path $McpExe -Label "Release native MCP"
if ((Split-Path -Leaf $exe) -cne "vibris-mcp.exe")
{
    throw "Native package executable must be named vibris-mcp.exe: $exe"
}
$jar = Resolve-IrisPatchedJar -Path $PatchedJar
$extraModJars = @(Get-ChildItem -LiteralPath (Split-Path -Parent $jar) -File -Filter "vibris*.jar")
if ($extraModJars.Count -ne 0)
{
    throw "Packaging must not emit a separate Vibris mod JAR: $($extraModJars[0].FullName)"
}

Write-Output ("PASS source_audit=true transport=grpc source_payload=reference tools=21 " +
    "jvm_language=kotlin native_mcp=cpp core_iris_jgit_imports=0 vibris_mixins=0 " +
    "renderdoc_dependencies=0 package_exe=1 package_iris_jar=1 extra_mod_jars=0")
