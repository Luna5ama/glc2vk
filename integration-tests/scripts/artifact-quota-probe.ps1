[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $ArtifactRoot,
    [long] $QuotaBytes = 3221225472,
    [switch] $UseSparseFiles,
    [string] $Scenario = "integration-tests/fixtures/artifacts/lru-pressure.json"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$expectedRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ".omo\tmp\ulw-v1-g006-c003\artifacts"))
$expectedScenario = [System.IO.Path]::GetFullPath((Join-Path $repoRoot `
    "integration-tests\fixtures\artifacts\lru-pressure.json"))
$resolvedRoot = if ([System.IO.Path]::IsPathRooted($ArtifactRoot)) {
    [System.IO.Path]::GetFullPath($ArtifactRoot)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repoRoot $ArtifactRoot))
}
$resolvedScenario = if ([System.IO.Path]::IsPathRooted($Scenario)) {
    [System.IO.Path]::GetFullPath($Scenario)
} else {
    [System.IO.Path]::GetFullPath((Join-Path $repoRoot $Scenario))
}
$failure = $null
$summary = $null
$locationPushed = $false
$previousProbeRoot = [System.Environment]::GetEnvironmentVariable("VIBRIS_ARTIFACT_PROBE_ROOT", "Process")

function Remove-ArtifactProbeRoot
{
    if (-not [string]::Equals($resolvedRoot, $expectedRoot,
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "Refusing to delete artifact root outside G006-C003 scope: $resolvedRoot"
    }
    if (Test-Path -LiteralPath $resolvedRoot)
    {
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    }
}

try
{
    if (-not [string]::Equals($resolvedRoot, $expectedRoot,
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "G006-C003 requires artifact root $expectedRoot."
    }
    if (-not [string]::Equals($resolvedScenario, $expectedScenario,
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "G006-C003 requires scenario $expectedScenario."
    }
    if ($QuotaBytes -ne 3221225472)
    {
        throw "G006-C003 requires the default 3 GiB quota."
    }
    if (-not $UseSparseFiles)
    {
        throw "G006-C003 requires -UseSparseFiles."
    }
    if (-not (Test-Path -LiteralPath $resolvedScenario -PathType Leaf))
    {
        throw "G006-C003 scenario is missing: $resolvedScenario"
    }
    $scenarioData = Get-Content -LiteralPath $resolvedScenario -Raw | ConvertFrom-Json
    $jobs = @($scenarioData.sparseJobs)
    $requiredAssertions = @(
        "startup_tmp_cleanup",
        "whole_job_lru",
        "active_finalizing_unreported_protection",
        "oversized_typed_failure",
        "terminal_delivery_after_rename",
        "failed_delivery_ttl_expiry"
    )
    if ([int] $scenarioData.schemaVersion -ne 1 -or $scenarioData.criterion -ne "G006-C003" -or
        [long] $scenarioData.quotaBytes -ne $QuotaBytes -or
        [long] $scenarioData.unreportedTtlSeconds -ne 600 -or $jobs.Count -ne 2 -or
        $jobs[0].requestId -ne "oldest" -or [long] $jobs[0].payloadBytes -ne 2147483648 -or
        [long] $jobs[0].manifestTimestampMillis -ne 1000 -or -not [bool] $jobs[0].reported -or
        $jobs[0].expectedAfterRecovery -ne "evicted" -or
        $jobs[1].requestId -ne "newest" -or [long] $jobs[1].payloadBytes -ne 1610612736 -or
        [long] $jobs[1].manifestTimestampMillis -ne 2000 -or -not [bool] $jobs[1].reported -or
        $jobs[1].expectedAfterRecovery -ne "retained")
    {
        throw "G006-C003 scenario contents do not match the frozen LRU pressure contract."
    }
    foreach ($assertion in $requiredAssertions)
    {
        if (@($scenarioData.requiredAssertions) -notcontains $assertion)
        {
            throw "G006-C003 scenario is missing required assertion: $assertion"
        }
    }

    Remove-ArtifactProbeRoot
    [System.Environment]::SetEnvironmentVariable("VIBRIS_ARTIFACT_PROBE_ROOT", $resolvedRoot, "Process")
    Push-Location $repoRoot
    $locationPushed = $true
    & (Join-Path $repoRoot "gradlew.bat") ":vibris-core:test" ":vibris-integration-tests:test" `
        "--rerun-tasks" `
        "--tests" "dev.vibris.core.ArtifactTerminalDeliveryTest" `
        "--tests" "dev.vibris.integration.ArtifactQuotaTest.realSparseDirectoriesWholeJobLru"
    if ($LASTEXITCODE -ne 0)
    {
        throw "Artifact quota integration test failed with exit code $LASTEXITCODE."
    }
    $summary = "PASS criterion=G006-C003 quota_bytes=$QuotaBytes sparse=true startup_tmp_clean=true " +
        "whole_job_lru=true protected_jobs=true oversized_typed=true result_after_rename=true " +
        "failed_delivery_ttl=true scenario_validated=true"
}
catch
{
    $failure = $_.Exception.Message
}
finally
{
    if ($locationPushed)
    {
        Pop-Location
    }
    [System.Environment]::SetEnvironmentVariable(
        "VIBRIS_ARTIFACT_PROBE_ROOT", $previousProbeRoot, "Process")
    try
    {
        Remove-ArtifactProbeRoot
    }
    catch
    {
        $cleanupFailure = $_.Exception.Message
        $failure = if ($null -eq $failure) { $cleanupFailure } else { "$failure; cleanup: $cleanupFailure" }
    }
}

$cleanup = "CLEANUP criterion=G006-C003 root_removed=$(-not (Test-Path -LiteralPath $expectedRoot))"
if ($null -ne $failure)
{
    Write-Output ("FAIL criterion=G006-C003 reason=" + ($failure -replace '[\r\n]+', ' '))
    Write-Output $cleanup
    exit 1
}
Write-Output $summary
Write-Output $cleanup