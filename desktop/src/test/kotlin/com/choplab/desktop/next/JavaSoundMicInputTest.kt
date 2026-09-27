package com.choplab.desktop.next

import java.lang.reflect.Proxy
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.TargetDataLine
import kotlin.test.*

/** The Java Sound microphone as the recorder reads it, from a scripted input line. */
class JavaSoundMicInputTest {
    /** Hands out [data] in reads of at most the requested length; after stop it returns nothing. */
    private class ScriptedLine(val format: AudioFormat, data: ByteArray) {
        private var rest = data
        var stopped = false
        var closed = false
        val line = Proxy.newProxyInstance(TargetDataLine::class.java.classLoader, arrayOf(TargetDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "getFormat" -> format
                "read" -> {
                    val target = args[0] as ByteArray
                    val count = if (stopped) 0 else minOf(args[2] as Int, rest.size)
                    rest.copyInto(target, args[1] as Int, 0, count)
                    rest = rest.copyOfRange(count, rest.size)
                    count
                }
                "stop", "flush" -> { stopped = true; Unit }
                "close" -> { closed = true; Unit }
                else -> error("Not used by the microphone: ${method.name}")
            }
        } as TargetDataLine
    }

    private fun le16(vararg values: Int) = ByteArray(values.size * 2) { (values[it / 2] shr (8 * (it % 2))).toByte() }

    @Test fun monoSamplesBecomeFloatsAndStopEndsTheReads() {
        val scripted = ScriptedLine(AudioFormat(48_000f, 16, 1, true, false), le16(0, 16_384, -32_768, 32_767))
        val mic = JavaSoundMicInput(scripted.line)
        assertEquals(48_000, mic.sampleRate)
        val buffer = FloatArray(8)
        assertEquals(4, mic.read(buffer))
        assertContentEquals(floatArrayOf(0f, .5f, -1f, 32_767 / 32_768f), buffer.copyOf(4))
        mic.stop()
        assertEquals(-1, mic.read(buffer), "A stopped line ends the take")
        mic.close()
        assertTrue(scripted.closed)
    }

    @Test fun stereoLinesAreAveragedToMonoAtTheirOwnRate() {
        val scripted = ScriptedLine(AudioFormat(44_100f, 16, 2, true, false), le16(16_384, 0, -16_384, -16_384, 100, -100))
        val mic = JavaSoundMicInput(scripted.line)
        assertEquals(44_100, mic.sampleRate)
        val buffer = FloatArray(2)
        assertEquals(2, mic.read(buffer), "At most one buffer's worth per read")
        assertContentEquals(floatArrayOf(.25f, -.5f), buffer)
        assertEquals(1, mic.read(buffer))
        assertEquals(0f, buffer[0])
        assertFailsWith<IllegalArgumentException>("16-bit little-endian only") {
            JavaSoundMicInput(ScriptedLine(AudioFormat(48_000f, 8, 1, true, false), ByteArray(0)).line)
        }
    }
}
