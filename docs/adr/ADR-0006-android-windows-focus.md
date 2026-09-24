# ADR-0006: Android and Windows focus

- Status: Accepted for the rebuild
- Date: 2026-09-25

## Decision

Concentrate the rebuild on Android 10+ and Windows. Retire the iOS target, CI, scripts and simulator distribution in stage1A; preserve their history at `archive/pre-rebuild-v0.18.0`. KMP remains useful for Android/JVM and does not require maintaining Native targets.

The baseline is merged PR101, commit `2866683a5118681cf518ef47e29cac8baf882edb`. The separate later local editor and schema8/9 work are not part of that baseline. DDJ-200/MIDI remains deferred, with a future adapter seam and its branch preserved.

## Consequences and rollback

Release contracts and new product work name only Android and Windows. Shared behavior still requires platform-specific verification. Historical iOS evidence is not evidence for the new implementation or for physical Apple devices.

Removal is confined to reviewed tracked paths in isolated worktrees. User data, recordings, uncommitted work, credentials and installed applications are not cleanup targets. Source can be recovered from the archive; resuming iOS would require a new product decision and current validation.

[ROADMAP](../ROADMAP.md) owns removal progress and acceptance. This ADR does not certify that every target/workflow reference has already been removed.
