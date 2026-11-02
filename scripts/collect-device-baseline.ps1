# Read-only adb evidence. A successful collection is not an application test pass.
[CmdletBinding()]
param(
    [string]$Serial,
    [string]$AdbPath = 'adb',
    [string]$OutputDirectory
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$script:probes = [System.Collections.Generic.List[object]]::new()
$script:selectedSerial = $null
$script:incomplete = $false

function Invoke-AdbEvidence {
    param([string]$Name, [string[]]$AdbArguments)

    $arguments = @()
    if ($script:selectedSerial) {
        $arguments += @('-s', $script:selectedSerial)
    }
    $arguments += $AdbArguments
    $exitCode = -1
    $lines = @()
    try {
        # Windows PowerShell treats native stderr as errors under Stop.
        $ErrorActionPreference = 'Continue'
        $LASTEXITCODE = 0
        $lines = @(& $AdbPath @arguments 2>&1 | ForEach-Object { "$_" })
        $exitCode = $LASTEXITCODE
    } catch {
        # Do not persist exception text: adb can include serials in errors.
        $exitCode = -1
    }
    $status = 'available'
    if ($exitCode -ne 0) {
        $status = 'adb-error'
        $script:incomplete = $true
        Write-Warning "$Name failed (adb exit $exitCode); collection is incomplete."
    }
    $probe = [pscustomobject]@{ Name = $Name; Status = $status; ExitCode = $exitCode }
    $script:probes.Add($probe)
    return [pscustomobject]@{ Probe = $probe; Lines = $lines }
}

function Set-InvalidEvidence {
    param($Result)
    $Result.Probe.Status = 'invalid-output'
    $script:incomplete = $true
    Write-Warning "$($Result.Probe.Name) returned unexpected output; collection is incomplete."
}

try {
    $null = Get-Command $AdbPath -ErrorAction Stop
    $devicesResult = Invoke-AdbEvidence -Name 'device-selection' -AdbArguments @('devices')
    if ($devicesResult.Probe.Status -ne 'available') {
        throw 'Unable to enumerate devices. No device was inspected.'
    }
    $devices = @(
        foreach ($line in $devicesResult.Lines) {
            if ($line -match '^(\S+)\s+(device|offline|unauthorized|recovery|sideload|bootloader|no permissions)\b') {
                [pscustomobject]@{ Id = $Matches[1]; State = $Matches[2] }
            }
        }
    )
    if ($Serial) {
        $selected = @($devices | Where-Object { $_.Id -ceq $Serial -and $_.State -eq 'device' })
        if ($selected.Count -ne 1) {
            throw 'The requested device is absent or not online/authorized. No device was inspected.'
        }
    } else {
        $selected = @($devices | Where-Object { $_.State -eq 'device' })
        if ($selected.Count -ne 1) {
            throw 'Specify -Serial, or connect exactly one online/authorized device. No device was inspected.'
        }
    }
    $script:selectedSerial = $selected[0].Id

    if (-not $OutputDirectory) {
        $name = 'lightning-device-baseline-{0}-{1}' -f (Get-Date -Format 'yyyyMMdd-HHmmss'), ([guid]::NewGuid().ToString('N').Substring(0, 8))
        $OutputDirectory = Join-Path ([System.IO.Path]::GetTempPath()) $name
    }
    $OutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
    if (Test-Path -LiteralPath $OutputDirectory) {
        throw 'OutputDirectory already exists. Choose a new directory to preserve earlier evidence.'
    }
    $null = New-Item -ItemType Directory -Path $OutputDirectory
    $startedAt = [DateTimeOffset]::Now.ToString('o')

    $metadata = [ordered]@{}
    $properties = [ordered]@{
        Manufacturer = 'ro.product.manufacturer'
        Model = 'ro.product.model'
        AndroidRelease = 'ro.build.version.release'
        ApiLevel = 'ro.build.version.sdk'
        Abis = 'ro.product.cpu.abilist'
    }
    foreach ($key in $properties.Keys) {
        $result = Invoke-AdbEvidence -Name "metadata-$key" -AdbArguments @('shell', 'getprop', $properties[$key])
        $metadata[$key] = $null
        if ($result.Probe.Status -eq 'available') {
            $value = ($result.Lines -join '').Trim()
            if ($result.Lines.Count -ne 1 -or [string]::IsNullOrWhiteSpace($value)) {
                Set-InvalidEvidence $result
            } else {
                $metadata[$key] = $value.Replace($script:selectedSerial, '[redacted-device]')
            }
        }
    }

    $userResult = Invoke-AdbEvidence -Name 'current-android-user' -AdbArguments @('shell', 'am', 'get-current-user')
    $androidUser = ($userResult.Lines -join '').Trim()
    if ($userResult.Probe.Status -ne 'available' -or $androidUser -notmatch '^\d+$') {
        throw 'Unable to select the current Android user.'
    }
    $metadata['AndroidUserId'] = [int]$androidUser

    $knownPackages = @('com.dsha.browser', 'com.dsha.sidecar', 'info.plateaukao.einkbro', 'acr.browser.barebones')
    $installed = @()
    $packagesResult = Invoke-AdbEvidence -Name 'installed-test-packages' -AdbArguments @('shell', 'pm', 'list', 'packages', '--user', $androidUser, '--show-versioncode')
    if ($packagesResult.Probe.Status -eq 'available') {
        $packageLines = @($packagesResult.Lines | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
        if ($packageLines.Count -eq 0 -or @($packageLines | Where-Object { $_ -notmatch '^package:[A-Za-z0-9_.]+ versionCode:\d+\s*$' }).Count -gt 0) {
            Set-InvalidEvidence $packagesResult
        } else {
            $installed = @(
                foreach ($line in $packageLines) {
                    if ($line -match '^package:([A-Za-z0-9_.]+) versionCode:(\d+)\s*$') {
                        $id = $Matches[1]
                        $version = $Matches[2]
                        if ($id -in $knownPackages -or $id.StartsWith('com.dsha.')) {
                            [pscustomobject]@{ PackageId = $id; VersionCode = $version }
                        }
                    }
                }
            )
        }
    }

    $processes = @()
    $processResult = Invoke-AdbEvidence -Name 'app-and-node-processes' -AdbArguments @('shell', 'ps', '-A', '-o', 'PID,PPID,UID,NAME')
    if ($processResult.Probe.Status -eq 'available') {
        if (-not ($processResult.Lines -match '^\s*PID\s+PPID\s+UID\s+NAME\s*$')) {
            Set-InvalidEvidence $processResult
        } else {
            $processes = @(
                foreach ($line in $processResult.Lines) {
                    if ($line -match '^\s*(\d+)\s+(\d+)\s+(\S+)\s+(\S+)\s*$') {
                        $processId = [int]$Matches[1]
                        $parentId = [int]$Matches[2]
                        $uid = $Matches[3]
                        $processName = $Matches[4]
                        $packageName = ($processName -split ':', 2)[0]
                        $isNode = $processName -match '(^|/)libnodebin\.so$'
                        if ($packageName -in $knownPackages -or $packageName.StartsWith('com.dsha.') -or $isNode) {
                            # Node names alone do not establish ownership; compare UID/PPID.
                            if ($isNode) { $processName = 'libnodebin.so' }
                            [pscustomobject]@{ Pid = $processId; ParentPid = $parentId; Uid = $uid; Name = $processName }
                        }
                    }
                }
            )
        }
    }

    $listeners = @()
    $listenerResult = Invoke-AdbEvidence -Name 'proxy-listener' -AdbArguments @('shell', 'ss', '-ltn')
    if ($listenerResult.Probe.Status -eq 'available') {
        if (-not ($listenerResult.Lines -match 'State\s+.*Local Address:Port')) {
            Set-InvalidEvidence $listenerResult
        } else {
            $listeners = @(
                foreach ($line in $listenerResult.Lines) {
                    if ($line -match '^\s*LISTEN\s+\d+\s+\d+\s+(\S+:31181)\s+') {
                        [pscustomobject]@{ LocalEndpoint = $Matches[1]; State = 'LISTEN'; Ownership = 'not-established' }
                    }
                }
            )
        }
    }

    # Retain only event categories, timestamps and PIDs, never raw URLs/log text.
    # Unicode regex escapes keep the script source ASCII.
    $patterns = [ordered]@{
        'payload-extracted' = '\u89e3\u538b '
        'node-started' = 'node \u5df2\u542f\u52a8'
        'ready-marker-only' = '\u5c31\u7eea'
        'core-ready' = '^\[android\] READY 127\.0\.0\.1:31181$'
        'proxy-applied' = 'WebView \u4ee3\u7406\u5df2\u751f\u6548'
        'proxy-cleared' = '\u5df2\u6e05\u7a7a\u65e7\u4ee3\u7406'
        'proxy-unsupported' = '\u4e0d\u652f\u6301 PROXY_OVERRIDE'
        'port-conflict' = 'EADDRINUSE'
        'mitmproxy-startup-error' = 'mitmproxy \u542f\u52a8\u5931\u8d25'
        'startup-error' = '^\[android\] FAILED|startup \u5931\u8d25|\u6838\u5fc3\u542f\u52a8\u5931\u8d25|\u7f3a\u5c11 node'
        'node-exited' = 'node \u5df2\u9000\u51fa'
    }
    $events = @()
    $logResult = Invoke-AdbEvidence -Name 'bounded-accel-log' -AdbArguments @('logcat', '-d', '-t', '200', '-v', 'threadtime', 'dsha-accel:V', '*:S')
    if ($logResult.Probe.Status -eq 'available') {
        $events = @(
            foreach ($line in $logResult.Lines) {
                if ($line -match '^(?<at>\d\d-\d\d\s+\d\d:\d\d:\d\d\.\d+)\s+(?<pid>\d+)\s+\d+\s+[VDIWEF]\s+dsha-accel\s*:\s*(?<message>.*)$') {
                    $at = $Matches['at']
                    $logPid = [int]$Matches['pid']
                    $message = $Matches['message']
                    foreach ($eventName in $patterns.Keys) {
                        if ($message -match $patterns[$eventName]) {
                            [pscustomobject]@{ DeviceLogTime = $at; Pid = $logPid; Event = $eventName }
                        }
                    }
                }
            }
        )
    }

    $status = 'collected'
    if ($script:incomplete) { $status = 'incomplete' }
    $report = [ordered]@{
        SchemaVersion = 1
        CollectionStatus = $status
        ApplicationVerdict = 'not-assessed'
        StartedAt = $startedAt
        FinishedAt = [DateTimeOffset]::Now.ToString('o')
        DeviceSelection = 'serial-omitted'
        Metadata = $metadata
        WatchedPackages = $knownPackages
        InstalledTestPackages = $installed
        Processes = $processes
        ProxyListeners = $listeners
        AccelLogEvents = $events
        Probes = @($script:probes.ToArray())
        Limitations = @(
            'Read-only, sequential snapshot; the device can change between probes.'
            'Missing processes/listeners/events are observations, not a test pass.'
            'An ss listener has no verified owner and may belong to another test package.'
            'Logcat is bounded to the last 200 buffered records with the dsha-accel tag filter.'
            'Old events may belong to previous launches or another package; compare time and PID.'
            'A ready marker can coexist with asynchronous mitmproxy startup failure.'
            'No request URLs, page rendering, WebView version or private app files are collected.'
        )
    }
    $reportPath = Join-Path $OutputDirectory 'evidence.json'
    $report | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8
    Write-Output "Evidence: $reportPath"
    Write-Output "Collection status: $status. Application verdict: not assessed."
    if ($script:incomplete) { exit 1 }
    exit 0
} catch {
    # Keep adb diagnostics and device identifiers out of the terminal/report.
    Write-Warning 'Collection aborted. Check adb availability, device selection and the new output directory. No application pass is implied.'
    exit 2
}
