$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root
if (-not (Get-Command codex -ErrorAction SilentlyContinue)) { throw "Codex CLI is not installed or not on PATH." }
& codex login status *> $null
if ($LASTEXITCODE -ne 0) { throw "Codex is not signed in. Run codex login." }
Get-Content "$Root\prompts\00_MASTER_PROMPT.md" -Raw | & codex exec -C $Root --ask-for-approval never --sandbox workspace-write -
exit $LASTEXITCODE
