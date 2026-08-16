$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root

if (Get-Command bash -ErrorAction SilentlyContinue) {
    & bash .\scripts\validate_project.sh
    if ($LASTEXITCODE -ne 0) { throw "Offline validation failed" }
} else {
    Write-Warning "bash is unavailable; skipping scripts/validate_project.sh. Use Git Bash or WSL for that check."
}

if (-not $env:ANDROID_SDK_ROOT -and -not $env:ANDROID_HOME -and -not (Test-Path "local.properties")) {
    throw "Android SDK is not configured."
}

& .\gradlew.bat --stacktrace clean :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --max-workers=1 --no-watch-fs
if ($LASTEXITCODE -ne 0) { throw "Gradle verification failed" }

$Apk = "app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $Apk)) { throw "APK not found: $Apk" }
$Hash = Get-FileHash $Apk -Algorithm SHA256
Write-Host "$($Hash.Hash.ToLower())  $Apk"
$Head = (& git rev-parse --short=12 HEAD).Trim()
$Receipt = "outputs\build-provenance-$Head.json"
& .\scripts\write-build-provenance.ps1 -OutputPath $Receipt
if ($LASTEXITCODE -ne 0) { throw "Build provenance failed" }
Write-Host "PASS: full verification completed"
