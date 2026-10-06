package com.choplab.sampler.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.choplab.core.*
import com.choplab.core.model.*
import com.choplab.core.separation.*
import com.choplab.jvm.WavCodec
import com.choplab.jvm.separation.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.FloatBuffer
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.math.abs

/** Opt-in actual ORT/HT-Demucs on an owned software emulator. No bundled model or model download. */
@RunWith(AndroidJUnit4::class)
class NextFourStemProductionTest {
    @Test fun pinnedOfflineModelThroughFourAssetsUndoExportAndArchive() = runBlocking {
        OfflineRuntimeFixture.run("choplabFourStemFixture", "android-four-stem-production") { fixture ->
            val admission = fixture.memory()
            admission.refusal()?.let { fixture.unavailable(it.name) }
            val supplied = fixture.externalAcceptance().resolve(FourStemSpec.MODEL_FILE)
            if (!Files.isRegularFile(supplied, LinkOption.NOFOLLOW_LINKS)) fixture.unavailable(SeparationProblem.MODEL_MISSING.name)
            // Input remains read-only. Both sides of the private seed must match the same published pin.
            FourStemModelStore.verify(supplied)
            verifyLocalModelAcquisition(fixture.directory.resolve("local-model-acquisition"), supplied)
            val models = fixture.directory.resolve("model")
            Files.createDirectory(models)
            val seeded = models.resolve(FourStemSpec.MODEL_FILE)
            Files.copy(supplied, seeded)
            FourStemModelStore.verify(seeded)
            val store = FourStemModelStore(models) { error("Model network access is forbidden in this fixture") }
            check(store.ensure(allowDownload = false) == seeded)
            val actualFactory = OnnxFourStemFactory(store)
            val backend = fixture.backend
            val source = fixture.original(); val sourceBytes = backend.assets.read(source)
            val before = Project(assets = frozenListOf(source), source = Source(source.hash, FrameRange(0, source.frames)),
                lyrics = frozenListOf(LyricLine("line", "Synthetic fixture", 0, 3840)))
            check(backend.studio.dispatch(Action.New(before)).accepted)
            val revision = backend.studio.document.value.revision
            val sourceStoredBytes = backend.assets.storedBytes()
            var cancelAtOutput = true
            var nativeOutputs = 0
            var opened = 0
            var closed = 0
            lateinit var worker: FourStemPort
            val observedFactory = FourStemSessionFactory { memory, available, allowDownload, check ->
                check(!allowDownload)
                val actual = try { actualFactory.open(memory, available, false, check) }
                catch (failure: Throwable) {
                    runCatching {
                        fixture.receipt("DIAGNOSTIC", mapOf("stage" to "four-stem-session-open",
                            "failureType" to failure.javaClass.simpleName,
                            "ortCode" to ((failure as? ai.onnxruntime.OrtException)?.code?.name ?: "NOT_ORT")))
                    }
                    throw failure
                }
                opened++
                object : FourStemInference {
                    override fun infer(channelMajor: FloatArray, check: () -> Unit, consume: (FloatBuffer) -> Unit) {
                        actual.infer(channelMajor, check) { actualOutput ->
                            nativeOutputs++
                            // Observe real native output, then cancel before the production pipeline publishes
                            // any of the four files. Never replace the model, output buffer or admission probe.
                            if (cancelAtOutput) { worker.cancel(); check() }
                            consume(actualOutput)
                        }
                    }
                    override fun cancel() = actual.cancel()
                    override fun close() { try { actual.close() } finally { closed++ } }
                }
            }
            worker = backend.createFourStemWorker(observedFactory, fixture::memory)
            try {
                for ((location, path) in listOf("fixture-temporary" to fixture.directory, "asset-store" to backend.assets.directory)) {
                    val fields = linkedMapOf<String, Any>("stage" to "four-stem-filesystem-space", "location" to location)
                    try { fields["nioUsableSpaceBytes"] = Files.getFileStore(path).usableSpace }
                    catch (failure: Exception) { fields["nioFailureType"] = failure.javaClass.simpleName }
                    try { fields["fileUsableSpaceBytes"] = path.toFile().usableSpace }
                    catch (failure: Exception) { fields["fileFailureType"] = failure.javaClass.simpleName }
                    fixture.receipt("DIAGNOSTIC", fields)
                }
                val invalid = worker.prepare(source.copy(hash = "f".repeat(64)), allowModelDownload = false)
                check(invalid is SeparationResult.Failure && invalid.failure.problem == SeparationProblem.INVALID_INPUT)
                check(opened == 0 && backend.assets.storedBytes() == sourceStoredBytes)
                val cancelled = withTimeout(180_000) { worker.prepare(source, allowModelDownload = false) }
                check(cancelled is SeparationResult.Failure && cancelled.failure.problem == SeparationProblem.CANCELLED) {
                    "Expected CANCELLED; actual=${(cancelled as? SeparationResult.Failure)?.failure?.problem?.name ?: "SUCCESS"}; " +
                        "opened=$opened; closed=$closed; nativeOutputs=$nativeOutputs"
                }
                check(nativeOutputs == 1 && opened == 1 && closed == 1)
                check(backend.assets.storedBytes() == sourceStoredBytes && backend.studio.document.value.project == before)
                check(Files.list(fixture.directory.resolve("profile/four-stem-temporary")).use { it.count() } == 0L)
                cancelAtOutput = false
                val progress = mutableListOf<SeparationProgress>()
                val started = System.nanoTime()
                val result = withTimeout(180_000) { worker.prepare(source, allowModelDownload = false, progress = progress::add) }
                check(result is SeparationResult.Success) { "Actual four-stem preparation refused: ${(result as? SeparationResult.Failure)?.failure?.problem}" }
                val prepared = result.value
                val preparedMillis = (System.nanoTime() - started) / 1_000_000
                val receipt = requireNotNull(worker.memoryReceipt())
                check(nativeOutputs == 2 && opened == 2 && closed == 2)
                check(prepared.modelSha256 == FourStemSpec.MODEL_SHA256 && prepared.stems.map { it.part } == StemPart.entries)
                check(prepared.stems.map { it.asset.hash }.distinct().size == 4)
                check(progress.first().completedFrames == 0L && progress.last().completedFrames == 22_050L)
                for (stem in prepared.stems) {
                    val audio = backend.assets.read(stem.asset).inputStream().use(WavCodec::read)
                    check(audio.info.floatingPoint && audio.info.bits == 32 && audio.info.sampleRate == 44_100)
                    check(audio.info.channels == 2 && audio.info.frames == 22_050L)
                    check(audio.samples.all { it.isFinite() } && audio.samples.any { abs(it) > .000001f })
                }
                check(backend.studio.document.value.project == before && backend.studio.document.value.revision == revision)
                val stale = prepared.placement(before.copy(source = null), 0, StemMix.INSTRUMENTAL, "stems")
                check(stale is SeparationResult.Failure && stale.failure.problem == SeparationProblem.STALE_DOCUMENT)
                val placement = prepared.placement(before, 0, StemMix.INSTRUMENTAL, "stems")
                check(placement is SeparationResult.Success)
                check(backend.studio.dispatch(Action.Edit(placement.value, expectedRevision = revision)).accepted)
                val after = backend.studio.document.value.project
                check(backend.studio.document.value.revision == revision + 1 && after.source == before.source && after.lyrics == before.lyrics)
                check(after.assets.size == 5 && after.clips.size == 4 && after.clips.all { it.range == FrameRange(0, 22_050) })
                check(after.tracks.map { it.mute } == listOf(false, false, false, true))
                check(backend.studio.dispatch(Action.Undo).accepted && backend.studio.document.value.project == before)
                check(backend.studio.dispatch(Action.Redo).accepted && backend.studio.document.value.project == after)
                check(!backend.studio.dispatch(Action.Edit(placement.value, expectedRevision = revision)).accepted)
                fixture.exportAndReopen(after, source, sourceBytes, 24_000)
                worker.close()
                val refused = worker.prepare(source, allowModelDownload = false)
                check(refused is SeparationResult.Failure && refused.failure.problem == SeparationProblem.CLOSED)
                check(Files.list(fixture.directory.resolve("profile/four-stem-temporary")).use { it.count() } == 0L)
                mapOf("realModel" to true, "modelSha256" to FourStemSpec.MODEL_SHA256, "modelBytes" to FourStemSpec.MODEL_BYTES,
                    "localModelInitialAcquire" to true, "localModelCancelCleanup" to true,
                    "localModelOldCachePreserved" to true, "localModelVerifiedRepair" to true, "modelHttpDownloadVerified" to false,
                    "sourceFrames" to 24_000, "stemFrames" to 22_050, "stems" to 4, "preparedMillis" to preparedMillis,
                    "memorySource" to receipt.source.name, "totalBytes" to receipt.totalBytes, "availableBytes" to receipt.availableBytes,
                    "lowMemory" to receipt.lowMemory, "measuredAtEpochMillis" to receipt.measuredAtEpochMillis,
                    "originalBytesExact" to true, "explicitApply" to true, "oneUndoRedo" to true, "nativeRanges" to true,
                    "cancelAtNativeOutput" to true, "cancelPublishedAssets" to 0, "invalidSourceRejected" to true,
                    "staleRejected" to true, "closedRejected" to true, "export24" to true, "archiveReopen" to true, "autosaveReopen" to true)
            } finally { worker.close() }
        }
    }

    /** Exercise the actual store with fixed local bytes; no HTTP success is inferred. */
    private fun verifyLocalModelAcquisition(directory: Path, supplied: Path) {
        var opened = 0
        var closed = 0
        val store = FourStemModelStore(directory) { url ->
            check(url == FourStemSpec.MODEL_URL)
            opened++
            FourStemModelStore.ModelDownload(FourStemSpec.MODEL_BYTES, Files.newInputStream(supplied)) { closed++ }
        }
        try {
            val missing = runCatching { store.ensure(allowDownload = false) }.exceptionOrNull()
            // The internal exception exposes these public JVM contracts, including after R8.
            check(missing is IllegalStateException && missing.message == SeparationProblem.MODEL_MISSING.name)
            check(opened == 0 && closed == 0)
            val acquired = store.ensure(allowDownload = true)
            check(acquired == store.model && opened == 1 && closed == 1)
            FourStemModelStore.verify(acquired)
            check(store.ensure(allowDownload = false) == acquired && opened == 1 && closed == 1)

            // Only this fixture's newly created cache is corrupted. Keep an unrelated file too.
            val previous = byteArrayOf(9, 8, 7)
            Files.write(acquired, previous)
            val unrelated = directory.resolve("previous.part")
            Files.write(unrelated, byteArrayOf(1))
            val invalid = runCatching { store.ensure(allowDownload = false) }.exceptionOrNull()
            check(invalid is IllegalStateException && invalid.message == SeparationProblem.MODEL_INVALID.name)
            check(opened == 1 && closed == 1)
            var copied = 0L
            var cancel = false
            val cancellation = CancellationException("Controlled local model acquisition cancellation")
            val cancelled = runCatching {
                store.ensure(allowDownload = true, progress = { count, total ->
                    check(total == FourStemSpec.MODEL_BYTES && count > 0)
                    copied = count
                    cancel = true
                }, check = { if (cancel) throw cancellation })
            }.exceptionOrNull()
            check(cancelled === cancellation)
            check(copied in 1L until FourStemSpec.MODEL_BYTES && opened == 2 && closed == 2)
            check(Files.readAllBytes(acquired).contentEquals(previous))
            check(Files.readAllBytes(unrelated).contentEquals(byteArrayOf(1)))
            check(Files.list(directory).use { it.count() } == 2L)

            val repaired = store.ensure(allowDownload = true)
            check(repaired == acquired && opened == 3 && closed == 3)
            FourStemModelStore.verify(repaired)
            check(Files.readAllBytes(unrelated).contentEquals(byteArrayOf(1)))
            check(Files.list(directory).use { it.count() } == 2L)
            FourStemModelStore.verify(supplied)
        } finally { check(directory.toFile().deleteRecursively()) { "Owned model acquisition cleanup failed" } }
    }
}
