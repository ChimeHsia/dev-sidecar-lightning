[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$Project,
    [string]$UpstreamProject
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$saved = @{
    DSHA_TEST_PROJECT = $env:DSHA_TEST_PROJECT
    DSHA_KOTLIN_HELPERS = $env:DSHA_KOTLIN_HELPERS
    DSHA_WAVE2_UPSTREAM = $env:DSHA_WAVE2_UPSTREAM
    DSHA_EXPECT_PACKAGED_VALIDATION = $env:DSHA_EXPECT_PACKAGED_VALIDATION
}
$failed = $false
try {
    $env:DSHA_TEST_PROJECT = (Resolve-Path -LiteralPath $Project).Path
    $env:DSHA_KOTLIN_HELPERS = Join-Path $env:DSHA_TEST_PROJECT 'kotlin/dev-sidecar-lightning-netcore-js.js'
    if (-not (Test-Path -LiteralPath $env:DSHA_KOTLIN_HELPERS)) {
        throw 'The extracted project must contain its compiled Kotlin/JS library.'
    }
    $env:DSHA_WAVE2_UPSTREAM = if ($UpstreamProject) {
        (Resolve-Path -LiteralPath $UpstreamProject).Path
    } else { $null }
    $env:DSHA_EXPECT_PACKAGED_VALIDATION = '1'
    $files = @(
        'node-entrypoint.test.cjs', 'http-navigation.test.js', 'proxy-runtime.test.cjs',
        'kotlin-helpers.test.js', 'kotlin-match-runtime.test.cjs', 'kotlin-choice-runtime.test.cjs',
        'kotlin-request-runtime.test.cjs', 'migration-wave2.test.cjs',
        'runtime-integration.test.js', 'proxy-transport.test.js', 'request-validation.test.js',
        'request-http2.test.cjs'
    ) | ForEach-Object { Join-Path $repoRoot "port/tests/$_" }
    & node --test --test-concurrency=1 --unhandled-rejections=strict @files
    if ($LASTEXITCODE -ne 0) { throw "Runtime regressions failed ($LASTEXITCODE)." }
    Write-Host 'Packaged-runtime local checks passed. Device acceptance remains separate.'
} catch {
    $failed = $true
    Write-Error $_ -ErrorAction Continue
} finally {
    foreach ($name in $saved.Keys) {
        [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process')
    }
}
if ($failed) { exit 1 }
