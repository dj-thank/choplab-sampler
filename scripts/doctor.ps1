$ErrorActionPreference = "Continue"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root

function Ok($Message) { Write-Host "OK   $Message" -ForegroundColor Green }
function Warn($Message) { Write-Host "WARN $Message" -ForegroundColor Yellow }
function Info($Message) { Write-Host "INFO $Message" -ForegroundColor Cyan }

Info "workspace: $Root"

if (Get-Command git -ErrorAction SilentlyContinue) { Ok (git --version) } else { Warn "git is not installed" }
if (Get-Command java -ErrorAction SilentlyContinue) {
    $JavaLine = (& java -version 2>&1 | Select-Object -First 1)
    Ok "java: $JavaLine"
} else { Warn "java is not installed; install JDK 17" }

$SdkRoot = $env:ANDROID_SDK_ROOT
if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
if (-not $SdkRoot -and (Test-Path "local.properties")) {
    $Line = Get-Content "local.properties" | Where-Object { $_ -like "sdk.dir=*" } | Select-Object -First 1
    if ($Line) { $SdkRoot = $Line.Substring(8).Replace("\:", ":").Replace("\\", "\") }
}

if ($SdkRoot -and (Test-Path $SdkRoot)) {
    Ok "Android SDK: $SdkRoot"
    $Packages = @(
        "platforms\android-36",
        "build-tools\36.0.0",
        "platform-tools",
        "ndk\29.0.14206865",
        "cmake\3.22.1"
    )
    foreach ($Package in $Packages) {
        if (Test-Path (Join-Path $SdkRoot $Package)) { Ok "SDK component: $Package" } else { Warn "missing SDK component: $Package" }
    }
} else { Warn "ANDROID_HOME/ANDROID_SDK_ROOT or local.properties is not configured" }

if (Get-Command adb -ErrorAction SilentlyContinue) { Ok ((& adb version | Select-Object -First 1)) } else { Warn "adb is not on PATH" }
if (Get-Command codex -ErrorAction SilentlyContinue) {
    Ok "codex: $(& codex --version | Select-Object -First 1)"
    & codex login status *> $null
    if ($LASTEXITCODE -eq 0) { Ok "Codex authentication is available" } else { Warn "Codex is not signed in; run codex login" }
} else { Warn "Codex CLI is not installed or not on PATH" }

if (Test-Path ".git") {
    Ok "Git repository"
    if ((git status --porcelain)) { Warn "working tree has uncommitted changes" } else { Ok "working tree is clean" }
} else { Warn "not a Git repository" }
