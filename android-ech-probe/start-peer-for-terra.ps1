param(
    [ValidateSet('accepted', 'reject', 'noech', 'wrong-host', 'expired', 'tamper', 'record-tamper', 'stall', 'cancel', 'untrusted')]
    [string]$Scenario = 'accepted',
    [int]$DevicePort = 31443
)
# Starts a test-only host peer and prints device commands. Does not invoke ADB or touch a device.
$ErrorActionPreference = 'Stop'
if ($DevicePort -lt 1024 -or $DevicePort -gt 65535) { throw 'Choose an unprivileged device reverse port' }
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$exe = Join-Path $taskRoot '.tools/go/ech-http-peer.exe'
if (-not (Test-Path $exe)) { throw 'Run the JVM integration check first to build the standard-library peer' }
$sessionRoot = Join-Path $taskRoot ('.tools/device-sessions/' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $sessionRoot | Out-Null
$peerMode = switch ($Scenario) { 'cancel' { 'stall' } 'untrusted' { 'accepted' } default { $Scenario } }
$expected = switch ($Scenario) {
    'accepted' { 'accepted' }
    'reject' { 'ECH_REJECTED' }
    'noech' { 'ECH_REJECTED' }
    'wrong-host' { 'PEER_AUTHENTICATION' }
    'expired' { 'PEER_AUTHENTICATION' }
    'untrusted' { 'PEER_AUTHENTICATION' }
    'cancel' { 'CANCELLED' }
    'stall' { 'TIMEOUT' }
    default { 'HANDSHAKE' }
}
$stdout = Join-Path $sessionRoot 'peer.jsonl'
$stderr = Join-Path $sessionRoot 'peer-stderr.txt'
$process = Start-Process -FilePath $exe -ArgumentList @('-mode', $peerMode, '-accept-timeout-seconds', '120') `
    -WindowStyle Hidden -RedirectStandardOutput $stdout -RedirectStandardError $stderr -PassThru
$ready = $null
$deadline = [DateTime]::UtcNow.AddSeconds(5)
while ([DateTime]::UtcNow -lt $deadline) {
    $line = Get-Content -LiteralPath $stdout -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($line) { $ready = $line | ConvertFrom-Json; break }
    if ($process.HasExited) { throw "Peer exited; inspect $stderr" }
    Start-Sleep -Milliseconds 50
}
if (-not $ready) { $process.Kill(); throw 'Peer failed to publish ready event' }
$trust = if ($Scenario -eq 'untrusted') { 'system' } else { 'anchors' }
$timeout = if ($Scenario -eq 'stall') { 700 } else { 3000 }
$argsText = "-e port $DevicePort -e expected $expected -e timeout_ms $timeout -e trust $trust " +
    "-e config_b64 $($ready.config_b64) -e ca_b64 $($ready.ca_b64)"
$activityExtras = "--es port $DevicePort --es expected $expected --es timeout_ms $timeout --es trust $trust " +
    "--es config_b64 $($ready.config_b64) --es ca_b64 $($ready.ca_b64)"
$fixture = [ordered]@{
    schema = 1
    scenario = $Scenario
    device_host = '127.0.0.1'
    device_port = $DevicePort
    host_port = $ready.port
    expected = $expected
    timeout_ms = $timeout
    trust = $trust
    config_b64 = $ready.config_b64
    ca_b64 = $ready.ca_b64
}
$fixture | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $sessionRoot 'public-fixture.json') -Encoding utf8
$commands = @(
    "# Scenario $Scenario; process $($process.Id); public test inputs only",
    "# Terra must select the intended device explicitly (replace DEVICE_SERIAL).",
    "adb -s DEVICE_SERIAL reverse tcp:$DevicePort tcp:$($ready.port)",
    "adb -s DEVICE_SERIAL shell am instrument -w -r $argsText com.dsha.nativeechtest/.EchProbeInstrumentation",
    "# Alternative Activity command; use a NEW peer session if instrumentation already consumed this one:",
    "adb -s DEVICE_SERIAL shell am start -W -n com.dsha.nativeechtest/.EchProbeActivity $activityExtras",
    "adb -s DEVICE_SERIAL logcat -d -s NativeEchProof:I '*:S'",
    "adb -s DEVICE_SERIAL reverse --remove tcp:$DevicePort",
    "# Host peer output: $stdout",
    "# Small PUBLIC fixture: $(Join-Path $sessionRoot 'public-fixture.json')",
    "# Require pass=true, globalDefaultsUnchanged=true AND independent peer evidence.",
    "# Negative cases: application_bytes=0. Accepted: ech_accepted=true and exact request_line."
)
[IO.File]::WriteAllLines((Join-Path $sessionRoot 'terra-commands.txt'), $commands)
Write-Output ($commands -join [Environment]::NewLine)
