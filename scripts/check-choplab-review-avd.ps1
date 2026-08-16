[CmdletBinding()]
param(
    [string]$AndroidSdkRoot = $env:ANDROID_SDK_ROOT,
    [string]$AvdHome = (Join-Path $env:USERPROFILE '.android\avd')
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

if ($null -eq $imagePath -or -not (Test-Path -LiteralPath $imagePath -PathType Container)) {
    $reasons.Add("Required system image is absent: $($manifest.systemImageId)")
}
if ($null -eq $avdManager -or -not (Test-Path -LiteralPath $avdManager -PathType Leaf)) {
    $reasons.Add('avdmanager.bat is absent')
}
if (Test-Path -LiteralPath $avdConfig -PathType Leaf) {
    $configText = Get-Content -LiteralPath $avdConfig -Raw
    $expectedSysDir = $manifest.systemImageRelativePath.TrimEnd('\') + '\'
    if ($configText -notmatch [regex]::Escape("image.sysdir.1=$expectedSysDir")) {
        $reasons.Add("Existing dedicated AVD does not match the pinned image: $avdConfig")
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
    existingPinnedAvd = Test-Path -LiteralPath $avdConfig -PathType Leaf
    mutationPerformed = $false
    reasons = @($reasons)
}

$result | ConvertTo-Json -Depth 5
if ($reasons.Count -ne 0) { exit 2 }
