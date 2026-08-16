[CmdletBinding()]
param(
    [string]$AndroidSdkRoot = $env:ANDROID_SDK_ROOT,
    [string]$AvdHome = $(
        if ($env:CHOPLAB_AVD_HOME) { $env:CHOPLAB_AVD_HOME }
        else { Join-Path $env:USERPROFILE '.android\avd' }
    ),
    [switch]$InstallMissingImage
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) {
    throw 'ANDROID_SDK_ROOT is required'
}

$manifestPath = Join-Path $PSScriptRoot '..\config\choplab-review-avd.json'
$manifest = Get-Content -LiteralPath $manifestPath -Raw -Encoding UTF8 | ConvertFrom-Json
$sdkManager = Join-Path $AndroidSdkRoot 'cmdline-tools\latest\bin\sdkmanager.bat'
$avdManager = Join-Path $AndroidSdkRoot 'cmdline-tools\latest\bin\avdmanager.bat'
$imagePath = Join-Path $AndroidSdkRoot $manifest.systemImageRelativePath
$avdDirectory = Join-Path $AvdHome ($manifest.avdName + '.avd')
$avdConfig = Join-Path $avdDirectory 'config.ini'
$iniPath = Join-Path $AvdHome ($manifest.avdName + '.ini')

foreach ($tool in @($sdkManager, $avdManager)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Required Android SDK tool is absent: $tool"
    }
}

$imageInstalled = Test-Path -LiteralPath $imagePath -PathType Container
$imageDownloaded = $false
if (-not $imageInstalled) {
    if (-not $InstallMissingImage) {
        throw "Required system image is absent. Rerun with -InstallMissingImage: $($manifest.systemImageId)"
    }
    $sdkOutput = @('y') | & $sdkManager $manifest.systemImageId 2>&1
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $imagePath -PathType Container)) {
        throw "System-image installation failed (exit $LASTEXITCODE):`n$($sdkOutput -join "`n")"
    }
    $imageInstalled = $true
    $imageDownloaded = $true
}

[System.IO.Directory]::CreateDirectory($AvdHome) | Out-Null
$env:ANDROID_AVD_HOME = $AvdHome
$created = $false

if ((Test-Path -LiteralPath $avdConfig) -xor (Test-Path -LiteralPath $iniPath)) {
    throw "Dedicated AVD is incomplete; refusing automatic replacement: $($manifest.avdName)"
}

if (-not (Test-Path -LiteralPath $avdConfig -PathType Leaf)) {
    $createOutput = @('no') | & $avdManager create avd `
        --name $manifest.avdName `
        --package $manifest.systemImageId `
        --device $manifest.deviceProfile 2>&1
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $avdConfig -PathType Leaf)) {
        throw "Dedicated AVD creation failed (exit $LASTEXITCODE):`n$($createOutput -join "`n")"
    }
    $created = $true
}

$expectedSysDir = $manifest.systemImageRelativePath.TrimEnd('\') + '\'
$existingConfig = Get-Content -LiteralPath $avdConfig -Raw -Encoding UTF8
$imageMatch = [regex]::Match($existingConfig, '(?m)^image\.sysdir\.1=(?<value>.+)$')
if ($imageMatch.Success -and $imageMatch.Groups['value'].Value.Trim() -ne $expectedSysDir) {
    throw "Existing dedicated AVD uses another image; refusing mutation: $avdConfig"
}

$desired = [ordered]@{
    'avd.id' = [string]$manifest.avdName
    'avd.name' = [string]$manifest.avdName
    'PlayStore.enabled' = 'yes'
    'image.sysdir.1' = $expectedSysDir
    'hw.device.name' = [string]$manifest.deviceProfile
    'hw.lcd.width' = [string]$manifest.resolution.width
    'hw.lcd.height' = [string]$manifest.resolution.height
    'hw.lcd.density' = [string]$manifest.resolution.densityDpi
    'hw.ramSize' = [string]$manifest.emulator.memoryMb
}

$lines = [System.Collections.Generic.List[string]]::new()
foreach ($line in ($existingConfig -split "`r?`n")) {
    if (-not [string]::IsNullOrWhiteSpace($line)) { $lines.Add($line) }
}
foreach ($entry in $desired.GetEnumerator()) {
    $prefix = $entry.Key + '='
    $replacement = $prefix + $entry.Value
    $index = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i].StartsWith($prefix, [StringComparison]::Ordinal)) {
            $index = $i
            break
        }
    }
    if ($index -ge 0) { $lines[$index] = $replacement }
    else { $lines.Add($replacement) }
}
$newConfig = ($lines -join [Environment]::NewLine) + [Environment]::NewLine
$configUpdated = $newConfig -cne $existingConfig
if ($configUpdated) {
    [System.IO.File]::WriteAllText(
        $avdConfig,
        $newConfig,
        [System.Text.UTF8Encoding]::new($false)
    )
}

$checkOutput = & (Join-Path $PSScriptRoot 'check-choplab-review-avd.ps1') `
    -AndroidSdkRoot $AndroidSdkRoot -AvdHome $AvdHome
$preflight = $checkOutput | ConvertFrom-Json
if ($preflight.status -ne 'READY_TO_PROVISION_OR_RUN') {
    throw "Post-provision preflight failed:`n$checkOutput"
}

[ordered]@{
    status = 'READY'
    mutationPerformed = $created -or $imageDownloaded -or $configUpdated
    imageInstalled = $imageInstalled
    imageDownloaded = $imageDownloaded
    avdCreated = $created
    configUpdated = $configUpdated
    avdName = $manifest.avdName
    avdHome = (Resolve-Path -LiteralPath $AvdHome).Path
    configPath = (Resolve-Path -LiteralPath $avdConfig).Path
    preflight = $preflight
} | ConvertTo-Json -Depth 8
