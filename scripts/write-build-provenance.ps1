[CmdletBinding()]
param(
    [string]$AppApk = 'app\build\outputs\apk\debug\app-debug.apk',
    [string]$TestApk = 'app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk',
    [Parameter(Mandatory = $true)]
    [string]$OutputPath
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location -LiteralPath $root

$trackedStatus = @(& git status --porcelain=v1 --untracked-files=no)
if ($LASTEXITCODE -ne 0) { throw 'git status failed' }
if ($trackedStatus.Count -ne 0) {
    throw "Tracked worktree is not clean:`n$($trackedStatus -join "`n")"
}

$head = (& git rev-parse HEAD).Trim()
$tree = (& git rev-parse 'HEAD^{tree}').Trim()
$commitEpoch = [long]((& git show -s --format=%ct HEAD).Trim())
$commitTimeUtc = [DateTimeOffset]::FromUnixTimeSeconds($commitEpoch).UtcDateTime

$sdkRoot = if ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT } else { $env:ANDROID_HOME }
if ([string]::IsNullOrWhiteSpace($sdkRoot) -and (Test-Path -LiteralPath 'local.properties' -PathType Leaf)) {
    $sdkLine = Get-Content -LiteralPath 'local.properties' -Encoding UTF8 |
        Where-Object { $_ -like 'sdk.dir=*' } |
        Select-Object -First 1
    if ($sdkLine) {
        $sdkRoot = $sdkLine.Substring(8).Replace('\:', ':').Replace('\\', '\')
    }
}
if ([string]::IsNullOrWhiteSpace($sdkRoot) -or -not (Test-Path -LiteralPath $sdkRoot -PathType Container)) {
    throw 'Android SDK is not available through ANDROID_SDK_ROOT, ANDROID_HOME, or local.properties'
}
$sdkRoot = (Resolve-Path -LiteralPath $sdkRoot).Path
$buildTools = Get-ChildItem -LiteralPath (Join-Path $sdkRoot 'build-tools') -Directory |
    Sort-Object Name -Descending |
    Select-Object -First 1
if ($null -eq $buildTools) { throw 'Android build-tools are unavailable' }
$aapt2 = Join-Path $buildTools.FullName 'aapt2.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
if (-not (Test-Path -LiteralPath $aapt2 -PathType Leaf)) { throw "aapt2 is unavailable: $aapt2" }
if (-not (Test-Path -LiteralPath $apksigner -PathType Leaf)) { throw "apksigner is unavailable: $apksigner" }

function Get-ApkReceipt([string]$path, [bool]$includePackage) {
    $resolved = (Resolve-Path -LiteralPath $path).Path
    $item = Get-Item -LiteralPath $resolved
    if ($item.LastWriteTimeUtc -lt $commitTimeUtc) {
        throw "APK predates HEAD and is stale: $resolved"
    }
    $signerOutput = @(& $apksigner verify --print-certs $resolved)
    if ($LASTEXITCODE -ne 0) { throw "APK signature verification failed: $resolved" }
    $signerLine = $signerOutput | Where-Object { $_ -match '^Signer #1 certificate SHA-256 digest:' } | Select-Object -First 1
    if ($null -eq $signerLine) { throw "Signer SHA-256 was not reported: $resolved" }
    $signerSha256 = ($signerLine -split ':', 2)[1].Trim().ToUpperInvariant()

    $package = $null
    if ($includePackage) {
        $badging = @(& $aapt2 dump badging $resolved)
        if ($LASTEXITCODE -ne 0) { throw "APK badging failed: $resolved" }
        $packageLine = $badging | Where-Object { $_ -match '^package:' } | Select-Object -First 1
        if ($null -eq $packageLine) { throw "Package metadata was not reported: $resolved" }
        $match = [regex]::Match($packageLine, "name='(?<name>[^']+)' versionCode='(?<code>[^']+)' versionName='(?<version>[^']+)'" )
        if (-not $match.Success) { throw "Package metadata could not be parsed: $packageLine" }
        $package = [ordered]@{
            name = $match.Groups['name'].Value
            versionCode = $match.Groups['code'].Value
            versionName = $match.Groups['version'].Value
        }
    }

    return [ordered]@{
        path = $resolved
        sizeBytes = $item.Length
        lastWriteTimeUtc = $item.LastWriteTimeUtc.ToString('o')
        sha256 = (Get-FileHash -LiteralPath $resolved -Algorithm SHA256).Hash.ToUpperInvariant()
        signerSha256 = $signerSha256
        package = $package
    }
}

$appReceipt = Get-ApkReceipt -path $AppApk -includePackage $true
$testReceipt = Get-ApkReceipt -path $TestApk -includePackage $false
if ($appReceipt.signerSha256 -ne $testReceipt.signerSha256) {
    throw 'App and androidTest APK signers do not match'
}

$outputParent = Split-Path -Parent $OutputPath
if ($outputParent) { New-Item -ItemType Directory -Path $outputParent -Force | Out-Null }
$receipt = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    source = [ordered]@{
        head = $head
        tree = $tree
        trackedClean = $true
    }
    toolchain = [ordered]@{
        javaHome = $env:JAVA_HOME
        androidSdkRoot = $sdkRoot
        buildTools = $buildTools.Name
    }
    appApk = $appReceipt
    androidTestApk = $testReceipt
}
$json = $receipt | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText($OutputPath, $json + [Environment]::NewLine, [System.Text.UTF8Encoding]::new($false))
Write-Output $json
