$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root
if (-not (Get-Command codex -ErrorAction SilentlyContinue)) { throw "Codex CLI is not installed or not on PATH." }
& codex login status *> $null
if ($LASTEXITCODE -ne 0) { & codex login }
& codex -C $Root
exit $LASTEXITCODE
