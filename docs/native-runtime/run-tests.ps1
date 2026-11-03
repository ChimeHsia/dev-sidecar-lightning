param(
    [Parameter(Mandatory = $true)][string]$Java,
    [string]$Cache = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1",
    [Parameter(Mandatory = $true)][string]$ConscryptJar,
    [Parameter(Mandatory = $true)][string]$GoExe
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$javaHome = Split-Path -Parent (Split-Path -Parent (Resolve-Path -LiteralPath $Java).Path)
& python (Join-Path $root 'scripts/test-native-core.py') `
    --java-home $javaHome --dependency-cache $Cache `
    --conscrypt-jar $ConscryptJar --go-exe $GoExe
if ($LASTEXITCODE -ne 0) { throw "Native aggregate tests failed: $LASTEXITCODE" }
