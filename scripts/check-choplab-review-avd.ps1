[CmdletBinding()]
param(
    [string]$AndroidSdkRoot = $env:ANDROID_SDK_ROOT,
    [string]$AvdHome = $(
        if ($env:CHOPLAB_AVD_HOME) { $env:CHOPLAB_AVD_HOME }
        else { Join-Path $env:USERPROFILE '.android\avd' }
    )
)

$ErrorActionPreference = 'Stop'
$manifestPath = Join-Path $PSScriptRoot '..\config\choplab-review-avd.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$reasons = [System.Collections.Generic.List[string]]::new()

if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    $reasons.Add('ANDROID_SDK_ROOT is not set')
}

$imagePath = if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    $null
} else {
    Join-Path $AndroidSdkRoot $manifest.systemImageRelativePath
}
$avdManager = if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    $null
} else {
    Join-Path $AndroidSdkRoot 'cmdline-tools\latest\bin\avdmanager.bat'
}
$avdConfig = Join-Path $AvdHome ($manifest.avdName + '.avd\config.ini')
$avdIni = Join-Path $AvdHome ($manifest.avdName + '.ini')
$completeAvdExists = (Test-Path -LiteralPath $avdConfig -PathType Leaf) -and
    (Test-Path -LiteralPath $avdIni -PathType Leaf)

if ($null -eq $imagePath -or -not (Test-Path -LiteralPath $imagePath -PathType Container)) {
    $reasons.Add("Required system image is absent: $($manifest.systemImageId)")
}
if (-not $completeAvdExists -and ($null -eq $avdManager -or -not (Test-Path -LiteralPath $avdManager -PathType Leaf))) {
    $reasons.Add('avdmanager.bat is absent')
}
if ((Test-Path -LiteralPath $avdConfig) -xor (Test-Path -LiteralPath $avdIni)) {
    $reasons.Add("Dedicated AVD is incomplete: $($manifest.avdName)")
}
if (Test-Path -LiteralPath $avdConfig -PathType Leaf) {
    $configText = Get-Content -LiteralPath $avdConfig -Raw -Encoding UTF8
    $expectedSysDir = $manifest.systemImageRelativePath.TrimEnd('\') + '\'
    if ($configText -notmatch [regex]::Escape("image.sysdir.1=$expectedSysDir")) {
        $reasons.Add("Existing dedicated AVD does not match the pinned image: $avdConfig")
    }
    foreach ($expectedLine in @(
        "avd.id=$($manifest.avdName)",
        "avd.name=$($manifest.avdName)",
        'PlayStore.enabled=yes',
        "hw.lcd.width=$($manifest.resolution.width)",
        "hw.lcd.height=$($manifest.resolution.height)",
        "hw.lcd.density=$($manifest.resolution.densityDpi)",
        "hw.ramSize=$($manifest.emulator.memoryMb)"
    )) {
        if ($configText -notmatch "(?m)^$([regex]::Escape($expectedLine))\r?$") {
            $reasons.Add("Existing dedicated AVD is not pinned to '$expectedLine': $avdConfig")
        }
    }
}

$result = [ordered]@{
    status = if ($reasons.Count -eq 0) { 'READY_TO_PROVISION_OR_RUN' } else { 'BLOCKED' }
    manifest = (Resolve-Path -LiteralPath $manifestPath).Path
    avdName = $manifest.avdName
    systemImageId = $manifest.systemImageId
    imagePath = $imagePath
    imagePresent = $null -ne $imagePath -and (Test-Path -LiteralPath $imagePath -PathType Container)
    avdManagerPresent = $null -ne $avdManager -and (Test-Path -LiteralPath $avdManager -PathType Leaf)
    existingPinnedAvd = $completeAvdExists
    avdHome = $AvdHome
    mutationPerformed = $false
    reasons = @($reasons)
}

$result | ConvertTo-Json -Depth 5
if ($reasons.Count -ne 0) { exit 2 }
