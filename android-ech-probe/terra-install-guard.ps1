param(
    [Parameter(Mandatory = $true)][string]$Serial,
    [string]$Adb = 'D:\App\Dev\Android\platform-tools\adb.exe',
    [ValidateRange(0, 2147483647)][int]$UserId = 0,
    # Optional explicit executable-wrapper arguments, used by the offline fake-adb regression tests.
    [string[]]$AdbPrefixArguments = @()
)
# Terra-only read-only guard; this file never installs, replaces, starts, stops or uninstalls an app.
# Requires PowerShell 7/.NET ProcessStartInfo.ArgumentList. No shell command strings are evaluated.
$ErrorActionPreference = 'Stop'
$package = 'com.dsha.nativeechtest'
if ([string]::IsNullOrWhiteSpace($Serial)) { throw 'Explicit device serial required' }

function Invoke-ReadOnlyAdb([string[]]$CommandArguments) {
    $start = [System.Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Adb
    $start.UseShellExecute = $false
    $start.CreateNoWindow = $true
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    foreach ($argument in @($AdbPrefixArguments) + @('-s', $Serial) + $CommandArguments) {
        $start.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw 'ADB query did not start; absence unknown' }
        # Drain both streams independently, so stderr is neither lost nor treated as package data.
        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(10000)) {
            $process.Kill()
            throw 'ADB query timed out; absence unknown'
        }
        # This is the native process's actual exit status, independent of stale $LASTEXITCODE.
        $exitCode = $process.ExitCode
        $stdout = $stdoutTask.GetAwaiter().GetResult()
        $stderr = $stderrTask.GetAwaiter().GetResult()
        $combined = $stdout + "`n" + $stderr
        if ($exitCode -ne 0) { throw "ADB query returned native exit $exitCode; absence unknown" }
        if ($combined -match '(?i)SecurityException|Permission\s+Denial|permission\s+denied|Exception|Failure|(?:^|\n)\s*Error\b') {
            throw 'ADB query reported an error or restricted user scope; absence unknown'
        }
        if (-not [string]::IsNullOrWhiteSpace($stderr)) {
            throw 'ADB query produced stderr; absence unknown'
        }
        return $stdout.Trim()
    } finally {
        $process.Dispose()
    }
}

$state = Invoke-ReadOnlyAdb -CommandArguments @('get-state')
if ($state -cne 'device') { throw 'Selected device unavailable; absence unknown' }

$current = Invoke-ReadOnlyAdb -CommandArguments @('shell', 'am', 'get-current-user')
if ($current -notmatch '^(0|[1-9][0-9]*)$' -or $current -cne $UserId.ToString([cultureinfo]::InvariantCulture)) {
    throw "Current user must equal explicit target user $UserId; absence unknown"
}

$listed = Invoke-ReadOnlyAdb -CommandArguments @('shell', 'pm', 'list', 'packages', '--user', "$UserId", '-u', $package)
if (-not [string]::IsNullOrWhiteSpace($listed)) {
    if (($listed -split '\r?\n') -ccontains "package:$package") {
        throw "Package or retained package data exists for user $UserId; STOP, never overwrite"
    }
    throw 'Package-list output was not empty; absence is not established'
}

# An empty scoped list cannot exclude a package installed/retained for another user.
# Accept only the exact global not-found response; empty, denied or real package dumps all fail.
$globalDump = Invoke-ReadOnlyAdb -CommandArguments @('shell', 'dumpsys', 'package', $package)
if ($globalDump -cne "Unable to find package: $package") {
    throw 'Global package absence not confirmed; package may exist for another user. STOP'
}

[pscustomobject]@{
    guardPassed = $true
    serial = $Serial
    userId = $UserId
    package = $package
    scopedListEmpty = $true
    globalAbsenceConfirmed = $true
    readOnly = $true
    installationPerformed = $false
}
