package com.choplab.jvm

import com.choplab.core.PcmPort
import com.choplab.core.ProgramCompiler
import com.choplab.core.model.Asset
import com.choplab.engine.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.math.abs
import kotlin.test.*

/** The original is heard at the song key even when the output once refused a key change. */
class SourceAuditionControllerTest {
    private fun compiler() = ProgramCompiler(object : PcmPort {
        override suspend fun load(asset: Asset): PcmAsset = error("No PAD loading in this test")
    })
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(5) }
    private val paced = { object : AudioSink {
        override val encoding = SinkEncoding.FLOAT32
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int =
            length.also { LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000L / 48_000) }
        override fun close() = Unit
    } }

    @Test fun aKeyTheOutputRefusedIsSentAgainBeforeTheOriginalPlaysOn() = runBlocking<Unit> {
        val driver = StreamingEnginePort(compiler(), paced)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val refuseKeys = AtomicBoolean(false)
        val audition = SourceAuditionController(driver, object : PcmPort {
            override suspend fun load(asset: Asset): PcmAsset = PcmAsset.fromInterleaved(FloatArray(48_000 * 2 * 30) { .1f })
        }, scope, send = { command ->
            if (refuseKeys.get() && command is EngineCommand.SetOriginalPitch) false else driver.applyMonitoring(command)
        })
        val song = Asset("d".repeat(64), "wav", 44, 48_000, 2, 48_000L * 30, "song")
        /** Source frames the original moves per output frame over a short stretch of playback. */
        suspend fun rate(): Double {
            val source = driver.originalPlayback().sourceFrame
            val output = driver.snapshot().frame
            delay(200)
            return (driver.originalPlayback().sourceFrame - source).toDouble() / (driver.snapshot().frame - output)
        }
        suspend fun rateBecomes(expected: Double) = withTimeout(5_000) { while (abs(rate() - expected) > expected * .05) Unit }
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(audition.play(song))
            rateBecomes(1.0)

            // Refused while the original stays loaded, e.g. with the command queue full.
            refuseKeys.set(true)
            assertFalse(audition.pitch(12f))
            rateBecomes(1.0)
            refuseKeys.set(false)
            assertTrue(audition.pause())
            assertTrue(audition.play(song), "Playing on sends the refused key first")
            rateBecomes(2.0)
        } finally { audition.close(); scope.cancel(); driver.close() }
    }

    @Test fun theOriginalIsScratchedInItsOwnFramesAndPlaysOnOnlyIfItWasPlaying() = runBlocking<Unit> {
        val driver = StreamingEnginePort(compiler(), paced)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        // A 44.1 kHz original, normalized to 48 kHz by its loader as usual.
        val audition = SourceAuditionController(driver, object : PcmPort {
            override suspend fun load(asset: Asset): PcmAsset = PcmAsset.fromInterleaved(FloatArray(48_000 * 2 * 10) { .1f })
        }, scope)
        val song = Asset("e".repeat(64), "wav", 44, 44_100, 2, 44_100L * 10, "song")
        try {
            waitUntil { driver.status.value.phase == DriverPhase.ATTACHED }
            assertFalse(audition.scratchTo(1_000.0, 480), "Nothing is held before a scratch starts")
            assertTrue(audition.play(song))
            waitUntil { driver.originalPlayback().playing }
            // Held at 2 s of the original's own 44.1 kHz frames, within 1 s to 4 s: playback pauses there.
            assertTrue(audition.scratchStart(song, 88_200, 44_100, 176_400))
            waitUntil { !driver.originalPlayback().playing }
            assertEquals(2.0, driver.originalPlayback().sourceFrame / 48_000.0, .01)
            // Moved half a second ahead over 0.25 s, then pulled far past the range end: it stops at 4 s.
            assertTrue(audition.scratchTo(110_250.0, 12_000))
            waitUntil { abs(driver.originalPlayback().sourceFrame / 48_000.0 - 2.5) < .01 }
            assertTrue(audition.scratchTo(176_000.0, 48_000))
            waitUntil { abs(driver.originalPlayback().sourceFrame / 48_000.0 - 4.0) < .01 }
            assertTrue(audition.scratchCut(0f))
            assertTrue(audition.scratchEnd())
            // It was playing when taken, so it plays on once from where the hand left it.
            waitUntil { driver.originalPlayback().playing && driver.originalPlayback().sourceFrame > 4.02 * 48_000 }
            assertTrue(audition.pause())
            waitUntil { !driver.originalPlayback().playing }
            // Taken while paused, it stays paused when let go.
            assertTrue(audition.scratchStart(song, 88_200, 44_100, 176_400))
            assertTrue(audition.scratchEnd())
            delay(100)
            assertFalse(driver.originalPlayback().playing, "Taken while paused, it stays paused")
        } finally { audition.close(); scope.cancel(); driver.close() }
    }
}
