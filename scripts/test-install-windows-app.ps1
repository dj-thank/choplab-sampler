param(
    [Parameter(Mandatory = $true)]
    [string]$AppImage,

    [Parameter(Mandatory = $true)]
    [string]$ExpectedVersion
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$installer = Join-Path $PSScriptRoot 'install-windows-app.ps1'
if (-not (Test-Path -LiteralPath $installer -PathType Leaf)) {
    throw "Installer script not found: $installer"
}

$testRoot = Join-Path ([IO.Path]::GetTempPath()) "choplab-installer-test-$([guid]::NewGuid().ToString('N'))"
$installRoot = Join-Path $testRoot 'Programs\ChopLab'
$startMenuRoot = Join-Path $testRoot 'Start Menu\Programs'
$desktopRoot = Join-Path $testRoot 'Desktop'
$projectRoot = Join-Path $testRoot 'LocalAppData\ChopLab\projects'
$sentinel = Join-Path $projectRoot 'keep-this-project.choplab'
$receipt = Join-Path $testRoot 'install-result.json'

try {
    New-Item -ItemType Directory -Path $projectRoot -Force | Out-Null
    Set-Content -LiteralPath $sentinel -Value 'preserve-me' -Encoding utf8

    & $installer `
        -AppImage $AppImage `
        -Version $ExpectedVersion `
        -InstallRoot $installRoot `
        -StartMenuRoot $startMenuRoot `
        -DesktopRoot $desktopRoot `
        -ReceiptOutput $receipt

    $first = Get-Content -LiteralPath $receipt -Raw -Encoding utf8 | ConvertFrom-Json
    $installedExe = [IO.Path]::GetFullPath([string]$first.installed_executable)
    $safeInstallRoot = [IO.Path]::GetFullPath($installRoot).TrimEnd([IO.Path]::DirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
    if (-not $installedExe.StartsWith($safeInstallRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Installed executable escaped test root: $installedExe"
    }
    if (-not (Test-Path -LiteralPath $installedExe -PathType Leaf)) {
        throw "Installed executable missing: $installedExe"
    }

    $sourceHash = (Get-FileHash -LiteralPath (Join-Path $AppImage 'ChopLab.exe') -Algorithm SHA256).Hash
    $installedHash = (Get-FileHash -LiteralPath $installedExe -Algorithm SHA256).Hash
    if ($sourceHash -ne $installedHash) { throw 'Installed executable hash does not match source app-image' }

    $shell = New-Object -ComObject WScript.Shell
    foreach ($shortcutPath in @($first.start_menu_shortcut, $first.desktop_shortcut)) {
        if (-not (Test-Path -LiteralPath $shortcutPath -PathType Leaf)) {
            throw "Shortcut missing: $shortcutPath"
        }
        $shortcut = $shell.CreateShortcut($shortcutPath)
        if ([IO.Path]::GetFullPath($shortcut.TargetPath) -ne $installedExe) {
            throw "Shortcut target mismatch: $shortcutPath -> $($shortcut.TargetPath)"
        }
    }

    if ((Get-Content -LiteralPath $sentinel -Raw -Encoding utf8).Trim() -ne 'preserve-me') {
        throw 'Installer modified the project-data sentinel'
    }

    & $installer `
        -AppImage $AppImage `
        -Version $ExpectedVersion `
        -InstallRoot $installRoot `
        -StartMenuRoot $startMenuRoot `
        -DesktopRoot $desktopRoot `
        -ReceiptOutput $receipt
    $second = Get-Content -LiteralPath $receipt -Raw -Encoding utf8 | ConvertFrom-Json
    if ([string]$second.installed_executable -ne [string]$first.installed_executable) {
        throw 'Idempotent installer run selected different bytes'
    }

    Write-Host "PASS: Windows app-image install is hash-bound, shortcut-bound, idempotent, and project-data preserving"
} finally {
    if (Test-Path -LiteralPath $testRoot) {
        $resolvedTestRoot = [IO.Path]::GetFullPath($testRoot)
        $resolvedTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
        if (-not $resolvedTestRoot.StartsWith($resolvedTempRoot, [StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove test directory outside temp: $resolvedTestRoot"
        }
        Remove-Item -LiteralPath $resolvedTestRoot -Recurse -Force
    }
}
