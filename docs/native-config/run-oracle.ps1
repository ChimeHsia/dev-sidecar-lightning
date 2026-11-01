param(
    [Parameter(Mandatory = $true)][string]$UpstreamCore,
    [string]$Java = 'D:\File\User\code\dev-sidecar-lightning\.jdk\jdk-21.0.2\bin\java.exe',
    [switch]$Regenerate
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = "$root/build/native-config"
$fixtures = "$root/netcore/src/test/fixtures/native-config"
if (!(Test-Path "$output/config-tests.jar")) { throw 'Run run-tests.ps1 first.' }
if ($Regenerate) {
    & node '--max-old-space-size=128' "$PSScriptRoot/oracle.cjs" generate $UpstreamCore $output
    if ($LASTEXITCODE -ne 0) { throw 'Upstream oracle generation failed' }
} else {
    Copy-Item -LiteralPath "$fixtures/oracle-cases.json" -Destination "$output/oracle-cases.json"
    Copy-Item -LiteralPath "$fixtures/upstream-provenance.json" -Destination "$output/upstream-provenance.json"
}
$runtime = Get-Content -Raw "$output/runtime-classpath.txt"
& $Java '-Xmx256m' '-XX:+UseSerialGC' '-XX:ActiveProcessorCount=2' '-cp' $runtime `
    'com.dsha.sidecar.net.config.native.NativeConfigOracleMain' `
    "$output/oracle-cases.json" "$output/kotlin-oracle-results.json"
if ($LASTEXITCODE -ne 0) { throw 'Kotlin oracle projection failed' }
& node '--max-old-space-size=128' "$PSScriptRoot/oracle.cjs" verify $UpstreamCore $output *> "$output/oracle-verify.log"
$code = $LASTEXITCODE
Get-Content "$output/oracle-verify.log"
if ($code -ne 0) { throw "Independent oracle failed ($code)" }
