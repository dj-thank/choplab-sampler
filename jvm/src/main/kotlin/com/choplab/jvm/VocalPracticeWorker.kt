package com.choplab.jvm

import com.choplab.core.ProgramCompiler
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import com.choplab.engine.*
import kotlinx.coroutines.*
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path

/** Renders from zero to preserve delay/reverb/compressor history, keeping only the requested interval. */
class VocalPracticeWorker(private val compiler: ProgramCompiler, private val assets: FileAssetStore,
                          private val scratch: Path, private val memory: PcmMemoryBudget = PcmMemoryBudget.shared,
                          private val blockFrames: Int = 4096, private val diskReserveBytes: Long = 64L shl 20,
                          private val usableDiskBytes: (Path) -> Long = { it.toFile().usableSpace }) : VocalPracticeRenderer {
    init { require(blockFrames in 1..4096 && diskReserveBytes >= 0) }
    override suspend fun render(project: Project, revision: Long, request: VocalPracticeRequest,
                                progress: (PracticeProgress) -> Unit): PracticeResult<Asset> = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        var file: Path? = null
        var reserved: PcmMemoryBudget.Reservation? = null
        var scratchReserved: Closeable? = null
        var program: EngineProgram? = null
        var engine: EngineCore? = null
        try {
            context.ensureActive()
            val bytes = 44L + request.outputFrames * 8L
            val directory = Files.createDirectories(scratch)
            // Adoption may copy across file systems, so both temporary copies must fit.
            if (usableDiskBytes(directory) - diskReserveBytes < bytes * 2 ||
                usableDiskBytes(assets.directory) - diskReserveBytes < bytes || assets.maxStoredBytes - assets.storedBytes() < bytes)
                return@withContext PracticeResult.Failure(PracticeProblem.LIMIT)
            scratchReserved = try { PcmScratchBudget.reserve(bytes * 2) }
                catch (_: IllegalArgumentException) { return@withContext PracticeResult.Failure(PracticeProblem.LIMIT) }
            // The input/output arrays coexist during WSOLA. All native/page reads reserve through the compiler.
            val outputBytes = if (request.inputFrames == request.outputFrames) 0 else request.outputFrames * 8L
            reserved = memory.reserve(request.inputFrames * 8L + outputBytes + blockFrames * 8L + MixerDsp.PCM_BYTES + 81_920)
            program = compiler.compile(project, request.target, revision)
            val duration = requireNotNull(program.arrangement).durationFrames
            if (duration == 0L) return@withContext PracticeResult.Failure(PracticeProblem.NO_AUDIO)
            if (request.endFrame > duration) return@withContext PracticeResult.Failure(PracticeProblem.INVALID_RANGE)
            val input = FloatArray(request.inputFrames * 2)
            val buffer = FloatArray(blockFrames * 2)
            engine = EngineCore(program, EngineConfig(controlCapacity = 4, eventCapacity = 8, outputMode = EngineOutputMode.EXPORT))
            check(engine.controls.offer(EngineCommand.StartSequence(0, 1)) == OfferResult.ACCEPTED)
            check(engine.controls.offer(EngineCommand.Stop(duration, 2)) == OfferResult.ACCEPTED)
            val latency = engine.latencyFrames
            val selectedStart = request.startFrame + latency
            val total = request.endFrame + latency
            var rendered = 0L
            progress(PracticeProgress(0, total))
            while (rendered < total) {
                context.ensureActive()
                val plan = engine.prepareOfflineBlock(minOf(blockFrames.toLong(), total - rendered).toInt())
                try { compiler.prepared(plan.windows) { engine.render(buffer, frameCount = plan.frames) } }
                catch (cancel: CancellationException) { throw cancel }
                catch (failure: PcmMemoryLimit) { throw failure }
                catch (_: Exception) { return@withContext PracticeResult.Failure(PracticeProblem.PCM_UNAVAILABLE) }
                if (engine.pcmUnderrunFrames != 0L) return@withContext PracticeResult.Failure(PracticeProblem.PCM_UNAVAILABLE)
                val from = maxOf(rendered, selectedStart)
                val until = minOf(rendered + plan.frames, total)
                if (until > from) buffer.copyInto(input, ((from - selectedStart) * 2).toInt(),
                    ((from - rendered) * 2).toInt(), ((until - rendered) * 2).toInt())
                rendered += plan.frames
                progress(PracticeProgress(rendered, total))
            }
            context.ensureActive()
            val samples = if (request.inputFrames == request.outputFrames) input else
                OfflineWsola.stretch(input, request.outputFrames, context::ensureActive)
            context.ensureActive()
            file = Files.createTempFile(directory, "practice-", ".wav")
            Files.newOutputStream(file).use { output ->
                val writer = WavCodec.FloatWriter(output, request.outputFrames.toLong())
                var at = 0
                while (at < request.outputFrames) {
                    context.ensureActive()
                    val count = minOf(4096, request.outputFrames - at)
                    writer.write(samples, at, count); at += count
                }
                writer.finish()
            }
            context.ensureActive()
            check(Files.size(file) == bytes)
            val hash = Files.newInputStream(file).use { digest(it, bytes) }
            val asset = Asset(hash, "wav", bytes, 48_000, 2, request.outputFrames.toLong(), "Practice", AssetRole.RENDERED)
            context.ensureActive()
            if (!assets.containsVerified(asset) && assets.storedBytes() + bytes > assets.maxStoredBytes)
                return@withContext PracticeResult.Failure(PracticeProblem.LIMIT)
            assets.adopt(asset, file) { !context.isActive }
            PracticeResult.Success(asset)
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: PcmMemoryLimit) { PracticeResult.Failure(PracticeProblem.LIMIT) }
          catch (_: Exception) { PracticeResult.Failure(PracticeProblem.FAILED) }
        finally {
            try { engine?.close(); program?.releasePreparation() }
            finally { try { reserved?.close(); file?.let(Files::deleteIfExists) } finally { scratchReserved?.close() } }
        }
    }
}
