[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Proto,
    [Parameter(Mandatory)] [Alias("JavaDescriptor")] [string] $JavaJar,
    [Parameter(Mandatory)] [Alias("DescriptorExe")] [string] $CppDump,
    [int] $TimeoutSeconds = 15
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$process = $null
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$tempBase = Join-Path $repoRoot ".vibris\tmp"
$tempRoot = Join-Path $tempBase "vibris-v2-protocol-descriptor"
$tempCreated = $false

try
{
    if (-not (Test-Path -LiteralPath $Proto -PathType Leaf))
    {
        throw "Missing protocol schema: $Proto"
    }
    if (-not (Test-Path -LiteralPath $JavaJar -PathType Leaf))
    {
        throw "Missing Java protocol JAR: $JavaJar"
    }
    if (-not (Test-Path -LiteralPath $CppDump -PathType Leaf))
    {
        throw "Missing C++ descriptor dump executable: $CppDump"
    }

    $gradlew = Join-Path $repoRoot "gradlew.bat"
    $previousErrorAction = $ErrorActionPreference
    try
    {
        $ErrorActionPreference = "Continue"
        $gradleOutput = (& $gradlew --version 2>&1 | Out-String)
        $gradleExitCode = $LASTEXITCODE
    }
    finally
    {
        $ErrorActionPreference = $previousErrorAction
    }
    if ($gradleExitCode -ne 0 -or $gradleOutput -notmatch "(?m)^Gradle 9\.2\.1\r?$")
    {
        throw "Expected Gradle 9.2.1.`n$gradleOutput"
    }

    $protoText = Get-Content -Raw -LiteralPath $Proto
    if ($protoText -match "(?im)^\s*(?:(?:optional|repeated)\s+)?bytes\s+")
    {
        throw "Protocol schema contains a BYTES field."
    }
    if ($protoText -match "(?i)\babsolute_path\b")
    {
        throw "Protocol schema contains an absolute_path field."
    }

    [void] (New-Item -ItemType Directory -Path $tempBase -Force)
    if (Test-Path -LiteralPath $tempRoot)
    {
        throw "Scoped temp path already exists: $tempRoot"
    }
    [void] (New-Item -ItemType Directory -Path $tempRoot)
    $tempCreated = $true
    $javaDescriptor = Join-Path $tempRoot "java-descriptor.bin"
    $cppDescriptor = Join-Path $tempRoot "cpp-descriptor.bin"

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead((Resolve-Path -LiteralPath $JavaJar))
    try
    {
        $entry = $archive.GetEntry("META-INF/vibris/vibris_control.desc")
        if ($null -eq $entry)
        {
            throw "Java protocol JAR does not contain META-INF/vibris/vibris_control.desc."
        }
        $input = $entry.Open()
        $output = [System.IO.File]::Create($javaDescriptor)
        try
        {
            $input.CopyTo($output)
        }
        finally
        {
            $output.Dispose()
            $input.Dispose()
        }
    }
    finally
    {
        $archive.Dispose()
    }

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $CppDump
    $startInfo.Arguments = "--output `"$cppDescriptor`""
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void] $process.Start()
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($TimeoutSeconds * 1000))
    {
        throw "Native descriptor dump timed out after $TimeoutSeconds seconds (PID $($process.Id))."
    }
    $process.WaitForExit()
    $outputText = $stdoutTask.Result
    $errorText = $stderrTask.Result
    if (-not [string]::IsNullOrWhiteSpace($errorText))
    {
        throw "Native descriptor dump wrote unexpected stderr: $errorText"
    }
    if ($process.ExitCode -ne 0)
    {
        throw "Native descriptor dump exited $($process.ExitCode)."
    }
    if (-not (Test-Path -LiteralPath $cppDescriptor -PathType Leaf))
    {
        throw "C++ descriptor dump did not write $cppDescriptor."
    }

    $javaHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $javaDescriptor).Hash
    $cppHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $cppDescriptor).Hash
    if ($javaHash -ne $cppHash)
    {
        throw "Descriptor parity mismatch: Java=$javaHash C++=$cppHash"
    }
    Write-Output "PASS gradle=9.2.1 descriptor_sha256=$javaHash proto_no_bytes=true proto_no_absolute_path=true"
}
finally
{
    if ($null -ne $process)
    {
        if (-not $process.HasExited)
        {
            Stop-Process -Id $process.Id -Force
            [void] $process.WaitForExit(2000)
        }
        $process.Dispose()
    }
    if ($tempCreated -and (Test-Path -LiteralPath $tempRoot))
    {
        $resolvedTempRoot = [System.IO.Path]::GetFullPath($tempRoot)
        $resolvedTempBase = [System.IO.Path]::GetFullPath($tempBase).TrimEnd([System.IO.Path]::DirectorySeparatorChar)
        if (-not $resolvedTempRoot.StartsWith($resolvedTempBase + [System.IO.Path]::DirectorySeparatorChar, `
                [System.StringComparison]::OrdinalIgnoreCase))
        {
            throw "Refusing to clean temp path outside criterion temp root: $resolvedTempRoot"
        }
        Remove-Item -LiteralPath $resolvedTempRoot -Recurse -Force
    }
    Write-Output "CLEANUP owned_process=$($null -ne $process) temp_removed=$tempCreated"
}
