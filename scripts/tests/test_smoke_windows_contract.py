"""Execute lifecycle helper behavior without launching or stopping any app."""
import os
from pathlib import Path
import shutil
import subprocess
import unittest


@unittest.skipUnless(shutil.which('pwsh'), 'PowerShell 7 is required for Windows lifecycle helpers')
class SmokeProcessIdentityTest(unittest.TestCase):
    def test_pid_reuse_exit_and_refresh_failure_are_not_live_ownership(self):
        script = Path(__file__).resolve().parents[1] / 'smoke-windows-app.ps1'
        code = r'''
$ErrorActionPreference = 'Stop'
$ast = [Management.Automation.Language.Parser]::ParseFile($env:CHOPLAB_SMOKE_TEST_SCRIPT, [ref]$null, [ref]$null)
$function = $ast.Find({ param($n) $n -is [Management.Automation.Language.FunctionDefinitionAst] -and $n.Name -eq 'Test-SmokeProcessAlive' }, $true)
. ([ScriptBlock]::Create($function.Extent.Text))
$time = [DateTime]::UtcNow
$process = [pscustomobject]@{ HasExited = $false; StartTime = $time }
$process | Add-Member -MemberType ScriptMethod -Name Refresh -Value { }
$owner = [pscustomobject]@{ Process = $process; StartedAt = $time.ToUniversalTime() }
if (-not (Test-SmokeProcessAlive $owner)) { throw 'Live owned fixture rejected' }
$process.StartTime = $time.AddSeconds(1)
if (Test-SmokeProcessAlive $owner) { throw 'Reused PID accepted' }
$process.StartTime = $time
$process.HasExited = $true
if (Test-SmokeProcessAlive $owner) { throw 'Exited fixture accepted' }
$process.HasExited = $false
$process | Add-Member -Force -MemberType ScriptMethod -Name Refresh -Value { throw 'gone' }
if (Test-SmokeProcessAlive $owner) { throw 'Failed identity query accepted' }
'''
        # A cold PowerShell start on a busy Linux runner has taken more than 15 seconds; the limit only stops a hang.
        result = subprocess.run(
            [shutil.which('pwsh'), '-NoProfile', '-NonInteractive', '-Command', code],
            env=dict(os.environ, CHOPLAB_SMOKE_TEST_SCRIPT=str(script),
                     POWERSHELL_TELEMETRY_OPTOUT='1', POWERSHELL_UPDATECHECK='Off'),
            capture_output=True, text=True, encoding='utf-8', timeout=120,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
