param(
    [Parameter(Mandatory = $true)][string]$AppImage,
    [ValidateSet('ChopLab.exe', 'ChopLab Preview.exe')][string]$ExecutableName = 'ChopLab.exe'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$appRoot = (Resolve-Path -LiteralPath $AppImage).Path
$exe = Join-Path $appRoot $ExecutableName
if (-not (Test-Path -LiteralPath $exe -PathType Leaf)) { throw 'Packaged executable missing' }
$appPrefix = [IO.Path]::GetFullPath($appRoot).TrimEnd('\') + '\'
# MainWindowHandle excludes hidden AWT frames. Enumerate handles, filter by our
# exact process identities first, and inspect only their caption/class/response.
if (-not ('ChopLabSmokeWindows' -as [type])) {
    Add-Type -TypeDefinition @"
using System;
using System.Collections.Generic;
using System.Runtime.InteropServices;
using System.Text;
public static class ChopLabSmokeWindows {
    private delegate bool EnumProc(IntPtr hwnd, IntPtr arg);
    [DllImport("user32.dll")] private static extern bool EnumWindows(EnumProc fn, IntPtr arg);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetClassName(IntPtr hwnd, StringBuilder text, int max);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowTextLength(IntPtr hwnd);
    [DllImport("user32.dll", SetLastError = true)] private static extern IntPtr SendMessageTimeout(IntPtr hwnd, uint msg, UIntPtr wp, IntPtr lp, uint flags, uint timeout, out UIntPtr result);
    [DllImport("user32.dll")] private static extern bool PostMessage(IntPtr hwnd, uint msg, UIntPtr wp, IntPtr lp);
    public static IntPtr[] Frames(int ownerPid) {
        var result = new List<IntPtr>();
        EnumWindows((hwnd, arg) => {
            uint pid;
            GetWindowThreadProcessId(hwnd, out pid);
            if (pid != (uint)ownerPid) return true;
            var name = new StringBuilder(128);
            GetClassName(hwnd, name, name.Capacity);
            if (name.ToString() == "SunAwtFrame" && GetWindowTextLength(hwnd) > 0) result.Add(hwnd);
            return true;
        }, IntPtr.Zero);
        return result.ToArray();
    }
    public static bool Responsive(IntPtr hwnd) {
        UIntPtr result;
        return SendMessageTimeout(hwnd, 0, UIntPtr.Zero, IntPtr.Zero, 2, 1000, out result) != IntPtr.Zero;
    }
    public static bool Close(IntPtr hwnd, int ownerPid) {
        uint pid;
        GetWindowThreadProcessId(hwnd, out pid);
        return pid == (uint)ownerPid && PostMessage(hwnd, 0x0010, UIntPtr.Zero, IntPtr.Zero);
    }
}
"@
}
$profileRoot = Join-Path ([IO.Path]::GetTempPath()) ('choplab-smoke-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $profileRoot | Out-Null
$owned = [Collections.Generic.List[object]]::new()
$cleanupErrors = [Collections.Generic.List[string]]::new()
$primaryError = $null
$passed = $false
$stdoutTask = $null
$stderrTask = $null

function Test-SmokeProcessAlive($Owner) {
    try {
        $Owner.Process.Refresh()
        return -not $Owner.Process.HasExited -and $Owner.Process.StartTime.ToUniversalTime() -eq $Owner.StartedAt
    } catch { return $false }
}

function Update-SmokeDescendants {
    # This is parent-PID discovery, never an executable-name search. A child must
    # have started while that exact parent lived and execute inside this image.
    for ($index = 0; $index -lt $owned.Count; $index++) {
        $owner = $owned[$index]
        try {
            $owner.Process.Refresh()
            $parentEnd = if ($owner.Process.HasExited) { $owner.Process.ExitTime.ToUniversalTime() } else { [DateTime]::UtcNow }
            $children = @(Get-CimInstance Win32_Process -Filter "ParentProcessId = $($owner.Process.Id)" -ErrorAction Stop)
            foreach ($child in $children) {
                if (-not $child.ExecutablePath -or -not $child.CreationDate) { continue }
                $childStarted = $child.CreationDate.ToUniversalTime()
                if ($childStarted -lt $owner.StartedAt -or $childStarted -gt $parentEnd) { continue }
                $childPath = [IO.Path]::GetFullPath([string]$child.ExecutablePath)
                if (-not $childPath.StartsWith($appPrefix, [StringComparison]::OrdinalIgnoreCase)) { continue }
                $candidate = [Diagnostics.Process]::GetProcessById([int]$child.ProcessId)
                # Keep a kernel handle before exit; a lookup-only Process can lose
                # the exit code when the child disappears from the process table.
                $null = $candidate.Handle
                $actualStart = $candidate.StartTime.ToUniversalTime()
                # CIM has microsecond precision; retain the process handle's exact
                # timestamp for every later operation after cross-checking the row.
                if ([Math]::Abs(($actualStart - $childStarted).TotalMilliseconds) -ge 1) { $candidate.Dispose(); continue }
                $existing = @($owned | Where-Object { $_.Process.Id -eq $candidate.Id -and $_.StartedAt -eq $actualStart })
                if ($existing.Count) { $candidate.Dispose(); continue }
                if ($owned.Count -ge 32) { $candidate.Dispose(); throw 'Smoke descendant budget exceeded' }
                $owned.Add([pscustomobject]@{ Process = $candidate; StartedAt = $actualStart })
            }
        } catch {
            # Losing one process to a normal exit is expected; a live owner's
            # discovery failure is not safe to ignore during lifecycle validation.
            if (Test-SmokeProcessAlive $owner) { throw }
        }
    }
}

try {
    $start = [Diagnostics.ProcessStartInfo]::new($exe)
    $start.UseShellExecute = $false
    $start.WindowStyle = [Diagnostics.ProcessWindowStyle]::Hidden
    $start.WorkingDirectory = $appRoot
    $start.RedirectStandardOutput = $true
    $start.RedirectStandardError = $true
    $start.Environment['LOCALAPPDATA'] = Join-Path $profileRoot 'Local'
    $start.Environment['APPDATA'] = Join-Path $profileRoot 'Roaming'
    $start.Environment['USERPROFILE'] = $profileRoot
    $start.Environment['JAVA_TOOL_OPTIONS'] = "-Duser.home=`"$profileRoot`""
    $process = [Diagnostics.Process]::Start($start)
    $owned.Add([pscustomobject]@{ Process = $process; StartedAt = $process.StartTime.ToUniversalTime() })
    $stdoutTask = $process.StandardOutput.ReadToEndAsync()
    $stderrTask = $process.StandardError.ReadToEndAsync()
    $deadline = [DateTime]::UtcNow.AddSeconds(60)
    $windowOwner = $null
    $frame = [IntPtr]::Zero
    do {
        Update-SmokeDescendants
        foreach ($owner in $owned) {
            if (-not (Test-SmokeProcessAlive $owner)) { continue }
            foreach ($candidateFrame in [ChopLabSmokeWindows]::Frames($owner.Process.Id)) {
                if ([ChopLabSmokeWindows]::Responsive($candidateFrame)) {
                    $windowOwner = $owner
                    $frame = $candidateFrame
                    break
                }
            }
            if ($null -ne $windowOwner) { break }
        }
        if ($null -ne $windowOwner) { break }
        if (-not @($owned | Where-Object { Test-SmokeProcessAlive $_ }).Count) { throw 'Owned packaged processes exited before an AWT frame responded' }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($null -eq $windowOwner) { throw 'No responsive owned AWT frame within 60 seconds (hidden frames were included)' }
    if (-not (Test-SmokeProcessAlive $windowOwner) -or -not [ChopLabSmokeWindows]::Close($frame, $windowOwner.Process.Id)) { throw 'Could not request a graceful close of the owned frame' }
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        Update-SmokeDescendants
        $alive = @($owned | Where-Object { Test-SmokeProcessAlive $_ })
        if (-not $alive.Count) { break }
        Start-Sleep -Milliseconds 200
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($alive.Count) { throw 'Owned packaged processes did not all exit after the frame closed' }
    foreach ($owner in $owned) {
        $exitCode = $owner.Process.ExitCode
        if ($null -eq $exitCode) { throw "Exit code unavailable for owned packaged process $($owner.Process.Id)" }
        if ($exitCode -ne 0) { throw "Owned packaged process $($owner.Process.Id) exited with $exitCode" }
    }
    $passed = $true
} catch {
    $primaryError = $_
} finally {
    try { Update-SmokeDescendants } catch { $cleanupErrors.Add("Descendant discovery: $($_.Exception.Message)") }
    foreach ($owner in @($owned | Sort-Object StartedAt -Descending)) {
        try {
            if (Test-SmokeProcessAlive $owner) {
                $owner.Process.Kill()
                if (-not $owner.Process.WaitForExit(10000)) { throw 'Owned process did not exit during cleanup' }
            }
        } catch { $cleanupErrors.Add("PID $($owner.Process.Id): $($_.Exception.Message)") }
    }
    # Preserve the original failure and bounded process output before any removal.
    $diagnostics = [Collections.Generic.List[string]]::new()
    foreach ($owner in $owned) {
        try {
            $owner.Process.Refresh()
            $state = if ($owner.Process.HasExited) { "exit=$($owner.Process.ExitCode)" } else { 'still running' }
            $diagnostics.Add("pid=$($owner.Process.Id) start=$($owner.StartedAt.ToString('o')) $state")
        } catch { $diagnostics.Add('Process diagnostics unavailable') }
        $owner.Process.Dispose()
    }
    foreach ($task in @($stdoutTask, $stderrTask)) {
        if ($null -ne $task -and $task.IsCompletedSuccessfully) {
            $text = $task.Result
            if ($text.Length) { $diagnostics.Add($text.Substring(0, [Math]::Min(8192, $text.Length))) }
        }
    }
    if ($null -ne $primaryError) {
        Write-Warning ("Original smoke failure: " + $primaryError.Exception.Message)
        Write-Warning ($diagnostics -join "`n")
    }
    $resolved = [IO.Path]::GetFullPath($profileRoot)
    $tempPrefix = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\') + '\'
    if (-not $resolved.StartsWith($tempPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        $cleanupErrors.Add('Smoke profile escaped temp root; removal refused')
    } else {
        $lastRemovalError = $null
        for ($attempt = 0; $attempt -lt 6; $attempt++) {
            try {
                if (Test-Path -LiteralPath $resolved) { Remove-Item -LiteralPath $resolved -Recurse -Force -ErrorAction Stop }
                $lastRemovalError = $null
                break
            } catch {
                $lastRemovalError = $_
                Start-Sleep -Milliseconds 500
            }
        }
        if ($null -ne $lastRemovalError) { $cleanupErrors.Add("Profile retained at $resolved; $($lastRemovalError.Exception.Message)") }
    }
}
if ($cleanupErrors.Count) { Write-Warning ($cleanupErrors -join "`n") }
if ($null -ne $primaryError) { throw $primaryError }
if ($cleanupErrors.Count) { throw 'Smoke lifecycle completed but cleanup was incomplete; see diagnostics above' }
if (-not $passed) { throw 'Smoke did not complete' }
Write-Host 'PASS: isolated packaged app opened a responsive AWT frame and all owned processes exited cleanly; audio/device behavior is not covered'
