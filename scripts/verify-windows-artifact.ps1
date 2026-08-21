param(
    [Parameter(Mandatory = $true)]
    [string]$AppImage,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedVersion,

    [Parameter(Mandatory = $false)]
    [string]$MetadataOutput
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($ExpectedVersion -notmatch '^[0-9]+\.[0-9]+\.[0-9]+$') {
    throw "ExpectedVersion must be numeric SemVer: $ExpectedVersion"
}

$appImagePath = Resolve-Path -LiteralPath $AppImage
$exePath = Join-Path $appImagePath 'ChopLab.exe'
if (-not (Test-Path -LiteralPath $exePath -PathType Leaf)) {
    throw "Packaged executable not found: $exePath"
}

$versionInfo = (Get-Item -LiteralPath $exePath).VersionInfo
$productVersionText = [string]$versionInfo.ProductVersion
$fileVersionText = [string]$versionInfo.FileVersion

function Convert-ToComparableVersion([string]$Text) {
    $match = [regex]::Match($Text, '[0-9]+(?:\.[0-9]+){1,3}')
    if (-not $match.Success) {
        throw "No numeric version found in: $Text"
    }
    $parts = $match.Value.Split('.')
    while ($parts.Count -lt 4) {
        $parts += '0'
    }
    return [Version]::new(
        [int]$parts[0],
        [int]$parts[1],
        [int]$parts[2],
        [int]$parts[3]
    )
}

$expected = Convert-ToComparableVersion $ExpectedVersion
$productVersion = Convert-ToComparableVersion $productVersionText
if (
    $productVersion.Major -ne $expected.Major -or
    $productVersion.Minor -ne $expected.Minor -or
    $productVersion.Build -ne $expected.Build
) {
    throw "Windows ProductVersion mismatch: expected $ExpectedVersion, found $productVersionText"
}

$exeHash = (Get-FileHash -LiteralPath $exePath -Algorithm SHA256).Hash.ToLowerInvariant()
$metadata = [ordered]@{
    version = $ExpectedVersion
    product_version = $productVersionText
    file_version = $fileVersionText
    executable = 'ChopLab.exe'
    executable_sha256 = $exeHash
    commit = $env:GITHUB_SHA
}

if ($MetadataOutput) {
    $parent = Split-Path -Parent $MetadataOutput
    if ($parent) {
        New-Item -ItemType Directory -Force -Path $parent | Out-Null
    }
    $metadata | ConvertTo-Json | Set-Content -LiteralPath $MetadataOutput -Encoding utf8
}

Write-Host "Verified Windows app-image version $ExpectedVersion; ProductVersion=$productVersionText; SHA-256=$exeHash"
