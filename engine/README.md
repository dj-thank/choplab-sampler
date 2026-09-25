# Engine 2A

`com.choplab.engine` is an independent Kotlin Multiplatform module with a JVM desktop target,
Java 17 bytecode and a JDK 21 toolchain. Android can consume that JVM artifact; common code
contains no Android, Java Sound, filesystem, coroutine, UI or legacy-engine dependency.

## Ownership and clock

Construct `PcmAsset`, `Pad`, `Pattern`, `EngineProgram` and `EngineCore` on a control/worker
thread. PCM is copied, validated finite and made private. A Program copies its PAD table;
multiple PADs share the same PCM object. Frames are 48 kHz stereo, interleaved float32,
with `[startFrame, endFrame)` ranges. Render writes into a caller buffer.

One control producer calls `controls.offer(command)`, one renderer calls `render`, and one
consumer drains `events.poll(reusedEvent)` / reads `readout.copyInto(reusedSnapshot)`.
The readout is published at the end of a render call. A failed bounded snapshot read must
be retried; an event loss count means control must reconcile the readout. Neither interface
invokes user callbacks in render. Direct `EngineCore` fields belong to the render owner.

Commands carry absolute frame deadlines and globally increasing order IDs. Song and original
audition have separate SPSC lanes, each with the configured capacity and ordered deadlines;
render merges their heads by frame then ID. A late command reports requested/applied frames.
Stop fences only old song commands, so future original audition does not block new song work.
Original pause/clear fences only its own lane. Full/earlier-deadline safety actions use separate
mailboxes. StopAll/Panic fence both lanes; StopAll uses the normal song stop and fades original
audition, while Panic silences both immediately. Pending safety
actions coalesce to the earliest deadline and strongest action. New commands require new IDs.
Song Stop retains its existing 96-frame PAD fade and 168-frame maximum tail.

960 PPQ timing uses integer phase (`3,000,000` units/tick); each audio frame adds milli-BPM.
Tempo changes keep that fractional phase. Swing is piecewise linear within each eighth,
with 500–750 permille first-sixteenth duration. All commands precede notes at the same frame.
Program swap preserves running voices and the sequence phase; future notes use the new Program.

## Fixed budgets and overload

- 32 primary voices and 16 fade slots. Scratch and sequence/click PADs use these same slots.
  The oldest eligible primary moves into a free fade slot on steal. If all fade slots are
  occupied, the new trigger is rejected and reported; an existing tail is never truncated
  to admit the new note. Suspended transport/scratch voices are not steal candidates.
- The **union** of Program, live/retained PAD voices, loaded original source and all pending
  program/source commands is limited to 128 MiB, configurable downward. Shared PCM is charged
  once. Producer-side reservations precede queue publication; rejected loads return `PCM_LIMIT`
  without changing playback. Render transfers/retains leases and clears retired asset references
  after their final owner disappears. Bounded atomic slot retirement never waits or spins in
  render. A racing producer retries through `FULL`. At most 4096 asset reservation slots exist.
  Replacing a source near the limit may require clearing it and waiting for render retirement
  before admitting its replacement. `residentBytes` reports the current graph; admission also
  includes pending reservations. Caller-owned inputs/worker copies are outside engine ownership.
  Immutable FIR tables add about 2.6 MiB shared by engines. There is no unbounded render cache.
- 128 PADs, at most 4096 notes/pattern, patterns up to 8 bars, ring capacities 2–8192.
  A full command lane returns `FULL`; full EventRing drops newest and increments
  a visible counter. Missing resident PADs produce silence and `ASSET_MISS`; disk prefetch
  belongs to an adapter and is not implemented by this resident-PCM engine.

## Continuous arrangement transport

`EngineProgram(..., arrangement = Arrangement(clips, durationFrames))` selects continuous
arrangement playback. Null retains the pattern sequencer; an empty arrangement stays silent
and never falls back to a pattern. Its explicit duration may include leading/trailing silence,
must cover every clip, and is bounded to 30 minutes. Arrangement/clip construction is worker
work: it validates 1024 stable unique clip IDs, 16 track indices, source ranges, finite gain/pan,
and at most 32 simultaneous clips, with exclusive clip ends processed before tied starts.
Excess overlap rejects the entire arrangement. PAD and clip PCM share one 128 MiB union limit.

Each `ArrangementClip` directly references `[sourceStartFrame, sourceEndFrame)` at its absolute
48 kHz `timelineStartFrame`; there is no conversion to PAD slots or steps. Core compiles track
gain/pan/mute/solo and take placement before publication. A separate preallocated 32-clip mixer
feeds the same master as 32 PAD voices plus 16 fade slots. These are **80 bounded simultaneous
sources**, not the earlier 48-source performance case. Active clips are mixed in compiled order,
including after seek, so their summation does not depend on partition/transport history.

`EngineCore.frame` / command `effectiveFrame` remain a monotonic audio-device command clock.
`EngineSnapshot.sequenceFrame` is the next 48 kHz source frame of transport and advances only
while `sequencePlaying`. This is the graph input position; the audible position also includes
the 72-frame master and device delay. `StartSequence` restarts at zero. `Pause` freezes the
transport position and any pattern-origin voice cursors; `Resume` continues them. Manual PAD
voices remain independently playable while paused. `Seek(..., sequenceFrame)` is arrangement
only and restores all overlapping clips at their exact mid-clip source position; it keeps the
playing/paused state, accepts the declared end, and rejects positions outside the arrangement.
Seeking sets the informational tick phase using the current tempo, not an invented tempo map.

Pause and seek clear the **shared** limiter history immediately. This deliberately also removes
72 frames of pending manual PAD history while its source cursor continues; newly synthesized
PAD/arrangement samples refill lookahead. Resume does not clear it again. Arrangement restart
also clears lookahead. A Program swap rebuilds active indices at the current sequenceFrame,
clamps a shortened end and releases old arrangement references; existing lookahead samples
finish their 72-frame delay. At the declared end, transport stops with sequenceFrame held at
the end and the limiter flushes its remaining 72 frames. Stop/Panic reset sequenceFrame and
tick phase to zero, with their existing fade/immediate-silence policies. Normal queue rejection
is still observable; Pause/Resume/Seek are not the emergency Stop/Panic mailbox.

## DSP and export semantics

`OriginalSource(asset, startFrame, endFrame, loop)` is one independent monitor voice with no
PAD ID. `SetOriginalSource` first loads it paused at its range start; replacing a playing source
keeps it playing and crossfades into the replacement. Play resumes (or restarts at
the range start after its exclusive end), Pause freezes its cursor, Seek uses absolute PCM
frames, and setting null clears it. Original operations do not alter song position or PAD
selection. Song Stop/Pause/Seek and Program swaps preserve its cursor/play state. StopAll and
Panic stop both buses and reset their cursors: StopAll keeps the song stop policy and adds a
96-frame original fade, followed by up to 72 frames of lookahead. Panic clears both immediately.
Queued original cancellation is fenced separately;
Studio must still reject stale external decode-job completions before creating new commands.

Play/resume, pause, seek, clear and playing-source replacement use a 96-frame smoothstep
transition from the last rendered dry stereo value to moving new PCM or silence. Pause freezes
the cursor immediately; its release uses held sample values, so resume starts at the same
source position. Natural end holds the cursor at the exclusive end and releases the last output
over 96 frames. These tails store only two sample values, retaining no old PCM reference.
Source seek/replacement never reset the shared master or alter the music bus.

`SetSongMonitorGain` (0–1) scales the composition/PAD bus before adding the original bus.
`SetOriginalMonitorGain` (0–2) scales only original audition. Both use 96-frame ramps and reach
exact zero; gain zero keeps cursor advancement. Master lookahead can contain another 72 frames
of prior sound. Below limiter threshold the gains are independent; overloaded mixed material
shares the linked safety limiter. Original playback is transparent unity-rate sample playback
using the shared reader/smoother; it adds no pitch-control capability. Readout publishes
`originalLoaded`, `originalPlaying`, `originalSourceFrame`, both monitor gains and the song clock.
As with manual PADs, a song seek/pause's shared-limiter reset also removes pending original
history while its independent source cursor/play state continues. Original pause/seek itself
preserves the other bus's limiter history.

`EngineConfig(outputMode = EXPORT)` rejects all original-audition and monitor-gain commands
with `MONITOR_DISABLED`, keeps song gain at unity, and never renders the original bus.
`OfflineRender` selects this mode and explicitly filters monitor commands. A platform streaming
export adapter must also select EXPORT. Monitor output now has at most **81** simultaneous
sources: 32 arrangement + 32 PAD + 16 fade + one original. Allocation/performance fixtures include
all of them, per-block seeks and changes to both gains; old 48-source numbers are not reused.

ONE_SHOT/GATE stop at their exclusive end; LOOP keeps its original period. Attack/decay/release
use smoothstep ADSR envelopes with configurable sustain, clamped for very short one-shots.
Note-off holds the actual envelope level; Stop can shorten a prior long release without a jump.
Source-domain endpoint crossfade mixes
the last W frames toward the first endpoint and the first W frames from the last endpoint,
using complementary smoothstep weights that meet at the same stereo endpoint mixture.
It keeps constant level and the original period, and runs before the pitch FIR. It changes
the seam neighborhood rather than inserting a periodic silence. W is clamped to half the
source range; a two-frame loop becomes its endpoint mixture. `loopCrossfadeFrames = 0` is an
explicit transparent-loop option. Pan is stereo balance: center preserves each channel.

Runtime pitch is sample-rate conversion, so pitch changes duration. A 128-tap, 512-phase
Kaiser table follows conservative upper-speed bands up to 8x. Integer positions at unity
rate are transparent. At +12 semitones the declared input pass band is up to 9 kHz and
stop band starts at 12 kHz. Worker resampling uses 512 taps/4096 phases, beta 14 and centered
group-delay compensation. The 44.1↔48/96→48 fixtures test 20 Hz–20 kHz and stop/image rejection.

Scratch accepts an absolute start position, timed source-position segments, CUT and end.
`ScratchPosition(frame, id, targetSourceFrame, durationFrames)` reaches the clamped target
over the specified following output frames. Negative speed is supported; speeds above 8x
are rejected. It is silent when motion ends or pushes beyond a source edge. CUT ramps over
96 frames. End fades scratch and resumes the suspended transport cursor with its attack
envelope. Segment delivery, UI timestamps and device output latency belong to the adapter.

Master is a linked stereo **sample-peak** limiter with 72-frame (1.5 ms) lookahead, −1 dBFS
ceiling and 50 ms gain release. It does not claim true-peak limiting. Its signal tail is the
72-frame delay. `OfflineRender` is a bounded convenience wrapper around the same EngineCore:
it trims exactly 72 frames, keeps `frameCount + tailFrames`, and does not infer musical tail
length or stop commands. Streaming/WAV headers/files belong to the platform export adapter.
`PcmQuantizer` emits 16/24-bit little-endian PCM, applies seeded TPDF only at that last boundary,
and retains RNG state across blocks. The engine never quantizes its internal float headroom.

## Verification boundary

The common tests contain partition identity, stereo, PCM/memory rejection, late/full/safety,
voice/tail overload, scratch/CUT, fractional timing, impulse/multitone/sweep quality and seeded
quantization fixtures. The desktop harness measures thread allocation over 10,000 warmed
192-frame blocks and reports local p99/max time; it also exercises concurrent ring/readout
publication. Build command after root registration: `:engine:desktopTest`.

These are LOCAL tests. Pixel/ART allocation, physical-route CPU/10-minute underruns, native
rate conversion, new/old matched-loudness listening, worst-case pitched/scratch CPU, and human
acceptance require their separately owned follow-up gates. No WAV/device/provider result is
implied by the engine module alone.
