package com.choplab.jvm

import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

/** Deterministic input-clock fixtures. These test file boundaries, not physical input/output latency. */
class ArmedVoiceRecorderTest {
    @Test fun twoSlowBarsAreExcludedAndTheWholeFiveMinuteRecordingLimitRemainsAvailable() {
        val scratch = Files.createTempDirectory("armed-take-")
        val store = FileAssetStore(Files.createTempDirectory("armed-store-"))
        val clock = AtomicLong(1_000_000_000)
        val mic = ClockedInput(clock)
        val recorder = VoiceRecorder(mic, scratch, 300, waitForCue = true, nanoTime = clock::get)
        assertTrue(recorder.cueAt(13_000_000_000)) // Two bars at 40 BPM, after input permission/open.
        var end = clock.get()
        repeat(48) { end += 250_000_000; mic.add(FloatArray(2_000) { .9f }, end) }
        withinSeconds(5) { mic.reads == 48 }
        assertEquals(0, recorder.recordedMillis, "Count-in did not consume disk or the 300-second take limit")
        repeat(1_200) { end += 250_000_000; mic.add(FloatArray(2_000) { .2f }, end) }
        withinSeconds(10) { recorder.full }
        assertTrue(recorder.full)
        assertFalse(recorder.armingTimedOut)
        val take = assertNotNull(recorder.finish(store, "VOICE"))
        assertEquals(300L * 8_000, take.asset.frames)
        assertEquals(0, take.leadFrames)
        val audio = store.openVerified(take.asset).use { WavCodec.read(it) }
        assertTrue(audio.samples.all { it == .2f }, "No count-in sound, padding or click is stored")
        assertEquals(1, mic.closes)
        assertEquals(0, Files.list(scratch).use { it.count() })
    }

    @Test fun aCueInsideAStereoBufferPreservesTheExactFirstFrameAndChannelIdentity() {
        val scratch = Files.createTempDirectory("armed-stereo-")
        val store = FileAssetStore(Files.createTempDirectory("armed-stereo-store-"))
        val clock = AtomicLong(1_000_000_000)
        val mic = ClockedInput(clock, channels = 2)
        val recorder = VoiceRecorder(mic, scratch, 1, waitForCue = true, nanoTime = clock::get)
        assertTrue(recorder.cueAt(1_187_500_000)) // frame 1,500 of the capture clock.
        assertFalse(recorder.cueAt(1_200_000_000), "A late duplicate cannot move an armed cue")
        mic.add(FloatArray(2_000) { .9f }, 1_125_000_000)
        val input = FloatArray(2_000) { if (it % 2 == 0) it / 2 / 1000f else -.4f }
        mic.add(input, 1_250_000_000)
        withinSeconds(5) { recorder.recordedMillis >= 62 }
        val take = assertNotNull(recorder.finish(store, "STEREO"))
        assertEquals(500, take.asset.frames)
        assertEquals(0, take.leadFrames)
        val audio = store.openVerified(take.asset).use { WavCodec.read(it) }
        assertContentEquals(input.copyOfRange(1_000, 2_000), audio.samples)
    }

    @Test fun cancellingBeforeTheCueStoresNothingAndArmingHasAnExplicitSeparateDeadline() {
        val scratch = Files.createTempDirectory("armed-cancel-")
        val store = FileAssetStore(Files.createTempDirectory("armed-cancel-store-"))
        val clock = AtomicLong(1_000_000_000)
        val mic = ClockedInput(clock)
        val recorder = VoiceRecorder(mic, scratch, 1, waitForCue = true, nanoTime = clock::get)
        try {
            assertFalse(recorder.cueAt(22_000_000_000), "Beyond the separate 20-second preparation bound")
            mic.add(FloatArray(2_000) { .9f }, 1_250_000_000)
            withinSeconds(5) { mic.reads == 1 }
            assertEquals(0, recorder.recordedMillis)
            assertNull(recorder.finish(store, "CANCELLED"))
            assertEquals(0, store.storedBytes())
            assertEquals(1, mic.closes)
        } finally { recorder.discard() }

        val clockAdvanced = CountDownLatch(1)
        val resumeCapture = CountDownLatch(1)
        val timedInput = ClockedInput(clock, afterClockAdvance = {
            clockAdvanced.countDown()
            check(resumeCapture.await(5, TimeUnit.SECONDS))
        })
        val timed = VoiceRecorder(timedInput, scratch, 1, waitForCue = true, nanoTime = clock::get)
        try {
            timedInput.add(FloatArray(2_000) { .5f }, clock.get() + 21_000_000_000)
            assertTrue(clockAdvanced.await(5, TimeUnit.SECONDS))
            assertTrue(timed.armingTimedOut, "The deadline is observable while the input read is still returning")
            assertFalse(timed.interrupted, "The capture thread has not processed that input yet")
            resumeCapture.countDown()
            withinSeconds(5) { timed.interrupted }
            assertTrue(timed.armingTimedOut)
            assertNull(timed.finish(store, "TIMEOUT"))
            assertEquals(0, Files.list(scratch).use { it.count() })
            assertEquals(1, timedInput.closes)
        } finally { resumeCapture.countDown(); timed.discard() }
    }

    @Test fun aLateShortTakeKeepsItsActualOffsetInsteadOfMovingTowardTheCue() {
        val scratch = Files.createTempDirectory("armed-late-")
        val store = FileAssetStore(Files.createTempDirectory("armed-late-store-"))
        val clock = AtomicLong(1_000_000_000)
        val mic = ClockedInput(clock)
        val recorder = VoiceRecorder(mic, scratch, 1, waitForCue = true, nanoTime = clock::get)
        assertTrue(recorder.cueAt(2_000_000_000))
        mic.add(FloatArray(100) { .2f }, 2_212_500_000)
        withinSeconds(5) { recorder.recordedMillis >= 12 }
        val take = assertNotNull(recorder.finish(store, "LATE"))
        assertEquals(100, take.asset.frames)
        assertEquals(-1_600, take.leadFrames, "200 ms late even though the take lasts only 12.5 ms")
    }

    private class ClockedInput(private val clock: AtomicLong, override val channels: Int = 1,
                               private val afterClockAdvance: () -> Unit = {}) : MicInput {
        override val sampleRate = 8_000
        private data class Buffer(val samples: FloatArray, val endNanos: Long)
        private val queue = LinkedBlockingQueue<Buffer>()
        @Volatile private var stopped = false
        @Volatile var reads = 0
        @Volatile var closes = 0
        fun add(samples: FloatArray, endNanos: Long) { queue.put(Buffer(samples, endNanos)) }
        override fun read(buffer: FloatArray): Int {
            while (!stopped) {
                val input = queue.poll(5, TimeUnit.MILLISECONDS) ?: continue
                input.samples.copyInto(buffer)
                clock.set(input.endNanos)
                afterClockAdvance()
                reads++
                return input.samples.size
            }
            return -1
        }
        override fun stop() { stopped = true }
        override fun close() { closes++ }
    }
}
