[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Exe,
    [Parameter(Mandatory)] [string] $FakeServerJar,
    [Parameter(Mandatory)] [string] $WorkspaceRoot,
    [int] $PingCount = 256,
    [int] $DropAfter = 64,
    [int] $TimeoutSeconds = 30
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ownedProcesses = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
$fixturePath = Join-Path $PSScriptRoot "..\fixtures\mcp\reconnect-g002-c003.json"
$fixture = Get-Content -Raw -LiteralPath $fixturePath | ConvertFrom-Json
$ports = @([int] $fixture.serverPort, [int] $fixture.reservedCleanupPort)
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$tempBase = Join-Path $repoRoot ".omo\tmp"
$tempRoot = Join-Path $tempBase "ulw-v1-g002-c003"
$expectedWorkspace = Join-Path $tempRoot "worktree"
$fakeServerRoot = Join-Path $tempRoot "fake-server"
$tempCreated = $false
$mcp = $null
$stderrTask = $null
$responses = [System.Collections.Generic.HashSet[int]]::new()

function Test-PortOpen
{
    param([int] $Port)

    $probe = [System.Net.Sockets.TcpClient]::new()
    try
    {
        $connect = $probe.ConnectAsync("127.0.0.1", $Port)
        return $connect.Wait(200) -and $probe.Connected
    }
    catch
    {
        return $false
    }
    finally
    {
        $probe.Dispose()
    }
}

function Wait-ForPort
{
    param([int] $Port, [System.Diagnostics.Process] $Process)

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([datetime]::UtcNow -lt $deadline)
    {
        if ($Process.HasExited)
        {
            throw "Fake server exited before listening on 127.0.0.1:$Port (exit $($Process.ExitCode))."
        }
        if (Test-PortOpen -Port $Port)
        {
            return
        }
        Start-Sleep -Milliseconds 50
    }
    throw "Fake server did not listen on 127.0.0.1:$Port within $TimeoutSeconds seconds."
}

function Wait-ForPortClosed
{
    param([int] $Port)

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([datetime]::UtcNow -lt $deadline)
    {
        if (-not (Test-PortOpen -Port $Port))
        {
            return
        }
        Start-Sleep -Milliseconds 50
    }
    throw "Listener on 127.0.0.1:$Port did not close within $TimeoutSeconds seconds."
}

function Start-FakeServer
{
    param([int] $Ordinal)

    $stdout = Join-Path $tempRoot "fake-server-$Ordinal.stdout"
    $stderr = Join-Path $tempRoot "fake-server-$Ordinal.stderr"
    $arguments = @("-jar", $FakeServerJar, "--port", $fixture.serverPort, "--work-root", $fakeServerRoot)
    $process = Start-Process -FilePath "java.exe" -ArgumentList $arguments -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput $stdout -RedirectStandardError $stderr
    $ownedProcesses.Add($process)
    Wait-ForPort -Port $fixture.serverPort -Process $process
    return $process
}

function Stop-FakeServer
{
    param([System.Diagnostics.Process] $Process)

    if (-not $Process.HasExited)
    {
        Stop-Process -Id $Process.Id -Force
        if (-not $Process.WaitForExit(2000))
        {
            throw "Fake server PID $($Process.Id) did not terminate."
        }
    }
    Wait-ForPortClosed -Port $fixture.serverPort
}

function Write-McpMessage
{
    param([hashtable] $Message)

    $mcp.StandardInput.WriteLine(($Message | ConvertTo-Json -Compress -Depth 8))
    $mcp.StandardInput.Flush()
}

function Read-McpResponse
{
    param([object] $ExpectedId)

    $lineTask = $mcp.StandardOutput.ReadLineAsync()
    if (-not $lineTask.Wait($TimeoutSeconds * 1000))
    {
        throw "MCP timed out waiting for JSON-RPC response ID '$ExpectedId'."
    }
    $line = $lineTask.Result
    if ($null -eq $line)
    {
        throw "MCP stdout closed before JSON-RPC response ID '$ExpectedId'."
    }
    $response = $line | ConvertFrom-Json
    if (-not ($response.PSObject.Properties.Name -contains "id") -or $response.id -ne $ExpectedId)
    {
        throw "MCP returned an unexpected JSON-RPC response while waiting for ID '$ExpectedId': $line"
    }
    if ($response.PSObject.Properties.Name -contains "error")
    {
        throw "MCP returned JSON-RPC error for ID '$ExpectedId': $($response.error | ConvertTo-Json -Compress)"
    }
    return $response
}

function Invoke-Status
{
    param([int] $Id)

    Write-McpMessage -Message @{
        jsonrpc = "2.0"
        id = $Id
        method = "tools/call"
        params = @{ name = "vibris_get_status"; arguments = @{} }
    }
    $response = Read-McpResponse -ExpectedId $Id
    if (-not $responses.Add($Id))
    {
        throw "JSON-RPC response ID '$Id' was resolved more than once."
    }
    $content = @($response.result.content)
    if ($content.Count -ne 1 -or $content[0].type -ne "text")
    {
        throw "Status response ID '$Id' did not contain exactly one text result."
    }
    $status = $content[0].text | ConvertFrom-Json
    if ($status.current_save_id -ne $fixture.expectedSaveId -or
        $status.current_dimension_id -ne $fixture.expectedDimensionId -or -not $status.runtime_ready)
    {
        throw "Status response ID '$Id' was not derived from the fake gRPC runtime."
    }
}

function Get-SummaryField
{
    param([string] $Text, [string] $Name)

    $matches = [regex]::Matches($Text, "(?m)(?:^|\s)$([regex]::Escape($Name))=([0-9]+)(?=\s|$)")
    if ($matches.Count -ne 1)
    {
        throw "MCP shutdown stderr must report '$Name' exactly once."
    }
    return [int64] $matches[0].Groups[1].Value
}

try
{
    if ($PingCount -ne 256 -or $DropAfter -ne 64)
    {
        throw "G002-C003 requires -PingCount 256 and -DropAfter 64."
    }
    foreach ($path in @($Exe, $FakeServerJar))
    {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf))
        {
            throw "Missing required executable artifact: $path"
        }
    }
    $resolvedWorkspace = [System.IO.Path]::GetFullPath($WorkspaceRoot)
    if (-not [string]::Equals($resolvedWorkspace.TrimEnd("\", "/"), $expectedWorkspace.TrimEnd("\", "/"),
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "WorkspaceRoot must be the scoped criterion worktree: $expectedWorkspace"
    }
    foreach ($port in $ports)
    {
        if (Test-PortOpen -Port $port)
        {
            throw "Criterion port 127.0.0.1:$port is already in use."
        }
    }
    [void] (New-Item -ItemType Directory -Path $tempBase -Force)
    if (Test-Path -LiteralPath $tempRoot)
    {
        throw "Scoped criterion root already exists: $tempRoot"
    }
    [void] (New-Item -ItemType Directory -Path $resolvedWorkspace -Force)
    [void] (New-Item -ItemType Directory -Path $fakeServerRoot)
    $tempCreated = $true
    $gitOutput = @(& git.exe -C $resolvedWorkspace init --quiet 2>&1)
    if ($LASTEXITCODE -ne 0)
    {
        throw "Unable to initialize scoped worktree: $($gitOutput -join [Environment]::NewLine)"
    }

    $server = Start-FakeServer -Ordinal 1
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Exe
    $startInfo.Arguments = "--workspace-root `"$resolvedWorkspace`" " +
        "--server-address 127.0.0.1:$($fixture.serverPort)"
    $startInfo.WorkingDirectory = $resolvedWorkspace
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $mcp = [System.Diagnostics.Process]::new()
    $mcp.StartInfo = $startInfo
    [void] $mcp.Start()
    $ownedProcesses.Add($mcp)
    $stderrTask = $mcp.StandardError.ReadToEndAsync()

    Write-McpMessage -Message @{
        jsonrpc = "2.0"
        id = "phase1-init"
        method = "initialize"
        params = @{
            protocolVersion = "2024-11-05"
            capabilities = @{}
            clientInfo = @{ name = "probe"; version = "1" }
        }
    }
    [void] (Read-McpResponse -ExpectedId "phase1-init")
    Write-McpMessage -Message @{ jsonrpc = "2.0"; method = "notifications/initialized"; params = @{} }

    for ($id = 1; $id -le $DropAfter; $id++)
    {
        Invoke-Status -Id $id
    }
    Stop-FakeServer -Process $server
    $server = Start-FakeServer -Ordinal 2
    for ($id = $DropAfter + 1; $id -le $PingCount; $id++)
    {
        Invoke-Status -Id $id
    }
    if ($responses.Count -ne $PingCount)
    {
        throw "Expected $PingCount unique JSON-RPC resolutions, observed $($responses.Count)."
    }

    $mcp.StandardInput.Close()
    if (-not $mcp.WaitForExit($TimeoutSeconds * 1000))
    {
        throw "MCP did not shut down after stdin closed (PID $($mcp.Id))."
    }
    $mcp.WaitForExit()
    $trailingOutput = $mcp.StandardOutput.ReadToEnd()
    $stderr = $stderrTask.Result
    if ($mcp.ExitCode -ne 0)
    {
        throw "MCP exited $($mcp.ExitCode): $stderr"
    }
    if (-not [string]::IsNullOrWhiteSpace($trailingOutput))
    {
        throw "MCP emitted unexpected stdout after the final JSON-RPC response: $trailingOutput"
    }
    $pendingPeak = Get-SummaryField -Text $stderr -Name "pending_peak"
    $pendingLimit = Get-SummaryField -Text $stderr -Name "pending_limit"
    $completionQueues = Get-SummaryField -Text $stderr -Name "completion_queues"
    $joinedWorkers = Get-SummaryField -Text $stderr -Name "worker_threads_joined"
    if ($pendingPeak -lt 1 -or $pendingPeak -gt $pendingLimit -or $pendingLimit -ne $fixture.registryLimit)
    {
        throw "Pending registry was not bounded: peak=$pendingPeak limit=$pendingLimit."
    }
    if ($completionQueues -ne 1 -or $joinedWorkers -ne 1)
    {
        throw "MCP did not shut down one CompletionQueue worker in joined state."
    }
    Write-Output ("PASS criterion=G002-C003 requests=$PingCount drop_after=$DropAfter " +
        "pending_peak=$pendingPeak pending_limit=$pendingLimit completion_queues=1 worker_threads_joined=1")
}
finally
{
    foreach ($process in @($ownedProcesses))
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
        $resolvedTemp = [System.IO.Path]::GetFullPath($tempRoot)
        $exactTemp = [System.IO.Path]::GetFullPath((Join-Path $repoRoot ".omo\tmp\ulw-v1-g002-c003"))
        if (-not [string]::Equals($resolvedTemp, $exactTemp, [System.StringComparison]::OrdinalIgnoreCase))
        {
            throw "Refusing to clean unexpected temp path: $resolvedTemp"
        }
        Remove-Item -LiteralPath $resolvedTemp -Recurse -Force
    }
    foreach ($port in $ports)
    {
        if (Test-PortOpen -Port $port)
        {
            throw "Cleanup left a listener on 127.0.0.1:$port."
        }
    }
    Write-Output ("CLEANUP owned_processes=$($ownedProcesses.Count) worktree_removed=true " +
        "ports=55063,55064 closed=true")
}