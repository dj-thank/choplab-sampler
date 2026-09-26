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

/** Host supplies opaque-handle resolution; paths never cross the document boundary. */
class FileProjectPort(private val assets: FileAssetStore, private val resolve: (Location) -> Path, private val codec: ArchiveCodec = ArchiveCodec()) : ProjectPort {
    override suspend fun save(project: Project, revision: Long, location: Location) = withContext(Dispatchers.IO) {
        require(revision >= 0)
        val context = coroutineContext
        atomicOutput(resolve(location), { !context[kotlinx.coroutines.Job]!!.isActive }) { output -> codec.write(project, assets, output) }
    }
    override suspend fun open(location: Location): Project = withContext(Dispatchers.IO) {
        val context = coroutineContext
        Files.newInputStream(resolve(location)).use { codec.read(it, assets) { !context[kotlinx.coroutines.Job]!!.isActive } }
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
        val bytes = Files.newInputStream(path).use { readBounded(it, ProjectLimits.MAX_ASSET_BYTES) }
        coroutineContext.ensureActive()
        val info = WavCodec.inspect(ByteArrayInputStream(bytes))
        val name = (displayName(location) ?: path.fileName.toString()).replace(':', '_').take(256)
        val asset = Asset(sha256(bytes), "wav", bytes.size.toLong(), info.sampleRate, info.channels, info.frames, name)
        val context = coroutineContext
        assets.publish(asset, ByteArrayInputStream(bytes)) { !context[kotlinx.coroutines.Job]!!.isActive }
        asset
    }
}

class WavPcmPort(private val assets: FileAssetStore, val cache: PcmAssetCache = PcmAssetCache()) : PcmPort, Closeable {
    override fun close() = cache.close()
    override suspend fun load(asset: Asset): PcmAsset = cache.get(asset) { decode(asset) }
    private suspend fun decode(asset: Asset): PcmAsset = withContext(Dispatchers.IO) {
        require(asset.extension == "wav") { "A host decoder is required for this codec" }
        val frames48 = (asset.frames * 48_000 + asset.sampleRate - 1) / asset.sampleRate
        require(frames48 * 8 <= EngineFormat.MAX_RESIDENT_BYTES && asset.frames * 8 <= EngineFormat.MAX_RESIDENT_BYTES)
        val context = coroutineContext
        val audio = assets.openVerified(asset).use { WavCodec.read(CancellableInput(it, context)) }
        require(audio.info.frames == asset.frames && audio.info.channels == asset.channels && audio.info.sampleRate == asset.sampleRate)
        coroutineContext.ensureActive()
        val stereo = if (audio.info.channels == 2) audio.samples else FloatArray(audio.samples.size * 2) { audio.samples[it / 2] }
        val normalized = if (audio.info.sampleRate == 48_000) stereo else OfflineResampler.resample(stereo, audio.info.sampleRate)
        coroutineContext.ensureActive()
        PcmAsset.fromInterleaved(normalized)
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
        coroutineContext.ensureActive()
        withContext(Dispatchers.IO) {
            val context = coroutineContext
            atomicOutput(resolve(request.location), { !context[kotlinx.coroutines.Job]!!.isActive }) { output ->
                StreamingWavRenderer.render(program, output, request.frames, request.tailFrames, request.bits, request.seed,
                    cancelled = { !context[kotlinx.coroutines.Job]!!.isActive })
            }
        }
        ExportReceipt(request.frames.toLong() + request.tailFrames, 48_000, 2, request.bits)
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
class DetachedEnginePort(private val compiler: ProgramCompiler) : EnginePort {
    private val engine = EngineCore()
    private val snapshot = EngineSnapshot()
    private val event = MutableEngineEvent()
    private val scratch = FloatArray(2)
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
            sequenceFrame = snapshot.sequenceFrame, sequencePaused = snapshot.sequencePaused)
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
