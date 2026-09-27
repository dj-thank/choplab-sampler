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
}
