package com.choplab.desktop.next

import java.lang.reflect.Proxy
import com.choplab.jvm.*
import kotlinx.coroutines.*
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.TargetDataLine
import kotlin.test.*

/** The Java Sound microphone as the recorder reads it, from a scripted input line. */
class JavaSoundMicInputTest {
    /** Hands out [data] in reads of at most the requested length; after stop it returns nothing. */
    private class ScriptedLine(val format: AudioFormat, data: ByteArray, val beforeOpen: () -> Unit = {}) {
        var bufferBytes = format.frameSize * (format.sampleRate.toInt() / 2)
        var opens = 0
        var closes = 0
        var failOpen = false
        var failClose = false
        private var rest = data
        var stopped = false
        var closed = false
        val line = Proxy.newProxyInstance(TargetDataLine::class.java.classLoader, arrayOf(TargetDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "getFormat" -> format
                "getBufferSize" -> bufferBytes
                "open" -> { beforeOpen(); opens++; if (failOpen) throw IllegalStateException("Synthetic open failure"); Unit }
                "start" -> Unit
                "read" -> {
                    val target = args[0] as ByteArray
                    val count = if (stopped) 0 else minOf(args[2] as Int, rest.size)
                    rest.copyInto(target, args[1] as Int, 0, count)
                    rest = rest.copyOfRange(count, rest.size)
                    count
                }
                "stop", "flush" -> { stopped = true; Unit }
                "close" -> { closes++; if (failClose) throw IllegalStateException("Synthetic close failure"); closed = true; Unit }
                else -> error("Not used by the microphone: ${method.name}")
            }
        } as TargetDataLine
    }

    private fun le16(vararg values: Int) = ByteArray(values.size * 2) { (values[it / 2] shr (8 * (it % 2))).toByte() }

    @Test fun monoSamplesBecomeFloatsAndStopEndsTheReads() = runBlocking<Unit> {
        val scripted = ScriptedLine(AudioFormat(48_000f, 16, 1, true, false), le16(0, 16_384, -32_768, 32_767))
        val mic = assertNotNull(JavaSoundMicInput.open(formats = listOf(scripted.format), supported = { true }, create = { scripted.line }))
        assertEquals(48_000, mic.sampleRate)
        val buffer = FloatArray(8)
        assertEquals(4, mic.read(buffer))
        assertContentEquals(floatArrayOf(0f, .5f, -1f, 32_767 / 32_768f), buffer.copyOf(4))
        mic.stop()
        assertEquals(-1, mic.read(buffer), "A stopped line ends the take")
        mic.close()
        assertTrue(scripted.closed)
    }

    @Test fun stereoLinesAreAveragedToMonoAtTheirOwnRate() = runBlocking<Unit> {
        val scripted = ScriptedLine(AudioFormat(44_100f, 16, 2, true, false), le16(16_384, 0, -16_384, -16_384, 100, -100))
        val mic = assertNotNull(JavaSoundMicInput.open(formats = listOf(scripted.format), supported = { true }, create = { scripted.line }))
        assertEquals(44_100, mic.sampleRate)
        val buffer = FloatArray(2)
        assertEquals(2, mic.read(buffer), "At most one buffer's worth per read")
        assertContentEquals(floatArrayOf(.25f, -.5f), buffer)
        assertEquals(1, mic.read(buffer))
        assertEquals(0f, buffer[0])
        mic.close()
        assertFailsWith<IllegalArgumentException>("16-bit little-endian only") {
            JavaSoundMicInput.open(formats = listOf(AudioFormat(48_000f, 8, 1, true, false)), supported = { true })
        }
    }
    @Test fun nativeAndConversionBuffersReserveBeforeOpeningAndRemainUntilClose() = runBlocking<Unit> {
        val memory = PcmMemoryBudget(1L shl 20)
        val format = AudioFormat(48_000f, 16, 2, true, false)
        val ceiling = 4096 + 48_000 * 4L * 2
        val scripted = ScriptedLine(format, ByteArray(0)) {
            assertEquals(ceiling, runBlocking { memory.statistics().usedBytes })
        }
        val input = assertNotNull(JavaSoundMicInput.open(memory, listOf(format), { true }, { scripted.line }))
        assertEquals(24_000, input.bufferFrames)
        assertEquals(4096L + scripted.bufferBytes, memory.statistics().usedBytes)
        input.stop(); assertTrue(memory.statistics().usedBytes > 0)
        scripted.failClose = true
        assertFailsWith<IllegalStateException> { input.close() }
        assertTrue(memory.statistics().usedBytes > 0, "Failed native close is not proof of release")
        scripted.failClose = false
        input.close(); input.close()
        assertEquals(0L, memory.statistics().usedBytes)
        assertEquals(ceiling, memory.statistics().peakBytes)
        assertEquals(2, scripted.closes, "One failed close and one actual release; repeated close is harmless")
    }

    @Test fun memoryFailurePrecedesNativeOpenAndBadBuffersOrOpenFailureReturnTheReservation() = runBlocking<Unit> {
        val memory = PcmMemoryBudget(1L shl 20)
        val format = AudioFormat(48_000f, 16, 1, true, false)
        var acquired = 0
        memory.reserve(memory.limitBytes - 8).use {
            assertFailsWith<PcmMemoryLimit> { JavaSoundMicInput.open(memory, listOf(format), { true }, { acquired++; error("No native open") }) }
            assertEquals(0, acquired)
        }
        for (failure in listOf("oversize", "format", "open")) {
            val line = ScriptedLine(if (failure == "format") AudioFormat(44_100f, 16, 1, true, false) else format, ByteArray(0))
            if (failure == "oversize") line.bufferBytes = 48_000 * 2 * 3
            line.failOpen = failure == "open"
            assertNull(JavaSoundMicInput.open(memory, listOf(format), { true }, { line.line }))
            assertTrue(line.closed); assertEquals(0L, memory.statistics().usedBytes)
        }
    }

}
