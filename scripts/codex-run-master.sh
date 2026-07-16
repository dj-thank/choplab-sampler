#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
if ! command -v codex >/dev/null 2>&1; then
  echo "ERROR: Codex CLI is not installed or not on PATH." >&2
  exit 1
fi
if ! codex login status >/dev/null 2>&1; then
  echo "ERROR: Codex is not signed in. Run: codex login" >&2
  exit 1
fi
codex exec -C "$ROOT" --ask-for-approval never --sandbox workspace-write - < "$ROOT/prompts/00_MASTER_PROMPT.md"
