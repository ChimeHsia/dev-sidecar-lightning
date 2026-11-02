param(
    [string]$JavaHome = 'D:\File\User\code\dev-sidecar-lightning\.jdk\jdk-21.0.2',
    [string]$Cache = "$env:USERPROFILE\.gradle\caches\modules-2\files-2.1",
    [Parameter(Mandatory = $true)][string]$ConscryptJar,
    [Parameter(Mandatory = $true)][string]$GoExe
)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$mutex = [System.Threading.Mutex]::new($false, 'Local\DshaNativeRescueBuild')
$acquired = $false
try {
    while (-not $acquired) {
        try {
            $acquired = $mutex.WaitOne(1000)
        } catch [System.Threading.AbandonedMutexException] {
            $acquired = $true
        }
    }
    & python (Join-Path $root 'scripts/test-native-core.py') `
        --java-home $JavaHome --dependency-cache $Cache `
        --conscrypt-jar $ConscryptJar --go-exe $GoExe
    if ($LASTEXITCODE -ne 0) { throw "Native body aggregate tests failed: $LASTEXITCODE" }
} finally {
    if ($acquired) { $mutex.ReleaseMutex() }
    $mutex.Dispose()
}
