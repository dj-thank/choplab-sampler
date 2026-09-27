package com.choplab.jvm.separation

import com.choplab.core.model.*
import com.choplab.core.separation.*
import com.choplab.engine.EngineFormat
import com.choplab.engine.WindowedResampler
import com.choplab.jvm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.io.FileOutputStream
import java.nio.file.*
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Four real model heads become four immutable float assets; this worker never edits a document. */
class FourStemService(private val assets: FileAssetStore, private val pcm: WavPcmPort,
                      private val temporaryDirectory: Path, private val factory: FourStemSessionFactory,
                      private val memoryProbe: () -> SeparationMemory) : FourStemPort {
    private val serial = Mutex()
    private val closed = AtomicBoolean()
    private val generation = AtomicLong()
    private val active = AtomicReference<FourStemInference?>()
    private val memory = AtomicReference<SeparationMemoryReceipt?>()
    override fun memoryReceipt(): SeparationMemoryReceipt? = memory.get()

    private fun checkMemory(): SeparationMemory {
        memory.set(null)
        val value = memoryProbe()
        memory.set(value.receipt)
        value.refusal()?.let { throw SeparationException(it) }
        return value
    }

    override suspend fun prepare(source: Asset, allowModelDownload: Boolean,
                                 progress: (SeparationProgress) -> Unit): SeparationResult<PreparedFourStems> {
        if (closed.get()) return separationFailure(SeparationProblem.CLOSED)
        if (!serial.tryLock()) return separationFailure(SeparationProblem.BUSY)
        memory.set(null)
        val request = generation.incrementAndGet()
        try {
            return withContext(Dispatchers.IO) {
                val context = coroutineContext
                val check = {
                    context.ensureActive()
                    if (closed.get() || generation.get() != request) throw SeparationException(SeparationProblem.CANCELLED)
                }
                var directory: Path? = null
                try {
                    check()
                    checkMemory()
                    if (!assets.containsVerified(source)) throw SeparationException(SeparationProblem.INVALID_INPUT)
                    val normalizedFrames = (source.frames * EngineFormat.SAMPLE_RATE + source.sampleRate - 1) / source.sampleRate
                    val outputFrames = (normalizedFrames * FourStemSpec.RATE + EngineFormat.SAMPLE_RATE - 1) / EngineFormat.SAMPLE_RATE
                    val outputBytes = 44 + outputFrames * 8
                    if (outputFrames !in 1..ProjectLimits.MAX_FRAMES || outputBytes > ProjectLimits.MAX_ASSET_BYTES ||
                        outputBytes * 4 > ProjectLimits.MAX_TOTAL_BYTES) throw SeparationException(SeparationProblem.TOO_LONG)
                    if (assets.storedBytes() + outputBytes * 4 > assets.maxStoredBytes)
                        throw SeparationException(SeparationProblem.NO_SPACE)
                    Files.createDirectories(temporaryDirectory)
                    require(!Files.isSymbolicLink(temporaryDirectory))
                    // Space for all temporary outputs and a cross-filesystem publication copy, plus bounded filesystem overhead.
                    if (Files.getFileStore(temporaryDirectory).usableSpace < outputBytes * 4 + 16L * 1024 * 1024 ||
                        Files.getFileStore(assets.directory).usableSpace < outputBytes * 4 + 16L * 1024 * 1024)
                        throw SeparationException(SeparationProblem.NO_SPACE)
                    val scratch = try { PcmScratchBudget.reserve(outputBytes * 4) }
                        catch (_: IllegalArgumentException) { throw SeparationException(SeparationProblem.NO_SPACE) }
                    scratch.use {
                        directory = Files.createTempDirectory(temporaryDirectory, "four-stem-")
                        val staged = pcm.memory.reserve(FourStemSpec.PIPELINE_PCM_BYTES).use {
                            pcm.acquire(source).use { lease ->
                                check()
                                val available = checkMemory()
                                val inference = factory.open(pcm.memory, available, allowModelDownload, check)
                                active.set(inference)
                                try {
                                    check()
                                    render(source, lease.pcm, requireNotNull(directory), inference, check, progress)
                                } finally { active.compareAndSet(inference, null); inference.close() }
                            }
                        }
                        check()
                        // The bounded validation/publication buffers remain charged after the large arrays have left scope.
                        pcm.memory.reserve(64 * 1024).use { assets.adoptFourStems(staged, check) }
                        check()
                        SeparationResult.Success(PreparedFourStems(source.hash, FourStemSpec.MODEL_SHA256,
                            staged.mapIndexed { index, item -> SeparatedStem(StemPart.entries[index], item.asset) }.frozen()))
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (failure: SeparationException) { separationFailure(failure.problem) }
                catch (_: PcmMemoryLimit) { separationFailure(SeparationProblem.PCM_LIMIT) }
                catch (_: Exception) { separationFailure(if (closed.get() || generation.get() != request) SeparationProblem.CANCELLED else SeparationProblem.FAILED) }
                finally {
                    directory?.let { path ->
                        Files.list(path).use { entries -> entries.forEach { Files.deleteIfExists(it) } }
                        Files.deleteIfExists(path)
                    }
                }
            }
        } finally { serial.unlock() }
    }

    private suspend fun render(source: Asset, decoded: com.choplab.engine.PcmAsset, directory: Path,
                               inference: FourStemInference, check: () -> Unit,
                               progress: (SeparationProgress) -> Unit): List<StagedStem> {
        val resampler = WindowedResampler(EngineFormat.SAMPLE_RATE, decoded.frameCount, FourStemSpec.RATE)
        val total = resampler.outputFrames
        val files = StemPart.entries.map { directory.resolve("${it.name.lowercase()}.wav") }
        val digests = StemPart.entries.map { MessageDigest.getInstance("SHA-256") }
        val streams = mutableListOf<FileOutputStream>()
        try {
            for (file in files) streams += FileOutputStream(file.toFile())
            val writers = streams.mapIndexed { index, stream -> WavCodec.FloatWriter(DigestOutputStream(stream, digests[index]), total.toLong(), FourStemSpec.RATE, 2) }
            progress(SeparationProgress(0, total.toLong()))
            FourStemPipeline().render(total, read = { start, count, destination ->
                var at = 0
                while (at < count) {
                    check()
                    // <=2742 input frames, including the sinc halo, always fits the existing 4096-frame window API.
                    val next = minOf(2048, count - at)
                    val first = resampler.inputStart(start + at)
                    val last = resampler.inputEnd(start + at + next)
                    val input = pcm.readWindow(decoded, first, last)
                    check()
                    val output = resampler.render(input, start + at, next)
                    for (frame in 0 until next) {
                        destination[at + frame] = output[frame * 2]
                        destination[FourStemSpec.FRAMES + at + frame] = output[frame * 2 + 1]
                    }
                    at += next
                }
            }, inference, emit = { stem, samples, count ->
                var at = 0
                while (at < count) { check(); val next = minOf(4096, count - at); writers[stem].write(samples, at, next); at += next }
            }, check, progress = { progress(SeparationProgress(it.toLong(), total.toLong())) })
            writers.forEach { it.finish() }
            streams.forEach { it.fd.sync() }
        } finally { streams.forEach { it.close() } }
        check()
        return files.mapIndexed { index, file ->
            val hash = digests[index].digest().hex()
            // Identical float audio (for example an all-silent source) shares the original immutable asset, without a self-derived cycle.
            val asset = if (hash == source.hash) source else Asset(hash, "wav", Files.size(file), FourStemSpec.RATE, 2,
                total.toLong(), "${StemPart.entries[index].name.lowercase()}.wav", AssetRole.RENDERED, derivedFrom = source.hash)
            StagedStem(asset, file)
        }
    }

    override fun cancel() { generation.incrementAndGet(); active.get()?.cancel() }
    override fun close() { if (closed.compareAndSet(false, true)) cancel() }
}
