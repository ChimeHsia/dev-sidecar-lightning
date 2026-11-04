param(
    [string]$JavaHome = 'D:\File\User\code\dev-sidecar-lightning\.jdk\jdk-21.0.2',
    [string]$AndroidJar = 'D:\App\Dev\Android\platforms\android-37.2\android.jar',
    [string]$CacheRoot = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1"
)
# Source/API check only: produces JVM class files, never DEX, resources, Gradle work or an APK.
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$output = Join-Path $taskRoot 'netcore/build/android-probe-api-check'
New-Item -ItemType Directory -Force $output | Out-Null
function Jar([string]$group, [string]$name, [string]$version) {
    $file = Get-ChildItem -LiteralPath "$CacheRoot/$group/$name/$version" -Filter "$name-$version.jar" -Recurse |
        Select-Object -First 1 -ExpandProperty FullName
    if (-not $file) { throw "Missing cached jar $group/$name/$version" }
    return $file
}
$stdlib = Jar 'org.jetbrains.kotlin' 'kotlin-stdlib' '2.0.21'
$annotations = Jar 'org.jetbrains' 'annotations' '13.0'
$api = Join-Path $taskRoot '.tools/conscrypt/android-api/classes.jar'
if ((Get-FileHash $api -Algorithm SHA256).Hash -ne '2cd9b6bc444cfca69048e8327383b7f5a13fc7978465e6e0fd5111a2000822f6') {
    throw 'Pinned AAR API hash mismatch'
}
$classpath = @($output, $api, $AndroidJar, $stdlib, $annotations) -join ';'
$compiler = @(
    (Jar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '2.0.21'),
    $stdlib, $annotations,
    (Jar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '2.0.21'),
    (Jar 'org.jetbrains.kotlin' 'kotlin-reflect' '1.6.10'),
    (Jar 'org.jetbrains.kotlin' 'kotlin-daemon-embeddable' '2.0.21'),
    (Jar 'org.jetbrains.intellij.deps' 'trove4j' '1.0.20200330'),
    (Jar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm' '1.6.4')
) -join ';'
$javac = Join-Path $JavaHome 'bin/javac.exe'
& $javac -J-Xmx256m --release 17 -cp $classpath -d $output `
    (Join-Path $taskRoot 'netcore/src/main/java/org/conscrypt/NativeEchPolicyAdapter.java')
if ($LASTEXITCODE -ne 0) { throw 'Android adapter API compilation failed' }
$sources = @(
    Get-Item "$taskRoot/netcore/src/main/kotlin/com/dsha/sidecar/net/dns/EchConfigList.kt"
    Get-Item "$taskRoot/netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/ech/ConscryptEchConnector.kt"
    Get-Item "$taskRoot/netcore/src/main/kotlin/com/dsha/sidecar/net/proxy/ech/EchDiagnostics.kt"
    Get-ChildItem "$PSScriptRoot/app/src/main/java/com/dsha/nativeechtest" -Filter '*.kt'
) | Select-Object -ExpandProperty FullName
& (Join-Path $JavaHome 'bin/java.exe') -Xmx256m '-Dfile.encoding=UTF-8' -cp $compiler `
    org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 `
    -classpath $classpath -d $output @sources
if ($LASTEXITCODE -ne 0) { throw 'Probe Kotlin Android API compilation failed' }
Write-Output 'Probe source/API check passed; no APK build or Android execution.'
