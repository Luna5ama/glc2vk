Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function ConvertTo-ProbeArgument
{
    param([string] $Value)

    if ($Value -notmatch '[\s"]')
    {
        return $Value
    }
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}

function Assert-ProbeMcpExecutable
{
    param([Parameter(Mandatory)] [string] $Exe)

    if ([System.IO.Path]::GetFileName($Exe) -cne "vibris-mcp.exe" -or
        -not (Test-Path -LiteralPath $Exe -PathType Leaf))
    {
        throw "Probe requires the real production vibris-mcp.exe: $Exe"
    }
}

function Invoke-ProbeProcess
{
    param(
        [Parameter(Mandatory)] [string] $FileName,
        [string[]] $Arguments = @(),
        [string] $WorkingDirectory,
        [string] $InputText,
        [Parameter(Mandatory)] [int] $TimeoutSeconds
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $FileName
    $startInfo.Arguments = [string]::Join(' ', @($Arguments | ForEach-Object {
        ConvertTo-ProbeArgument $_
    }))
    if (-not [string]::IsNullOrWhiteSpace($WorkingDirectory))
    {
        $startInfo.WorkingDirectory = $WorkingDirectory
    }
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void] $process.Start()
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    if ($null -ne $InputText)
    {
        $process.StandardInput.Write($InputText)
    }
    $process.StandardInput.Close()
    if (-not $process.WaitForExit($TimeoutSeconds * 1000))
    {
        $process.Kill()
        [void] $process.WaitForExit(2000)
        $process.Dispose()
        throw "$FileName timed out after $TimeoutSeconds seconds."
    }
    if (-not $stdout.Wait($TimeoutSeconds * 1000) -or
        -not $stderr.Wait($TimeoutSeconds * 1000))
    {
        $process.Dispose()
        throw "$FileName output pipes did not close within $TimeoutSeconds seconds."
    }
    $result = [pscustomobject] @{
        ExitCode = $process.ExitCode
        Stdout = $stdout.Result
        Stderr = $stderr.Result
    }
    $process.Dispose()
    if ($result.ExitCode -ne 0)
    {
        throw "$FileName exited $($result.ExitCode): $($result.Stderr)"
    }
    return $result
}

function Test-ProbePort
{
    param([Parameter(Mandatory)] [int] $Port)

    $client = [System.Net.Sockets.TcpClient]::new()
    try
    {
        $connect = $client.ConnectAsync("127.0.0.1", $Port)
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

function Wait-ProbePort
{
    param(
        [Parameter(Mandatory)] [int] $Port,
        [Parameter(Mandatory)] [bool] $Open,
        [System.Diagnostics.Process] $Process,
        [Parameter(Mandatory)] [int] $TimeoutSeconds
    )

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while ([datetime]::UtcNow -lt $deadline)
    {
        if ($null -ne $Process -and $Process.HasExited -and $Open)
        {
            throw "Process $($Process.Id) exited before port $Port opened."
        }
        if ((Test-ProbePort -Port $Port) -eq $Open)
        {
            return
        }
        Start-Sleep -Milliseconds 50
    }
    throw "Port 127.0.0.1:$Port did not become open=$Open within $TimeoutSeconds seconds."
}

function Start-ProbeServer
{
    param(
        [Parameter(Mandatory)] [string] $Jar,
        [Parameter(Mandatory)] [int] $Port,
        [Parameter(Mandatory)] [string] $WorkRoot,
        [Parameter(Mandatory)] [string] $PendingRoot,
        [Parameter(Mandatory)] [System.Collections.IList] $Owned,
        [Parameter(Mandatory)] [int] $TimeoutSeconds
    )

    if (Test-ProbePort -Port $Port)
    {
        throw "Criterion port 127.0.0.1:$Port is already in use."
    }
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = "java.exe"
    $startInfo.Arguments = [string]::Join(' ', @(
        ConvertTo-ProbeArgument "-jar"
        ConvertTo-ProbeArgument $Jar
        ConvertTo-ProbeArgument "--port"
        ConvertTo-ProbeArgument ([string] $Port)
        ConvertTo-ProbeArgument "--work-root"
        ConvertTo-ProbeArgument $WorkRoot
        ConvertTo-ProbeArgument "--pending-root"
        ConvertTo-ProbeArgument $PendingRoot
    ))
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void] $process.Start()
    $entry = [pscustomobject] @{
        Process = $process
        Stdout = $process.StandardOutput.ReadToEndAsync()
        Stderr = $process.StandardError.ReadToEndAsync()
    }
    [void] $Owned.Add($entry)
    Wait-ProbePort -Port $Port -Open $true -Process $process -TimeoutSeconds $TimeoutSeconds
    return $entry
}

function Start-ProbeMcp
{
    param(
        [Parameter(Mandatory)] [string] $Exe,
        [Parameter(Mandatory)] [string] $Workspace,
        [Parameter(Mandatory)] [int] $Port,
        [Parameter(Mandatory)] [System.Collections.IList] $Owned
    )

    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Exe
    $startInfo.Arguments = [string]::Join(' ', @(
        ConvertTo-ProbeArgument "--server-address"
        ConvertTo-ProbeArgument "127.0.0.1:$Port"
    ))
    $startInfo.WorkingDirectory = $Workspace
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void] $process.Start()
    $entry = [pscustomobject] @{
        Process = $process
        Stdout = $null
        Stderr = $process.StandardError.ReadToEndAsync()
    }
    [void] $Owned.Add($entry)
    return $entry
}

function Send-ProbeMcpMessage
{
    param(
        [Parameter(Mandatory)] [System.Diagnostics.Process] $Process,
        [Parameter(Mandatory)] [object] $Message
    )

    $Process.StandardInput.WriteLine(($Message | ConvertTo-Json -Compress -Depth 20))
    $Process.StandardInput.Flush()
}

function Read-ProbeMcpResponse
{
    param(
        [Parameter(Mandatory)] [System.Diagnostics.Process] $Process,
        [Parameter(Mandatory)] [object] $ExpectedId,
        [Parameter(Mandatory)] [int] $TimeoutSeconds,
        [scriptblock] $Poll,
        [object] $PeakWorkingSet
    )

    $lineTask = $Process.StandardOutput.ReadLineAsync()
    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    while (-not $lineTask.IsCompleted -and [datetime]::UtcNow -lt $deadline)
    {
        if ($Process.HasExited)
        {
            throw "MCP exited before response '$ExpectedId' (exit $($Process.ExitCode))."
        }
        if ($null -ne $Poll)
        {
            & $Poll
        }
        if ($null -ne $PeakWorkingSet)
        {
            $Process.Refresh()
            if ($Process.PeakWorkingSet64 -gt $PeakWorkingSet.Peak)
            {
                $PeakWorkingSet.Peak = $Process.PeakWorkingSet64
            }
        }
        Start-Sleep -Milliseconds 5
    }
    if (-not $lineTask.IsCompleted)
    {
        throw "MCP timed out waiting for response '$ExpectedId'."
    }
    if ($null -ne $PeakWorkingSet)
    {
        $Process.Refresh()
        if ($Process.PeakWorkingSet64 -gt $PeakWorkingSet.Peak)
        {
            $PeakWorkingSet.Peak = $Process.PeakWorkingSet64
        }
    }
    $line = $lineTask.Result
    if ($null -eq $line)
    {
        throw "MCP stdout closed before response '$ExpectedId'."
    }
    $response = $line | ConvertFrom-Json
    if (-not ($response.PSObject.Properties.Name -contains "id") -or $response.id -ne $ExpectedId)
    {
        throw "MCP returned an unexpected response while waiting for '$ExpectedId': $line"
    }
    if ($response.PSObject.Properties.Name -contains "error")
    {
        throw "MCP JSON-RPC request '$ExpectedId' failed: " +
            ($response.error | ConvertTo-Json -Compress -Depth 20)
    }
    return $response
}

function Initialize-ProbeMcp
{
    param(
        [Parameter(Mandatory)] [System.Diagnostics.Process] $Process,
        [Parameter(Mandatory)] [int] $TimeoutSeconds
    )

    Send-ProbeMcpMessage -Process $Process -Message @{
        jsonrpc = "2.0"
        id = "source-init"
        method = "initialize"
        params = @{
            protocolVersion = "2024-11-05"
            capabilities = @{}
            clientInfo = @{ name = "source-probe"; version = "1" }
        }
    }
    $response = Read-ProbeMcpResponse -Process $Process -ExpectedId "source-init" `
        -TimeoutSeconds $TimeoutSeconds
    if ($response.result.protocolVersion -ne "2024-11-05")
    {
        throw "MCP negotiated an unexpected protocol version."
    }
    Send-ProbeMcpMessage -Process $Process -Message @{
        jsonrpc = "2.0"
        method = "notifications/initialized"
        params = @{}
    }
}

function Invoke-ProbeMcpTool
{
    param(
        [Parameter(Mandatory)] [System.Diagnostics.Process] $Process,
        [Parameter(Mandatory)] [object] $Id,
        [Parameter(Mandatory)] [string] $Name,
        [Parameter(Mandatory)] [object] $Arguments,
        [Parameter(Mandatory)] [int] $TimeoutSeconds,
        [scriptblock] $Poll,
        [object] $PeakWorkingSet
    )

    Send-ProbeMcpMessage -Process $Process -Message @{
        jsonrpc = "2.0"
        id = $Id
        method = "tools/call"
        params = @{ name = $Name; arguments = $Arguments }
    }
    return Read-ProbeMcpResponse -Process $Process -ExpectedId $Id -TimeoutSeconds $TimeoutSeconds `
        -Poll $Poll -PeakWorkingSet $PeakWorkingSet
}

function Get-ProbeToolPayload
{
    param([Parameter(Mandatory)] [object] $Response)

    $content = @($Response.result.content)
    if ($content.Count -ne 1 -or $content[0].type -ne "text")
    {
        throw "Tool response '$($Response.id)' did not contain exactly one text item."
    }
    return $content[0].text | ConvertFrom-Json
}

function Set-ProbeConfig
{
    param(
        [Parameter(Mandatory)] [System.Diagnostics.Process] $Process,
        [Parameter(Mandatory)] [int] $TimeoutSeconds
    )

    $response = Invoke-ProbeMcpTool -Process $Process -Id "source-config" -Name "vibris_configure" `
        -Arguments @{
            save_id = "test-save"
            dimension_id = "minecraft:overworld"
            time_preset_id = "noon"
            camera_preset_id = "origin"
            fov = 70.0
            default_warmup_frames = 0
        } -TimeoutSeconds $TimeoutSeconds
    if ($response.result.isError)
    {
        throw "vibris_configure failed: " +
            ((Get-ProbeToolPayload -Response $response) | ConvertTo-Json -Compress -Depth 20)
    }
}

function Assert-ProbeServerPendingRoot
{
    param(
        [Parameter(Mandatory)] [System.Diagnostics.Process] $Process,
        [Parameter(Mandatory)] [string] $PendingRoot,
        [Parameter(Mandatory)] [int] $TimeoutSeconds
    )

    $response = Invoke-ProbeMcpTool -Process $Process -Id "source-pending-root" `
        -Name "vibris_get_status" -Arguments @{} -TimeoutSeconds $TimeoutSeconds
    if ($response.result.isError)
    {
        throw "Unable to read the server pending root."
    }
    $payload = Get-ProbeToolPayload -Response $response
    $reported = [System.IO.Path]::GetFullPath([string] $payload.pending_shaders_root)
    $expected = [System.IO.Path]::GetFullPath($PendingRoot)
    if (-not [string]::Equals($reported, $expected,
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "Fake server advertised '$reported', expected literal PendingRoot '$expected'."
    }
}

function Stop-ProbeMcp
{
    param(
        [Parameter(Mandatory)] [object] $Entry,
        [Parameter(Mandatory)] [int] $TimeoutSeconds
    )

    $process = $Entry.Process
    if (-not $process.StandardInput.BaseStream.CanWrite)
    {
        return
    }
    $trailingTask = $process.StandardOutput.ReadToEndAsync()
    $process.StandardInput.Close()
    if (-not $process.WaitForExit($TimeoutSeconds * 1000))
    {
        throw "MCP PID $($process.Id) did not exit after stdin closed."
    }
    if (-not $trailingTask.Wait($TimeoutSeconds * 1000) -or
        -not $Entry.Stderr.Wait($TimeoutSeconds * 1000))
    {
        throw "MCP output pipes did not close within $TimeoutSeconds seconds."
    }
    $trailing = $trailingTask.Result
    if (-not [string]::IsNullOrWhiteSpace($trailing))
    {
        throw "MCP emitted unexpected trailing stdout: $trailing"
    }
    if ($process.ExitCode -ne 0)
    {
        throw "MCP exited $($process.ExitCode): $($Entry.Stderr.Result)"
    }
}

function Stop-ProbeOwnedProcesses
{
    param(
        [Parameter(Mandatory)] [System.Collections.IList] $Owned,
        [Parameter(Mandatory)] [int] $TimeoutSeconds
    )

    $failures = [System.Collections.Generic.List[string]]::new()
    for ($index = $Owned.Count - 1; $index -ge 0; $index--)
    {
        $process = $Owned[$index].Process
        try
        {
            if (-not $process.HasExited)
            {
                $process.Kill()
                if (-not $process.WaitForExit($TimeoutSeconds * 1000))
                {
                    throw "Owned PID $($process.Id) did not terminate."
                }
            }
        }
        catch
        {
            $failures.Add($_.Exception.Message)
        }
        finally
        {
            $process.Dispose()
        }
    }
    if ($failures.Count -ne 0)
    {
        throw [string]::Join('; ', $failures)
    }
}

function Get-ProbeFileHash
{
    param([Parameter(Mandatory)] [string] $Path)

    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    $stream = [System.IO.File]::OpenRead($Path)
    try
    {
        return ([System.BitConverter]::ToString($algorithm.ComputeHash($stream))).Replace('-', '')
    }
    finally
    {
        $stream.Dispose()
        $algorithm.Dispose()
    }
}

function Get-ProbeTree
{
    param([Parameter(Mandatory)] [string] $Root)

    $fullRoot = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    return @(Get-ChildItem -LiteralPath $fullRoot -File -Recurse -Force | Sort-Object FullName |
        ForEach-Object {
            [pscustomobject] @{
                RelativePath = $_.FullName.Substring($fullRoot.Length + 1).Replace('\', '/')
                Length = $_.Length
                Hash = Get-ProbeFileHash -Path $_.FullName
            }
        })
}

function Assert-ProbeTreesEqual
{
    param(
        [Parameter(Mandatory)] [string] $Expected,
        [Parameter(Mandatory)] [string] $Actual
    )

    $expectedTree = @(Get-ProbeTree -Root $Expected)
    $actualTree = @(Get-ProbeTree -Root $Actual)
    $expectedLines = @($expectedTree | ForEach-Object {
        "$($_.RelativePath)|$($_.Length)|$($_.Hash)"
    })
    $actualLines = @($actualTree | ForEach-Object {
        "$($_.RelativePath)|$($_.Length)|$($_.Hash)"
    })
    if ([string]::Join("`n", $expectedLines) -cne [string]::Join("`n", $actualLines))
    {
        $expectedText = [string]::Join('; ', $expectedLines)
        $actualText = [string]::Join('; ', $actualLines)
        throw "Prepared source tree mismatch. Expected [$expectedText], actual [$actualText]."
    }
    return $expectedTree
}

function Assert-ProbePendingClean
{
    param([Parameter(Mandatory)] [string] $PendingRoot)

    if (-not (Test-Path -LiteralPath $PendingRoot -PathType Container))
    {
        return
    }
    $unexpected = @(Get-ChildItem -LiteralPath $PendingRoot -Force | Where-Object {
        $_.Name -ne ".staging"
    })
    $staging = Join-Path $PendingRoot ".staging"
    $stagingChildren = @(if (Test-Path -LiteralPath $staging) {
        Get-ChildItem -LiteralPath $staging -Force
    })
    if ($unexpected.Count -ne 0 -or $stagingChildren.Count -ne 0)
    {
        throw "Pending root retained a source or staging child: $PendingRoot"
    }
}

function Expand-ProbeFile
{
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [int64] $Length
    )

    $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open,
        [System.IO.FileAccess]::Write, [System.IO.FileShare]::Read)
    try
    {
        $stream.SetLength($Length)
        $stream.Flush()
    }
    finally
    {
        $stream.Dispose()
    }
}
