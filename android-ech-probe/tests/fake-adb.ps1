# Offline guard regression fixture. Never calls ADB, a phone, Gradle or a package manager.
$ErrorActionPreference = 'Stop'
$command = $args -join ' '
[IO.File]::AppendAllText($env:NATIVE_ECH_GUARD_TEST_LOG, $command + "`n")
$scenario = $env:NATIVE_ECH_GUARD_TEST_SCENARIO
$prefix = '-s TEST_SERIAL '
if (-not $command.StartsWith($prefix)) { [Console]::Error.WriteLine('Unexpected serial'); exit 9 }
$query = $command.Substring($prefix.Length)
switch ($query) {
    'get-state' {
        if ($scenario -eq 'offline') { [Console]::WriteLine('offline'); exit 0 }
        if ($scenario -eq 'transport-exit') { [Console]::WriteLine('device'); exit 7 }
        [Console]::WriteLine('device')
    }
    'shell am get-current-user' {
        if ($scenario -eq 'other-current-user') { [Console]::WriteLine('100'); exit 0 }
        if ($scenario -eq 'malformed-current-user') { [Console]::WriteLine('Current user: 0'); exit 0 }
        [Console]::WriteLine('0')
    }
    'shell pm list packages --user 0 -u com.dsha.nativeechtest' {
        switch ($scenario) {
            'list-exit' { exit 1 }
            'list-security-stdout-zero' { [Console]::WriteLine('java.lang.SecurityException: Shell cannot access user 100'); exit 0 }
            'list-security-stderr-zero' { [Console]::Error.WriteLine('java.lang.SecurityException: Shell cannot access user 100'); exit 0 }
            'list-error-stderr-zero' { [Console]::Error.WriteLine('daemon diagnostic'); exit 0 }
            'list-denied-stdout-zero' { [Console]::WriteLine('Permission Denial: user 100'); exit 0 }
            'installed-user0' { [Console]::WriteLine('package:com.dsha.nativeechtest'); exit 0 }
            'unexpected-list-output' { [Console]::WriteLine('package:com.dsha.nativeechtest.extra'); exit 0 }
        }
    }
    'shell dumpsys package com.dsha.nativeechtest' {
        switch ($scenario) {
            'global-exit' { [Console]::WriteLine('Unable to find package: com.dsha.nativeechtest'); exit 1 }
            'global-empty' { exit 0 }
            'global-security-stdout-zero' { [Console]::WriteLine('java.lang.SecurityException: Permission denied'); exit 0 }
            'global-security-stderr-zero' { [Console]::Error.WriteLine('SecurityException'); exit 0 }
            'installed-user100' { [Console]::WriteLine('Package [com.dsha.nativeechtest] (123): User 0: installed=false User 100: installed=true'); exit 0 }
            'retained-user100' { [Console]::WriteLine('Package [com.dsha.nativeechtest] (123): User 100: installed=false'); exit 0 }
            'wrong-global-package' { [Console]::WriteLine('Unable to find package: another.package'); exit 0 }
            'contradictory-global-output' {
                [Console]::WriteLine('Unable to find package: com.dsha.nativeechtest')
                [Console]::WriteLine('Package [com.dsha.nativeechtest] (123)')
                exit 0
            }
        }
        [Console]::WriteLine('Unable to find package: com.dsha.nativeechtest')
    }
    default { [Console]::Error.WriteLine('Forbidden or unexpected command'); exit 8 }
}
exit 0
