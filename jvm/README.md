# JVM persistence and audio adapters

`ProjectJson` implements strict schema 10 with deterministic field order,
unknown/duplicate-field rejection and input bounds. `ArchiveCodec` requires
`project.json` first, then lexically ordered `assets/<sha256>.<extension>` entries.
Every included asset is size/hash checked; required entries cannot be omitted.
Optional PCM caches are explicitly derived and cannot be a durable music source.

`FileAssetStore` verifies WAV format, frames, channel identity and finite float
samples before atomic publication. Stage 2A enables verified WAV only. The model
reserves metadata for other containers, but their extension alone is never
accepted as decoder verification. Original WAV bytes are preserved. Existing
corrupt asset files are not overwritten automatically.

`AutosaveStore` stores documents in three atomic slots after required assets have
been flushed, verified and published. Revision, generation and document hash
share one envelope. Recovery picks the newest generation whose document and
required assets validate, then falls back on corruption. Atomic rename support
is required; an unsupported filesystem causes a failed save, not a silent
non-atomic fallback. File sync plus atomic replacement does not claim a tested
device power-loss durability guarantee. Asset GC is deliberately absent, so Undo,
Redo, previous generations and abandoned work do not lose their bytes.

`WavCodec` reads/writes PCM16, PCM24 and IEEE float32 RIFF WAVE with bounded
allocation. Float headroom and stereo identity survive; seeded TPDF is applied
only on integer output through the engine quantizer. `WavExportPort` renders the
same EngineProgram, stops the sequence at the requested content boundary and
keeps an explicit tail. Original inputs are not modified.

Production export uses `StreamingWavRenderer`: one EngineCore, one float block and
one persistent quantizer/byte block. It trims the engine-reported lookahead and
writes exactly content plus requested tail frames. Buffer sizes depend on block
size, not song length. Cancellation is checked each render block and before atomic
publication. The array-returning OfflineRender helper is used only by comparison
tests. Seeded integer output is byte-identical across tested block sizes.

`WavPcmPort` shares a `PcmAssetCache` across compiles. Cache identity includes the
content hash and format metadata, excluding human labels. Concurrent requests
share one load, different loads use one decode slot, failed/cancelled loads are
not retained, and an LRU caps retained PCM at 128 MiB by default. Dispose the port
or its shared cache when the host closes. No cache lock is taken by render.

The cache and EngineProgram hold references to the same immutable PCM objects;
their counters must not be added as if every reference were another sample copy.
An evicted asset can remain alive in an engine/queued program until that owner
releases it. Each compiled program still admits at most 128 MiB before loading.
Cache retention, source decode, normalization/FIR scratch and active engine
programs are separate budgets; 128 MiB is not a total JVM heap or RSS claim.
One decode slot bounds simultaneous temporary decode/normalization work. This is
resident caching, not long-source prefetch.

`WavExportPort.export(project, PlaybackTarget, request)` uses the selected pattern
or arrangement graph, including track/clip mix and selected take compensation.
The legacy String-pattern overload still rejects documents containing timeline
material so older callers cannot silently omit it. Hosts must explicitly choose
the arrangement target. Empty/muted timelines export their requested silence;
declared duration includes muted and solo-inactive clip ends. Content export is
bounded to the same 30 minutes, with up to 480,000 explicit tail frames. The
standalone WAV output limit is separate from import/archive asset and PCM budgets;
raising output duration does not expand the resident cache or whole-file decoder.

`LegacySalvage` recognizes the actual `project.txt` metadata for schemas 1–7:
schema 1 raw mono PCM16, schemas 2–6 mono WAV, and schema 7 mono/stereo WAV.
Independent synthetic fixtures cover each schema. Salvage returns verified audio
and a new empty arrangement; old editing state is not claimed as migrated.
Schemas 8/9 are unsupported. The supplied archive remains unchanged.

These are local contracts and synthetic checks. Output-device continuity,
physical audio, long-source streaming and Human acceptance remain host/product
acceptance work.
