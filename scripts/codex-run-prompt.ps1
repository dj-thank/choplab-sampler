param(
    [Parameter(Mandatory = $true)]
    [string]$PromptPath
)
$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $Root
if (-not (Test-Path $PromptPath)) { throw "Prompt file not found: $PromptPath" }
if (-not (Get-Command codex -ErrorAction SilentlyContinue)) { throw "Codex CLI is not installed or not on PATH." }
& codex login status *> $null
if ($LASTEXITCODE -ne 0) { throw "Codex is not signed in. Run codex login." }
Get-Content $PromptPath -Raw | & codex exec -C $Root --ask-for-approval never --sandbox workspace-write -
exit $LASTEXITCODE
