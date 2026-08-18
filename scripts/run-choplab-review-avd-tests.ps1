[CmdletBinding()]
param(
    [string]$Serial = 'emulator-5592',
    [string]$AndroidSdkRoot = $env:ANDROID_SDK_ROOT,
    [string]$AppApk = 'app\build\outputs\apk\debug\app-debug.apk',
    [string]$TestApk = 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk',
    [Parameter(Mandatory = $true)]
    [string]$BuildProvenancePath,
    [string]$OutputPath = 'work\choplab-review-avd-latest.json',
    [int]$BootTimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($Serial -notmatch '^emulator-\d+$') {
    throw "Only an explicit emulator serial is accepted; refusing '$Serial'"
}
if ([string]::IsNullOrWhiteSpace($AndroidSdkRoot)) { throw 'ANDROID_SDK_ROOT is required' }
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location -LiteralPath $root
$manifest = Get-Content -LiteralPath 'config\choplab-review-avd.json' -Raw -Encoding UTF8 | ConvertFrom-Json
$trackedStatus = @(& git status --porcelain=v1 --untracked-files=no)
if ($LASTEXITCODE -ne 0) { throw 'git status failed' }
if ($trackedStatus.Count -ne 0) {
    throw "Tracked worktree must be clean before an AVD evidence run:`n$($trackedStatus -join "`n")"
}
$sourceHead = (& git rev-parse HEAD).Trim()
$sourceTree = (& git rev-parse 'HEAD^{tree}').Trim()
$provenanceResolved = (Resolve-Path -LiteralPath $BuildProvenancePath).Path
$provenance = Get-Content -LiteralPath $provenanceResolved -Raw -Encoding UTF8 | ConvertFrom-Json
if (-not [bool]$provenance.source.trackedClean -or
    $provenance.source.head -ne $sourceHead -or
    $provenance.source.tree -ne $sourceTree) {
    throw "Build provenance does not match current HEAD/tree: $provenanceResolved"
}
$adb = Join-Path $AndroidSdkRoot 'platform-tools\adb.exe'
if (-not (Test-Path -LiteralPath $adb -PathType Leaf)) { throw "adb is absent: $adb" }

function Invoke-Adb([string[]]$Arguments, [switch]$AllowFailure) {
    $output = @(& $adb -s $Serial @Arguments 2>&1)
    $exitCode = $LASTEXITCODE
    if (-not $AllowFailure -and $exitCode -ne 0) {
        throw "adb -s $Serial $($Arguments -join ' ') failed ($exitCode):`n$($output -join "`n")"
    }
    return [pscustomobject]@{ ExitCode = $exitCode; Lines = $output; Text = ($output -join "`n") }
}

function Wait-EmulatorReady([int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $stableProbes = 0
    do {
        $state = @(& $adb -s $Serial get-state 2>$null)
        if ($LASTEXITCODE -eq 0 -and ($state -join '').Trim() -eq 'device') {
            $boot = (Invoke-Adb -Arguments @('shell', 'getprop', 'sys.boot_completed') -AllowFailure).Text.Trim()
            $packageProbe = Invoke-Adb -Arguments @('shell', 'cmd', 'package', 'path', 'android') -AllowFailure
            if ($boot -eq '1' -and $packageProbe.ExitCode -eq 0 -and $packageProbe.Text -match '^package:') {
                $stableProbes++
            } else {
                $stableProbes = 0
            }
        }
        if ($stableProbes -lt 3) { Start-Sleep -Seconds 2 }
    } while ($stableProbes -lt 3 -and [DateTime]::UtcNow -lt $deadline)
    if ($stableProbes -lt 3) { throw "Emulator did not become stably ready within $TimeoutSeconds seconds: $Serial" }
}

function Get-EmulatorLocale {
    $value = (Invoke-Adb -Arguments @('shell', 'getprop', 'persist.sys.locale')).Text.Trim()
    if ([string]::IsNullOrWhiteSpace($value)) {
        $value = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'system', 'system_locales')).Text.Trim()
    }
    return $value
}

Wait-EmulatorReady -TimeoutSeconds $BootTimeoutSeconds

$api = (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.build.version.sdk')).Text.Trim()
$fingerprint = (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.build.fingerprint')).Text.Trim()
if ([int]$api -ne [int]$manifest.apiLevel) { throw "Expected API $($manifest.apiLevel), got $api" }
if ($fingerprint -notmatch 'google/sdk_gphone64_x86_64') { throw "Unexpected emulator image: $fingerprint" }
$locale = Get-EmulatorLocale
$localeConfigured = $false
if (($locale -replace '_', '-') -ine [string]$manifest.locale) {
    Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'system_locales', [string]$manifest.locale) | Out-Null
    Invoke-Adb -Arguments @('reboot') | Out-Null
    Wait-EmulatorReady -TimeoutSeconds $BootTimeoutSeconds
    $locale = Get-EmulatorLocale
    $localeConfigured = $true
}
if (($locale -replace '_', '-') -ine [string]$manifest.locale) {
    throw "Expected $($manifest.locale) locale after configuration, got '$locale'"
}

$appResolved = (Resolve-Path -LiteralPath $AppApk).Path
$testResolved = (Resolve-Path -LiteralPath $TestApk).Path
$appSha256 = (Get-FileHash -LiteralPath $appResolved -Algorithm SHA256).Hash.ToUpperInvariant()
$testSha256 = (Get-FileHash -LiteralPath $testResolved -Algorithm SHA256).Hash.ToUpperInvariant()
if ($provenance.appApk.sha256 -ne $appSha256 -or $provenance.androidTestApk.sha256 -ne $testSha256) {
    throw "Build provenance APK hashes do not match the selected artifacts: $provenanceResolved"
}
$originalFontScale = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'system', 'font_scale')).Text.Trim()
$originalAutoRotation = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'system', 'accelerometer_rotation')).Text.Trim()
$originalUserRotation = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'system', 'user_rotation')).Text.Trim()
$runs = [System.Collections.Generic.List[object]]::new()
$restoreErrors = [System.Collections.Generic.List[string]]::new()
$failure = $null
$restoredState = $null

try {
    Invoke-Adb -Arguments @('logcat', '-c') | Out-Null
    $appInstall = Invoke-Adb -Arguments @('install', '-r', $appResolved)
    if ($appInstall.Text -notmatch '(?m)^Success\s*$') { throw "App install did not report Success:`n$($appInstall.Text)" }
    $testInstall = Invoke-Adb -Arguments @('install', '-r', '-t', $testResolved)
    if ($testInstall.Text -notmatch '(?m)^Success\s*$') { throw "Test install did not report Success:`n$($testInstall.Text)" }

    $matrix = @(
        [pscustomobject]@{ Name = 'portrait-font-1.0'; FontScale = '1.0'; Rotation = 'portrait' },
        [pscustomobject]@{ Name = 'portrait-font-1.3'; FontScale = '1.3'; Rotation = 'portrait' },
        [pscustomobject]@{ Name = 'portrait-font-2.0'; FontScale = '2.0'; Rotation = 'portrait' },
        [pscustomobject]@{ Name = 'landscape-font-1.0'; FontScale = '1.0'; Rotation = 'landscape' }
    )

    foreach ($entry in $matrix) {
        Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'font_scale', $entry.FontScale) | Out-Null
        if ($entry.Rotation -eq 'landscape') {
            Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0') | Out-Null
            Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'user_rotation', '1') | Out-Null
        } else {
            Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'accelerometer_rotation', '0') | Out-Null
            Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'user_rotation', '0') | Out-Null
        }
        Start-Sleep -Seconds 1
        $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $instrumentation = Invoke-Adb -Arguments @(
            'shell', 'am', 'instrument', '-w', '-r',
            '-e', 'class', 'com.choplab.sampler.ui.SourceWaveformDeviceTest',
            'com.choplab.sampler.test/androidx.test.runner.AndroidJUnitRunner'
        ) -AllowFailure
        $stopwatch.Stop()
        $passed = $instrumentation.ExitCode -eq 0 -and
            $instrumentation.Text -match '(?m)^OK \(4 tests\)\s*$' -and
            $instrumentation.Text -notmatch '(?m)^FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed'
        $runs.Add([ordered]@{
            name = $entry.Name
            fontScale = $entry.FontScale
            rotation = $entry.Rotation
            durationSeconds = [Math]::Round($stopwatch.Elapsed.TotalSeconds, 3)
            passed = $passed
            transcript = $instrumentation.Text
        })
        if (-not $passed) { throw "Instrumentation failed for $($entry.Name):`n$($instrumentation.Text)" }
    }

    $logs = (Invoke-Adb -Arguments @('logcat', '-d', '-v', 'brief') -AllowFailure).Text
    $fatalMatches = @($logs -split "`r?`n" | Where-Object {
        $_ -match 'FATAL EXCEPTION|ANR in com\.choplab\.sampler|com\.choplab\.sampler.*has died'
    })
    $bluetoothFatalMatches = @($logs -split "`r?`n" | Where-Object {
        $_ -match 'com\.android\.bluetooth.*(FATAL EXCEPTION|SIGABRT|has died)'
    })
    if ($fatalMatches.Count -ne 0) { throw "ChopLab fatal/ANR evidence found:`n$($fatalMatches -join "`n")" }
    if ($bluetoothFatalMatches.Count -ne 0) { throw "Emulator Bluetooth instability found:`n$($bluetoothFatalMatches -join "`n")" }
} catch {
    $failure = $_
} finally {
    foreach ($restore in @(
        @('shell', 'settings', 'put', 'system', 'font_scale', $originalFontScale),
        @('shell', 'settings', 'put', 'system', 'accelerometer_rotation', $originalAutoRotation),
        @('shell', 'settings', 'put', 'system', 'user_rotation', $originalUserRotation),
        @('shell', 'am', 'force-stop', 'com.choplab.sampler')
    )) {
        $result = Invoke-Adb -Arguments $restore -AllowFailure
        if ($result.ExitCode -ne 0) { $restoreErrors.Add("$($restore -join ' '): $($result.Text)") }
    }
    if ($restoreErrors.Count -eq 0) {
        $restoredFontScale = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'system', 'font_scale') -AllowFailure).Text.Trim()
        $restoredAutoRotation = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'system', 'accelerometer_rotation') -AllowFailure).Text.Trim()
        $restoredUserRotation = (Invoke-Adb -Arguments @('shell', 'settings', 'get', 'system', 'user_rotation') -AllowFailure).Text.Trim()
        $appPid = (Invoke-Adb -Arguments @('shell', 'pidof', 'com.choplab.sampler') -AllowFailure).Text.Trim()
        if ($restoredFontScale -ne $originalFontScale) {
            $restoreErrors.Add("font_scale readback '$restoredFontScale' != '$originalFontScale'")
        }
        if ($restoredAutoRotation -ne $originalAutoRotation) {
            $restoreErrors.Add("accelerometer_rotation readback '$restoredAutoRotation' != '$originalAutoRotation'")
        }
        if ($restoredUserRotation -ne $originalUserRotation) {
            $restoreErrors.Add("user_rotation readback '$restoredUserRotation' != '$originalUserRotation'")
        }
        if (-not [string]::IsNullOrWhiteSpace($appPid)) {
            $restoreErrors.Add("ChopLab process remains after force-stop: $appPid")
        }
        $restoredState = [ordered]@{
            fontScale = $restoredFontScale
            accelerometerRotation = $restoredAutoRotation
            userRotation = $restoredUserRotation
            appPid = $appPid
        }
    }
}

if ($restoreErrors.Count -ne 0) {
    $restoreMessage = "Device-state restoration failed:`n$($restoreErrors -join "`n")"
    if ($null -ne $failure) { throw "$($failure.Exception.Message)`n$restoreMessage" }
    throw $restoreMessage
}
if ($null -ne $failure) { throw $failure }

$receipt = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    source = [ordered]@{
        head = $sourceHead
        tree = $sourceTree
        trackedClean = $true
        buildProvenancePath = $provenanceResolved
    }
    serial = $Serial
    apiLevel = [int]$api
    fingerprint = $fingerprint
    locale = $locale
    localeConfiguredDuringRun = $localeConfigured
    appApk = [ordered]@{
        path = $appResolved
        sha256 = $appSha256
    }
    androidTestApk = [ordered]@{
        path = $testResolved
        sha256 = $testSha256
    }
    runs = @($runs)
    fatalOrAnrCount = 0
    bluetoothFatalCount = 0
    restored = [ordered]@{
        fontScale = $originalFontScale
        accelerometerRotation = $originalAutoRotation
        userRotation = $originalUserRotation
        appForceStopped = $true
        readback = $restoredState
    }
    gates = [ordered]@{
        composeInstrumentation = 'PASS'
        frameworkNode = 'PASS'
        physicalDevice = 'NOT_CLAIMED'
        humanGo = 'NOT_CLAIMED'
    }
}
$outputParent = Split-Path -Parent $OutputPath
if ($outputParent) { [System.IO.Directory]::CreateDirectory($outputParent) | Out-Null }
$json = $receipt | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText($OutputPath, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
Write-Output $json
