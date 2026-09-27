package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.model.*
import com.choplab.engine.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext
import java.io.*
import java.nio.file.*
import java.util.UUID

/**
 * Host supplies opaque-handle resolution; paths never cross the document boundary. [displayName] lets a host name a
 * rescued document after the file the user picked when the bytes arrive through a scratch file.
 */
class FileProjectPort(
    private val assets: FileAssetStore,
    private val resolve: (Location) -> Path,
    private val codec: ArchiveCodec = ArchiveCodec(),
    private val displayName: (Location) -> String? = { null },
) : ProjectPort {
    override suspend fun save(project: Project, revision: Long, location: Location) = withContext(Dispatchers.IO) {
        require(revision >= 0)
        val context = coroutineContext
        atomicOutput(resolve(location), { !context[kotlinx.coroutines.Job]!!.isActive }) { output -> codec.write(project, assets, output) }
    }
    override suspend fun open(location: Location): Project = openDocument(location).project
    /** A project file of the earlier app (schemas 1–7) opens as a new document holding only its audio; the file stays as it is. */
    override suspend fun openDocument(location: Location): OpenedProject = withContext(Dispatchers.IO) {
        val context = coroutineContext
        val cancelled = { !context[kotlinx.coroutines.Job]!!.isActive }
        val path = resolve(location)
        if (!Files.newInputStream(path).use(LegacySalvage::recognizes))
            return@withContext OpenedProject(Files.newInputStream(path).use { codec.read(it, assets, cancelled) })
        val rescued = Files.newInputStream(path).use { LegacySalvage().read(it, assets, cancelled) }
        context.ensureActive()
        val title = (displayName(location) ?: path.fileName?.toString())?.let(::hostName)
            ?.let { if (it.endsWith(".choplab", ignoreCase = true)) it.dropLast(".choplab".length).trim() else it }
            ?.takeIf { it.isNotEmpty() } ?: "Rescued audio"
        val project = rescued.newProject("rescued-" + (rescued.audio.firstOrNull()?.asset?.hash?.take(16) ?: "empty"), title)
        OpenedProject(project, rescued.notice(project))
    }
}

/** Bounded local WAV import. Other codecs remain the host decoder's explicit responsibility.
 * [displayName] lets a host keep the name the user saw when the bytes arrive through a scratch file.
 */
class WavImportPort(
    private val assets: FileAssetStore,
    private val resolve: (Location) -> Path,
    private val displayName: (Location) -> String? = { null },
) : ImportPort {
    override suspend fun import(location: Location): Asset = withContext(Dispatchers.IO) {
        val path = resolve(location)
        require(Files.isRegularFile(path) && Files.size(path) <= ProjectLimits.MAX_ASSET_BYTES)
        val context = coroutineContext
        val cancelled = { !context[kotlinx.coroutines.Job]!!.isActive }
        val pending = Files.createTempFile("choplab-wav-import-", ".pending")
        try {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            Files.newInputStream(path).use { input -> Files.newOutputStream(pending).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count > 0)
                    bytes += count
                    require(bytes <= ProjectLimits.MAX_ASSET_BYTES)
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            } }
            val info = Files.newInputStream(pending).use { WavCodec.inspect(CancellableInput(it, context)) }
            val name = (displayName(location) ?: path.fileName.toString()).replace(':', '_').take(256)
            val asset = Asset(digest.digest().hex(), "wav", bytes, info.sampleRate, info.channels, info.frames, name)
            assets.adopt(asset, pending, cancelled)
            context.ensureActive()
            return@withContext asset
        } finally { Files.deleteIfExists(pending) }
    }
}

class WavPcmPort(private val assets: FileAssetStore, val cache: PcmAssetCache = PcmAssetCache(),
                 private val decoder: OriginalAudioDecoder? = null,
                 val memory: PcmMemoryBudget = PcmMemoryBudget.shared) : PrefetchPcmPort, Closeable {
    private val prefetch = PcmPrefetchWorker()
    private val legacy = java.util.concurrent.ConcurrentLinkedQueue<PcmLease>()
    private val closed = java.util.concurrent.atomic.AtomicBoolean()
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        cache.close()
        while (true) (legacy.poll() ?: break).close()
        kotlinx.coroutines.runBlocking { memory.releaseOwner(this@WavPcmPort, prefetch::close) }
    }
    override fun residentBytes(asset: Asset): Long = PcmResidency.bytes(asset)
    override suspend fun prefetch(pcm: PcmAsset, firstFrame: Int, endFrame: Int) = prefetch.prefetch(pcm, firstFrame, endFrame)
    override suspend fun <T> prepared(windows: List<PcmWindow>, render: () -> T): T = prefetch.prepared(windows, render)
    /** Caller holds a PCM lease and reserves the returned <=4096-frame window for its own lifetime. */
    suspend fun readWindow(pcm: PcmAsset, firstFrame: Int, endFrame: Int): FloatArray = prefetch.read(pcm, firstFrame, endFrame)
    override suspend fun acquire(asset: Asset): PcmLease {
        check(!closed.get()) { "PCM port is closed" }
        val lease = cache.acquire(asset, discard = { memory.discard(it) }, decode = { decode(asset) })
        try { memory.touch(lease.pcm); check(!closed.get()) { "PCM port is closed" }; return lease }
        catch (failure: Throwable) { lease.close(); throw failure }
    }
    /** Compatibility callers keep their returned object until this port closes; production uses acquire. */
    override suspend fun load(asset: Asset): PcmAsset {
        val lease = acquire(asset)
        legacy.add(lease)
        if (closed.get()) { if (legacy.remove(lease)) lease.close(); error("PCM port is closed") }
        return lease.pcm
    }
    private suspend fun decode(asset: Asset): PcmLease {
        var unpublished: PcmAsset? = null
        var published: PcmLease? = null
        return try { withContext(Dispatchers.IO) {
            memory.reserve(decodePeakBytes(asset)).use { reservation ->
                try {
                val context = coroutineContext
                val cancelled = { context[kotlinx.coroutines.Job]?.isActive == false }
                val data = if (!PcmResidency.resident(asset)) {
                    val path = assets.verifiedPath(asset, cancelled)
                    val source = if (asset.extension == "wav") WavFrameSource.open(path, cancelled)
                        else requireNotNull(decoder) { "A host decoder is required for this codec" }.openPcm(path, asset.hash, cancelled)
                    prefetch.open(asset, source).also { unpublished = it }
                } else {
                    val audio = if (asset.extension == "wav") assets.openVerified(asset).use { WavCodec.read(CancellableInput(it, context)) }
                        else requireNotNull(decoder) { "A host decoder is required for this codec" }
                            .decode(assets.verifiedPath(asset, cancelled), asset.hash, cancelled)
                    require(audio.info.frames == asset.frames && audio.info.channels == asset.channels && audio.info.sampleRate == asset.sampleRate)
                    coroutineContext.ensureActive()
                    val stereo = if (audio.info.channels == 2) audio.samples else FloatArray(audio.samples.size * 2) { audio.samples[it / 2] }
                    val normalized = if (audio.info.sampleRate == 48_000) stereo else OfflineResampler.resample(stereo, audio.info.sampleRate)
                    coroutineContext.ensureActive()
                    PcmAsset.fromInterleaved(normalized)
                }
                coroutineContext.ensureActive()
                reservation.publish(data, this@WavPcmPort) { prefetch.discard(data) }.also {
                    published = it; unpublished = null
                }
                } catch (failure: Throwable) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { unpublished?.let { prefetch.discard(it) }; unpublished = null }
                    throw failure
                }
            }
        } } catch (failure: Throwable) {
            published?.let { it.close(); memory.discard(it.pcm) }
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { unpublished?.let { prefetch.discard(it) } }
            throw failure
        }
    }
    companion object {
        /** Existing resident decoder can hold native, mono expansion, resampler input/result and defensive copy. */
        fun decodePeakBytes(asset: Asset): Long {
            if (!PcmResidency.resident(asset)) return PcmResidency.bytes(asset)
            val native = asset.frames * asset.channels * 4
            val stereo = asset.frames * 8
            val output = PcmResidency.frames(asset) * 8
            return native + (if (asset.channels == 1) stereo else 0) + output + 256 * 1024 +
                (if (asset.sampleRate != 48_000) stereo + output + 512L * 4097 * 4 else 0)
        }
    }
}

/** Offline export compiles the same Program and renders through EngineCore. */
class WavExportPort(private val compiler: ProgramCompiler, private val resolve: (Location) -> Path) : ExportPort {
    override suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt {
        require(project.clips.isEmpty() && project.tracks.isEmpty() && project.takes.isEmpty()) {
            "Choose an explicit PlaybackTarget to export a document containing timeline material"
        }
        return export(project, PlaybackTarget.Pattern(patternId), request)
    }
    override suspend fun export(project: Project, target: PlaybackTarget, request: ExportRequest): ExportReceipt = withContext(Dispatchers.Default) {
        val program = compiler.compile(project, target, 0)
        try {
        coroutineContext.ensureActive()
        withContext(Dispatchers.IO) {
            val context = coroutineContext
            atomicOutput(resolve(request.location), { !context[kotlinx.coroutines.Job]!!.isActive }) { output ->
                StreamingWavRenderer.render(program, output, request.frames, request.tailFrames, request.bits, request.seed,
                    cancelled = { !context[kotlinx.coroutines.Job]!!.isActive },
                    prepared = { windows, render -> kotlinx.coroutines.runBlocking(context) { compiler.prepared(windows, render) } })
            }
        }
        ExportReceipt(request.frames.toLong() + request.tailFrames, 48_000, 2, request.bits)
        } finally { program.releasePreparation() }
    }
}

private class CancellableInput(input: InputStream, private val context: CoroutineContext) : FilterInputStream(input) {
    override fun read(): Int { context.ensureActive(); return `in`.read() }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int { context.ensureActive(); return `in`.read(buffer, offset, length) }
}

/**
 * Control-only fallback when no output driver owns this engine. It advances a bounded silent render
 * to acknowledge edits while paused/disconnected. It never claims audible playback or device success.
 * A host must replace this port when attaching its output driver; it must not share this EngineCore.
 */
class DetachedEnginePort(private val compiler: ProgramCompiler) : EnginePort, Closeable {
    private val engine = EngineCore()
    private val snapshot = EngineSnapshot()
    private val event = MutableEngineEvent()
    private val scratch = FloatArray(2)
    override fun close() { engine.close() }
    override suspend fun prepare(project: Project, patternId: String, revision: Long): EngineProgram = compiler.compile(project, patternId, revision)
    override suspend fun prepare(project: Project, target: PlaybackTarget, revision: Long): EngineProgram = compiler.compile(project, target, revision)
    override suspend fun apply(command: EngineCommand): Boolean {
        engine.readout.copyInto(snapshot)
        if (command.effectiveFrame > snapshot.frame || engine.controls.offer(command) != OfferResult.ACCEPTED) return false
        engine.render(scratch)
        var applied = false
        while (engine.events.poll(event)) if (event.orderId == command.orderId &&
            (event.type == EngineEventType.APPLIED || event.type == EngineEventType.LATE)) applied = true
        return applied
    }
    override fun snapshot(): TransportState {
        engine.readout.copyInto(snapshot)
        return TransportState(snapshot.frame, snapshot.sequencePlaying, snapshot.programRevision, snapshot.activeVoices, snapshot.eventOverflows, outputAttached = false,
            sequenceFrame = snapshot.sequenceFrame, sequencePaused = snapshot.sequencePaused,
            metronomeEnabled = snapshot.metronomeEnabled, countInBeatsRemaining = snapshot.countInBeatsRemaining,
            recordingStartFrame = snapshot.recordingStartFrame, recordingStartSequenceFrame = snapshot.recordingStartSequenceFrame,
            recordingStartedFrame = snapshot.recordingStartedFrame)
    }
}

internal fun atomicOutput(targetPath: Path, cancelled: () -> Boolean = { false }, write: (OutputStream) -> Unit) {
    val target = targetPath.toAbsolutePath().normalize()
    val parent = requireNotNull(target.parent)
    require(Files.isDirectory(parent) && !Files.isSymbolicLink(target))
    val pending = parent.resolve(".choplab-${UUID.randomUUID()}.pending")
    try {
        FileOutputStream(pending.toFile()).use { output -> write(output); output.fd.sync() }
        require(!cancelled()) { "Output cancelled before publication" }
        Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } finally { Files.deleteIfExists(pending) }
}
