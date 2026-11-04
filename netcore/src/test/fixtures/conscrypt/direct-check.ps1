param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$CacheRoot = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$taskOutput = [IO.Path]::GetFullPath((Join-Path $taskRoot 'netcore/build/conscrypt-ech-check'))
New-Item -ItemType Directory -Force $taskOutput | Out-Null
function CachedJar([string]$group, [string]$name, [string]$version) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $CacheRoot "$group/$name/$version") -Filter "$name-$version.jar" -Recurse |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached jar $group/$name/$version" }
    return $jar
}
$stdlib = CachedJar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.0.21'
$annotations = CachedJar 'org.jetbrains' 'annotations' '13.0'
$junit = CachedJar 'junit' 'junit' '4.13.2'
$hamcrest = CachedJar 'org.hamcrest' 'hamcrest-core' '1.3'
$conscrypt = Join-Path $taskRoot '.tools/conscrypt/conscrypt-openjdk-uber-2.8.0.jar'
$android = Join-Path $taskRoot '.tools/conscrypt/android-api/classes.jar'
$peer = Join-Path $taskRoot '.tools/go/ech-peer.exe'
if ((Get-FileHash $conscrypt -Algorithm SHA256).Hash -ne '4022024808c54211275c7d58ed81e4e13ed8ba170351588cba19f6b947478668') {
    throw 'Official Conscrypt JAR hash mismatch'
}
if ((Get-FileHash $android -Algorithm SHA256).Hash -ne '2cd9b6bc444cfca69048e8327383b7f5a13fc7978465e6e0fd5111a2000822f6') {
    throw 'Official Android classes.jar hash mismatch'
}
$compilerJars = @(
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '2.0.21'),
    $stdlib,
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '2.0.21'),
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-reflect' '1.6.10'),
    (CachedJar 'org.jetbrains.kotlin' 'kotlin-daemon-embeddable' '2.0.21'),
    (CachedJar 'org.jetbrains.intellij.deps' 'trove4j' '1.0.20200330'),
    (CachedJar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.6.4'),
    $annotations
)
$java = Join-Path $JavaHome 'bin/java.exe'
$javac = Join-Path $JavaHome 'bin/javac.exe'
$adapter = Join-Path $taskRoot 'netcore/src/main/java/org/conscrypt/NativeEchPolicyAdapter.java'
& $javac -J-Xmx256m --release 17 -cp $conscrypt -d $taskOutput $adapter
if ($LASTEXITCODE -ne 0) { throw 'Pinned Java adapter compilation failed' }
$adapterTests = Join-Path $taskRoot 'netcore/src/test/java/org/conscrypt/NativeEchPolicyAdapterTest.java'
$javaTestClasspath = @($taskOutput, $conscrypt, $junit, $hamcrest) -join [IO.Path]::PathSeparator
& $javac -J-Xmx256m --release 17 -cp $javaTestClasspath -d $taskOutput $adapterTests
if ($LASTEXITCODE -ne 0) { throw 'Java adapter tests compilation failed' }
$mainSources = @(
    (Join-Path $taskRoot 'netcore/src/main/kotlin/com/dsha/sidecar/net/dns/EchConfigList.kt'),
    (Join-Path $taskRoot 'netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/ech/ConscryptEchConnector.kt')
)
$tests = Get-ChildItem (Join-Path $taskRoot 'netcore/src/test/kotlin/com/dsha/sidecar/net/proxy/ech/*.kt') |
    Select-Object -ExpandProperty FullName
$runtime = @($taskOutput, $stdlib, $annotations, $conscrypt, $junit, $hamcrest) -join [IO.Path]::PathSeparator
$allSources = @($mainSources) + @($tests)
& $java -Xmx256m '-Dfile.encoding=UTF-8' -cp ($compilerJars -join [IO.Path]::PathSeparator) `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 `
    -classpath $runtime -d $taskOutput @allSources
if ($LASTEXITCODE -ne 0) { throw 'Direct JVM compilation failed' }
if (Test-Path $android) {
    $androidOutput = Join-Path $taskOutput 'android-api-compile'
    New-Item -ItemType Directory -Force $androidOutput | Out-Null
    & $javac -J-Xmx256m --release 17 -cp $android -d $androidOutput $adapter
    if ($LASTEXITCODE -ne 0) { throw 'Android AAR pinned Java adapter compilation failed' }
    $androidClasspath = @($androidOutput, $stdlib, $annotations, $android) -join [IO.Path]::PathSeparator
    & $java -Xmx256m '-Dfile.encoding=UTF-8' -cp ($compilerJars -join [IO.Path]::PathSeparator) `
        org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 `
        -classpath $androidClasspath -d $androidOutput @mainSources
    if ($LASTEXITCODE -ne 0) { throw 'Android AAR public-API compilation failed' }
}
$evidence = Join-Path $taskOutput 'loopback-evidence.txt'
Set-Content -LiteralPath $evidence -Value 'Independent peer: Go 1.27.3 crypto/tls; client: official Conscrypt 2.8.0 JVM' -Encoding utf8
$testClasspath = @($taskOutput, $runtime) -join [IO.Path]::PathSeparator
& $java -Xmx128m '-Dfile.encoding=UTF-8' "-Dech.peer.exe=$peer" "-Dech.proof.output=$evidence" `
    -cp $testClasspath org.junit.runner.JUnitCore com.dsha.sidecar.net.proxy.ech.ConscryptEchConnectorTest org.conscrypt.NativeEchPolicyAdapterTest
if ($LASTEXITCODE -ne 0) { throw 'ECH loopback tests failed' }
Get-Content -LiteralPath $evidence
