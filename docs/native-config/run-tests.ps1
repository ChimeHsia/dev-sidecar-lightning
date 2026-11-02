param(
    [string]$Java = 'D:\File\User\code\dev-sidecar-lightning\.jdk\jdk-21.0.2\bin\java.exe',
    [string]$Cache = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $root 'build/native-config'
New-Item -ItemType Directory -Force -Path $output | Out-Null
function Jar([string]$group, [string]$name, [string]$version) {
    $file = Get-ChildItem -LiteralPath "$Cache/$group/$name/$version" -Recurse -Filter "$name-$version.jar" | Select-Object -First 1
    if (!$file) { throw "Missing cached dependency: $group/$name/$version" }
    $file.FullName
}
$stdlib = Jar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.0.21'
$compiler = @(
    (Jar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '2.0.21'), $stdlib,
    (Jar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '2.0.21'),
    (Jar 'org.jetbrains.kotlin' 'kotlin-reflect' '2.2.10'),
    (Jar 'org.jetbrains.kotlin' 'kotlin-daemon-embeddable' '2.0.21'),
    (Jar 'org.jetbrains.intellij.deps' 'trove4j' '1.0.20200330'),
    (Jar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.6.4'),
    (Jar 'org.jetbrains' 'annotations' '13.0')
)
$runtime = @(
    $stdlib, (Jar 'org.jetbrains' 'annotations' '13.0'),
    (Jar 'junit' 'junit' '4.13.2'), (Jar 'org.hamcrest' 'hamcrest-core' '1.3'),
    (Jar 'org.jetbrains.kotlin' 'kotlin-test' '2.0.21'),
    (Jar 'org.jetbrains.kotlin' 'kotlin-test-junit' '2.0.21')
) -join ';'
$sources = @(
    Get-Item "$root/netcore/src/main/kotlin/com/dsha/sidecar/net/config/ConfigMerge.kt"
    Get-ChildItem "$root/netcore/src/main/kotlin/com/dsha/sidecar/net/config/native" -Filter '*.kt'
    Get-Item "$root/netcore/src/test/kotlin/com/dsha/sidecar/net/config/ConfigMergeTest.kt"
    Get-ChildItem "$root/netcore/src/test/kotlin/com/dsha/sidecar/net/config/native" -Filter '*.kt'
) | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
$arguments = @(
    '-no-stdlib', '-no-reflect', '-jvm-target', '17',
    '-classpath', ('"' + $runtime.Replace('\', '/') + '"'),
    '-d', ('"' + "$output/config-tests.jar".Replace('\', '/') + '"')
) + $sources
[IO.File]::WriteAllLines("$output/compiler.args", $arguments)
& $Java '-Xmx256m' '-XX:+UseSerialGC' '-XX:ActiveProcessorCount=2' '-cp' ($compiler -join ';') `
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' "@$output/compiler.args" *> "$output/compile.log"
if ($LASTEXITCODE -ne 0) { Get-Content "$output/compile.log" -Tail 80; throw 'Direct compilation failed' }
$tests = @(
    'com.dsha.sidecar.net.config.ConfigMergeTest',
    'com.dsha.sidecar.net.config.native.BoundedJson5Test',
    'com.dsha.sidecar.net.config.native.NativeConfigRepositoryTest',
    'com.dsha.sidecar.net.config.native.RemoteConfigPolicyTest',
    'com.dsha.sidecar.net.config.native.LegacyFileCompatibilityTest',
    'com.dsha.sidecar.net.config.native.HttpsRemoteConfigFetcherTest'
)
& $Java '-Xmx256m' '-XX:+UseSerialGC' '-XX:ActiveProcessorCount=2' '-cp' "$output/config-tests.jar;$runtime" `
    'org.junit.runner.JUnitCore' @tests *> "$output/junit.log"
$testExit = $LASTEXITCODE
Get-Content "$output/junit.log"
if ($testExit -ne 0) { throw "Local config tests failed ($testExit)" }
[IO.File]::WriteAllText("$output/runtime-classpath.txt", "$output/config-tests.jar;$runtime")
[IO.File]::WriteAllText("$output/compiler-classpath.txt", ($compiler -join ';'))
Write-Host 'Direct compiler and local tests complete. No Gradle or device used.'
