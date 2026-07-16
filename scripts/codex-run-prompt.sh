#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
PROMPT_PATH="${1:-}"
if [[ -z "$PROMPT_PATH" ]]; then
  echo "Usage: $0 prompts/01_AUDIT_AND_PLAN.md" >&2
  exit 2
fi
if [[ ! -f "$PROMPT_PATH" ]]; then
  echo "ERROR: prompt file not found: $PROMPT_PATH" >&2
  exit 1
fi
if ! command -v codex >/dev/null 2>&1; then
  echo "ERROR: Codex CLI is not installed or not on PATH." >&2
  exit 1
fi
if ! codex login status >/dev/null 2>&1; then
  echo "ERROR: Codex is not signed in. Run: codex login" >&2
  exit 1
fi
codex exec -C "$ROOT" --ask-for-approval never --sandbox workspace-write - < "$PROMPT_PATH"
