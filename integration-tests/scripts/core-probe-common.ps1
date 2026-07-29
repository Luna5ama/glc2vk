Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function ConvertTo-CoreArgument
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

function Resolve-CoreListen
{
    param([Parameter(Mandatory)] [string] $Listen)

    if ($Listen -notmatch '^127[.]0[.]0[.]1:([0-9]+)$')
    {
        throw "Listen must use literal loopback, for example 127.0.0.1:55071."
    }
    $port = [int] $Matches[1]
    if ($port -lt 1 -or $port -gt 65535)
    {
        throw "Listen port must be between 1 and 65535."
    }
    return $port
}

function Test-CorePort
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

function Wait-CorePort
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
        if ($Open -and $null -ne $Process -and $Process.HasExited)
        {
            throw "Process $($Process.Id) exited before port 127.0.0.1:$Port opened."
        }
        if ((Test-CorePort -Port $Port) -eq $Open)
        {
            return
        }
        Start-Sleep -Milliseconds 50
    }
    throw "Port 127.0.0.1:$Port did not become open=$Open within $TimeoutSeconds seconds."
}

function Assert-CoreArtifact
{
    param(
        [Parameter(Mandatory)] [string] $Path,
        [Parameter(Mandatory)] [string] $Label
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf))
    {
        throw "Missing $Label artifact: $Path"
    }
}

function Assert-CoreCriterionRoot
{
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $ExpectedRoot
    )

    $actual = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    $expected = [System.IO.Path]::GetFullPath($ExpectedRoot).TrimEnd('\', '/')
    if (-not [string]::Equals($actual, $expected, [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "Criterion root must resolve exactly to $expected."
    }
    if (Test-Path -LiteralPath $actual)
    {
        throw "Scoped criterion root already exists: $actual"
    }
    [void] (New-Item -ItemType Directory -Path $actual)
    return $actual
}

function New-CoreProcessEntry
{
    param(
        [Parameter(Mandatory)] [string] $Kind,
        [Parameter(Mandatory)] [System.Diagnostics.Process] $Process,
        [Parameter(Mandatory)] [bool] $CaptureServerOutput
    )

    return [pscustomobject] @{
        Kind = $Kind
        Process = $Process
        Stderr = $Process.StandardError.ReadToEndAsync()
        Stdout = if ($CaptureServerOutput) { $Process.StandardOutput.ReadToEndAsync() } else { $null }
        PendingRead = $null
        Messages = [System.Collections.Generic.List[object]]::new()
        InputClosed = $false
        Graceful = $false
    }
}

function Start-CoreServer
{
    param(
        [Parameter(Mandatory)] [string] $Jar,
        [Parameter(Mandatory)] [int] $Port,
        [Parameter(Mandatory)] [string] $WorkRoot,
        [Parameter(Mandatory)] [string] $PendingRoot,
        [Parameter(Mandatory)] [string] $ArtifactRoot,
        [Parameter(Mandatory)] [System.Collections.IList] $Owned,
        [Parameter(Mandatory)] [int] $TimeoutSeconds
    )

    if (Test-CorePort -Port $Port)
    {
        throw "Criterion port 127.0.0.1:$Port is already in use."
    }
    $arguments = @(
        "--sun-misc-unsafe-memory-access=allow",
        "-jar", $Jar, "--port", [string] $Port, "--work-root", $WorkRoot,
        "--pending-root", $PendingRoot, "--artifact-root", $ArtifactRoot,
        "--probe-control-stdin"
    )
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = "java.exe"
    $startInfo.Arguments = [string]::Join(' ', @($arguments | ForEach-Object {
        ConvertTo-CoreArgument $_
    }))
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void] $process.Start()
    $entry = New-CoreProcessEntry -Kind "server" -Process $process -CaptureServerOutput $true
    [void] $Owned.Add($entry)
    Wait-CorePort -Port $Port -Open $true -Process $process -TimeoutSeconds $TimeoutSeconds
    return $entry
}

function Start-CoreClient
{
    param(
        [Parameter(Mandatory)] [string] $Exe,
        [Parameter(Mandatory)] [int] $Port,
        [Parameter(Mandatory)] [string] $WorkspaceId,
        [Parameter(Mandatory)] [string] $InstanceId,
        [Parameter(Mandatory)] [string] $WorkingDirectory,
        [Parameter(Mandatory)] [System.Collections.IList] $Owned,
        [Parameter(Mandatory)] [int] $TimeoutSeconds
    )

    $parsedInstance = [guid]::Empty
    if (-not [guid]::TryParse($InstanceId, [ref] $parsedInstance))
    {
        throw "Client instance_id is not a UUID: $InstanceId"
    }
    $arguments = @(
        "--host", "127.0.0.1", "--port", [string] $Port,
        "--workspace-id", $WorkspaceId, "--instance-id", $InstanceId
    )
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $Exe
    $startInfo.Arguments = [string]::Join(' ', @($arguments | ForEach-Object {
        ConvertTo-CoreArgument $_
    }))
    $startInfo.WorkingDirectory = $WorkingDirectory
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardInput = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    [void] $process.Start()
    $entry = New-CoreProcessEntry -Kind "client" -Process $process -CaptureServerOutput $false
    [void] $Owned.Add($entry)
    $hello = Wait-CoreClientMessage -Entry $entry -StartIndex 0 -TimeoutSeconds $TimeoutSeconds `
        -Match { param($message) $message.type -eq "ServerHello" }
    if ($hello.type -ne "ServerHello")
    {
        throw "Native core client did not emit ServerHello."
    }
    return $entry
}

function Send-CoreClientCommand
{
    param(
        [Parameter(Mandatory)] [object] $Entry,
        [Parameter(Mandatory)] [object] $Command
    )

    if ($Entry.InputClosed -or $Entry.Process.HasExited)
    {
        throw "Cannot write to closed client PID $($Entry.Process.Id)."
    }
    $line = $Command | ConvertTo-Json -Compress -Depth 30
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($line + "`n")
    $Entry.Process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
    $Entry.Process.StandardInput.BaseStream.Flush()
}

function Read-CoreClientMessage
{
    param(
        [Parameter(Mandatory)] [object] $Entry,
        [Parameter(Mandatory)] [int] $TimeoutMilliseconds
    )

    while ($true)
    {
        if ($null -eq $Entry.PendingRead)
        {
            $Entry.PendingRead = $Entry.Process.StandardOutput.ReadLineAsync()
        }
        if (-not $Entry.PendingRead.Wait($TimeoutMilliseconds))
        {
            throw "Client PID $($Entry.Process.Id) timed out waiting for a JSONL event."
        }
        $line = $Entry.PendingRead.Result
        $Entry.PendingRead = $null
        if ($null -eq $line)
        {
            throw "Client PID $($Entry.Process.Id) closed stdout before the expected event."
        }
        if ([string]::IsNullOrWhiteSpace($line))
        {
            continue
        }
        try
        {
            $message = $line | ConvertFrom-Json
        }
        catch
        {
            throw "Client PID $($Entry.Process.Id) emitted non-JSON stdout: $line"
        }
        if (-not ($message.PSObject.Properties.Name -contains "type"))
        {
            throw "Client PID $($Entry.Process.Id) emitted JSON without a type: $line"
        }
        $Entry.Messages.Add($message)
        return $message
    }
}

function Wait-CoreClientMessage
{
    param(
        [Parameter(Mandatory)] [object] $Entry,
        [int] $StartIndex = 0,
        [Parameter(Mandatory)] [scriptblock] $Match,
        [Parameter(Mandatory)] [int] $TimeoutSeconds
    )

    $deadline = [datetime]::UtcNow.AddSeconds($TimeoutSeconds)
    $index = $StartIndex
    while ([datetime]::UtcNow -lt $deadline)
    {
        while ($index -lt $Entry.Messages.Count)
        {
            $message = $Entry.Messages[$index]
            $index++
            if (& $Match $message)
            {
                return $message
            }
        }
        $remaining = [int] [math]::Max(1, ($deadline - [datetime]::UtcNow).TotalMilliseconds)
        $message = Read-CoreClientMessage -Entry $Entry -TimeoutMilliseconds $remaining
        $index = $Entry.Messages.Count
        if (& $Match $message)
        {
            return $message
        }
    }
    throw "Client PID $($Entry.Process.Id) did not emit the expected event within $TimeoutSeconds seconds."
}

function Add-CoreJsonLines
{
    param(
        [Parameter(Mandatory)] [AllowEmptyString()] [string] $Text,
        [Parameter(Mandatory)] [System.Collections.IList] $Messages,
        [Parameter(Mandatory)] [string] $Label
    )

    foreach ($line in @($Text -split "`r?`n"))
    {
        if ([string]::IsNullOrWhiteSpace($line))
        {
            continue
        }
        try
        {
            [void] $Messages.Add(($line | ConvertFrom-Json))
        }
        catch
        {
            throw "$Label emitted non-JSON stdout: $line"
        }
    }
}

function Stop-CoreClient
{
    param(
        [Parameter(Mandatory)] [object] $Entry,
        [switch] $SendClose,
        [Parameter(Mandatory)] [int] $TimeoutSeconds
    )

    if ($Entry.Graceful -or $Entry.Process.HasExited)
    {
        return
    }
    if ($SendClose)
    {
        Send-CoreClientCommand -Entry $Entry -Command @{ op = "close" }
    }
    $tail = $Entry.Process.StandardOutput.ReadToEndAsync()
    $Entry.Process.StandardInput.Close()
    $Entry.InputClosed = $true
    if (-not $Entry.Process.WaitForExit($TimeoutSeconds * 1000))
    {
        throw "Client PID $($Entry.Process.Id) did not exit after stdin shutdown."
    }
    $Entry.Process.WaitForExit()
    if (-not $tail.Wait($TimeoutSeconds * 1000) -or -not $Entry.Stderr.Wait($TimeoutSeconds * 1000))
    {
        throw "Client PID $($Entry.Process.Id) output pipes did not close."
    }
    Add-CoreJsonLines -Text $tail.Result -Messages $Entry.Messages -Label "Client"
    if ($Entry.Process.ExitCode -ne 0 -or -not [string]::IsNullOrWhiteSpace($Entry.Stderr.Result))
    {
        throw "Client PID $($Entry.Process.Id) exited $($Entry.Process.ExitCode): $($Entry.Stderr.Result)"
    }
    $Entry.Graceful = $true
}

function Stop-CoreClientAbrupt
{
    param(
        [Parameter(Mandatory)] [object] $Entry,
        [Parameter(Mandatory)] [int] $TimeoutSeconds
    )

    if (-not $Entry.Process.HasExited)
    {
        $Entry.Process.Kill()
    }
    if (-not $Entry.Process.WaitForExit($TimeoutSeconds * 1000))
    {
        throw "Client PID $($Entry.Process.Id) survived an abrupt transport-loss kill."
    }
    $Entry.Process.WaitForExit()
    if (-not $Entry.Stderr.Wait($TimeoutSeconds * 1000))
    {
        throw "Client PID $($Entry.Process.Id) stderr did not close after transport loss."
    }
    $Entry.InputClosed = $true
    $Entry.Graceful = $true
}

function Stop-CoreServer
{
    param(
        [Parameter(Mandatory)] [object] $Entry,
        [Parameter(Mandatory)] [int] $TimeoutSeconds
    )

    if (-not $Entry.Process.HasExited)
    {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes("shutdown`n")
        $Entry.Process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
        $Entry.Process.StandardInput.BaseStream.Flush()
        $Entry.Process.StandardInput.Close()
        $Entry.InputClosed = $true
    }
    if (-not $Entry.Process.WaitForExit($TimeoutSeconds * 1000))
    {
        throw "Server PID $($Entry.Process.Id) did not exit after stdin shutdown."
    }
    $Entry.Process.WaitForExit()
    if (-not $Entry.Stdout.Wait($TimeoutSeconds * 1000) -or
        -not $Entry.Stderr.Wait($TimeoutSeconds * 1000))
    {
        throw "Server PID $($Entry.Process.Id) output pipes did not close."
    }
    if ($Entry.Process.ExitCode -ne 0 -or -not [string]::IsNullOrWhiteSpace($Entry.Stderr.Result))
    {
        throw "Server PID $($Entry.Process.Id) exited $($Entry.Process.ExitCode): $($Entry.Stderr.Result)"
    }
    Add-CoreJsonLines -Text $Entry.Stdout.Result -Messages $Entry.Messages -Label "Server"
    $Entry.Graceful = $true
    return @($Entry.Messages)
}

function Stop-CoreOwnedProcesses
{
    param(
        [Parameter(Mandatory)] [System.Collections.IList] $Owned,
        [Parameter(Mandatory)] [int] $TimeoutSeconds
    )

    $failures = [System.Collections.Generic.List[string]]::new()
    for ($index = $Owned.Count - 1; $index -ge 0; $index--)
    {
        $entry = $Owned[$index]
        $process = $entry.Process
        try
        {
            if (-not $process.HasExited)
            {
                if (-not $entry.InputClosed)
                {
                    if ($entry.Kind -eq "server")
                    {
                        $process.StandardInput.WriteLine("shutdown")
                        $process.StandardInput.Flush()
                    }
                    $process.StandardInput.Close()
                    $entry.InputClosed = $true
                }
                if (-not $process.WaitForExit($TimeoutSeconds * 1000))
                {
                    $process.Kill()
                    if (-not $process.WaitForExit($TimeoutSeconds * 1000))
                    {
                        throw "Owned PID $($process.Id) did not terminate."
                    }
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

function New-CoreSource
{
    param(
        [Parameter(Mandatory)] [string] $PendingRoot,
        [Parameter(Mandatory)] [string] $Uuid,
        [Parameter(Mandatory)] [string] $Content
    )

    $parsed = [guid]::Empty
    if (-not [guid]::TryParse($Uuid, [ref] $parsed))
    {
        throw "Fixture source UUID is invalid: $Uuid"
    }
    $directory = Join-Path $PendingRoot $Uuid
    if (Test-Path -LiteralPath $directory)
    {
        throw "Fixture source already exists: $directory"
    }
    [void] (New-Item -ItemType Directory -Path $directory)
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Content)
    [System.IO.File]::WriteAllBytes((Join-Path $directory "source.txt"), $bytes)
    return [pscustomobject] @{
        uuid = $Uuid
        file_count = 1
        total_bytes = $bytes.Length
    }
}

function Remove-CoreUnownedSource
{
    param(
        [Parameter(Mandatory)] [string] $PendingRoot,
        [Parameter(Mandatory)] [string] $Uuid
    )

    $parsed = [guid]::Empty
    if (-not [guid]::TryParse($Uuid, [ref] $parsed))
    {
        throw "Refusing to remove a non-UUID source child: $Uuid"
    }
    $root = [System.IO.Path]::GetFullPath($PendingRoot).TrimEnd('\', '/')
    $path = [System.IO.Path]::GetFullPath((Join-Path $root $Uuid))
    if (-not [string]::Equals((Split-Path -Parent $path), $root,
            [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "Refusing to remove source outside pending root: $path"
    }
    if (Test-Path -LiteralPath $path)
    {
        Remove-Item -LiteralPath $path -Recurse -Force
    }
}

function New-CoreSubmitCommand
{
    param(
        [Parameter(Mandatory)] [string] $MessageId,
        [Parameter(Mandatory)] [string] $RequestId,
        [Parameter(Mandatory)] [object[]] $Sources,
        [Parameter(Mandatory)] [object] $Context,
        [Parameter(Mandatory)] [uint32] $WaitFrames,
        [Parameter(Mandatory)] [object] $Timeouts
    )

    return [ordered] @{
        op = "submit"
        message_id = $MessageId
        request_id = $RequestId
        sources = $Sources
        context = $Context
        wait_frames = $WaitFrames
        timeouts = $Timeouts
    }
}

function Get-CoreFailureCode
{
    param([Parameter(Mandatory)] [object] $Message)

    if ($Message.PSObject.Properties.Name -contains "code")
    {
        return [string] $Message.code
    }
    if ($Message.PSObject.Properties.Name -contains "error")
    {
        return [string] $Message.error.code
    }
    return ""
}

function Get-CoreSummary
{
    param([Parameter(Mandatory)] [object[]] $Messages)

    $summaries = @($Messages | Where-Object { $_.type -eq "ServerSummary" })
    if ($summaries.Count -ne 1)
    {
        throw "Server must emit exactly one final ServerSummary."
    }
    return $summaries[0]
}

function Get-CoreExecutionCount
{
    param(
        [Parameter(Mandatory)] [object] $Summary,
        [Parameter(Mandatory)] [string] $RequestId
    )

    $property = $Summary.execution_counts.PSObject.Properties[$RequestId]
    if ($null -eq $property)
    {
        return 0
    }
    return [int] $property.Value
}

function Get-CoreContextSignature
{
    param([Parameter(Mandatory)] [object] $Context)

    $culture = [System.Globalization.CultureInfo]::InvariantCulture
    return [string]::Join('|', @(
        [string] $Context.save_id,
        [string] $Context.dimension_id,
        [string] $Context.time_preset_id,
        [string] $Context.weather_preset_id,
        [string] $Context.camera_preset_id,
        ([double] $Context.fov).ToString('R', $culture),
        [string] ([uint32] $Context.resolution.width),
        [string] ([uint32] $Context.resolution.height),
        [string] $Context.settings_preset_id
    ))
}

function Assert-CoreRootEmpty
{
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $Label
    )

    if (-not (Test-Path -LiteralPath $Root -PathType Container))
    {
        throw "$Label root is missing: $Root"
    }
    if (@(Get-ChildItem -LiteralPath $Root -Force).Count -ne 0)
    {
        throw "$Label root is not empty: $Root"
    }
}

function Remove-CoreCriterionRoot
{
    param(
        [Parameter(Mandatory)] [string] $Root,
        [Parameter(Mandatory)] [string] $ExpectedRoot
    )

    $actual = [System.IO.Path]::GetFullPath($Root).TrimEnd('\', '/')
    $expected = [System.IO.Path]::GetFullPath($ExpectedRoot).TrimEnd('\', '/')
    if (-not [string]::Equals($actual, $expected, [System.StringComparison]::OrdinalIgnoreCase))
    {
        throw "Refusing to remove unexpected criterion root: $actual"
    }
    if (Test-Path -LiteralPath $actual)
    {
        Remove-Item -LiteralPath $actual -Recurse -Force
    }
}