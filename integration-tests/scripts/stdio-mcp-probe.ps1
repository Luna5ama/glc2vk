[CmdletBinding()]
param(
    [Parameter(Mandatory)] [string] $Exe,
    [Parameter(Mandatory)] [string] $WorkspaceRoot,
    [Parameter(Mandatory)] [string] $FakeServerJar,
    [Parameter(Mandatory)] [string] $Requests,
    [int] $TimeoutSeconds = 15
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$ownedProcesses = [System.Collections.Generic.List[System.Diagnostics.Process]]::new()
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\.."))
$tempBase = Join-Path $repoRoot ".omo\tmp"
$tempRoot = Join-Path $tempBase "ulw-v1-g002-c001"
$tempCreated = $false
$port = 55061
$workspace = [System.IO.Path]::GetFullPath($WorkspaceRoot)
$configDirectory = Join-Path $workspace ".codex"
$configPath = Join-Path $configDirectory "vibris-session.json"
$configDirectoryExisted = Test-Path -LiteralPath $configDirectory -PathType Container
$configExisted = Test-Path -LiteralPath $configPath -PathType Leaf
$configBytes = if ($configExisted) { [System.IO.File]::ReadAllBytes($configPath) } else { $null }

function Test-PortOpen
{
    $client = [System.Net.Sockets.TcpClient]::new()
    try
    {
        $connect = $client.ConnectAsync("127.0.0.1", $port)
        return $connect.Wait(200) -and $client.Connected
    }
    catch
    {
        return $false
    }
    finally
    {
        $client.Dispose()
    }
}

function Wait-ForServer
{
    param([System.Diagnostics.Process] $Server)

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([datetime]::UtcNow -lt $deadline)
    {
        if ($Server.HasExited)
        {
            throw "Fake gRPC server exited before listening (exit $($Server.ExitCode))."
        }
        if (Test-PortOpen)
        {
            return
        }
        Start-Sleep -Milliseconds 50
    }
    throw "Fake gRPC server did not listen on 127.0.0.1:$port within $TimeoutSeconds seconds."
}

function ConvertTo-QuotedArgument
{
    param([string] $Value)

    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

function Initialize-Worktree
{
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = "git.exe"
    $startInfo.Arguments = "-C $(ConvertTo-QuotedArgument $workspace) init --quiet"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void] $process.Start()
    $ownedProcesses.Add($process)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    if (-not $process.WaitForExit($TimeoutSeconds * 1000))
    {
        throw "git init timed out after $TimeoutSeconds seconds (PID $($process.Id))."
    }
    $process.WaitForExit()
    if ($process.ExitCode -ne 0)
    {
        throw "git init exited $($process.ExitCode). stdout: $($stdoutTask.Result) stderr: $($stderrTask.Result)"
    }
}

function Invoke-Mcp
{
    param([object[]] $Messages)

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Exe
    $startInfo.Arguments = "--workspace-root $(ConvertTo-QuotedArgument $workspace) " +
        "--server-address 127.0.0.1:$port"
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void] $process.Start()
    $ownedProcesses.Add($process)
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    foreach ($message in $Messages)
    {
        $process.StandardInput.WriteLine(($message | ConvertTo-Json -Compress -Depth 20))
    }
    $process.StandardInput.Close()
    if (-not $process.WaitForExit($TimeoutSeconds * 1000))
    {
        throw "Native stdio MCP timed out after $TimeoutSeconds seconds (PID $($process.Id))."
    }
    $process.WaitForExit()
    $stdout = $stdoutTask.Result
    $stderr = $stderrTask.Result
    if ($process.ExitCode -ne 0)
    {
        throw "Native stdio MCP exited $($process.ExitCode). stderr: $stderr"
    }

    $lines = @($stdout -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    if ($lines.Count -ne $Messages.Count)
    {
        throw "Expected $($Messages.Count) JSON-RPC responses, received $($lines.Count). stdout: $stdout"
    }
    $responses = @($lines | ForEach-Object { $_ | ConvertFrom-Json })
    if (@($responses | Where-Object { $_.jsonrpc -ne "2.0" }).Count -ne 0)
    {
        throw "stdout contained a non-JSON-RPC line: $stdout"
    }
    return [pscustomobject] @{ Responses = $responses; Stderr = $stderr }
}

function Get-Response
{
    param([object[]] $Responses, [int] $Id)

    $matched = @($Responses | Where-Object { $_.id -eq $Id })
    if ($matched.Count -ne 1)
    {
        throw "Expected exactly one JSON-RPC response with id $Id."
    }
    if ($null -ne $matched[0].PSObject.Properties["error"])
    {
        throw "JSON-RPC request $Id failed: $($matched[0].error | ConvertTo-Json -Compress -Depth 20)"
    }
    return $matched[0]
}

function Get-ToolPayload
{
    param([object] $Response)

    $content = @($Response.result.content)
    if ($content.Count -ne 1 -or $content[0].type -ne "text")
    {
        throw "Tool response $($Response.id) must contain exactly one text item."
    }
    return $content[0].text | ConvertFrom-Json
}

function Get-NamedValue
{
    param([object] $Value, [string] $Name)

    if ($null -eq $Value -or $Value -is [string] -or $Value -is [ValueType])
    {
        return
    }
    if ($Value -is [System.Collections.IEnumerable] -and $Value -isnot [pscustomobject])
    {
        foreach ($item in $Value)
        {
            Get-NamedValue -Value $item -Name $Name
        }
        return
    }
    foreach ($property in $Value.PSObject.Properties)
    {
        if ($property.Name -ceq $Name)
        {
            $property.Value
        }
        Get-NamedValue -Value $property.Value -Name $Name
    }
}

try
{
    foreach ($path in @($Exe, $FakeServerJar, $Requests))
    {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf))
        {
            throw "Missing required file: $path"
        }
    }
    $messages = @(Get-Content -LiteralPath $Requests | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_)
    } | ForEach-Object { $_ | ConvertFrom-Json })
    if ($messages.Count -ne 6)
    {
        throw "Expected six request fixture messages, found $($messages.Count)."
    }

    [void] (New-Item -ItemType Directory -Path $tempBase -Force)
    if (Test-Path -LiteralPath $tempRoot)
    {
        throw "Scoped temp path already exists: $tempRoot"
    }
    [void] (New-Item -ItemType Directory -Path $tempRoot)
    $tempCreated = $true
    if (-not (Test-Path -LiteralPath $workspace))
    {
        $resolvedRoot = [System.IO.Path]::GetFullPath($tempRoot)
        if (-not $workspace.StartsWith(
                $resolvedRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) +
                    [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase))
        {
            throw "Refusing to create WorkspaceRoot outside criterion temp root: $workspace"
        }
        [void] (New-Item -ItemType Directory -Path $workspace)
        Initialize-Worktree
    }
    if (-not (Test-Path -LiteralPath (Join-Path $workspace ".git")))
    {
        throw "WorkspaceRoot is not a Git worktree: $workspace"
    }
    $serverRoot = Join-Path $tempRoot "server"
    $server = Start-Process -FilePath "java.exe" -ArgumentList @(
        "-jar", $FakeServerJar, "--port", $port, "--work-root", $serverRoot
    ) -PassThru -WindowStyle Hidden -RedirectStandardOutput (Join-Path $tempRoot "server.stdout") `
        -RedirectStandardError (Join-Path $tempRoot "server.stderr")
    $ownedProcesses.Add($server)
    Wait-ForServer -Server $server

    $first = Invoke-Mcp -Messages $messages
    $initialize = Get-Response -Responses $first.Responses -Id 1
    if ($initialize.result.protocolVersion -ne "2024-11-05")
    {
        throw "Native stdio MCP negotiated an unexpected protocol version."
    }
    $toolsResponse = Get-Response -Responses $first.Responses -Id 2
    $listed = @($toolsResponse.result.tools | ForEach-Object { $_.name })
    $expectedTools = @(
        "vibris_get_config",
        "vibris_list_presets",
        "vibris_configure",
        "vibris_get_status",
        "vibris_profile",
        "vibris_run_recipe",
        "vibris_run_actions"
    )
    if ([string]::Join("`n", $listed) -cne [string]::Join("`n", $expectedTools))
    {
        throw "tools/list did not expose exactly the expected 7-tool surface."
    }

    $configured = Get-ToolPayload (Get-Response -Responses $first.Responses -Id 3)
    $configuredIds = @(Get-NamedValue -Value $configured -Name "workspace_id")
    if ($configuredIds.Count -ne 1 -or [string]::IsNullOrWhiteSpace([string] $configuredIds[0]))
    {
        throw "vibris_configure did not return one workspace_id."
    }
    $workspaceId = [string] $configuredIds[0]
    $parsedWorkspaceId = [guid]::Empty
    if (-not [guid]::TryParse($workspaceId, [ref] $parsedWorkspaceId))
    {
        throw "vibris_configure workspace_id is not a UUID: $workspaceId"
    }
    if (-not (Test-Path -LiteralPath $configPath -PathType Leaf))
    {
        throw "vibris_configure did not persist $configPath."
    }

    $config = Get-ToolPayload (Get-Response -Responses $first.Responses -Id 4)
    if (@(Get-NamedValue -Value $config -Name "workspace_id") -cnotcontains $workspaceId)
    {
        throw "vibris_get_config did not return the configured workspace_id."
    }
    $status = Get-ToolPayload (Get-Response -Responses $first.Responses -Id 5)
    if (@(Get-NamedValue -Value $status -Name "current_save_id") -cnotcontains "test-save" -or
        @(Get-NamedValue -Value $status -Name "current_dimension_id") -cnotcontains "minecraft:overworld" -or
        @(Get-NamedValue -Value $status -Name "runtime_ready") -notcontains $true)
    {
        throw "vibris_get_status did not expose the fake gRPC runtime markers."
    }
    $presets = Get-ToolPayload (Get-Response -Responses $first.Responses -Id 6)
    if (@(Get-NamedValue -Value $presets -Name "preset_id") -cnotcontains "default")
    {
        throw "vibris_list_presets did not expose the fake gRPC preset."
    }

    $restartMessages = @($messages | Where-Object { $_.id -in @(1, 4) })
    $second = Invoke-Mcp -Messages $restartMessages
    $restartedConfig = Get-ToolPayload (Get-Response -Responses $second.Responses -Id 4)
    if (@(Get-NamedValue -Value $restartedConfig -Name "workspace_id") -cnotcontains $workspaceId)
    {
        throw "Native stdio MCP restart did not preserve workspace_id $workspaceId."
    }
    Write-Output "PASS tools=6 workspace_id=$workspaceId grpc_status=test-save restart_persisted=true"
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
    if ($configExisted)
    {
        [void] (New-Item -ItemType Directory -Path $configDirectory -Force)
        [System.IO.File]::WriteAllBytes($configPath, $configBytes)
    }
    elseif (Test-Path -LiteralPath $configPath)
    {
        Remove-Item -LiteralPath $configPath -Force
    }
    if (-not $configDirectoryExisted -and (Test-Path -LiteralPath $configDirectory) -and
        @(Get-ChildItem -LiteralPath $configDirectory -Force).Count -eq 0)
    {
        Remove-Item -LiteralPath $configDirectory -Force
    }
    if ($tempCreated -and (Test-Path -LiteralPath $tempRoot))
    {
        $resolvedBase = [System.IO.Path]::GetFullPath($tempBase).TrimEnd([System.IO.Path]::DirectorySeparatorChar)
        $resolvedRoot = [System.IO.Path]::GetFullPath($tempRoot)
        if (-not $resolvedRoot.StartsWith($resolvedBase + [System.IO.Path]::DirectorySeparatorChar,
                [System.StringComparison]::OrdinalIgnoreCase))
        {
            throw "Refusing to clean temp path outside criterion temp root: $resolvedRoot"
        }
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    }
    if (Test-PortOpen)
    {
        throw "Cleanup left a listener on 127.0.0.1:$port."
    }
    Write-Output "CLEANUP owned_processes=$($ownedProcesses.Count) temp_removed=$tempCreated listener_closed=true"
}
