[CmdletBinding()]
param(
    [ValidateSet('All', 'Node', 'Netcore', 'Browser')]
    [string]$Suite = 'All',
    [switch]$Offline,
    [string]$JavaHome,
    [string]$GradleBin
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$originalJavaHome = $env:JAVA_HOME
$originalPath = $env:Path
$failures = @()

function Get-TestJavaHome {
    if ($JavaHome) {
        $candidate = $JavaHome
    } elseif (Test-Path -LiteralPath (Join-Path $repoRoot '.jdk\jdk-21.0.2\bin\java.exe')) {
        $candidate = Join-Path $repoRoot '.jdk\jdk-21.0.2'
    } else {
        $candidate = $env:JAVA_HOME
    }
    if (-not $candidate -or -not (Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe'))) {
        throw 'Set -JavaHome or JAVA_HOME to a JDK 21 installation.'
    }
    return (Resolve-Path -LiteralPath $candidate).Path
}

function Get-NetcoreGradle {
    if ($GradleBin) {
        return (Get-Command $GradleBin -ErrorAction Stop).Source
    }
    $gradleUserHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else {
        Join-Path $env:USERPROFILE '.gradle'
    }
    foreach ($distribution in @('gradle-8.14-all', 'gradle-8.14.5-bin')) {
        $cache = Join-Path $gradleUserHome "wrapper\dists\$distribution"
        if (Test-Path -LiteralPath $cache) {
            $binary = Get-ChildItem -LiteralPath $cache -Filter gradle.bat -Recurse |
                Select-Object -First 1
            if ($binary) { return $binary.FullName }
        }
    }
    $installed = Get-Command gradle -ErrorAction SilentlyContinue
    if ($installed) { return $installed.Source }
    throw 'Root modules require Gradle 8.14. Set -GradleBin to its gradle.bat.'
}

function Show-JunitResults([string]$ResultsPath) {
    $reports = @(Get-ChildItem -LiteralPath $ResultsPath -Filter 'TEST-*.xml' -ErrorAction Stop)
    if ($reports.Count -eq 0) { throw "No JUnit results found in $ResultsPath" }
    $tests = 0
    $skipped = 0
    $failed = 0
    foreach ($report in $reports) {
        [xml]$result = Get-Content -LiteralPath $report.FullName -Raw
        $tests += [int]$result.testsuite.tests
        $skipped += [int]$result.testsuite.skipped
        $failed += [int]$result.testsuite.failures + [int]$result.testsuite.errors
    }
    Write-Host "JUnit results: $tests tests, $skipped skipped, $failed failures/errors."
    Write-Host "Reports: $ResultsPath"
    if ($tests -eq 0 -or $failed -gt 0) { throw 'JUnit results did not establish a passing baseline.' }
}

$selected = if ($Suite -eq 'All') { @('Node', 'Netcore', 'Browser') } else { @($Suite) }
try {
    foreach ($current in $selected) {
        Write-Host "`nRunning $current regression baseline..."
        try {
            if ($current -eq 'Node') {
                $node = (Get-Command node -ErrorAction Stop).Source
                $tests = @(Get-ChildItem -LiteralPath (Join-Path $repoRoot 'port\tests') -Filter '*.test.cjs')
                if ($tests.Count -eq 0) { throw 'No Node contract tests found.' }
                & $node --test @($tests.FullName)
                if ($LASTEXITCODE -ne 0) { throw "Node exited with code $LASTEXITCODE." }
            } else {
                $env:JAVA_HOME = Get-TestJavaHome
                $env:Path = "$env:JAVA_HOME\bin;$originalPath"
                $gradleArgs = @("--console=plain", "-Dorg.gradle.java.home=$env:JAVA_HOME")
                if ($Offline) { $gradleArgs += '--offline' }
                if ($current -eq 'Netcore') {
                    $binary = Get-NetcoreGradle
                    $gradleArgs += @('-p', $repoRoot, ':netcore:test', '-Pdsha.live=')
                    & $binary @gradleArgs
                    if ($LASTEXITCODE -ne 0) { throw "Gradle exited with code $LASTEXITCODE." }
                    Show-JunitResults (Join-Path $repoRoot 'netcore\build\test-results\test')
                } else {
                    $binary = Join-Path $repoRoot 'lightning-browser\gradlew.bat'
                    $gradleArgs += @(
                        '-p', (Join-Path $repoRoot 'lightning-browser'),
                        ':app:testLightningPlusDebugUnitTest',
                        '--no-configuration-cache',
                        '-Dorg.gradle.unsafe.isolated-projects=false'
                    )
                    & $binary @gradleArgs
                    if ($LASTEXITCODE -ne 0) { throw "Gradle exited with code $LASTEXITCODE." }
                    Show-JunitResults (Join-Path $repoRoot 'lightning-browser\app\build\test-results\testLightningPlusDebugUnitTest')
                }
            }
            Write-Host "$current baseline: PASS"
        } catch {
            $failures += $current
            Write-Warning "$current baseline: FAIL - $($_.Exception.Message)"
        }
    }
} finally {
    $env:JAVA_HOME = $originalJavaHome
    $env:Path = $originalPath
}

if ($failures.Count -gt 0) {
    Write-Error "Baseline failed: $($failures -join ', ')." -ErrorAction Continue
    exit 1
}
Write-Host "`nSelected regression suites passed. Device rendering requires separate manual acceptance."
exit 0
