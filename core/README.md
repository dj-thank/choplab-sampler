# Core contracts

`Studio` is the only document/edit/selection/job/engine-command owner. Hosts send
`Action` and collect its read-only flows. `Project` is an immutable schema 10
document; selection, jobs, transport and host locations are not serialized.

`EditSession` preserves the previous ProductionSession transaction semantics:
planning consumes no history, each required effect is acknowledged in order,
and commit is exactly once. Failed/cancelled effects leave document, revision and
Undo/Redo unchanged. History is capped at 100 and gesture keys coalesce edits.

`EnginePort.apply` returns a matching APPLIED or LATE application acknowledgement,
not queue admission. LATE reports successful application at a later engine frame.
Studio bounds acknowledgement waiting to two seconds. An attached driver owns
render and acknowledgement handling; a host without an output device can use
the JVM `DetachedEnginePort`. `audiblePending` distinguishes a document applied
to a detached engine from a document routed to a driver. Neither is human sound
acceptance. The port must cooperate with coroutine cancellation; a driver must
resynchronize after timeout rather than apply an abandoned command later.

Worker results are fenced by job ID, document generation and revision. Import
returns only after verified original bytes are available in AssetStore.
Cancellation/project replacement cannot publish a stale result as current work.
Heavy Program preparation runs outside the actor. `dispatch(Edit)` still waits
for its commit result, while another caller can send Stop, Cancel or New. Those
actions cancel the pending plan without consuming Undo. `WorkState.preparationId`
identifies this phase; an imported/opened document remains busy until preparation
and commit finish. Render acknowledgement is still a bounded control operation.

Program compilation shares each decoded asset among its PADs. It rejects a
combined 48 kHz stereo residency exceeding 128 MiB before loading. This stage
does not implement long-source prefetch. The JVM loader also bounds the source
decode and refuses unsupported codecs; a host must supply a verified decoder
before enabling additional codecs. Transient worker copies are separate from
the engine's resident PCM accounting. Human labels permit punctuation; filesystem
safety belongs to validated IDs, content-addressed entry names and host locations.

Playback is explicit: `PlaybackTarget.Pattern(id)` retains the original default;
`PlaybackTarget.Arrangement(takeIds)` plays document clips plus only selected takes.
An empty arrangement stays silent and never falls back to a stored pattern. The
arrangement program keeps assigned PADs for manual audition while setting pattern
sequencing to null. PCM admission covers the union of PAD and audible clip assets.

Clip `timelineStartFrame` is an optional exact 48 kHz anchor, taking priority over
the legacy musical `startTick`; frame anchors do not move when BPM changes. Clip
source boundaries both use `ProgramCompiler.sourceFrameTo48k` (ceil), so mixed-rate
adjacent cuts neither duplicate nor omit a normalized sample. Positive take
compensation shifts earlier; negative starts trim the normalized view exactly.
Takes fully before zero are omitted. Original source ranges remain immutable.

Track mute wins; any solo excludes non-solo tracks. Silent clips retain the
composition end. Track and clip gain multiply (audible results above 8 reject),
and pan adds with [-1,1] clamping. Admission rejects more than 1024 audible clips,
16 audible tracks, 32 overlaps or 30 minutes before PCM loading. Pause/Resume/Seek
project the engine's `sequenceFrame`; this is not a measured DAC playback cursor.
