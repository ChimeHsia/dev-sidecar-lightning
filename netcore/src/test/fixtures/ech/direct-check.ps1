param(
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [string]$CacheRoot = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
)
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$taskOutput = [System.IO.Path]::GetFullPath((Join-Path $taskRoot 'netcore/build/native-ech-hpke-check'))
if (-not $taskOutput.StartsWith($taskRoot + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Output must be inside this worktree'
}
New-Item -ItemType Directory -Force $taskOutput | Out-Null
function CachedJar([string]$group, [string]$name, [string]$version) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $CacheRoot "$group/$name/$version") -Filter "$name-$version.jar" -Recurse |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $jar) { throw "Missing cached jar $group/$name/$version" }
    return $jar
}
$stdlib = CachedJar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.0.21'
$annotations = CachedJar 'org.jetbrains' 'annotations' '13.0'
$bc = CachedJar 'org.bouncycastle' 'bcprov-jdk18on' '1.79'
$junit = CachedJar 'junit' 'junit' '4.13.2'
$hamcrest = CachedJar 'org.hamcrest' 'hamcrest-core' '1.3'
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
$runtime = @($stdlib, $annotations, $bc, $junit, $hamcrest) -join [System.IO.Path]::PathSeparator
$java = Join-Path $JavaHome 'bin/java.exe'
$sources = @(
    Get-ChildItem (Join-Path $taskRoot 'netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/ech/*.kt')
    Get-ChildItem (Join-Path $taskRoot 'netcore/src/test/kotlin/com/dsha/sidecar/net/proxy/ech/*.kt')
) | Select-Object -ExpandProperty FullName
& $java -Xmx256m '-Dfile.encoding=UTF-8' -cp ($compilerJars -join [System.IO.Path]::PathSeparator) `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 `
    -classpath $runtime -d $taskOutput @sources
if ($LASTEXITCODE -ne 0) { throw 'Direct compilation failed' }
$resources = Join-Path $taskRoot 'netcore/src/test/resources'
$testClasspath = @($taskOutput, $resources, $runtime) -join [System.IO.Path]::PathSeparator
& $java -Xmx128m '-Dfile.encoding=UTF-8' -cp $testClasspath org.junit.runner.JUnitCore `
    com.dsha.sidecar.net.proxy.ech.HpkeTest com.dsha.sidecar.net.proxy.ech.NativeTlsCryptoTest
if ($LASTEXITCODE -ne 0) { throw 'Primitive tests failed' }
