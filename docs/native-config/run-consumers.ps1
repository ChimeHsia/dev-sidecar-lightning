param(
    [Parameter(Mandatory = $true)][string]$Java,
    [Parameter(Mandatory = $true)][string]$NativeCore,
    [Parameter(Mandatory = $true)][string]$NativeRuntime
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$output = Join-Path $root 'build/native-config'
$compiler = Get-Content -Raw "$output/compiler-classpath.txt"
$runtime = Get-Content -Raw "$output/runtime-classpath.txt"
$base = "$NativeCore/netcore/src/main/kotlin/com/dsha/sidecar/net"
$sources = @(
    Get-ChildItem "$base/rules/native" -Filter '*.kt'
    Get-Item "$base/rules/HostnameAllMatcher.kt"
    Get-Item "$base/rules/HostnameMatcher.kt"
    Get-Item "$base/rules/PathRuleMatcher.kt"
    Get-Item "$base/http/HeaderTransforms.kt"
    Get-Item "$base/http/RequestRetryPolicy.kt"
    Get-Item "$base/dns/DnsProviderConfig.kt"
    Get-Item "$base/dns/IpAddress.kt"
    Get-Item "$NativeRuntime/netcore/src/main/kotlin/com/dsha/sidecar/net/runtime/NativeRuntimeConfig.kt"
    Get-Item "$PSScriptRoot/ConsumerSmoke.kt"
)
$before = @{}
$sources | ForEach-Object { $before[$_.FullName] = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
$copies = @{}
$index = 0
$pinnedSources = $sources | ForEach-Object {
    $folder = "$output/consumer-inputs/$index"
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
    $copy = Join-Path $folder $_.Name
    Copy-Item -LiteralPath $_.FullName -Destination $copy
    if ($before[$_.FullName] -ne (Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash.ToLowerInvariant()) {
        throw 'Consumer input changed while being copied'
    }
    $copies[$_.FullName] = $copy
    $index++
    Get-Item -LiteralPath $copy
}
$sources | ForEach-Object {
    if ($before[$_.FullName] -ne (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()) {
        throw 'Peer source changed during snapshot'
    }
}
$arguments = @(
    '-no-stdlib', '-no-reflect', '-jvm-target', '17',
    '-classpath', ('"' + $runtime.Replace('\', '/') + '"'),
    '-d', ('"' + "$output/consumer-smoke.jar".Replace('\', '/') + '"')
) + ($pinnedSources | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' })
[IO.File]::WriteAllLines("$output/consumer-compiler.args", $arguments)
& $Java '-Xmx256m' '-XX:+UseSerialGC' '-XX:ActiveProcessorCount=2' '-cp' $compiler `
    'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler' "@$output/consumer-compiler.args" *> "$output/consumer-compile.log"
if ($LASTEXITCODE -ne 0) { Get-Content "$output/consumer-compile.log" -Tail 80; throw 'Consumer compilation failed' }
$before | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath "$output/consumer-source-hashes.json"
$copies | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath "$output/consumer-source-copies.json"
& $Java '-Xmx256m' '-XX:+UseSerialGC' '-XX:ActiveProcessorCount=2' '-cp' "$output/consumer-smoke.jar;$runtime" `
    'nativeconfig.smoke.ConsumerSmoke' *> "$output/consumer-smoke.log"
$code = $LASTEXITCODE
Get-Content "$output/consumer-smoke.log"
if ($code -ne 0) { throw "Consumer smoke failed ($code)" }
