param(
    [Parameter(Mandatory = $true)][string]$Java,
    [string]$Cache = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $root 'build/native-proxy'
New-Item -ItemType Directory -Force $output | Out-Null
function Jar([string]$group, [string]$name, [string]$version) {
    $file = Get-ChildItem -LiteralPath "$Cache/$group/$name/$version" -Recurse -Filter "$name-$version.jar" |
        Select-Object -First 1
    if (!$file) { throw "Missing cached dependency: $group/$name/$version" }
    return $file.FullName
}
$stdlib = Jar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.0.21'
$compiler = @(
    (Jar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '2.0.21'),
    $stdlib,
    (Jar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '2.0.21'),
    (Jar 'org.jetbrains.kotlin' 'kotlin-reflect' '2.2.10'),
    (Jar 'org.jetbrains.kotlin' 'kotlin-daemon-embeddable' '2.0.21'),
    (Jar 'org.jetbrains.intellij.deps' 'trove4j' '1.0.20200330'),
    (Jar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.6.4'),
    (Jar 'org.jetbrains' 'annotations' '13.0')
)
$runtime = @(
    $stdlib,
    (Jar 'org.jetbrains' 'annotations' '13.0'),
    (Jar 'org.bouncycastle' 'bcprov-jdk18on' '1.79'),
    (Jar 'org.bouncycastle' 'bcpkix-jdk18on' '1.79'),
    (Jar 'org.bouncycastle' 'bcutil-jdk18on' '1.79'),
    (Jar 'junit' 'junit' '4.13.2'),
    (Jar 'org.hamcrest' 'hamcrest-core' '1.3')
) -join ';'
$sources = @(
    foreach ($package in @('http', 'tls', 'native')) {
        Get-ChildItem "$root/netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/$package" -Filter '*.kt'
    }
    Get-ChildItem "$root/netcore/src/test/kotlin/com/dsha/sidecar/net/proxy/native" -Filter '*.kt'
    Get-Item "$root/netcore/src/test/kotlin/com/dsha/sidecar/net/proxy/tls/EphemeralTestCa.kt"
) | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
$arguments = @(
    '-no-stdlib', '-no-reflect', '-jvm-target', '17',
    '-classpath', ('"' + $runtime.Replace('\', '/') + '"'),
    '-d', ('"' + "$output/engine-tests.jar".Replace('\', '/') + '"')
) + $sources
$argFile = Join-Path $output 'compiler.args'
[IO.File]::WriteAllLines($argFile, $arguments)
& $Java '-Xmx256m' '-cp' ($compiler -join ';') 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' "@$argFile"
if ($LASTEXITCODE -ne 0) { throw "Direct Kotlin compilation failed: $LASTEXITCODE" }
& $Java '-Xmx256m' '-cp' "$output/engine-tests.jar;$runtime" 'org.junit.runner.JUnitCore' `
    'com.dsha.sidecar.net.proxy.native.NativeProxyServerTest' `
    'com.dsha.sidecar.net.proxy.native.NativeProxyCompatibilityTest'
if ($LASTEXITCODE -ne 0) { throw "Native proxy tests failed: $LASTEXITCODE" }
