# ExecPlan registry

Updated: 2026-09-11

## Current selection

Exactly one plan is selected: [UI clarity and responsive control quality](ui-quality-20260911.md).

Baseline is PR96 `3fe5906bbc29e54bb46689606ffb0cac3599e1ca`. This follow-up improves UI, preserves the four stages/128 PAD/audio/data contracts, fixes three debug-sensitive startup test assertions, and adds actual shared-UI rendering evidence. It does not merge, release, change dependencies or install an app.

See [current state](../../docs/PROJECT_STATE.md), [UI receipt](../../docs/ui/UI_QUALITY_20260911.md) and the selected plan for current evidence boundaries.

## Retained prior plans

[Runtime-quality integration](runtime-quality-20260910.md) is the immediately preceding checkpoint, not a simultaneously selected plan. Its Windows/iOS/policy CI succeeded; its Android run failed three tests. Those results do not certify the UI follow-up.

The older complete registry remains byte-for-byte in [README_20260903.md](README_20260903.md). PR89 release hardening and dependency PR90/91 remain separate unaccepted work. Historical PR69 and release intentions are not new merge instructions.
