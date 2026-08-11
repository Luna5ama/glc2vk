Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "iris-probe-common.ps1")
. (Join-Path $PSScriptRoot "..\..\tools\git-process.ps1")

function Resolve-G007PatchedJar
{
    $pattern = Join-Path $script:IrisRoot "build\libs\iris-fabric-*-local.jar"
    return Resolve-IrisPatchedJar -Path $pattern
}

function New-G007ProbeScope
{
    param(
        [Parameter(Mandatory)] [ValidateSet("c001", "c002", "c003")] [string] $Criterion,
        [Parameter(Mandatory)] [string] $WorkspaceRoot
    )

    $workspace = [System.IO.Path]::GetFullPath($WorkspaceRoot)
    $root = [System.IO.Path]::GetFullPath((Join-Path $script:VibrisRoot ".omo\tmp\ulw-v1-g007-$Criterion"))
    $expected = Join-Path $root "worktree"
    if (-not [string]::Equals($workspace, $expected, [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "G007-$($Criterion.ToUpperInvariant()) requires WorkspaceRoot $expected."
    }
    $scope = New-IrisProbeScope -Criterion $Criterion -Gate "G007" -GameDir (Join-Path $root "game")
    [void] (New-Item -ItemType Directory -Path $workspace)
    $scope | Add-Member -NotePropertyName WorkspaceRoot -NotePropertyValue $workspace
    return $scope
}

function Invoke-G007Git
{
    param(
        [Parameter(Mandatory)] [string] $WorkspaceRoot,
        [Parameter(Mandatory)] [string[]] $GitArguments
    )

    $text = Invoke-TrustedGitText -Root $WorkspaceRoot -Arguments $GitArguments `
        -Label "G007 Git $([string]::Join(' ', $GitArguments))"
    return @($text -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
}

function Initialize-G007Workspace
{
    param([Parameter(Mandatory)] [object] $Scope)

    $source = Join-Path $script:VibrisRoot `
        "integration-tests\fixtures\shaderpacks\capture-known-resources\shaders"
    [void] (Invoke-G007Git -WorkspaceRoot $Scope.WorkspaceRoot -GitArguments @("init", "--quiet"))
    Copy-Item -LiteralPath $source -Destination (Join-Path $Scope.WorkspaceRoot "shaders") -Recurse
    [void] (Invoke-G007Git -WorkspaceRoot $Scope.WorkspaceRoot -GitArguments @("add", "--", "shaders"))
    [void] (Invoke-G007Git -WorkspaceRoot $Scope.WorkspaceRoot -GitArguments @(
        "-c", "user.name=Vibris Probe", "-c", "user.email=vibris@example.invalid",
        "commit", "--quiet", "-m", "baseline"
    ))
    $revision = @(Invoke-G007Git -WorkspaceRoot $Scope.WorkspaceRoot -GitArguments @("rev-parse", "HEAD"))
    return [string] $revision[0]
}

function Set-G007WorkspaceCandidate
{
    param([Parameter(Mandatory)] [object] $Scope)

    $marker = Join-Path $Scope.WorkspaceRoot "shaders\lib\vibris_fixture.glsl"
    [System.IO.File]::WriteAllText($marker, "#define VIBRIS_FIXTURE_COLOR vec4(0.75, 0.25, 0.5, 1.0)`r`n")
    $statusLines = @(Invoke-G007Git -WorkspaceRoot $Scope.WorkspaceRoot `
        -GitArguments @("status", "--short", "--", "shaders"))
    $status = [string] $statusLines[0]
    if ([string]::IsNullOrWhiteSpace($status)) { throw "A/B candidate did not differ from baseline commit." }
}

function Start-G007Runtime
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [Parameter(Mandatory)] [string] $Scenario,
        [ValidateRange(1, 600)] [int] $TimeoutSeconds = 180
    )

    $contextPath = Join-Path $script:VibrisRoot `
        "integration-tests\fixtures\context\overworld-sunset-rooftop.json"
    $context = Get-Content -Raw -LiteralPath $contextPath | ConvertFrom-Json
    [void] (Write-IrisPresetCatalog -Scope $Scope -Context $context)
    Start-IrisPackagedClient -Scope $Scope -PatchedJar (Resolve-G007PatchedJar) `
        -Scenario $Scenario -TimeoutSeconds $TimeoutSeconds
    Wait-IrisWorldReady -Scope $Scope -TimeoutSeconds $TimeoutSeconds
    return $context
}

function Start-G007Mcp
{
    param(
        [Parameter(Mandatory)] [string] $Exe,
        [Parameter(Mandatory)] [string] $WorkspaceRoot,
        [Parameter(Mandatory)] [object[]] $Messages
    )

    foreach ($message in @($Messages | Where-Object { $_.method -ceq "tools/call" }))
    {
        $message.params.arguments | Add-Member -NotePropertyName "worktree_root" `
            -NotePropertyValue ([System.IO.Path]::GetFullPath($WorkspaceRoot)) -Force
        $name = [string] $message.params.name
        $isControl = $name -ceq "vibris_run_recipe" -and
            $null -ne $message.params.arguments.PSObject.Properties["operation"]
        if (-not $isControl -and $name -in @("vibris_run_recipe", "vibris_run_actions", "vibris_run_matrix"))
        {
            if ($null -eq $message.params.arguments.PSObject.Properties["preset_id"])
            {
                $message.params.arguments | Add-Member -NotePropertyName "preset_id" `
                    -NotePropertyValue "automation" -Force
            }
        }
    }

    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = [System.IO.Path]::GetFullPath($Exe)
    $arguments = @("--server-address", "127.0.0.1:$script:IrisPort")
    $start.Arguments = [string]::Join(" ", @($arguments | ForEach-Object { ConvertTo-CoreArgument $_ }))
    $start.WorkingDirectory = [System.IO.Path]::GetFullPath($WorkspaceRoot)
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
        Count = $Messages.Count
    }
    foreach ($message in $Messages)
    {
        $process.StandardInput.WriteLine(($message | ConvertTo-Json -Compress -Depth 30))
    }
    $process.StandardInput.Close()
    return $entry
}

function Complete-G007Mcp
{
    param(
        [Parameter(Mandatory)] [object] $Entry,
        [ValidateRange(1, 600)] [int] $TimeoutSeconds = 180
    )

    if (-not $Entry.Process.WaitForExit($TimeoutSeconds * 1000))
    {
        throw "Native MCP timed out after $TimeoutSeconds seconds (PID $($Entry.Process.Id))."
    }
    $Entry.Process.WaitForExit()
    $stdout = $Entry.Output.Result
    $stderr = $Entry.Error.Result
    if ($Entry.Process.ExitCode -ne 0) { throw "Native MCP exited $($Entry.Process.ExitCode): $stderr" }
    $lines = @($stdout -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($lines.Count -ne $Entry.Count)
    {
        throw "Expected $($Entry.Count) JSON-RPC responses, received $($lines.Count): $stdout"
    }
    return @($lines | ForEach-Object { $_ | ConvertFrom-Json })
}

function Invoke-G007Mcp
{
    param(
        [Parameter(Mandatory)] [string] $Exe,
        [Parameter(Mandatory)] [string] $WorkspaceRoot,
        [Parameter(Mandatory)] [object[]] $Messages,
        [ValidateRange(1, 600)] [int] $TimeoutSeconds = 180
    )

    $entry = Start-G007Mcp -Exe $Exe -WorkspaceRoot $WorkspaceRoot -Messages $Messages
    try { return Complete-G007Mcp -Entry $entry -TimeoutSeconds $TimeoutSeconds }
    finally { $entry.Process.Dispose() }
}

function Stop-G007Mcp
{
    param([AllowNull()] [object] $Entry)

    if ($null -eq $Entry) { return }
    if (-not $Entry.Process.HasExited -and
        $Entry.Process.StartTime.ToUniversalTime().ToString("O") -ceq $Entry.Created)
    {
        Stop-Process -Id $Entry.Process.Id -Force
        [void] $Entry.Process.WaitForExit(5000)
    }
    $Entry.Process.Dispose()
}

function Get-G007Response
{
    param([Parameter(Mandatory)] [object[]] $Responses, [Parameter(Mandatory)] [int] $Id)

    $matches = @($Responses | Where-Object { $_.id -eq $Id })
    if ($matches.Count -ne 1) { throw "Expected exactly one JSON-RPC response with id $Id." }
    return $matches[0]
}

function Get-G007ToolPayload
{
    param([Parameter(Mandatory)] [object] $Response)

    if ($null -ne $Response.PSObject.Properties["error"])
    {
        throw "JSON-RPC request $($Response.id) failed: $($Response.error | ConvertTo-Json -Compress -Depth 20)"
    }
    if ($null -ne $Response.result.PSObject.Properties["structuredContent"])
    {
        return $Response.result.structuredContent
    }
    $content = @($Response.result.content)
    if ($content.Count -ne 1 -or $content[0].type -cne "text")
    {
        throw "Tool response $($Response.id) did not contain one text result."
    }
    return $content[0].text | ConvertFrom-Json
}

function Assert-G007CompletedResult
{
    param(
        [Parameter(Mandatory)] [object] $Scope,
        [Parameter(Mandatory)] [object] $Payload,
        [switch] $ArtifactsOptional
    )

    if ($Payload.success -ne $true -or $null -eq $Payload.PSObject.Properties["diagnostics"] -or
        $null -eq $Payload.PSObject.Properties["timings"] -or
        $null -eq $Payload.PSObject.Properties["frame_ids"])
    {
        throw "Synchronous result omitted success, diagnostics, timings, or frame IDs: " +
            ($Payload | ConvertTo-Json -Compress -Depth 20)
    }
    if ([long] $Payload.timings.completed_at_unix_ms -lt [long] $Payload.timings.started_at_unix_ms -or
        [long] $Payload.timings.total_ms -lt 0)
    {
        throw "Synchronous result timings are invalid."
    }
    $artifacts = @($Payload.artifacts)
    if (-not $ArtifactsOptional -and $artifacts.Count -eq 0) { throw "Completed result has no artifacts." }
    foreach ($artifact in $artifacts)
    {
        $path = Resolve-IrisOwnedArtifact -Scope $Scope -Path $artifact.path
        if (-not [System.IO.Path]::IsPathRooted([string] $artifact.path) -or
            -not (Test-Path -LiteralPath $path -PathType Leaf) -or
            (Get-Item -LiteralPath $path).Length -ne [long] $artifact.byte_size)
        {
            throw "Artifact path is unreadable or disagrees with metadata: $($artifact.path)"
        }
    }
    if ($artifacts.Count -eq 0)
    {
        if (-not [string]::IsNullOrEmpty([string] $Payload.manifest_path))
        {
            throw "Artifact-free result unexpectedly returned a manifest."
        }
        return
    }
    $manifest = Resolve-IrisOwnedArtifact -Scope $Scope -Path $Payload.manifest_path
    if (-not [System.IO.Path]::IsPathRooted([string] $Payload.manifest_path) -or
        -not (Test-Path -LiteralPath $manifest -PathType Leaf))
    {
        throw "Completed result manifest is not an absolute readable file."
    }
}

function Read-G007Messages
{
    param([Parameter(Mandatory)] [string] $Path)

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "Missing request fixture: $Path" }
    return @(Get-Content -LiteralPath $Path | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        ForEach-Object { $_ | ConvertFrom-Json })
}
