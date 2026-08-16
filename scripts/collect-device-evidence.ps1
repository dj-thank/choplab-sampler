[CmdletBinding()]
param(
    [string]$Serial,
    [switch]$InstallAndTest,
    [string]$EvidenceRoot = "work/device-evidence",
    [string]$JavaHome = "F:\CodexData\ChopLab\tools\jdk17\jdk-17.0.20+8",
    [string]$AndroidSdk = "F:\CodexData\ChopLab\tools\android-sdk",
    [string]$GradleHome = "F:\CodexData\ChopLab\tools\gradle-home"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$adb = Join-Path $AndroidSdk "platform-tools\adb.exe"
$apksigner = Join-Path $AndroidSdk "build-tools\35.0.0\apksigner.bat"
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$env:ANDROID_SDK_ROOT = $AndroidSdk
$env:GRADLE_USER_HOME = $GradleHome

function Invoke-NativeChecked {
    param(
        [Parameter(Mandatory)] [string]$FilePath,
        [Parameter(Mandatory)] [string[]]$ArgumentList,
        [Parameter(Mandatory)] [string]$LogPath
    )
    $output = & $FilePath @ArgumentList 2>&1
    $output | Set-Content -LiteralPath $LogPath -Encoding utf8
    if ($LASTEXITCODE -ne 0) {
        throw "$FilePath failed with exit code $LASTEXITCODE. See $LogPath"
    }
    return $output
}

function Get-ApkSignerSha256 {
    param([Parameter(Mandatory)] [string]$ApkPath)
    $certificate = & $apksigner verify --print-certs $ApkPath 2>&1
    if ($LASTEXITCODE -ne 0) { throw "apksigner failed for $ApkPath" }
    $line = $certificate | Select-String "Signer #1 certificate SHA-256 digest:" | Select-Object -First 1
    if (-not $line) { throw "No SHA-256 signer found for $ApkPath" }
    return ($line.Line -split ":", 2)[1].Trim().ToUpperInvariant()
}

Push-Location $repoRoot
try {
    $head = (& git rev-parse HEAD).Trim()
    $tree = (& git rev-parse "HEAD^{tree}").Trim()
    $trackedStatus = (& git status --porcelain=v1 --untracked-files=no) -join "`n"
    if ($trackedStatus) {
        throw "Tracked worktree must be clean before evidence collection.`n$trackedStatus"
    }

    $runId = "{0}-{1}" -f (Get-Date -Format "yyyyMMdd-HHmmss"), $head.Substring(0, 8)
    $resolvedEvidenceRoot = Join-Path $repoRoot $EvidenceRoot
    $runDirectory = Join-Path $resolvedEvidenceRoot $runId
    if (Test-Path -LiteralPath $runDirectory) { throw "Evidence directory already exists: $runDirectory" }
    New-Item -ItemType Directory -Path $runDirectory | Out-Null

    (& git status --porcelain=v1) | Set-Content -LiteralPath (Join-Path $runDirectory "git-status.txt") -Encoding utf8
    (& git diff --binary HEAD) | Set-Content -LiteralPath (Join-Path $runDirectory "git-diff.patch") -Encoding utf8

    $buildArgs = @(
        ":app:testDebugUnitTest",
        ":app:lintDebug",
        ":app:assembleDebug",
        ":app:assembleDebugAndroidTest",
        "--no-daemon"
    )
    Invoke-NativeChecked -FilePath (Join-Path $repoRoot "gradlew.bat") -ArgumentList $buildArgs -LogPath (Join-Path $runDirectory "gradle.log") | Out-Null

    $appSource = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
    $testSource = Get-ChildItem -LiteralPath (Join-Path $repoRoot "app\build\outputs\apk\androidTest\debug") -Filter "*.apk" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $testSource) { throw "Android test APK not found" }
    $appArtifact = Join-Path $runDirectory "app-debug.apk"
    $testArtifact = Join-Path $runDirectory "app-debug-androidTest.apk"
    Copy-Item -LiteralPath $appSource -Destination $appArtifact
    Copy-Item -LiteralPath $testSource.FullName -Destination $testArtifact

    $appHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $appArtifact).Hash
    $testHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $testArtifact).Hash
    $appSigner = Get-ApkSignerSha256 $appArtifact
    $testSigner = Get-ApkSignerSha256 $testArtifact
    $deviceEvidence = $null

    if ($InstallAndTest) {
        if (-not $Serial) { throw "-Serial is required with -InstallAndTest" }
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "get-state") -LogPath (Join-Path $runDirectory "adb-state.txt") | Out-Null
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("devices", "-l") -LogPath (Join-Path $runDirectory "adb-devices.txt") | Out-Null
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "dumpsys", "package", "com.choplab.sampler") -LogPath (Join-Path $runDirectory "package-before.txt") | Out-Null
        $packagePathOutput = Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "pm", "path", "com.choplab.sampler") -LogPath (Join-Path $runDirectory "pm-path-before.txt")
        $packagePath = (($packagePathOutput | Select-Object -First 1) -replace "^package:", "").Trim()
        $preinstalledApk = Join-Path $runDirectory "preinstalled-base.apk"
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "pull", $packagePath, $preinstalledApk) -LogPath (Join-Path $runDirectory "pull-before.txt") | Out-Null
        $installedSigner = Get-ApkSignerSha256 $preinstalledApk
        if ($installedSigner -ne $appSigner) {
            throw "Signer mismatch. Installed=$installedSigner candidate=$appSigner. Installation stopped."
        }

        $autosaveFiles = @(
            "files/projects/autosave.choplab",
            "files/projects/autosave.previous.choplab",
            "files/projects/autosave.previous2.choplab"
        )
        Invoke-NativeChecked -FilePath $adb -ArgumentList (@("-s", $Serial, "shell", "run-as", "com.choplab.sampler", "sha256sum") + $autosaveFiles) -LogPath (Join-Path $runDirectory "autosave-before.txt") | Out-Null
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "install", "-r", $appArtifact) -LogPath (Join-Path $runDirectory "install.txt") | Out-Null
        Invoke-NativeChecked -FilePath $adb -ArgumentList (@("-s", $Serial, "shell", "run-as", "com.choplab.sampler", "sha256sum") + $autosaveFiles) -LogPath (Join-Path $runDirectory "autosave-after.txt") | Out-Null

        $installedPathOutput = Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "pm", "path", "com.choplab.sampler") -LogPath (Join-Path $runDirectory "pm-path-after.txt")
        $installedPath = (($installedPathOutput | Select-Object -First 1) -replace "^package:", "").Trim()
        $installedApk = Join-Path $runDirectory "installed-base.apk"
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "pull", $installedPath, $installedApk) -LogPath (Join-Path $runDirectory "pull-after.txt") | Out-Null
        $installedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $installedApk).Hash
        if ($installedHash -ne $appHash) { throw "Installed base APK hash mismatch: $installedHash != $appHash" }

        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "install", "-r", $testArtifact) -LogPath (Join-Path $runDirectory "test-install.txt") | Out-Null
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "am", "instrument", "-w", "-r", "com.choplab.sampler.test/androidx.test.runner.AndroidJUnitRunner") -LogPath (Join-Path $runDirectory "instrumentation.txt") | Out-Null
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "dumpsys", "package", "com.choplab.sampler") -LogPath (Join-Path $runDirectory "package-after.txt") | Out-Null
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "logcat", "-d", "-v", "threadtime", "-t", "2000") -LogPath (Join-Path $runDirectory "logcat-tail.txt") | Out-Null
        $deviceEvidence = [ordered]@{
            serial = $Serial
            installed_base_sha256 = $installedHash
            signer_sha256 = $installedSigner
            install_log = "install.txt"
            instrumentation_log = "instrumentation.txt"
            autosave_before = "autosave-before.txt"
            autosave_after = "autosave-after.txt"
        }
    }

    $manifest = [ordered]@{
        schema = 1
        run_id = $runId
        captured_at = (Get-Date).ToString("o")
        git = [ordered]@{
            head = $head
            tree = $tree
            tracked_clean = $true
            status_file = "git-status.txt"
            diff_file = "git-diff.patch"
        }
        build = [ordered]@{
            command = ".\gradlew.bat $($buildArgs -join ' ')"
            log = "gradle.log"
        }
        app_apk = [ordered]@{
            file = "app-debug.apk"
            size = (Get-Item -LiteralPath $appArtifact).Length
            sha256 = $appHash
            signer_sha256 = $appSigner
        }
        test_apk = [ordered]@{
            file = "app-debug-androidTest.apk"
            size = (Get-Item -LiteralPath $testArtifact).Length
            sha256 = $testHash
            signer_sha256 = $testSigner
        }
        device = $deviceEvidence
        gate = if ($InstallAndTest) { "INSTRUMENTATION_EVIDENCE_COLLECTED" } else { "LOCAL_ARTIFACT_EVIDENCE_COLLECTED" }
    }
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDirectory "manifest.json") -Encoding utf8
    Write-Output $runDirectory
} finally {
    Pop-Location
}
