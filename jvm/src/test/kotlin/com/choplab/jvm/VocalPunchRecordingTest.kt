package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Scripted capture timestamps prove stored frames, not microphone/output latency on a physical route. */
class VocalPunchRecordingTest {
    @Test fun punchCandidateAndCompSelectionAreSeparateUndoStepsAndKeepAllBytesThroughExportAndArchive() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("punch-production-")
        val paths = ConcurrentHashMap<String, Path>()
        fun location(path: Path) = Location("file-${paths.size}").also { paths[it.handle] = path }
        val backend = EditorBackend.create(directory.resolve("profile"), { StreamingEnginePort(it, { error("No native audio") }) },
            { assets, compiler -> HostFileServices(WavImportPort(assets, resolve = { paths.getValue(it.handle) }),
                FileProjectPort(assets, resolve = { paths.getValue(it.handle) }), WavExportPort(compiler) { paths.getValue(it.handle) }) })
        val pcm = WavPcmPort(backend.assets)
        val renderer = VocalCompRenderer(backend.assets, pcm, directory.resolve("comp"))
        val clock = AtomicLong(1_000_000_000)
        val mic = ClockedInput(clock, 48_000)
        val recorder = VoiceTakes(backend.assets, directory.resolve("capture"), captureChannels = 2, nanoTime = clock::get) { mic }
        suspend fun idle() = withTimeout(5000) {
            while (backend.studio.work.value.jobId != null || backend.studio.work.value.preparationId != null) delay(2)
        }
        try {
            val originalBytes = ByteArrayOutputStream().also { output ->
                WavCodec.writeFloat(output, FloatArray(96_000) { if (it % 2 == 0) .25f else -.15f })
            }.toByteArray()
            val originalFile = directory.resolve("original.wav")
            Files.write(originalFile, originalBytes)
            val original = WavImportPort(backend.assets, resolve = { originalFile }).import(Location("original"))
            val track = Track("voice", "Voice", TrackKind.VOCAL)
            val take = Take("original", track.id, original.hash, FrameRange(0, original.frames), 0)
            val clip = Clip("old", track.id, original.hash, take.range)
            assertTrue(backend.studio.dispatch(Action.Edit(VocalCompEdits.retain(Project(), original, take, track, clip = clip))).accepted)
            val before = backend.studio.document.value
            val plan = VocalPunchPlan.create(12_000, 36_000, 48_000, 1, 2, before.project.tempo)
            assertEquals(VoiceTakes.Start.STARTED, recorder.start(1, true, VoiceCaptureWindow(plan.captureFrames, plan.armingSeconds)))
            assertTrue(recorder.cueAt(clock.get() + plan.captureStart * 1_000_000_000 / 48_000))
            mic.feed(clock.get(), 0, 48_000, 257)
            withinSeconds(5) { recorder.windowComplete && mic.closes == 1 }
            val captured = assertNotNull(recorder.stop("Punch candidate"))
            assertEquals(plan.captureFrames, captured.asset.frames)
            assertEquals(0, captured.leadFrames)
            val candidateBytes = backend.assets.read(captured.asset)
            val candidate = Take("candidate", track.id, captured.asset.hash, FrameRange(0, captured.asset.frames), plan.captureStart)
            assertTrue(backend.studio.dispatch(Action.Edit(VocalCompEdits.retain(before.project, captured.asset, candidate), before.revision)).accepted)
            val kept = backend.studio.document.value
            assertEquals(before.revision + 1, kept.revision)
            assertEquals(before.project.clips, kept.project.clips, "Recording a punch only saves a candidate")
            assertEquals(2, kept.project.takes.size)
            assertTrue(backend.studio.dispatch(Action.Undo).accepted)
            assertEquals(before.project, backend.studio.document.value.project)
            assertTrue(backend.studio.dispatch(Action.Redo).accepted)
            assertEquals(kept.project, backend.studio.document.value.project)

            val current = backend.studio.document.value
            val draft = plan.splice(current.project, VocalCompEdits.fullTake(current.project, take.id, "punched"), candidate.id)
            val fullDisk = VocalCompRenderer(backend.assets, pcm, directory.resolve("full"), usableDiskBytes = { 0 })
            assertEquals(VocalProblem.LIMIT, assertFailsWith<VocalEditException> { fullDisk.render(current.project, draft, "Comp") }.problem)
            assertEquals(current, backend.studio.document.value, "A failed render keeps both the candidate and old clip")
            val rendered = renderer.render(current.project, draft, "Comp")
            assertEquals(48_000, rendered.frames, "Crossfade handles do not shorten the comp")
            assertEquals(current, backend.studio.document.value, "Preparing or previewing is not an apply")
            assertTrue(backend.studio.dispatch(Action.Edit(VocalCompEdits.apply(current.project, draft, rendered, track, "chosen", setOf("old")), current.revision)).accepted)
            val saved = backend.studio.document.value.project
            assertEquals(current.revision + 1, backend.studio.document.value.revision)
            assertEquals(kept.project.takes, saved.takes)
            assertEquals(listOf("original", "candidate", "original"), saved.vocalComps.single().segments.map { it.takeId })
            assertTrue(backend.studio.dispatch(Action.Undo).accepted)
            assertEquals(kept.project, backend.studio.document.value.project, "Undo selection keeps the recorded candidate")
            assertTrue(backend.studio.dispatch(Action.Redo).accepted)
            assertEquals(saved, backend.studio.document.value.project)
            val output = directory.resolve("punch.wav")
            assertTrue(backend.studio.dispatch(Action.Export(ExportRequest(location(output), 48_000, bits = 24), PlaybackTarget.Arrangement())).accepted)
            idle()
            val exported = Files.newInputStream(output).use { WavCodec.read(it) }
            assertEquals(48_000, exported.info.frames); assertEquals(24, exported.info.bits)
            assertEquals(.25f, exported.samples[5_000 * 2], .000001f)
            assertEquals(-.15f, exported.samples[5_000 * 2 + 1], .000001f)
            val mid = samples(24_000, 24_001)
            assertEquals(mid[0], exported.samples[24_000 * 2], .000001f)
            assertEquals(mid[1], exported.samples[24_000 * 2 + 1], .000001f)
            assertEquals(.25f, exported.samples[40_000 * 2], .000001f)
            val archive = directory.resolve("punch.choplab")
            assertTrue(backend.studio.dispatch(Action.Save(location(archive))).accepted); idle()
            val fresh = FileAssetStore(directory.resolve("fresh"))
            assertEquals(saved, Files.newInputStream(archive).use { ArchiveCodec().read(it, fresh) })
            assertContentEquals(originalBytes, fresh.read(original))
            assertContentEquals(candidateBytes, fresh.read(captured.asset))
            backend.flushAutosave()
            assertEquals(saved, AutosaveStore(directory.resolve("profile/autosave"), fresh).recover()!!.project)
        } finally { recorder.close(); pcm.close(); backend.shutdown() }
    }

    @Test fun theRecordingThreadClosesAtTheExclusivePunchEndForEveryRateAndBufferPartition() = runBlocking<Unit> {
        for (rate in listOf(8_000, 44_100, 48_000)) for (block in listOf(1, 17, 96, 257, 1024)) {
            val clock = AtomicLong(1_000_000_000)
            val store = FileAssetStore(Files.createTempDirectory("punch-store-"))
            val scratch = Files.createTempDirectory("punch-capture-")
            val mic = ClockedInput(clock, rate)
            val takes = VoiceTakes(store, scratch, captureChannels = 2, nanoTime = clock::get) { mic }
            try {
                val duration = 1157L
                val cue = 31_000_001L
                val first = ceilRatio(cue.toBigInteger() * rate.toBigInteger(), 1_000_000_000L.toBigInteger()).toInt()
                val end = endFrame(cue, duration, rate).toInt()
                assertEquals(VoiceTakes.Start.STARTED, takes.start(1, waitForCue = true, window = VoiceCaptureWindow(duration)))
                assertTrue(takes.cueAt(clock.get() + cue))
                assertFalse(takes.cueAt(clock.get() + cue + 1), "A duplicate cue cannot move either boundary")
                mic.feed(origin = 1_000_000_000, firstFrame = 0, endFrame = end + 2048, block = block)
                withinSeconds(5) { takes.windowComplete && mic.closes == 1 }
                assertFalse(takes.full || takes.interrupted || takes.armingTimedOut)
                val take = assertNotNull(takes.stop("PUNCH"))
                assertEquals((end - first).toLong(), take.asset.frames, "rate=$rate block=$block")
                assertEquals(rate, take.asset.sampleRate)
                assertEquals(2, take.asset.channels)
                assertContentEquals(samples(first, end), store.openVerified(take.asset).use { WavCodec.read(it) }.samples,
                    "No pre-roll, post-roll or swapped stereo: rate=$rate block=$block")
                assertEquals(1, mic.closes)
                assertEquals("ChopLab-NEXT-voice", mic.closedBy)
                assertEquals(0, Files.list(scratch).use { it.count() })
            } finally { takes.close() }
        }
    }

    @Test fun absoluteEndRoundingDoesNotAddAFrameAndALateInputCannotMovePunchOut() = runBlocking<Unit> {
        // ceil(start) + ceil(duration) would write one extra frame for this 44.1 kHz interval.
        assertEquals(99, VoiceCaptureWindow(107).nativeEnd(1, 44_100))
        assertEquals(98, VoiceCaptureWindow(107).nativeEnd(-10_000, 44_100))
        val rate = 8_000
        val clock = AtomicLong(1_000_000_000)
        val mic = ClockedInput(clock, rate)
        val store = FileAssetStore(Files.createTempDirectory("punch-late-store-"))
        val scratch = Files.createTempDirectory("punch-late-")
        val takes = VoiceTakes(store, scratch, captureChannels = 2, nanoTime = clock::get) { mic }
        try {
            assertEquals(VoiceTakes.Start.STARTED, takes.start(1, true, VoiceCaptureWindow(4_800)))
            assertTrue(takes.cueAt(1_000_000_000))
            // The first 25 ms never arrive. The end stays at 100 ms; no silence or extension is fabricated.
            mic.feed(1_000_000_000, firstFrame = 200, endFrame = 1024, block = 257)
            withinSeconds(5) { takes.windowComplete && mic.closes == 1 }
            val take = assertNotNull(takes.stop("LATE PUNCH"))
            assertEquals(600, take.asset.frames)
            assertEquals(-200, take.leadFrames)
            assertContentEquals(samples(200, 800), store.openVerified(take.asset).use { WavCodec.read(it) }.samples)
            assertFalse(takes.interrupted)
        } finally { takes.close() }
    }

    @Test fun preRollUsesItsOwnBoundAndCancelLossAndQuotaNeverPretendToCompleteTheWindow() = runBlocking<Unit> {
        val clock = AtomicLong(1_000_000_000)
        val store = FileAssetStore(Files.createTempDirectory("punch-lifecycle-store-"))
        val scratch = Files.createTempDirectory("punch-lifecycle-")
        val mic = ClockedInput(clock, 8_000)
        val takes = VoiceTakes(store, scratch, captureChannels = 2, nanoTime = clock::get) { mic }
        try {
            assertEquals(VoiceTakes.Start.STARTED, takes.start(1, true, VoiceCaptureWindow(4_800, armingSeconds = 26)))
            assertFalse(takes.cueAt(28_000_000_000))
            assertTrue(takes.cueAt(25_000_000_000), "Explicit pre-roll can extend the separate 20-second count-in deadline")
            mic.feed(1_000_000_000, firstFrame = 0, endFrame = 800, block = 200)
            withinSeconds(5) { mic.reads == 4 }
            assertEquals(0, takes.recordedMillis)
            assertFalse(takes.windowComplete || takes.armingTimedOut)
            takes.discard()
            assertFalse(takes.cueAt(25_000_000_000))
            assertNull(takes.stop("CANCELLED"))
            assertEquals(0, store.storedBytes())
            assertEquals(1, mic.closes)
        } finally { takes.close() }

        val slowMic = ClockedInput(clock, 8_000)
        val slow = VoiceTakes(store, scratch, captureChannels = 2, nanoTime = clock::get) { slowMic }
        try {
            assertEquals(VoiceTakes.Start.STARTED, slow.start(1, true, VoiceCaptureWindow(4_800, armingSeconds = 26)))
            val cue = clock.get() + 24_000_000_000
            assertTrue(slow.cueAt(cue))
            slowMic.feed(cue, firstFrame = 0, endFrame = 800, block = 800)
            withinSeconds(5) { slow.windowComplete && slowMic.closes == 1 }
            assertFalse(slow.armingTimedOut || slow.interrupted)
            assertEquals(800, assertNotNull(slow.stop("AFTER PRE-ROLL")).asset.frames)
        } finally { slow.close() }

        val lostMic = ClockedInput(clock, 8_000)
        val lost = VoiceTakes(store, scratch, captureChannels = 2, nanoTime = clock::get) { lostMic }
        try {
            assertEquals(VoiceTakes.Start.STARTED, lost.start(1, true, VoiceCaptureWindow(4_800)))
            val origin = clock.get()
            assertTrue(lost.cueAt(origin))
            lostMic.feed(origin, 0, 100, 100)
            withinSeconds(5) { lost.recordedMillis >= 12 }
            lostMic.stop()
            withinSeconds(5) { lost.interrupted && lostMic.closes == 1 }
            assertFalse(lost.windowComplete)
            assertEquals(100, assertNotNull(lost.stop("INTERRUPTED")).asset.frames, "Only received audio is retained")
        } finally { lost.close() }
        var opens = 0
        val small = FileAssetStore(Files.createTempDirectory("punch-quota-store-"), maxStoredBytes = 44L + 48_000 * 8)
        val bounded = VoiceTakes(small, scratch, captureChannels = 2) { opens++; ClockedInput(clock, 48_000) }
        try {
            assertEquals(VoiceTakes.Start.NO_ROOM, bounded.start(2, true, VoiceCaptureWindow(72_000)))
            assertEquals(0, opens, "A punch window is not silently shortened to fit quota")
        } finally { bounded.close() }
        assertEquals(0, Files.list(scratch).use { it.count() })
    }

    private class ClockedInput(private val clock: AtomicLong, override val sampleRate: Int) : MicInput {
        override val channels = 2
        private data class Buffer(val samples: FloatArray, val endNanos: Long)
        private val queue = LinkedBlockingQueue<Buffer>()
        @Volatile private var stopped = false
        @Volatile var closes = 0
        @Volatile var reads = 0
        @Volatile var closedBy: String? = null
        fun feed(origin: Long, firstFrame: Int, endFrame: Int, block: Int) {
            var at = firstFrame
            while (at < endFrame) {
                val end = minOf(at + block, endFrame)
                // The first callback gives its block's end; later blocks retain that same native frame clock.
                val nanos = if (at == firstFrame) origin + firstFrame * 1_000_000_000L / sampleRate +
                    (end - at) * 1_000_000_000L / sampleRate else origin + end * 1_000_000_000L / sampleRate
                queue.put(Buffer(samples(at, end), nanos))
                at = end
            }
        }
        override fun read(buffer: FloatArray): Int {
            while (!stopped) {
                val input = queue.poll(5, TimeUnit.MILLISECONDS) ?: continue
                input.samples.copyInto(buffer)
                clock.set(input.endNanos)
                reads++
                return input.samples.size
            }
            return -1
        }
        override fun stop() { stopped = true }
        override fun close() { closes++; closedBy = Thread.currentThread().name }
    }

    private companion object {
        fun samples(start: Int, end: Int) = FloatArray((end - start) * 2) { index ->
            val frame = start + index / 2
            if (index % 2 == 0) (frame % 1024 + 1) / 2048f else -(frame % 511 + 1) / 1024f
        }
        fun ceilRatio(numerator: BigInteger, denominator: BigInteger): Long = numerator.divideAndRemainder(denominator).let {
            (if (it[1].signum() > 0) it[0] + BigInteger.ONE else it[0]).longValueExact()
        }
        fun endFrame(cue: Long, duration: Long, rate: Int): Long = ceilRatio(
            (cue.toBigInteger() * 48_000.toBigInteger() + duration.toBigInteger() * 1_000_000_000L.toBigInteger()) * rate.toBigInteger(),
            48_000.toBigInteger() * 1_000_000_000L.toBigInteger())
    }
}
