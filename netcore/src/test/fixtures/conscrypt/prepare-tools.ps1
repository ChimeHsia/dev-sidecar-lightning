param()
$ErrorActionPreference = 'Stop'
$taskRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../../../..')).Path
$toolRoot = Join-Path $taskRoot '.tools'
New-Item -ItemType Directory -Force (Join-Path $toolRoot 'conscrypt'), (Join-Path $toolRoot 'go') | Out-Null
function VerifiedDownload([string]$uri, [string]$target, [string]$expected, [bool]$mavenChecksum = $false) {
    if (-not (Test-Path -LiteralPath $target)) {
        Invoke-WebRequest -Uri $uri -OutFile $target
    }
    $actual = (Get-FileHash -LiteralPath $target -Algorithm SHA256).Hash
    if ($actual -ne $expected) { throw "SHA256 mismatch: $target" }
    if ($mavenChecksum) {
        $published = (Invoke-WebRequest -Uri "$uri.sha256").Content.Trim()
        if ($published -ne $actual) { throw "Maven SHA256 mismatch: $target" }
    }
}
$base = 'https://repo.maven.apache.org/maven2/org/conscrypt'
$jar = Join-Path $toolRoot 'conscrypt/conscrypt-openjdk-uber-2.8.0.jar'
$aar = Join-Path $toolRoot 'conscrypt/conscrypt-android-2.8.0.aar'
VerifiedDownload "$base/conscrypt-openjdk-uber/2.8.0/conscrypt-openjdk-uber-2.8.0.jar" $jar `
    '4022024808c54211275c7d58ed81e4e13ed8ba170351588cba19f6b947478668' $true
VerifiedDownload "$base/conscrypt-android/2.8.0/conscrypt-android-2.8.0.aar" $aar `
    '752044c289bc98e2dcabc00a1f88ca158b3aa3f505b38f124552bf4b2a0902b1' $true
$zip = Join-Path $toolRoot 'go/go1.27.3.windows-amd64.zip'
VerifiedDownload 'https://go.dev/dl/go1.27.3.windows-amd64.zip' $zip `
    'f26081f438319757f8a1b46d7d0b2c5ec7583d4038e485e0aa725cfde7885fff'
$go = Join-Path $toolRoot 'go/go/bin/go.exe'
if (-not (Test-Path -LiteralPath $go)) {
    Expand-Archive -LiteralPath $zip -DestinationPath (Join-Path $toolRoot 'go')
}
$android = Join-Path $toolRoot 'conscrypt/android-api'
if (-not (Test-Path -LiteralPath (Join-Path $android 'classes.jar'))) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [IO.Compression.ZipFile]::ExtractToDirectory($aar, $android)
}
if ((Get-FileHash (Join-Path $android 'classes.jar') -Algorithm SHA256).Hash -ne `
    '2cd9b6bc444cfca69048e8327383b7f5a13fc7978465e6e0fd5111a2000822f6') {
    throw 'Android classes.jar mismatch'
}
$taskOldEnv = @{}
foreach ($name in @('GOCACHE', 'GOMODCACHE', 'GOTOOLCHAIN', 'GOMAXPROCS', 'GOMEMLIMIT', 'GOPROXY')) {
    $taskOldEnv[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}
try {
    $env:GOCACHE = Join-Path $toolRoot 'go/build-cache'
    $env:GOMODCACHE = Join-Path $toolRoot 'go/module-cache'
    $env:GOTOOLCHAIN = 'local'
    $env:GOMAXPROCS = '2'
    $env:GOMEMLIMIT = '192MiB'
    $env:GOPROXY = 'off'
    & $go version
    & $go build -p=1 -trimpath -o (Join-Path $toolRoot 'go/ech-peer.exe') (Join-Path $PSScriptRoot 'ech-peer.go')
    if ($LASTEXITCODE -ne 0) { throw 'Independent Go peer compilation failed' }
} finally {
    foreach ($name in $taskOldEnv.Keys) {
        [Environment]::SetEnvironmentVariable($name, $taskOldEnv[$name], 'Process')
    }
}
