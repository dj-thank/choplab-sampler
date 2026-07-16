param(
    [switch]$InstallSdk,
    [switch]$Build
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root

if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw "Java is missing. Install JDK 17." }

$SdkRoot = $env:ANDROID_SDK_ROOT
if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
if (-not $SdkRoot -and (Test-Path "local.properties")) {
    $Line = Get-Content "local.properties" | Where-Object { $_ -like "sdk.dir=*" } | Select-Object -First 1
    if ($Line) { $SdkRoot = $Line.Substring(8).Replace("\:", ":").Replace("\\", "\") }
}

if ($SdkRoot) {
    $env:ANDROID_SDK_ROOT = $SdkRoot
    $env:ANDROID_HOME = $SdkRoot
    if (-not (Test-Path "local.properties")) {
        $Escaped = $SdkRoot.Replace("\", "\\").Replace(":", "\:")
        Set-Content -Path "local.properties" -Value "sdk.dir=$Escaped" -Encoding ascii
        Write-Host "Created local.properties"
    }
}

if ($InstallSdk) {
    $SdkManager = Get-Command sdkmanager -ErrorAction SilentlyContinue
    if (-not $SdkManager -and $SdkRoot) {
        $Candidate = Join-Path $SdkRoot "cmdline-tools\latest\bin\sdkmanager.bat"
        if (Test-Path $Candidate) { $SdkManager = Get-Item $Candidate }
    }
    if (-not $SdkManager) { throw "sdkmanager is missing. Install Android command-line tools or use Android Studio SDK Manager." }
    & $SdkManager.Source "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;29.0.14206865" "cmake;3.22.1"
    if ($LASTEXITCODE -ne 0) { throw "sdkmanager failed" }
}

if (Get-Command bash -ErrorAction SilentlyContinue) {
    & bash .\scripts\validate_project.sh
    if ($LASTEXITCODE -ne 0) { throw "Offline validation failed" }
} else {
    Write-Warning "bash is unavailable; skipping scripts/validate_project.sh. Use Git Bash or WSL for that check."
}
& .\gradlew.bat --version
if ($LASTEXITCODE -ne 0) { throw "Gradle wrapper failed" }

if ($Build) {
    & .\scripts\verify.ps1
} else {
    Write-Host "Bootstrap complete. Run scripts\verify.ps1 for the full Android build gate."
}
