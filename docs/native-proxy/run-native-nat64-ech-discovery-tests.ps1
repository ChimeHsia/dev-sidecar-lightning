param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$DependencyCache = "$(if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE '.gradle' })/caches/modules-2/files-2.1"
)

$ErrorActionPreference = 'Stop'
$workspace = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$output = Join-Path $workspace ('netcore\build\native-nat64-ech-discovery-' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $output
$temporary = Join-Path $output 'tmp'
$null = New-Item -ItemType Directory -Path $temporary

function CachedJar([string]$group, [string]$artifact, [string]$version) {
    $base = Join-Path $DependencyCache "$group\$artifact\$version"
    $matches = @(Get-ChildItem -LiteralPath $base -Recurse -File -Filter "$artifact-$version.jar")
    if ($matches.Count -ne 1) { throw "Expected one cached $artifact $version jar at $base" }
    return $matches[0].FullName
}

$stdlib = CachedJar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.0.21'
$annotations = CachedJar 'org.jetbrains' 'annotations' '13.0'
$junit = CachedJar 'junit' 'junit' '4.13.2'
$hamcrest = CachedJar 'org.hamcrest' 'hamcrest-core' '1.3'
$kotlinTest = CachedJar 'org.jetbrains.kotlin' 'kotlin-test' '2.0.21'
$kotlinJunit = CachedJar 'org.jetbrains.kotlin' 'kotlin-test-junit' '2.0.21'
$bc = @('bcprov-jdk18on', 'bcpkix-jdk18on', 'bcutil-jdk18on') |
    ForEach-Object { CachedJar 'org.bouncycastle' $_ '1.79' }
$compilerClasspath = @(
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '2.0.21'),
    $stdlib,
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '2.0.21'),
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-reflect' '1.6.10'),
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-daemon-embeddable' '2.0.21'),
    (CachedJar 'org.jetbrains.intellij.deps' 'trove4j' '1.0.20200330'),
    (CachedJar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.6.4'),
    $annotations
) -join [IO.Path]::PathSeparator
$testClasspath = (@($stdlib, $annotations, $junit, $hamcrest, $kotlinTest, $kotlinJunit) + $bc) -join [IO.Path]::PathSeparator
$java = Join-Path $JavaHome 'bin\java.exe'
$classes = Join-Path $output 'classes.jar'
$sources = @(Get-ChildItem -LiteralPath (Join-Path $workspace 'netcore\src\main\kotlin') -Recurse -File -Filter '*.kt' |
    ForEach-Object { $_.FullName })
$testRoot = Join-Path $workspace 'netcore\src\test\kotlin\com\dsha\sidecar\net'
$sources += @(
    (Join-Path $testRoot 'dns\native\NativeDnsResolverTest.kt'),
    (Join-Path $testRoot 'dns\native\NativeDnsTestPackets.kt'),
    (Join-Path $testRoot 'dns\native\NativeDnsWireTest.kt'),
    (Join-Path $testRoot 'dns\native\NativeDnsTestTls.kt'),
    (Join-Path $testRoot 'dns\native\NativeDnsTestServers.kt'),
    (Join-Path $testRoot 'dns\native\SocketDnsTransportTest.kt'),
    (Join-Path $testRoot 'dns\native\NativeEchTestData.kt'),
    (Join-Path $testRoot 'dns\native\NativeHttpsWireTest.kt'),
    (Join-Path $testRoot 'dns\native\SocketHttpsTransportTest.kt'),
    (Join-Path $testRoot 'dns\native\NativeEchResolverTest.kt'),
    (Join-Path $testRoot 'dns\native\NativeNat64JsonTest.kt'),
    (Join-Path $testRoot 'dns\native\NativeNat64TransportTest.kt'),
    (Join-Path $testRoot 'dns\native\NativeNat64Ipv6Test.kt'),
    (Join-Path $testRoot 'dns\native\NativeNat64EchDiscoveryTest.kt'),
    (Join-Path $testRoot 'dns\native\NativeNat64EchDiscoveryIpv6Test.kt'),
    (Join-Path $testRoot 'dns\IpAddressTest.kt'),
    (Join-Path $testRoot 'dns\Nat64AddressTest.kt'),
    (Join-Path $testRoot 'dns\DnsPacketTest.kt'),
    (Join-Path $testRoot 'rules\HostnameMatcherTest.kt')
)

Write-Output "Direct Kotlin 2.0.21 / JDK 21 compile; -Xmx256m; output: $output"
& $java '-Xmx256m' "-Djava.io.tmpdir=$temporary" '-cp' $compilerClasspath `
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' '-no-stdlib' '-no-reflect' `
    '-jvm-target' '17' '-classpath' $testClasspath '-d' $classes @sources
if ($LASTEXITCODE -ne 0) { throw "Compiler exited with $LASTEXITCODE" }

Write-Output 'JUnit 4.13.2; -Xmx192m; automatic NAT64 ECH discovery plus frozen 232 regressions'
& $java '-Xmx192m' "-Djava.io.tmpdir=$temporary" '-cp' "$classes;$testClasspath" `
    'com.dsha.sidecar.net.dns.native.NativeNat64TestRunner' `
    'com.dsha.sidecar.net.dns.native.NativeDnsResolverTest' `
    'com.dsha.sidecar.net.dns.native.NativeDnsWireTest' `
    'com.dsha.sidecar.net.dns.native.SocketDnsTransportTest' `
    'com.dsha.sidecar.net.dns.native.NativeHttpsWireTest' `
    'com.dsha.sidecar.net.dns.native.SocketHttpsTransportTest' `
    'com.dsha.sidecar.net.dns.native.NativeEchResolverTest' `
    'com.dsha.sidecar.net.dns.native.NativeNat64JsonTest' `
    'com.dsha.sidecar.net.dns.native.NativeNat64TransportTest' `
    'com.dsha.sidecar.net.dns.native.NativeNat64Ipv6Test' `
    'com.dsha.sidecar.net.dns.native.NativeNat64EchDiscoveryTest' `
    'com.dsha.sidecar.net.dns.native.NativeNat64EchDiscoveryIpv6Test' `
    'com.dsha.sidecar.net.dns.IpAddressTest' `
    'com.dsha.sidecar.net.dns.Nat64AddressTest' `
    'com.dsha.sidecar.net.dns.DnsPacketTest' `
    'com.dsha.sidecar.net.rules.HostnameMatcherTest'
if ($LASTEXITCODE -ne 0) { throw "JUnit exited with $LASTEXITCODE" }
