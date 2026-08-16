[CmdletBinding()]
param(
    [string]$AndroidSdkRoot = $env:ANDROID_SDK_ROOT,
    [string]$AvdHome = $(
        if ($env:CHOPLAB_AVD_HOME) { $env:CHOPLAB_AVD_HOME }
        else { Join-Path $env:USERPROFILE '.android\avd' }
    ),
    [string]$TaskId = 'choplab-review-avd',
    [string]$Owner = 'choplab-review-avd'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) { throw 'ANDROID_SDK_ROOT is required' }
$manifestPath = Join-Path $PSScriptRoot '..\config\choplab-review-avd.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$port = [int]$manifest.emulator.port
$serial = "emulator-$port"
$emulator = Join-Path $AndroidSdkRoot 'emulator\emulator.exe'
$adb = Join-Path $AndroidSdkRoot 'platform-tools\adb.exe'
$tracker = Join-Path $env:USERPROFILE '.codex\scripts\Start-CodexTrackedProcess.ps1'

foreach ($tool in @($emulator, $adb, $tracker)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) { throw "Required tool is absent: $tool" }
}

$preflight = & (Join-Path $PSScriptRoot 'check-choplab-review-avd.ps1') `
    -AndroidSdkRoot $AndroidSdkRoot -AvdHome $AvdHome
$preflightResult = $preflight | ConvertFrom-Json
if ($preflightResult.status -ne 'READY_TO_PROVISION_OR_RUN') { throw "AVD preflight failed:`n$preflight" }

$deviceLines = @(& $adb devices)
if ($deviceLines | Where-Object { $_ -match "^$([regex]::Escape($serial))\s" }) {
    throw "The dedicated emulator serial is already present: $serial"
}

$env:ANDROID_AVD_HOME = $AvdHome
$arguments = @(
    '-avd', [string]$manifest.avdName,
    '-port', [string]$port,
    '-no-window',
    '-no-snapshot',
    '-no-boot-anim',
    '-gpu', 'swiftshader_indirect',
    '-memory', [string]$manifest.emulator.memoryMb,
    '-netdelay', 'none',
    '-netspeed', 'full',
    '-no-metrics'
)
foreach ($feature in $manifest.emulator.disabledFeatures) {
    $arguments += @('-feature', "-$feature")
}
$tracked = & $tracker `
    -TaskId $TaskId `
    -Owner $Owner `
    -WorkingDirectory (Resolve-Path (Join-Path $PSScriptRoot '..')).Path `
    -FilePath $emulator `
    -ArgumentList $arguments `
    -Port $port `
    -TtlHours 6 `
    -SingletonKey 'choplab-review-avd-api36' `
    -StopMethod 'graceful-then-tree'

[ordered]@{
    status = 'STARTED'
    expectedSerial = $serial
    avdName = $manifest.avdName
    avdHome = (Resolve-Path -LiteralPath $AvdHome).Path
    trackedProcess = $tracked | ConvertFrom-Json
    argumentsRecorded = $false
} | ConvertTo-Json -Depth 6
