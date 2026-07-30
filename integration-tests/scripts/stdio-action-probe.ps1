[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Exe,
    [Parameter(Mandatory)] [string] $WorkspaceRoot,
    [Parameter(Mandatory)] [string] $Requests,
    [ValidateRange(1, 600)] [int] $TimeoutSeconds = 180
)

. (Join-Path $PSScriptRoot "stdio-job-probe-common.ps1")

function Get-OwnedTreeSnapshot
{
    param([Parameter(Mandatory)] [string] $Root)

    if (-not (Test-Path -LiteralPath $Root -PathType Container)) { return "" }
    return [string]::Join("`n", @(Get-ChildItem -LiteralPath $Root -Recurse -Force |
        ForEach-Object { $_.FullName.Substring($Root.Length).TrimStart('\', '/') } | Sort-Object))
}

function Assert-Rejected
{
    param([Parameter(Mandatory)] [object] $Response)

    if ($null -ne $Response.PSObject.Properties["error"]) { return }
    if ($null -ne $Response.result.PSObject.Properties["isError"] -and $Response.result.isError -eq $true)
    {
        return
    }
    throw "Forbidden request $($Response.id) was not rejected."
}

$scope = $null
$failure = $null
$summary = $null
try
{
    $exePath = Resolve-IrisArtifact -Path $Exe -Label "Release native MCP"
    $messages = Read-G007Messages -Path $Requests
    if ($messages.Count -ne 10) { throw "G007-C003 fixture must contain ten frozen messages." }
    $scope = New-G007ProbeScope -Criterion "c003" -WorkspaceRoot $WorkspaceRoot
    [void] (Initialize-G007Workspace -Scope $scope)
    [void] (Start-G007Runtime -Scope $scope -Scenario "g007-c003" -TimeoutSeconds $TimeoutSeconds)

    $allowedMessages = @($messages | Where-Object { $_.id -in @(1, 2, 3, 4, 5) })
    $allowedResponses = Invoke-G007Mcp -Exe $exePath -WorkspaceRoot $scope.WorkspaceRoot `
        -Messages $allowedMessages -TimeoutSeconds $TimeoutSeconds
    $tools = @((Get-G007Response -Responses $allowedResponses -Id 2).result.tools |
        ForEach-Object { $_.name })
    $expectedTools = @(
        "vibris_get_config", "vibris_list_presets", "vibris_configure",
        "vibris_get_status", "vibris_profile", "vibris_run_recipe", "vibris_run_actions"
    )
    if ([string]::Join("`n", $tools) -cne [string]::Join("`n", $expectedTools) -or
        @($tools | Where-Object { $_ -match '(?i)atomic|submit|poll|wait' }).Count -ne 0)
    {
        throw "tools/list did not remain the expected 7-tool surface."
    }
    $empty = Get-G007ToolPayload (Get-G007Response -Responses $allowedResponses -Id 4)
    $allowed = Get-G007ToolPayload (Get-G007Response -Responses $allowedResponses -Id 5)
    Assert-G007CompletedResult -Scope $scope -Payload $empty -ArtifactsOptional
    Assert-G007CompletedResult -Scope $scope -Payload $allowed
    if ($empty.kind -cne "action_sequence" -or @($empty.artifacts).Count -ne 0 -or
        $allowed.kind -cne "action_sequence" -or @($allowed.frame_ids).Count -ne 1)
    {
        throw "Empty or allowed action sequence returned the wrong terminal shape."
    }
    $actionKinds = @($allowed.action_results | ForEach-Object { $_.kind })
    $expectedActionKinds = @(
        "get_shader_status", "get_shader_errors", "get_gpu_metrics",
        "list_textures", "list_ssbos", "list_patched_shaders"
    )
    if ([string]::Join("`n", $actionKinds) -cne [string]::Join("`n", $expectedActionKinds))
    {
        throw "Runtime action results were missing or out of order."
    }

    $pendingBefore = Get-OwnedTreeSnapshot -Root $scope.PendingRoot
    $artifactsBefore = Get-OwnedTreeSnapshot -Root $scope.ArtifactRoot
    $forbiddenMessages = @($messages[0]) + @($messages | Where-Object { $_.id -in @(6, 7, 8, 9, 10) })
    $forbiddenResponses = Invoke-G007Mcp -Exe $exePath -WorkspaceRoot $scope.WorkspaceRoot `
        -Messages $forbiddenMessages -TimeoutSeconds $TimeoutSeconds
    foreach ($id in 6..10)
    {
        Assert-Rejected -Response (Get-G007Response -Responses $forbiddenResponses -Id $id)
    }
    if ((Get-OwnedTreeSnapshot -Root $scope.PendingRoot) -cne $pendingBefore -or
        (Get-OwnedTreeSnapshot -Root $scope.ArtifactRoot) -cne $artifactsBefore)
    {
        throw "Forbidden action input reached source preparation or artifact creation."
    }
    $runShell = @($messages | Where-Object { $_.id -eq 6 })[0]
    if ($runShell.params.arguments.actions[0].command -cne "cmd /c whoami")
    {
        throw "Frozen run_shell adversarial command changed."
    }
    $summary = "PASS criterion=G007-C003 tools=7 empty_actions=true allowed_job=true " +
        "forbidden_before_prepare=5 invalid_metrics=true duplicate_selector=true traversal_rejected=true " +
        "absolute_path_rejected=true atomic_tools=false"
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
Write-Output "CLEANUP criterion=G007-C003 listener_closed=true root_removed=true multimc_untouched=true"
