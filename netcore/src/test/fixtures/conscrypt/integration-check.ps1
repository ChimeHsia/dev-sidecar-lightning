param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$GoExe,
    [string]$CacheRoot = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$output = Join-Path $taskRoot 'netcore/build/ech-integration'
New-Item -ItemType Directory -Force $output, (Join-Path $taskRoot '.tools/go') | Out-Null
$taskOldEnv = @{}
foreach ($name in @('GOCACHE', 'GOMODCACHE', 'GOTOOLCHAIN', 'GOMAXPROCS', 'GOMEMLIMIT', 'GOPROXY')) {
    $taskOldEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $env:GOCACHE = Join-Path $taskRoot '.tools/go/build-cache'
    $env:GOMODCACHE = Join-Path $taskRoot '.tools/go/module-cache'
    $env:GOTOOLCHAIN = 'local'
    $env:GOMAXPROCS = '2'
    $env:GOMEMLIMIT = '192MiB'
    $env:GOPROXY = 'off'
    & $GoExe build -p=1 -trimpath -o (Join-Path $taskRoot '.tools/go/ech-peer.exe') (Join-Path $PSScriptRoot 'ech-peer.go')
    if ($LASTEXITCODE -ne 0) { throw 'Independent ECH peer compilation failed' }
    & $GoExe build -p=1 -trimpath -o (Join-Path $taskRoot '.tools/go/ech-http-peer.exe') (Join-Path $PSScriptRoot 'ech-http-peer.go')
    if ($LASTEXITCODE -ne 0) { throw 'Independent HTTP ECH peer compilation failed' }
} finally {
    foreach ($name in $taskOldEnv.Keys) { [Environment]::SetEnvironmentVariable($name, $taskOldEnv[$name], 'Process') }
}
function Jar([string]$group, [string]$name, [string]$version) {
    $file = Get-ChildItem -LiteralPath "$CacheRoot/$group/$name/$version" -Recurse -Filter "$name-$version.jar" |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $file) { throw "Missing cached dependency $group/$name/$version" }
    return $file
}
$stdlib = Jar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.0.21'
$annotations = Jar 'org.jetbrains' 'annotations' '13.0'
$junit = Jar 'junit' 'junit' '4.13.2'
$hamcrest = Jar 'org.hamcrest' 'hamcrest-core' '1.3'
$conscrypt = Join-Path $taskRoot '.tools/conscrypt/conscrypt-openjdk-uber-2.8.0.jar'
if ((Get-FileHash $conscrypt -Algorithm SHA256).Hash -ne '4022024808c54211275c7d58ed81e4e13ed8ba170351588cba19f6b947478668') {
    throw 'Conscrypt JAR hash mismatch'
}
$runtime = @(
    $output, $stdlib, $annotations, $conscrypt, $junit, $hamcrest,
    (Jar 'org.bouncycastle' 'bcprov-jdk18on' '1.79'),
    (Jar 'org.bouncycastle' 'bcpkix-jdk18on' '1.79'),
    (Jar 'org.bouncycastle' 'bcutil-jdk18on' '1.79')
) -join ';'
$compiler = @(
    (Jar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '2.0.21'),
    $stdlib, $annotations,
    (Jar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '2.0.21'),
    (Jar 'org.jetbrains.kotlin' 'kotlin-reflect' '1.6.10'),
    (Jar 'org.jetbrains.kotlin' 'kotlin-daemon-embeddable' '2.0.21'),
    (Jar 'org.jetbrains.intellij.deps' 'trove4j' '1.0.20200330'),
    (Jar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.6.4')
) -join ';'
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
& $javac -J-Xmx256m --release 17 -cp $runtime -d $output `
    (Join-Path $taskRoot 'netcore/src/main/java/org/conscrypt/NativeEchPolicyAdapter.java') `
    (Join-Path $taskRoot 'netcore/src/test/java/org/conscrypt/NativeEchPolicyAdapterTest.java')
if ($LASTEXITCODE -ne 0) { throw 'Java adapter compilation failed' }
$sources = @(
    foreach ($package in @('http', 'tls', 'native', 'ech')) {
        Get-ChildItem "$taskRoot/netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/$package" -Filter '*.kt'
    }
    Get-Item "$taskRoot/netcore/src/main/kotlin/com/dsha/sidecar/net/dns/EchConfigList.kt"
    Get-Item "$taskRoot/netcore/src/test/kotlin/com/dsha/sidecar/net/proxy/tls/EphemeralTestCa.kt"
    Get-Item "$taskRoot/netcore/src/test/kotlin/com/dsha/sidecar/net/proxy/native/ConscryptNativeTlsDialerTest.kt"
    Get-Item "$taskRoot/netcore/src/test/kotlin/com/dsha/sidecar/net/proxy/ech/ConscryptEchConnectorTest.kt"
    Get-Item "$taskRoot/netcore/src/test/kotlin/com/dsha/sidecar/net/proxy/ech/EchAndroidDiagnosticsTest.kt"
) | Select-Object -ExpandProperty FullName
& $java -Xmx256m '-Dfile.encoding=UTF-8' -cp $compiler org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 17 -classpath $runtime -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Direct integration compilation failed' }
$proof = Join-Path $output 'connector-evidence.txt'
$integration = Join-Path $output 'proxy-evidence.txt'
Set-Content $proof 'Frozen connector tests; official Conscrypt 2.8.0 and independent Go peer'
Set-Content $integration 'NativeProxyServer verified CONNECT/MITM -> Conscrypt ECH -> Go HTTP origin'
& $java -Xmx128m '-Dfile.encoding=UTF-8' "-Dech.peer.exe=$taskRoot/.tools/go/ech-peer.exe" `
    "-Dech.proof.output=$proof" "-Dech.http.peer.exe=$taskRoot/.tools/go/ech-http-peer.exe" `
    "-Dech.integration.output=$integration" -cp $runtime org.junit.runner.JUnitCore `
    com.dsha.sidecar.net.proxy.ech.ConscryptEchConnectorTest `
    org.conscrypt.NativeEchPolicyAdapterTest `
    com.dsha.sidecar.net.proxy.ech.EchAndroidDiagnosticsTest `
    com.dsha.sidecar.net.proxy.native.ConscryptNativeTlsDialerTest
if ($LASTEXITCODE -ne 0) { throw 'Direct integration tests failed' }
Get-Content $integration
