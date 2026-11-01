[CmdletBinding()]
param(
    [switch]$Offline,
    [string]$JavaHome,
    [string]$GradleBin,
    [string]$UpstreamProject
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$oldLocation = Get-Location
$oldJava = $env:JAVA_HOME
$oldPath = $env:Path
$oldHelpers = $env:DSHA_KOTLIN_HELPERS
$oldUpstream = $env:DSHA_WAVE2_UPSTREAM
$failed = $false

try {
    if (-not $JavaHome) {
        $localJdk = Join-Path $repoRoot '.jdk\jdk-21.0.2'
        $JavaHome = if (Test-Path -LiteralPath "$localJdk\bin\java.exe") { $localJdk } else { $env:JAVA_HOME }
    }
    if (-not $JavaHome -or -not (Test-Path -LiteralPath "$JavaHome\bin\java.exe")) {
        throw 'Set -JavaHome or JAVA_HOME to JDK 21.'
    }
    $env:JAVA_HOME = (Resolve-Path -LiteralPath $JavaHome).Path
    $env:Path = "$env:JAVA_HOME\bin;$oldPath"

    if (-not $GradleBin) {
        $gradleCache = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' }
        foreach ($distribution in @('gradle-8.14-all', 'gradle-8.14.5-bin')) {
            $cache = Join-Path $gradleCache "wrapper\dists\$distribution"
            if (Test-Path -LiteralPath $cache) {
                $binary = Get-ChildItem -LiteralPath $cache -Filter gradle.bat -Recurse | Select-Object -First 1
                if ($binary) { $GradleBin = $binary.FullName; break }
            }
        }
    }
    if (-not $GradleBin) { throw 'Set -GradleBin to Gradle 8.14 gradle.bat.' }
    $GradleBin = (Get-Command $GradleBin -ErrorAction Stop).Source
    $node = (Get-Command node -ErrorAction Stop).Source
    Set-Location -LiteralPath $repoRoot

    $gradleArguments = @(
        ':netcore:test', ':netcore-js:jsNodeProductionLibraryDistribution',
        '-Pdsha.live=', '--console=plain', '--no-daemon', '--max-workers=1',
        "-Dorg.gradle.java.home=$env:JAVA_HOME",
        '-Dorg.gradle.jvmargs=-Xms32m -Xmx768m -XX:+UseSerialGC -XX:ActiveProcessorCount=2 -XX:ReservedCodeCacheSize=96m',
        '-Pkotlin.compiler.execution.strategy=in-process'
    )
    if ($Offline) { $gradleArguments += '--offline' }
    & $GradleBin @gradleArguments
    if ($LASTEXITCODE -ne 0) { throw "Kotlin build/tests failed ($LASTEXITCODE)." }

    $env:DSHA_KOTLIN_HELPERS = Join-Path $repoRoot 'netcore-js\build\dist\js\productionLibrary\dev-sidecar-lightning-netcore-js.js'
    if (-not (Test-Path -LiteralPath $env:DSHA_KOTLIN_HELPERS)) { throw 'Kotlin/JS output is missing.' }
    $env:DSHA_WAVE2_UPSTREAM = if ($UpstreamProject) { (Resolve-Path -LiteralPath $UpstreamProject).Path } else { $null }
    & $node --test --unhandled-rejections=strict --test-concurrency=1 `
        'port/tests/migration-wave2.test.cjs' `
        'port/tests/kotlin-helpers.test.js' `
        'port/tests/kotlin-match-runtime.test.cjs' `
        'port/tests/kotlin-choice-runtime.test.cjs' `
        'port/tests/kotlin-request-runtime.test.cjs'
    if ($LASTEXITCODE -ne 0) { throw "Wave-two differential tests failed ($LASTEXITCODE)." }
    if (-not $UpstreamProject) {
        Write-Host 'Original Node replay was omitted. Supply -UpstreamProject to validate the stored fixtures against the extracted core.'
    }
    Write-Host 'Wave-two JVM, Kotlin/JS, fixture comparisons and selected runtime adapter tests passed. Device acceptance is separate.'
} catch {
    $failed = $true
    Write-Error $_ -ErrorAction Continue
} finally {
    $env:JAVA_HOME = $oldJava
    $env:Path = $oldPath
    $env:DSHA_KOTLIN_HELPERS = $oldHelpers
    $env:DSHA_WAVE2_UPSTREAM = $oldUpstream
    Set-Location -LiteralPath $oldLocation
}
if ($failed) { exit 1 }
