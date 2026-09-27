package com.choplab.desktop.next

import java.lang.reflect.Proxy
import javax.sound.sampled.SourceDataLine
import kotlin.test.*

/** What the Java Sound output reports for the diagnostics card, from a scripted line. */
class JavaSoundSinkTest {
    /** A 4096-byte line (512 float stereo frames) whose free space and play position the test sets. */
    private class ScriptedLine {
        var free = 4096
        var played = 0L
        val line = Proxy.newProxyInstance(SourceDataLine::class.java.classLoader, arrayOf(SourceDataLine::class.java)) { _, method, args ->
            when (method.name) {
                "available" -> free
                "getBufferSize" -> 4096
                "getLongFramePosition" -> played
                "write" -> (args[2] as Int).also { free -= it }
                "stop", "flush", "close" -> Unit
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
}
