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
$apkanalyzer = Join-Path $AndroidSdk "cmdline-tools\latest\bin\apkanalyzer.bat"
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

function Get-ApkManifestValue {
    param(
        [Parameter(Mandatory)] [string]$ApkPath,
        [Parameter(Mandatory)] [string]$Property
    )
    $value = & $apkanalyzer manifest $Property $ApkPath 2>&1
    if ($LASTEXITCODE -ne 0) { throw "apkanalyzer failed for $Property in $ApkPath" }
    return (($value | Select-Object -First 1).ToString().Trim())
}

function Get-PackageDumpValue {
    param(
        [Parameter(Mandatory)] [AllowEmptyString()] [string[]]$Dump,
        [Parameter(Mandatory)] [string]$Pattern,
        [Parameter(Mandatory)] [string]$Name
    )
    $match = [regex]::Match(($Dump -join "`n"), $Pattern)
    if (-not $match.Success) { throw "Could not parse $Name from package dump" }
    return $match.Groups[1].Value
}

function Restore-DeviceStateBestEffort {
    param(
        [Parameter(Mandatory)] [string]$TargetSerial,
        [Parameter(Mandatory)] [string]$TargetDirectory,
        [Parameter(Mandatory)] [string]$Foreground,
        [Parameter(Mandatory)] [string]$Rotation,
        [Parameter(Mandatory)] [string]$Volume
    )
    $forceStopOutput = & $adb -s $TargetSerial shell am force-stop com.choplab.sampler 2>&1
    Set-Content -LiteralPath (Join-Path $TargetDirectory "force-stop.txt") -Value $forceStopOutput -Encoding utf8
    $rotationOutput = & $adb -s $TargetSerial shell settings put system accelerometer_rotation $Rotation 2>&1
    Set-Content -LiteralPath (Join-Path $TargetDirectory "rotation-restore.txt") -Value $rotationOutput -Encoding utf8
    $volumeOutput = & $adb -s $TargetSerial shell cmd media_session volume --stream 3 --set $Volume 2>&1
    Set-Content -LiteralPath (Join-Path $TargetDirectory "volume-restore.txt") -Value $volumeOutput -Encoding utf8
    if ($Foreground -match "launcher") {
        $foregroundOutput = & $adb -s $TargetSerial shell input keyevent 3 2>&1
    } else {
        $foregroundOutput = & $adb -s $TargetSerial shell am start -n $Foreground 2>&1
    }
    Set-Content -LiteralPath (Join-Path $TargetDirectory "foreground-restore.txt") -Value $foregroundOutput -Encoding utf8
}

$restoreRequired = $false
$restoreSerial = $null
$restoreDirectory = $null
$restoreForeground = $null
$restoreRotation = $null
$restoreVolume = $null
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
        "--no-daemon",
        "--max-workers=1",
        "--no-watch-fs"
    )
    Invoke-NativeChecked -FilePath (Join-Path $repoRoot "gradlew.bat") -ArgumentList $buildArgs -LogPath (Join-Path $runDirectory "gradle.log") | Out-Null

    $appSource = Join-Path $repoRoot "app\build\outputs\apk\debug\app-debug.apk"
    $testSource = Join-Path $repoRoot "app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk"
    if (-not (Test-Path -LiteralPath $testSource -PathType Leaf)) {
        throw "Expected Android test APK not found: $testSource"
    }
    $appArtifact = Join-Path $runDirectory "app-debug.apk"
    $testArtifact = Join-Path $runDirectory "app-debug-androidTest.apk"
    Copy-Item -LiteralPath $appSource -Destination $appArtifact
    Copy-Item -LiteralPath $testSource -Destination $testArtifact

    $appHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $appArtifact).Hash
    $testHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $testArtifact).Hash
    $appSigner = Get-ApkSignerSha256 $appArtifact
    $testSigner = Get-ApkSignerSha256 $testArtifact
    $appPackage = Get-ApkManifestValue $appArtifact "application-id"
    $appVersionName = Get-ApkManifestValue $appArtifact "version-name"
    $appVersionCode = Get-ApkManifestValue $appArtifact "version-code"
    $testPackage = Get-ApkManifestValue $testArtifact "application-id"
    $testVersionName = Get-ApkManifestValue $testArtifact "version-name"
    $testVersionCode = Get-ApkManifestValue $testArtifact "version-code"
    if ($appPackage -ne "com.choplab.sampler" -or $testPackage -ne "com.choplab.sampler.test") {
        throw "Unexpected APK package identity: app=$appPackage test=$testPackage"
    }
    $deviceEvidence = $null

    if ($InstallAndTest) {
        if (-not $Serial) { throw "-Serial is required with -InstallAndTest" }
        $adbState = Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "get-state") -LogPath (Join-Path $runDirectory "adb-state.txt")
        if ((($adbState | Select-Object -First 1).Trim()) -ne "device") {
            throw "ADB target $Serial is not in device state"
        }
        $adbDevices = Invoke-NativeChecked -FilePath $adb -ArgumentList @("devices", "-l") -LogPath (Join-Path $runDirectory "adb-devices.txt")
        $matchingDevice = $adbDevices | Where-Object { $_ -match "^$([regex]::Escape($Serial))\s+device(?:\s|$)" }
        if (@($matchingDevice).Count -ne 1) { throw "Serial $Serial is not listed exactly once in device state" }
        $logcatStart = Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "date", "+%s") -LogPath (Join-Path $runDirectory "logcat-start.txt")
        $activityBefore = Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "dumpsys", "activity", "activities") -LogPath (Join-Path $runDirectory "activity-before.txt")
        $rotationBefore = ((Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "settings", "get", "system", "accelerometer_rotation") -LogPath (Join-Path $runDirectory "rotation-before.txt") | Select-Object -First 1).Trim())
        $volumeBeforeOutput = Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "cmd", "media_session", "volume", "--stream", "3", "--get") -LogPath (Join-Path $runDirectory "volume-before.txt")
        $volumeMatch = [regex]::Match(($volumeBeforeOutput -join "`n"), "volume is\s+(\d+)")
        if (-not $volumeMatch.Success) { throw "Could not parse media volume before the run" }
        $volumeBefore = $volumeMatch.Groups[1].Value
        $foregroundMatch = [regex]::Match(($activityBefore -join "`n"), "topResumedActivity=.*?\s([A-Za-z0-9._]+/[A-Za-z0-9._$]+)")
        if (-not $foregroundMatch.Success) { throw "Could not parse foreground activity before the run" }
        $foregroundBefore = $foregroundMatch.Groups[1].Value
        $restoreRequired = $true
        $restoreSerial = $Serial
        $restoreDirectory = $runDirectory
        $restoreForeground = $foregroundBefore
        $restoreRotation = $rotationBefore
        $restoreVolume = $volumeBefore

        $packageBefore = Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "dumpsys", "package", $appPackage) -LogPath (Join-Path $runDirectory "package-before.txt")
        $installedVersionNameBefore = Get-PackageDumpValue $packageBefore "versionName=([^\s]+)" "installed versionName before"
        $installedVersionCodeBefore = Get-PackageDumpValue $packageBefore "versionCode=(\d+)" "installed versionCode before"
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
        $autosaveBefore = Invoke-NativeChecked -FilePath $adb -ArgumentList (@("-s", $Serial, "shell", "run-as", "com.choplab.sampler", "sha256sum") + $autosaveFiles) -LogPath (Join-Path $runDirectory "autosave-before.txt")
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "install", "-r", $appArtifact) -LogPath (Join-Path $runDirectory "install.txt") | Out-Null
        $autosaveAfter = Invoke-NativeChecked -FilePath $adb -ArgumentList (@("-s", $Serial, "shell", "run-as", "com.choplab.sampler", "sha256sum") + $autosaveFiles) -LogPath (Join-Path $runDirectory "autosave-after.txt")
        $normalizedBefore = ($autosaveBefore | ForEach-Object { $_.ToString().Trim() }) -join "`n"
        $normalizedAfter = ($autosaveAfter | ForEach-Object { $_.ToString().Trim() }) -join "`n"
        if ($normalizedBefore -ne $normalizedAfter) {
            throw "Autosave hashes changed across installation. See autosave-before.txt and autosave-after.txt"
        }

        $installedPathOutput = Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "pm", "path", "com.choplab.sampler") -LogPath (Join-Path $runDirectory "pm-path-after.txt")
        $installedPath = (($installedPathOutput | Select-Object -First 1) -replace "^package:", "").Trim()
        $installedApk = Join-Path $runDirectory "installed-base.apk"
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "pull", $installedPath, $installedApk) -LogPath (Join-Path $runDirectory "pull-after.txt") | Out-Null
        $installedHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $installedApk).Hash
        if ($installedHash -ne $appHash) { throw "Installed base APK hash mismatch: $installedHash != $appHash" }

        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "install", "-r", $testArtifact) -LogPath (Join-Path $runDirectory "test-install.txt") | Out-Null
        $instrumentation = Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "am", "instrument", "-w", "-r", "com.choplab.sampler.test/androidx.test.runner.AndroidJUnitRunner") -LogPath (Join-Path $runDirectory "instrumentation.txt")
        $instrumentationText = $instrumentation -join "`n"
        if ($instrumentationText -match "FAILURES!!!" -or $instrumentationText -notmatch "OK \([0-9]+ tests?\)") {
            throw "Instrumentation did not report an all-green result. See instrumentation.txt"
        }
        $packageAfter = Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "dumpsys", "package", $appPackage) -LogPath (Join-Path $runDirectory "package-after.txt")
        $installedVersionNameAfter = Get-PackageDumpValue $packageAfter "versionName=([^\s]+)" "installed versionName after"
        $installedVersionCodeAfter = Get-PackageDumpValue $packageAfter "versionCode=(\d+)" "installed versionCode after"
        if ($installedVersionNameAfter -ne $appVersionName -or $installedVersionCodeAfter -ne $appVersionCode) {
            throw "Installed package version differs from candidate after install"
        }
        $testPathOutput = Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "pm", "path", $testPackage) -LogPath (Join-Path $runDirectory "pm-path-test.txt")
        $testInstalledPath = (($testPathOutput | Select-Object -First 1) -replace "^package:", "").Trim()
        $installedTestApk = Join-Path $runDirectory "installed-test-base.apk"
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "pull", $testInstalledPath, $installedTestApk) -LogPath (Join-Path $runDirectory "pull-test.txt") | Out-Null
        $installedTestHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $installedTestApk).Hash
        $installedTestSigner = Get-ApkSignerSha256 $installedTestApk
        if ($installedTestHash -ne $testHash -or $installedTestSigner -ne $testSigner) {
            throw "Installed test APK identity mismatch"
        }
        $testPackageDump = Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "dumpsys", "package", $testPackage) -LogPath (Join-Path $runDirectory "package-test.txt")
        $installedTestVersionName = Get-PackageDumpValue $testPackageDump "versionName=([^\s]+)" "installed test versionName"
        $installedTestVersionCode = Get-PackageDumpValue $testPackageDump "versionCode=(\d+)" "installed test versionCode"
        $expectedTestVersionName = if ($testVersionName -eq "UNKNOWN") { "null" } else { $testVersionName }
        $expectedTestVersionCode = if ($testVersionCode -eq "UNKNOWN") { "0" } else { $testVersionCode }
        if ($installedTestVersionName -ne $expectedTestVersionName -or $installedTestVersionCode -ne $expectedTestVersionCode) {
            throw "Installed test package version differs from candidate"
        }
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "logcat", "-d", "-v", "threadtime", "-T", (($logcatStart | Select-Object -First 1).Trim())) -LogPath (Join-Path $runDirectory "logcat-window.txt") | Out-Null
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "am", "force-stop", "com.choplab.sampler") -LogPath (Join-Path $runDirectory "force-stop.txt") | Out-Null
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "settings", "put", "system", "accelerometer_rotation", $rotationBefore) -LogPath (Join-Path $runDirectory "rotation-restore.txt") | Out-Null
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "cmd", "media_session", "volume", "--stream", "3", "--set", $volumeBefore) -LogPath (Join-Path $runDirectory "volume-restore.txt") | Out-Null
        if ($foregroundBefore -match "launcher") {
            Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "input", "keyevent", "3") -LogPath (Join-Path $runDirectory "foreground-restore.txt") | Out-Null
        } else {
            Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "am", "start", "-n", $foregroundBefore) -LogPath (Join-Path $runDirectory "foreground-restore.txt") | Out-Null
        }
        $activityFinal = Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "dumpsys", "activity", "activities") -LogPath (Join-Path $runDirectory "activity-final.txt")
        $volumeFinalOutput = Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "cmd", "media_session", "volume", "--stream", "3", "--get") -LogPath (Join-Path $runDirectory "volume-final.txt")
        $rotationFinal = ((Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "settings", "get", "system", "accelerometer_rotation") -LogPath (Join-Path $runDirectory "rotation-final.txt") | Select-Object -First 1).Trim())
        $volumeFinalMatch = [regex]::Match(($volumeFinalOutput -join "`n"), "volume is\s+(\d+)")
        $foregroundFinalMatch = [regex]::Match(($activityFinal -join "`n"), "topResumedActivity=.*?\s([A-Za-z0-9._]+/[A-Za-z0-9._$]+)")
        $volumeFinal = if ($volumeFinalMatch.Success) { $volumeFinalMatch.Groups[1].Value } else { "UNKNOWN" }
        $foregroundFinal = if ($foregroundFinalMatch.Success) { $foregroundFinalMatch.Groups[1].Value } else { "UNKNOWN" }
        $foregroundRestored = $foregroundFinal -eq $foregroundBefore -or
            ($foregroundBefore -match "launcher" -and $foregroundFinal -match "launcher")
        if ($rotationFinal -ne $rotationBefore -or $volumeFinal -ne $volumeBefore -or -not $foregroundRestored) {
            throw "Device state restoration mismatch. See before/final evidence files"
        }
        $restoreRequired = $false
        Invoke-NativeChecked -FilePath $adb -ArgumentList @("-s", $Serial, "shell", "run-as", "com.choplab.sampler", "ls", "files/projects") -LogPath (Join-Path $runDirectory "projects-final.txt") | Out-Null
        $deviceEvidence = [ordered]@{
            serial = $Serial
            installed_base_sha256 = $installedHash
            installed_test_sha256 = $installedTestHash
            signer_sha256 = $installedSigner
            package = $appPackage
            version_name = $installedVersionNameAfter
            version_code = $installedVersionCodeAfter
            test_package = $testPackage
            test_version_name = $installedTestVersionName
            test_version_code = $installedTestVersionCode
            install_log = "install.txt"
            instrumentation_log = "instrumentation.txt"
            autosave_before = "autosave-before.txt"
            autosave_after = "autosave-after.txt"
            autosave_preservation_pass = $true
            foreground_before = $foregroundBefore
            rotation_before = $rotationBefore
            volume_before = [int]$volumeBefore
            foreground_restore_log = "foreground-restore.txt"
            rotation_restore_log = "rotation-restore.txt"
            volume_restore_log = "volume-restore.txt"
            foreground_final = $foregroundFinal
            rotation_final = $rotationFinal
            volume_final = [int]$volumeFinal
            state_restoration_pass = $true
            logcat_start = "logcat-start.txt"
            logcat_window = "logcat-window.txt"
            final_activity = "activity-final.txt"
            final_volume = "volume-final.txt"
            final_rotation = "rotation-final.txt"
            final_projects = "projects-final.txt"
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
            package = $appPackage
            version_name = $appVersionName
            version_code = $appVersionCode
        }
        test_apk = [ordered]@{
            file = "app-debug-androidTest.apk"
            size = (Get-Item -LiteralPath $testArtifact).Length
            sha256 = $testHash
            signer_sha256 = $testSigner
            package = $testPackage
            version_name = $testVersionName
            version_code = $testVersionCode
        }
        device = $deviceEvidence
        gate = if ($InstallAndTest) { "INSTRUMENTATION_EVIDENCE_COLLECTED" } else { "LOCAL_ARTIFACT_EVIDENCE_COLLECTED" }
    }
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $runDirectory "manifest.json") -Encoding utf8
    Write-Output $runDirectory
} finally {
    if ($restoreRequired -and $restoreSerial -and $restoreDirectory) {
        Restore-DeviceStateBestEffort -TargetSerial $restoreSerial -TargetDirectory $restoreDirectory `
            -Foreground $restoreForeground -Rotation $restoreRotation -Volume $restoreVolume
    }
    Pop-Location
}
