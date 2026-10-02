package com.choplab.jvm

import com.choplab.core.model.*
import com.choplab.core.vocal.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.math.*
import kotlin.test.*

class VocalCoachWorkerTest {
    @Test fun localReferenceDifferencesFollowSongFramesAndRecommendOnlyTheMeasuredLine() = runBlocking {
        val f = Fixture()
        try {
            val before = f.project
            val bytes = f.project.assets.associate { it.hash to f.store.read(it) }
            val report = assertIs<CoachResult.Success<VocalCoachReport>>(f.worker.analyze(f.project, 7, f.request)).value
            assertEquals(7L, report.revision)
            assertEquals(2, report.lines.size)
            val first = report.lines.first()
            assertEquals(150, first.onsetDifferenceMillis)
            assertTrue(abs(assertNotNull(first.pitchDifferenceCents) - 50) <= 2)
            assertTrue(first.comparedPitchHops > 30)
            assertEquals("line-1", report.suggestedLine?.lineId)
            assertEquals(0, report.lines.last().onsetDifferenceMillis)
            assertEquals(0, report.lines.last().meanAbsolutePitchCents)
            assertEquals(before, f.project)
            for (asset in f.project.assets) assertContentEquals(bytes[asset.hash], f.store.read(asset))
            assertEquals(2, f.store.directory.toFile().listFiles()!!.size)
        } finally { f.close() }
    }

    @Test fun noReferenceRapUnconfirmedAndContaminatedInputsNeverInventPitchScores() = runBlocking {
        val f = Fixture()
        try {
            val free = assertIs<CoachResult.Success<VocalCoachReport>>(f.worker.analyze(f.project, 0,
                f.request.copy(referenceTakeId = null))).value
            assertNotNull(free.lines.first().observedPitchHz)
            assertNull(free.suggestedLine)
            free.lines.forEach { assertNull(it.pitchDifferenceCents); assertTrue(CoachExclusion.NO_REFERENCE in it.exclusions) }
            val rap = assertIs<CoachResult.Success<VocalCoachReport>>(f.worker.analyze(f.project, 0,
                f.request.copy(mode = CoachMode.RAP))).value
            assertEquals(150, rap.lines.first().onsetDifferenceMillis)
            rap.lines.forEach { assertNull(it.observedPitchHz); assertNull(it.pitchDifferenceCents); assertEquals(0, it.comparedPitchHops)
                assertTrue(CoachExclusion.RAP_PITCH in it.exclusions) }
            for ((input, reason) in listOf(CoachVoiceInput.UNCONFIRMED to CoachExclusion.UNCONFIRMED_VOICE,
                CoachVoiceInput.ACCOMPANIMENT_PRESENT to CoachExclusion.ACCOMPANIMENT)) {
                val report = assertIs<CoachResult.Success<VocalCoachReport>>(f.worker.analyze(f.project, 0, f.request.copy(voiceInput = input))).value
                assertNull(report.suggestedLine)
                report.lines.forEach { assertNull(it.onsetDifferenceMillis); assertNull(it.observedPitchHz); assertTrue(reason in it.exclusions) }
            }
        } finally { f.close() }
    }

    @Test fun silenceMissingCoverageBudgetAndCancellationKeepOriginalsAndReleaseResources() = runBlocking {
        val f = Fixture(silent = true)
        try {
            val silent = assertIs<CoachResult.Success<VocalCoachReport>>(f.worker.analyze(f.project, 0, f.request)).value
            assertNull(silent.suggestedLine)
            silent.lines.forEach { assertNull(it.observedPitchHz); assertNull(it.pitchDifferenceCents); assertNull(it.onsetDifferenceMillis)
                assertTrue(CoachExclusion.UNVOICED_OR_UNCERTAIN in it.exclusions) }
            val moved = f.project.copy(takes = f.project.takes.map { if (it.id == "take") it.copy(timelineStartFrame = 24_000) else it }.frozen())
            val missing = assertIs<CoachResult.Success<VocalCoachReport>>(f.worker.analyze(moved, 0, f.request)).value
            assertTrue(CoachExclusion.MISSING_TAKE_RANGE in missing.lines.first().exclusions)
            assertNull(missing.lines.first().pitchDifferenceCents)
            val small = PcmMemoryBudget(1024)
            WavPcmPort(f.store, memory = small).use { pcm ->
                assertEquals(CoachResult.Failure(CoachProblem.LIMIT), VocalCoachWorker(pcm).analyze(f.project, 0, f.request))
            }
            assertEquals(0L, small.statistics().usedBytes)
            val pending = async(start = CoroutineStart.LAZY) { f.worker.analyze(f.project, 0, f.request) }
            pending.cancel(); assertFailsWith<CancellationException> { pending.await() }
            assertIs<CoachResult.Success<*>>(f.worker.analyze(f.project, 0, f.request))
            assertFailsWith<IllegalArgumentException> { f.request.copy(endFrame = 48_000L * 31) }
            assertEquals(CoachResult.Failure(CoachProblem.INVALID_INPUT), f.worker.analyze(f.project, 0, f.request.copy(takeId = "missing")))
        } finally { f.close() }
    }

    private class Fixture(private val silent: Boolean = false) {
        val root = Files.createTempDirectory("coach-local-")
        val store = FileAssetStore(root.resolve("assets"))
        val memory = PcmMemoryBudget()
        val pcm = WavPcmPort(store, memory = memory)
        val worker = VocalCoachWorker(pcm)
        private fun asset(candidate: Boolean): Asset {
            val data = FloatArray(96_000 * 2) { index ->
                val frame = index / 2
                val time = frame / 48_000.0
                val active = if (candidate) time in .25..<.85 || time in 1.1..<1.8 else time in .1..<.8 || time in 1.1..<1.8
                val hz = if (candidate && time < 1) 220 * 2.0.pow(50.0 / 1200) else 220.0
                if (!active || silent && candidate) 0f else (.2 * sin(2 * PI * hz * time) * if (index % 2 == 0) 1.0 else -.5).toFloat()
            }
            val bytes = ByteArrayOutputStream().also { WavCodec.writeFloat(it, data) }.toByteArray()
            return Asset(sha256(bytes), "wav", bytes.size.toLong(), 48_000, 2, 96_000, if (candidate) "Take" else "Reference")
                .also { store.publish(it, bytes.inputStream()) }
        }
        private val original = asset(true)
        private val reference = asset(false)
        val project = Project(assets = frozenListOf(original, reference), tracks = frozenListOf(Track("voice", "Voice", TrackKind.VOCAL)),
            takes = frozenListOf(Take("take", "voice", original.hash, FrameRange(0, original.frames), 0),
                Take("reference", "voice", reference.hash, FrameRange(0, reference.frames), 0)),
            lyrics = frozenListOf(LyricLine("line-1", "First", 0, 1920), LyricLine("line-2", "Second", 1920, 3840)))
        val request = VocalCoachRequest("take", "reference", 0, 96_000, voiceInput = CoachVoiceInput.VOICE_ONLY)
        suspend fun close() { pcm.close(); assertEquals(0L, memory.statistics().usedBytes); root.toFile().deleteRecursively() }
    }
}
