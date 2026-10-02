package com.choplab.desktop.next

import java.lang.reflect.Proxy
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.SourceDataLine
import kotlin.test.*

/** What the Java Sound output reports for the diagnostics card, from a scripted line. */
class JavaSoundSinkTest {
    /** A 4096-byte line (512 float stereo frames) whose free space and play position the test sets. */
    private class ScriptedLine {
        var free = 4096
        var played = 0L
        var open = true
        val format = AudioFormat(AudioFormat.Encoding.PCM_FLOAT, 48_000f, 32, 2, 8, 48_000f, false)
        val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "available" -> free
                "getBufferSize" -> 4096
                "getLongFramePosition" -> played
                "isOpen" -> open
                "getFormat" -> format
                "write" -> (args[2] as Int).also { free -= it }
                "close" -> Unit.also { open = false }
                "stop", "flush" -> Unit
                else -> error("Not used by the sink: ${method.name}")
            }
        } as SourceDataLine
    }

    @Test fun reportsBufferPendingFramesAndDryLines() {
        val scripted = ScriptedLine()
        val sink = JavaSoundSink(scripted.line, SinkEncoding.FLOAT32)
        assertEquals(512, sink.bufferFrames())
        val block = ByteArray(2048)

        // An empty line before the first write is the start, not a dropout.
        assertEquals(2048, sink.write(block, 0, 2048))
        assertEquals(0, sink.underruns())
        assertEquals(256L, sink.pendingFrames())
        scripted.played = 100
        assertEquals(156L, sink.pendingFrames())

        // Fed and still holding sound: no dropout. Then the line has played everything and sits empty: one dropout.
        assertEquals(2048, sink.write(block, 0, 2048))
        assertEquals(0, sink.underruns())
        scripted.free = 4096
        scripted.played = 512
        assertEquals(2048, sink.write(block, 0, 2048))
        assertEquals(1, sink.underruns())
        assertEquals(256L, sink.pendingFrames())

        // A full line takes nothing and counts nothing.
        scripted.free = 0
        assertEquals(0, sink.write(block, 0, 2048))
        assertEquals(1, sink.underruns())
    }

    @Test fun reportsFormatAndInvalidatesTimingWhenTheLinePositionResets() {
        val scripted = ScriptedLine()
        val sink = JavaSoundSink(scripted.line, SinkEncoding.FLOAT32)
        assertEquals(48_000, sink.timingSampleRate())
        assertEquals(2, sink.timingChannels())
        assertEquals(0L, sink.timingEpoch())
        sink.write(ByteArray(2048), 0, 2048)
        scripted.played = 100
        assertEquals(156L, sink.pendingFrames())
        assertEquals(0L, sink.timingEpoch())
        scripted.played = 20
        assertEquals(236L, sink.pendingFrames())
        assertEquals(1L, sink.timingEpoch())
        assertEquals(236L, sink.pendingFrames())
        assertEquals(1L, sink.timingEpoch())
    }

    @Test fun refusesTimingAfterTheLineIsClosed() {
        val scripted = ScriptedLine()
        val sink = JavaSoundSink(scripted.line, SinkEncoding.FLOAT32)
        sink.close()
        assertFalse(scripted.open)
        assertFailsWith<IllegalStateException> { sink.pendingFrames() }
        assertFailsWith<IllegalStateException> { sink.timingEpoch() }
    }
}
