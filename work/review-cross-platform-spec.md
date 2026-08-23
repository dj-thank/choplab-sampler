# Spec pass — local parent

Fixed point: `9a4e9edc2686914c28c91b2d614dfb95281935c2`

Execution: local parent spec pass, independently re-read from `plans/active/cross-platform-production-continuity-20260823.md` and `docs/cross-platform-polish-direction-matrix-20260823.md`.

## Finding

- The plan states: “Failure must not claim recording or loop success.” The successful recorder branch restarts the Beat without catching playback-device failure. A missing Windows endpoint can therefore leave the recorder running and no Beat loop, contradicting the selected direction's recoverable-failure requirement. Add a deterministic failing audio-port test and transition back to idle with an actionable status.

The source-capture CHOP route, startup recovery/autosave separation, normal vocal loop restart, scope exclusions, and `LOCAL_PASS` evidence match the spec. Pixel `DEVICE_PASS` remains correctly unclaimed while disconnected.

Resolution: fixed. The new test proves no exception escapes, recorder state returns idle, loop state is cleared, the temporary take is not promoted, and the status names the Windows output-device recovery action. Re-review found no unresolved Spec issue.
