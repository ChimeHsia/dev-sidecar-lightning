$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $taskRoot 'netcore/build/guard-regression'
New-Item -ItemType Directory -Force $output | Out-Null
$guard = Join-Path $PSScriptRoot '../terra-install-guard.ps1'
$shell = (Get-Process -Id $PID).Path
$fake = Join-Path $PSScriptRoot 'fake-adb.ps1'
$oldScenario = $env:NATIVE_ECH_GUARD_TEST_SCENARIO
$oldLog = $env:NATIVE_ECH_GUARD_TEST_LOG
$oldExit = $global:LASTEXITCODE
$scenarios = @(
    'absent', 'offline', 'transport-exit', 'other-current-user', 'malformed-current-user',
    'list-exit', 'list-security-stdout-zero', 'list-security-stderr-zero',
    'list-error-stderr-zero', 'list-denied-stdout-zero', 'installed-user0', 'unexpected-list-output',
    'global-exit', 'global-empty', 'global-security-stdout-zero', 'global-security-stderr-zero',
    'installed-user100', 'retained-user100', 'wrong-global-package', 'contradictory-global-output'
)
try {
    foreach ($scenario in $scenarios) {
        $env:NATIVE_ECH_GUARD_TEST_SCENARIO = $scenario
        $env:NATIVE_ECH_GUARD_TEST_LOG = Join-Path $output "$scenario.log"
        [IO.File]::WriteAllText($env:NATIVE_ECH_GUARD_TEST_LOG, '')
        $global:LASTEXITCODE = 91 # Cannot be mistaken for the freshly captured process status.
        $passed = $false
        $result = $null
        try {
            $result = & $guard -Serial TEST_SERIAL -Adb $shell -AdbPrefixArguments @('-NoProfile', '-File', $fake)
            $passed = $true
        } catch {
            if ($scenario -eq 'absent') { throw }
        }
        if ($passed -ne ($scenario -eq 'absent')) { throw "Guard decision incorrect: $scenario" }
        if ($passed -and (-not $result.guardPassed -or $result.userId -ne 0 -or $result.installationPerformed)) {
            throw 'Success result has incorrect scope or performed an installation'
        }
        $queries = @(Get-Content $env:NATIVE_ECH_GUARD_TEST_LOG)
        if ($queries | Where-Object { $_ -match '(?i)\binstall\b|\buninstall\b|list users|packages -u' }) {
            throw "Non-scoped or mutating query in $scenario"
        }
        if ($scenario -eq 'absent' -and $queries.Count -ne 4) { throw 'Success skipped a required query' }
        if ($scenario -eq 'installed-user0' -and $queries.Count -ne 3) { throw 'Did not stop after local package found' }
        if ($scenario -eq 'other-current-user' -and $queries.Count -ne 2) { throw 'Queried package after wrong current user' }
        Write-Output "PASS $scenario"
    }
    Write-Output "OK ($($scenarios.Count) offline native-process guard tests; no device/ADB/Gradle)"
} finally {
    $env:NATIVE_ECH_GUARD_TEST_SCENARIO = $oldScenario
    $env:NATIVE_ECH_GUARD_TEST_LOG = $oldLog
    $global:LASTEXITCODE = $oldExit
}
