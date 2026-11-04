param(
    [switch]$BuildGo,
    [ValidateSet('debug', 'release')][string]$Variant = 'debug',
    [string]$JavaHome = 'D:\File\User\code\dev-sidecar-lightning\.jdk\jdk-21.0.2',
    [string]$AndroidSdk = 'D:\App\Dev\Android'
)
$ErrorActionPreference = 'Stop'
if (-not $BuildGo) { throw 'APK build is gated: wait for explicit BUILD_GO from the parent before invoking -BuildGo.' }
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$taskOldJava = $env:JAVA_HOME
$taskOldSdk = $env:ANDROID_HOME
try {
    $env:JAVA_HOME = $JavaHome
    $env:ANDROID_HOME = $AndroidSdk
    $gradleTask = if ($Variant -eq 'debug') { ':app:assembleDebug' } else { ':app:assembleRelease' }
    & (Join-Path $taskRoot 'lightning-browser/gradlew.bat') -p $PSScriptRoot --offline --no-daemon --max-workers=1 `
        "-Dorg.gradle.java.home=$JavaHome" $gradleTask
    if ($LASTEXITCODE -ne 0) { throw 'Dedicated probe APK build failed' }
    $apk = Join-Path $PSScriptRoot "app/build/outputs/apk/$Variant/app-$Variant.apk"
    Get-FileHash -LiteralPath $apk -Algorithm SHA256
} finally {
    $env:JAVA_HOME = $taskOldJava
    $env:ANDROID_HOME = $taskOldSdk
}
