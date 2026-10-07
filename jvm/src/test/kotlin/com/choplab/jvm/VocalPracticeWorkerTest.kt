package com.choplab.jvm

import com.choplab.core.persistence.*
import com.choplab.core.*
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import com.choplab.engine.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.math.*
import kotlin.test.*

class VocalPracticeWorkerTest {
    private class Fixture(val frames: Int, effects: Boolean = true) : AutoCloseable {
        val root = Files.createTempDirectory("practice-worker-")
        val store = FileAssetStore(root.resolve("assets"))
        val pcm = WavPcmPort(store)
        val compiler = ProgramCompiler(pcm)
        val asset: Asset
        val project: Project
        val scratch = root.resolve("temporary")
        init {
            val file = root.resolve("original.wav")
            Files.newOutputStream(file).use { output ->
                val writer = WavCodec.FloatWriter(output, frames.toLong())
                val chunk = FloatArray(4096 * 2)
                var at = 0
                while (at < frames) {
                    val count = minOf(4096, frames - at)
                    for (i in 0 until count) {
                        chunk[i*2] = (.08 * sin(2 * PI * 440 * (at+i) / 48_000)).toFloat()
                        chunk[i*2+1] = chunk[i*2] * -.37f
                    }
                    writer.write(chunk, frameCount = count); at += count
                }
                writer.finish()
            }
            val bytes = Files.size(file)
            asset = Asset(Files.newInputStream(file).use { digest(it, bytes) }, "wav", bytes, 48_000, 2, frames.toLong(), "Original")
            store.adopt(asset, file)
            val track = Track("voice", "Voice", TrackKind.VOCAL, fx = if (effects)
                TrackFx(MixInsert(MixEq(3f,-2f,1f), compressor=MixCompressor(true,-30f)), .7f, .5f) else TrackFx())
            project = Project(assets=frozenListOf(asset), tracks=frozenListOf(track), clips=frozenListOf(
                Clip("clip", track.id, asset.hash, FrameRange(0,frames.toLong()), timelineStartFrame=0)),
                mix = if (effects) MixSettings(MixDelay(true,137,.3f,.4f), MixReverb(true,.1f,.2f,.2f)) else MixSettings())
        }
        fun worker(block: Int = 4096, compiled: ProgramCompiler = compiler, memory: PcmMemoryBudget = PcmMemoryBudget.shared) =
            VocalPracticeWorker(compiled, store, scratch, memory, block)
        fun samples(asset: Asset) = store.openVerified(asset).use { WavCodec.read(it).samples }
        fun noTemporary() { if (Files.exists(scratch)) assertEquals(0, Files.list(scratch).use { it.count() }) }
        override fun close() { pcm.close(); root.toFile().deleteRecursively() }
    }

    @Test fun selectedRangeEqualsFullProductionExportWithFxHistoryStereoAndOneLatencyRemoval() = runBlocking<Unit> {
        Fixture(8192).use { f ->
            val original = f.store.read(f.asset)
            val snapshot = ProjectJson.encode(f.project)
            val program = f.compiler.compile(f.project, PlaybackTarget.Arrangement(), 7)
            val oracle = try { OfflineRender.render(program, listOf(EngineCommand.StartSequence(0,1), EngineCommand.Stop(8192,2)),8192,blockFrames=17) }
                finally { program.releasePreparation() }
            val request = VocalPracticeRequest(317,7001)
            val expected = oracle.copyOfRange(317*2,7001*2)
            for (block in listOf(1,17,192,4096)) {
                val result = assertIs<PracticeResult.Success<Asset>>(f.worker(block).render(f.project,7,request)).value
                assertEquals(6684L,result.frames); assertEquals(AssetRole.RENDERED,result.role)
                assertContentEquals(expected,f.samples(result),"block=$block")
                assertEquals(0,PcmMemoryBudget.shared.statistics().leasedAssets)
            }
            val path=f.root.resolve("export.wav")
            WavExportPort(f.compiler) { path }.export(f.project,PlaybackTarget.Arrangement(),
                ExportRequest(Location("export"),8192,bits=24,tailMode=ExportTailMode.EXACT))
            val exported=Files.newInputStream(path).use { WavCodec.read(it).samples }
            expected.indices.forEach { assertEquals(expected[it],exported[317*2+it],2.5e-7f) }
            assertContentEquals(original,f.store.read(f.asset)); assertContentEquals(snapshot,ProjectJson.encode(f.project)); f.noTemporary()
            val archive = ByteArrayOutputStream().also { ArchiveCodec().write(f.project,f.store,it) }.toByteArray()
            val reopened = FileAssetStore(f.root.resolve("reopened"))
            val restored = archive.inputStream().use { ArchiveCodec().read(it,reopened) }
            assertEquals(f.project,restored)
            assertEquals(listOf(f.asset),restored.assets,"Practice previews are not document assets")
            assertContentEquals(original,reopened.read(f.asset))
        }
        assertEquals(0,PcmMemoryBudget.shared.statistics().usedBytes)
    }

    @Test fun slowPreservesTonePitchAndLinkedStereoWithAnExactRoundedDuration() = runBlocking<Unit> {
        Fixture(48_000,false).use { f ->
            for (speed in listOf(.6,.8,1.6)) {
                val request=VocalPracticeRequest(1,48_000,speed)
                val asset=assertIs<PracticeResult.Success<Asset>>(f.worker().render(f.project,0,request)).value
                val samples=f.samples(asset)
                assertEquals(request.outputFrames.toLong(),asset.frames)
                for(i in 0 until request.outputFrames) assertEquals(samples[i*2]*-.37f,samples[i*2+1],3e-8f)
                val from=4096; val until=request.outputFrames-4096
                val crossings=(from+1 until until).count { samples[(it-1)*2]<0 && samples[it*2]>=0 }
                assertEquals(440.0,crossings*48_000.0/(until-from),3.0)
            }
            assertFailsWith<IllegalArgumentException> { VocalPracticeRequest(0,48_000*30L,.6) }
            f.noTemporary()
        }
    }

    @Test fun pagedInputIsPreparedOnTheWorkerAndPcmFailureNeverPublishesSilence() = runBlocking<Unit> {
        Fixture(48_000*50,false).use { f ->
            var windows=0
            val strict=object:PrefetchPcmPort by f.pcm {
                override suspend fun <T> prepared(requests:List<PcmWindow>,render:()->T):T {
                    windows+=requests.size; return f.pcm.prepared(requests,render)
                }
            }
            val asset=assertIs<PracticeResult.Success<Asset>>(f.worker(compiled=ProgramCompiler(strict)).render(f.project,0,VocalPracticeRequest(4000,6000))).value
            assertTrue(windows>0); assertTrue(f.samples(asset).any { it!=0f })
            val before=f.store.storedBytes()
            val broken=object:PrefetchPcmPort by f.pcm {
                override suspend fun <T> prepared(windows:List<PcmWindow>,render:()->T):T = throw java.io.IOException("unavailable")
            }
            assertEquals(PracticeResult.Failure(PracticeProblem.PCM_UNAVAILABLE),f.worker(compiled=ProgramCompiler(broken)).render(f.project,0,VocalPracticeRequest(7000,8000)))
            assertEquals(before,f.store.storedBytes()); assertEquals(0,PcmMemoryBudget.shared.statistics().leasedAssets); f.noTemporary()
        }
    }

    @Test fun cancellationBeforeTheSelectedRangeAndBudgetRefusalReleaseOwnedWork() = runBlocking<Unit> {
        Fixture(48_000).use { f ->
            val before=f.store.storedBytes()
            val work=async {
                val context=currentCoroutineContext()
                f.worker(192).render(f.project,0,VocalPracticeRequest(40_000,48_000)) { if(it.renderedFrames>=4096) context.cancel() }
            }
            assertFailsWith<CancellationException> { work.await() }
            assertEquals(before,f.store.storedBytes()); assertEquals(0,PcmMemoryBudget.shared.statistics().leasedAssets); f.noTemporary()
            val small=PcmMemoryBudget(16_384)
            assertEquals(PracticeResult.Failure(PracticeProblem.LIMIT),f.worker(memory=small).render(f.project,0,VocalPracticeRequest(0,1024)))
            assertEquals(0,small.statistics().usedBytes); assertEquals(before,f.store.storedBytes())
        }
        PcmScratchBudget.reserve(ProjectLimits.MAX_TOTAL_BYTES).close()
    }

    @Test fun diskAssetAndSharedScratchLimitsRejectBeforeRenderingAndPermitRetry() = runBlocking<Unit> {
        Fixture(8192, false).use { f ->
            val before = f.store.read(f.asset)
            val request = VocalPracticeRequest(0, 4096)
            var rendered = false
            val lowDisk = VocalPracticeWorker(f.compiler, f.store, f.scratch, usableDiskBytes = { 0 })
            assertEquals(PracticeResult.Failure(PracticeProblem.LIMIT), lowDisk.render(f.project, 0, request) { rendered = true })
            val oneCopy = VocalPracticeWorker(f.compiler, f.store, f.scratch, diskReserveBytes = 0,
                usableDiskBytes = { 44L + request.outputFrames * 8L })
            assertEquals(PracticeResult.Failure(PracticeProblem.LIMIT), oneCopy.render(f.project, 0, request) { rendered = true })
            val destinationFull = VocalPracticeWorker(f.compiler, f.store, f.scratch,
                usableDiskBytes = { if (it == f.store.directory) 0 else Long.MAX_VALUE })
            assertEquals(PracticeResult.Failure(PracticeProblem.LIMIT), destinationFull.render(f.project, 0, request) { rendered = true })
            val fullStore = FileAssetStore(f.store.directory, maxStoredBytes = f.asset.byteCount)
            assertEquals(PracticeResult.Failure(PracticeProblem.LIMIT),
                VocalPracticeWorker(f.compiler, fullStore, f.scratch).render(f.project, 0, request) { rendered = true })
            PcmScratchBudget.reserve(ProjectLimits.MAX_TOTAL_BYTES).use {
                assertEquals(PracticeResult.Failure(PracticeProblem.LIMIT), f.worker().render(f.project, 0, request) { rendered = true })
            }
            assertFalse(rendered)
            assertContentEquals(before, f.store.read(f.asset))
            f.noTemporary()
            assertIs<PracticeResult.Success<Asset>>(f.worker().render(f.project, 0, request))
            f.noTemporary()
        }
        PcmScratchBudget.reserve(ProjectLimits.MAX_TOTAL_BYTES).close()
    }
}
