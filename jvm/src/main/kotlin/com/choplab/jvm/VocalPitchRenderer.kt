package com.choplab.jvm

import com.choplab.core.model.*
import com.choplab.core.vocal.*
import com.choplab.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.nio.file.Files
import java.nio.file.Path

sealed interface VocalPitchRenderResult {
    val report: PitchCorrectionReport
    data class Rendered(val asset: Asset, override val report: PitchCorrectionReport) : VocalPitchRenderResult
    data class Unchanged(override val report: PitchCorrectionReport) : VocalPitchRenderResult
}

/** Offline only. Reserves every PCM workspace before allocation; never mutates or owns the source port. */
class VocalPitchRenderer(
    private val assets: FileAssetStore,
    private val pcm: WavPcmPort,
    private val scratch: Path,
    private val diskReserveBytes: Long = 64L shl 20,
    private val usableDiskBytes: (Path) -> Long = { it.toFile().usableSpace },
) {
    private val serial = Mutex()

    /** Exact normalized original range for A audition, including native-rate clips and subranges. No document edit. */
    suspend fun original(project: Project, draft: VocalPitchDraft, name: String): Asset =
        requireNotNull((produce(project, draft.copy(settings = draft.settings.copy(amount = 0f)), name, { _, _, _ -> }, true)
            as VocalPitchRenderResult.Rendered).asset)

    suspend fun render(project: Project, draft: VocalPitchDraft, name: String,
                      progress: (PitchCorrectionPhase, Int, Int) -> Unit = { _, _, _ -> }): VocalPitchRenderResult =
        produce(project, draft, name, progress, false)

    private suspend fun produce(project: Project, draft: VocalPitchDraft, name: String,
                      progress: (PitchCorrectionPhase, Int, Int) -> Unit, publishOriginal: Boolean): VocalPitchRenderResult = serial.withLock {
        requireLabel(name)
        VocalPitchEdits.validate(project, draft)
        val source = project.asset(draft.sourceAssetHash)
        val first = draft.firstFrame48(source).toInt()
        val frames = draft.frames48(source).toInt()
        val bytes = 44L + frames * 8L
        try {
            withContext(Dispatchers.IO) {
                val context = currentCoroutineContext()
                val check = { context.ensureActive() }
                check()
                val directory = Files.createDirectories(scratch)
                // Atomic adoption can fall back to a cross-filesystem copy: charge BOTH output copies.
                if (usableDiskBytes(directory) - diskReserveBytes < bytes * 2 ||
                    usableDiskBytes(assets.directory) - diskReserveBytes < bytes || assets.maxStoredBytes - assets.storedBytes() < bytes)
                    throw VocalPitchException(VocalPitchProblem.LIMIT)
                val scratchReservation = try { PcmScratchBudget.reserve(bytes * 2) }
                    catch (_: IllegalArgumentException) { throw VocalPitchException(VocalPitchProblem.LIMIT) }
                scratchReservation.use {
                    pcm.acquire(source).use { lease ->
                        pcm.memory.reserve(workspaceBytes(frames)).use {
                            val pending = Files.createTempFile(directory, "pitch-", ".wav")
                            try {
                                val reader = PitchPcmReader { at, count, destination ->
                                    check()
                                    // Paged I/O may suspend. This bridge runs exclusively on the offline IO worker.
                                    val window = runBlocking(context) { pcm.readWindow(lease.pcm, first + at, first + at + count) }
                                    window.copyInto(destination)
                                    count
                                }
                                val report = FileOutputStream(pending.toFile()).use { file ->
                                    val output = BufferedOutputStream(file, OUTPUT_BUFFER_BYTES)
                                    val writer = WavCodec.FloatWriter(output, frames.toLong())
                                    val result = OfflinePitchCorrection.process(reader, frames, draft.settings,
                                        { samples, count -> check(); writer.write(samples, frameCount = count) }, check, progress)
                                    writer.finish(); output.flush(); file.fd.sync()
                                    result
                                }
                                check()
                                if (report.correctedFrames == 0 && !publishOriginal) VocalPitchRenderResult.Unchanged(report)
                                else {
                                    val hash = Files.newInputStream(pending).use { input ->
                                        digest(object : FilterInputStream(input) {
                                            override fun read(): Int { check(); return super.read() }
                                            override fun read(b: ByteArray, off: Int, len: Int): Int { check(); return super.read(b, off, len) }
                                        }, bytes)
                                    }
                                    val asset = project.assets.firstOrNull { it.hash == hash } ?: Asset(hash, "wav", bytes, 48_000, 2, frames.toLong(), name,
                                        AssetRole.RENDERED, derivedFrom = source.hash)
                                    assets.adopt(asset, pending) { !context.isActive }
                                    check()
                                    VocalPitchRenderResult.Rendered(asset, report)
                                }
                            } finally { Files.deleteIfExists(pending) }
                        }
                    }
                }
            }
        } catch (limit: PcmMemoryLimit) {
            throw VocalPitchException(VocalPitchProblem.LIMIT)
        }
    }

    companion object {
        private const val OUTPUT_BUFFER_BYTES = 65_536
        // FloatWriter 16KiB + returned readWindow 32KiB + stream 64KiB + digest/verification 16KiB.
        fun workspaceBytes(frames: Int): Long = OfflinePitchCorrection.workspaceBytes(frames) + 131_072
    }
}
