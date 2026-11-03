param(
    [Parameter(Mandatory = $true)][string]$GradleBin,
    [Parameter(Mandatory = $true)][string]$JavaHome,
    [Parameter(Mandatory = $true)][string]$AndroidSdk,
    [Parameter(Mandatory = $true)][string]$ConscryptAar,
    [switch]$AllowDownload
)

$ErrorActionPreference = 'Stop'
$workspace = Split-Path -Parent $PSScriptRoot
$project = Join-Path $workspace 'android-native-check'
$output = Join-Path $workspace 'port/.run/android-native-check'
$gradle = (Resolve-Path -LiteralPath $GradleBin).Path
$jdk = (Resolve-Path -LiteralPath $JavaHome).Path
$sdk = (Resolve-Path -LiteralPath $AndroidSdk).Path
$aar = (Resolve-Path -LiteralPath $ConscryptAar).Path
if (-not (Test-Path -LiteralPath (Join-Path $jdk 'bin/java.exe'))) {
    throw 'JavaHome must point to JDK 21.'
}
$digest = (Get-FileHash -LiteralPath $aar -Algorithm SHA256).Hash.ToLowerInvariant()
if ($digest -ne '752044c289bc98e2dcabc00a1f88ca158b3aa3f505b38f124552bf4b2a0902b1') {
    throw 'Expected the pinned Conscrypt Android 2.8.0 AAR.'
}

$previousJava = $env:JAVA_HOME
$previousSdk = $env:ANDROID_HOME
$mutex = [System.Threading.Mutex]::new($false, 'Local\DshaNativeRescueBuild')
$held = $false
try {
    while (-not $held) { $held = $mutex.WaitOne(1000) }
    New-Item -ItemType Directory -Path $output -Force | Out-Null
    $env:JAVA_HOME = $jdk
    $env:ANDROID_HOME = $sdk
    $arguments = @(
        '-p', $project, ':app:assembleDebug', ':app:assembleDebugAndroidTest', ':app:lintDebug',
        '--no-daemon', "-PnativeConscryptAar=$aar"
    )
    if (-not $AllowDownload) { $arguments += '--offline' }
    & $gradle @arguments 2>&1 | Tee-Object -FilePath (Join-Path $output 'build.log')
    if ($LASTEXITCODE -ne 0) { throw "Android compilation failed: $LASTEXITCODE" }
    $apk = Join-Path $project 'app/build/outputs/apk/debug/app-debug.apk'
    & python (Join-Path $PSScriptRoot 'verify-native-android-apk.py') --apk $apk `
        --conscrypt-aar $aar --report (Join-Path $output 'apk-report.json')
    if ($LASTEXITCODE -ne 0) { throw "Android APK verification failed: $LASTEXITCODE" }
    Copy-Item -LiteralPath $apk -Destination (Join-Path $output 'native-core-check.apk')
    $testApk = Join-Path $project 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
    Copy-Item -LiteralPath $testApk -Destination (Join-Path $output 'native-core-check-tests.apk')
} finally {
    $env:JAVA_HOME = $previousJava
    $env:ANDROID_HOME = $previousSdk
    if ($held) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
