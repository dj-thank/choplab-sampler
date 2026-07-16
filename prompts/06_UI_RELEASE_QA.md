Continue the active ExecPlan with complete Compose UI/lifecycle integration and release hardening.

- Make every verified feature reachable and understandable on a phone without reproducing proprietary AKAI/MPC visual trade dress.
- Preserve a fast capture/import → range → chop → auto-next assign → pad → pattern → song → export workflow.
- Add accessible labels, touch targets, progress/cancel/error states and safe destructive-action behavior.
- Verify permission denial/retry, MediaProjection/foreground-service lifecycle, audio focus, activity recreation, engine teardown, project recovery and MIDI disconnects.
- Add Compose/ViewModel tests for critical transitions where feasible.
- Remove dead migration code only after parity is proven.
- Update README, architecture, feature matrix, validation and project-state documents truthfully.

Run `./scripts/verify.sh`. If a device is present, install/smoke-test and record exact device evidence. Have `qa_reviewer` perform an owner-level release review, fix all material findings and commit a release candidate.
