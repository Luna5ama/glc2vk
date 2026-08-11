[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Exe,
    [Parameter(Mandatory)] [string] $WorkspaceRoot,
    [Parameter(Mandatory)] [string] $Requests,
    [ValidateRange(1, 600)] [int] $TimeoutSeconds = 180
)

. (Join-Path $PSScriptRoot "stdio-job-probe-common.ps1")

$scope = $null
$failure = $null
$summary = $null
try
{
    $exePath = Resolve-IrisArtifact -Path $Exe -Label "Release native MCP"
    $messages = Read-G007Messages -Path $Requests
    if ($messages.Count -ne 3 -or @($messages | Where-Object {
            $_.method -in @("vibris_submit", "vibris_get_result", "vibris_wait")
        }).Count -ne 0)
    {
        throw "G007-C001 fixture must contain initialize and exactly two request-scoped recipe calls only."
    }
    $scope = New-G007ProbeScope -Criterion "c001" -WorkspaceRoot $WorkspaceRoot
    [void] (Initialize-G007Workspace -Scope $scope)
    [void] (Start-G007Runtime -Scope $scope -Scenario "g007-c001" -TimeoutSeconds $TimeoutSeconds)

    $responses = Invoke-G007Mcp -Exe $exePath -WorkspaceRoot $scope.WorkspaceRoot `
        -Messages $messages -TimeoutSeconds $TimeoutSeconds
    $reload = Get-G007ToolPayload (Get-G007Response -Responses $responses -Id 3)
    $bundle = Get-G007ToolPayload (Get-G007Response -Responses $responses -Id 4)
    Assert-G007CompletedResult -Scope $scope -Payload $reload
    Assert-G007CompletedResult -Scope $scope -Payload $bundle
    if ($reload.kind -cne "load_and_screenshot" -or @($reload.frame_ids).Count -ne 1)
    {
        throw "load_and_screenshot did not return one final screenshot frame."
    }
    $names = @(@($bundle.artifacts | ForEach-Object { $_.file_name }) +
        @($bundle.artifact_groups | ForEach-Object { @($_.artifacts | ForEach-Object { $_.file_name }) }))
    $expected = @("screenshot.png", "colortex0.main.bin", "depthtex0.bin", "iris_ssbo_6.bin",
        "shader.log", "manifest.json")
    foreach ($name in $expected)
    {
        if ($names -cnotcontains $name) { throw "capture_debug_bundle omitted $name." }
    }
    if ($bundle.kind -cne "capture_debug_bundle" -or @($bundle.frame_ids).Count -ne 1)
    {
        throw "capture_debug_bundle did not return one same-frame bundle."
    }
    $summary = "PASS criterion=G007-C001 recipes=2 synchronous_results=2 diagnostics=true " +
        "timings=true frame_ids=true readable_artifacts=true manifests=true atomic_tools=false"
}
catch
{
    $failure = $_.Exception
}
finally
{
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
Write-Output "CLEANUP criterion=G007-C001 listener_closed=true root_removed=true multimc_untouched=true"
