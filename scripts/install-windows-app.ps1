param(
    [Parameter(Mandatory = $true)]
    [string]$AppImage,

    [Parameter(Mandatory = $true)]
    [string]$Version,

    [string]$InstallRoot = (Join-Path $env:LOCALAPPDATA 'Programs\ChopLab'),

    [string]$StartMenuRoot = (Join-Path $env:APPDATA 'Microsoft\Windows\Start Menu\Programs'),

    [string]$DesktopRoot = [Environment]::GetFolderPath('Desktop'),

    [string]$ReceiptOutput
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($Version -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') {
    throw "Version must be numeric SemVer: $Version"
}

function Get-SafeFullPath([string]$Path, [string]$Label) {
    if ([string]::IsNullOrWhiteSpace($Path)) { throw "$Label must not be empty" }
    $fullPath = [IO.Path]::GetFullPath($Path)
    $volumeRoot = [IO.Path]::GetPathRoot($fullPath)
    if ($fullPath.TrimEnd([IO.Path]::DirectorySeparatorChar) -eq $volumeRoot.TrimEnd([IO.Path]::DirectorySeparatorChar)) {
        throw "$Label must not be a volume root: $fullPath"
    }
    return $fullPath
}

function Convert-ToComparableVersion([string]$Text) {
    $match = [regex]::Match($Text, '[0-9]+(?:\.[0-9]+){1,3}')
    if (-not $match.Success) { throw "No numeric version found in: $Text" }
    $parts = [Collections.Generic.List[string]]::new()
    $parts.AddRange([string[]]$match.Value.Split('.'))
    while ($parts.Count -lt 4) { $parts.Add('0') }
    return [Version]::new([int]$parts[0], [int]$parts[1], [int]$parts[2], [int]$parts[3])
}

function Get-AppImageDigest([string]$Directory) {
    $entries = Get-ChildItem -LiteralPath $Directory -File -Recurse -Force | ForEach-Object {
        $relativePath = [IO.Path]::GetRelativePath($Directory, $_.FullName).Replace('\', '/')
        $fileHash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        "$fileHash`t$relativePath"
    }
    $orderedEntries = @($entries | Sort-Object)
    if ($orderedEntries.Count -eq 0) { throw "App-image contains no files: $Directory" }
    $payload = [Text.Encoding]::UTF8.GetBytes(($orderedEntries -join "`n") + "`n")
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        return [Convert]::ToHexString($sha256.ComputeHash($payload)).ToLowerInvariant()
    } finally {
        $sha256.Dispose()
    }
}

function New-ChopLabShortcut(
    [string]$ShortcutPath,
    [string]$TargetPath,
    [string]$WorkingDirectory
) {
    $shortcutDirectory = Split-Path -Parent $ShortcutPath
    New-Item -ItemType Directory -Path $shortcutDirectory -Force | Out-Null
    $shell = New-Object -ComObject WScript.Shell
    try {
        $shortcut = $shell.CreateShortcut($ShortcutPath)
        $shortcut.TargetPath = $TargetPath
        $shortcut.WorkingDirectory = $WorkingDirectory
        $shortcut.Description = 'ChopLab — おとひろい PC'
        $shortcut.IconLocation = "$TargetPath,0"
        $shortcut.Save()
    } finally {
        if ($null -ne $shell) { [void][Runtime.InteropServices.Marshal]::ReleaseComObject($shell) }
    }
}

$sourceDirectory = Get-SafeFullPath $AppImage 'AppImage'
if (-not (Test-Path -LiteralPath $sourceDirectory -PathType Container)) {
    throw "Windows app-image not found: $sourceDirectory"
}
$sourceExe = Join-Path $sourceDirectory 'ChopLab.exe'
if (-not (Test-Path -LiteralPath $sourceExe -PathType Leaf)) {
    throw "ChopLab.exe not found in app-image: $sourceExe"
}

$expectedVersion = Convert-ToComparableVersion $Version
$productVersion = Convert-ToComparableVersion ([string](Get-Item -LiteralPath $sourceExe).VersionInfo.ProductVersion)
if (
    $productVersion.Major -ne $expectedVersion.Major -or
    $productVersion.Minor -ne $expectedVersion.Minor -or
    $productVersion.Build -ne $expectedVersion.Build
) {
    throw "Windows ProductVersion mismatch: expected $Version, found $productVersion"
}

$safeInstallRoot = Get-SafeFullPath $InstallRoot 'InstallRoot'
$safeStartMenuRoot = Get-SafeFullPath $StartMenuRoot 'StartMenuRoot'
$safeDesktopRoot = Get-SafeFullPath $DesktopRoot 'DesktopRoot'
$sourceHash = (Get-FileHash -LiteralPath $sourceExe -Algorithm SHA256).Hash.ToLowerInvariant()
$sourceAppImageHash = Get-AppImageDigest $sourceDirectory
$installDirectory = Join-Path $safeInstallRoot "$Version-$($sourceAppImageHash.Substring(0, 12))"
$installedExe = Join-Path $installDirectory 'ChopLab.exe'
$reusedExisting = $false
$stagingDirectory = Join-Path $safeInstallRoot ".staging-$PID-$([guid]::NewGuid().ToString('N'))"

New-Item -ItemType Directory -Path $safeInstallRoot -Force | Out-Null
try {
    if (Test-Path -LiteralPath $installDirectory) {
        if (-not (Test-Path -LiteralPath $installedExe -PathType Leaf)) {
            throw "Existing versioned install is incomplete: $installDirectory"
        }
        $installedAppImageHash = Get-AppImageDigest $installDirectory
        if ($installedAppImageHash -ne $sourceAppImageHash) {
            throw "Existing versioned install has a different app-image content digest: $installDirectory"
        }
        $reusedExisting = $true
    } else {
        New-Item -ItemType Directory -Path $stagingDirectory | Out-Null
        Get-ChildItem -LiteralPath $sourceDirectory -Force | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination $stagingDirectory -Recurse
        }
        $stagedExe = Join-Path $stagingDirectory 'ChopLab.exe'
        if (-not (Test-Path -LiteralPath $stagedExe -PathType Leaf)) {
            throw "Staged app-image is missing ChopLab.exe: $stagedExe"
        }
        $stagedAppImageHash = Get-AppImageDigest $stagingDirectory
        if ($stagedAppImageHash -ne $sourceAppImageHash) {
            throw 'Staged app-image content digest does not match source app-image'
        }
        Move-Item -LiteralPath $stagingDirectory -Destination $installDirectory
    }
} finally {
    if (Test-Path -LiteralPath $stagingDirectory) {
        $resolvedStaging = [IO.Path]::GetFullPath($stagingDirectory)
        $resolvedInstallPrefix = $safeInstallRoot.TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
        if (-not $resolvedStaging.StartsWith($resolvedInstallPrefix, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove staging directory outside InstallRoot: $resolvedStaging"
        }
        Remove-Item -LiteralPath $resolvedStaging -Recurse -Force
    }
}

$startMenuShortcut = Join-Path $safeStartMenuRoot 'ChopLab.lnk'
$desktopShortcut = Join-Path $safeDesktopRoot 'ChopLab.lnk'
New-ChopLabShortcut $startMenuShortcut $installedExe $installDirectory
New-ChopLabShortcut $desktopShortcut $installedExe $installDirectory

$projectDataDirectory = if ([string]::IsNullOrWhiteSpace($env:LOCALAPPDATA)) {
    $null
} else {
    Join-Path $env:LOCALAPPDATA 'ChopLab\projects'
}
$result = [ordered]@{
    version = $Version
    source_executable = $sourceExe
    source_executable_sha256 = $sourceHash
    source_app_image_sha256 = $sourceAppImageHash
    install_directory = $installDirectory
    installed_executable = $installedExe
    start_menu_shortcut = $startMenuShortcut
    desktop_shortcut = $desktopShortcut
    project_data_directory = $projectDataDirectory
    project_data_modified = $false
    reused_existing = $reusedExisting
    installed_at_utc = [DateTimeOffset]::UtcNow.ToString('o')
}

$receiptDirectory = Join-Path $safeInstallRoot 'receipts'
New-Item -ItemType Directory -Path $receiptDirectory -Force | Out-Null
$installReceipt = Join-Path $receiptDirectory "$Version-$sourceAppImageHash.json"
$result | ConvertTo-Json | Set-Content -LiteralPath $installReceipt -Encoding utf8
if (-not [string]::IsNullOrWhiteSpace($ReceiptOutput)) {
    $safeReceiptOutput = [IO.Path]::GetFullPath($ReceiptOutput)
    $receiptParent = Split-Path -Parent $safeReceiptOutput
    if ($receiptParent) { New-Item -ItemType Directory -Path $receiptParent -Force | Out-Null }
    $result | ConvertTo-Json | Set-Content -LiteralPath $safeReceiptOutput -Encoding utf8
}

$result | ConvertTo-Json
