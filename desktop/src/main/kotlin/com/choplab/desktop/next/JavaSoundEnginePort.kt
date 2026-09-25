package com.choplab.desktop.next

import com.choplab.core.ProgramCompiler
import com.choplab.jvm.StreamingEnginePort
import javax.sound.sampled.*

typealias AudioSink = com.choplab.jvm.AudioSink
typealias SinkEncoding = com.choplab.jvm.SinkEncoding
typealias DriverPhase = com.choplab.jvm.DriverPhase
typealias DriverFault = com.choplab.jvm.DriverFault
typealias DriverStatus = com.choplab.jvm.DriverStatus
typealias DriverReceipt = com.choplab.jvm.DriverReceipt
typealias DriverPlayback = com.choplab.jvm.DriverPlayback

/** Windows device adapter; command acknowledgement and output ownership are shared with Android. */
class JavaSoundEnginePort(
    compiler: ProgramCompiler,
    sinkFactory: () -> AudioSink = { JavaSoundSink.open() },
    blockFrames: Int = 256,
    acknowledgementMillis: Long = 1_000,
) : StreamingEnginePort(compiler, sinkFactory, blockFrames, acknowledgementMillis)
private class JavaSoundSink(private val line: SourceDataLine, override val encoding: SinkEncoding) : AudioSink {
    override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
        val frameBytes = encoding.bytesPerSample * 2
        val available = line.available() / frameBytes * frameBytes
        if (available <= 0) return 0
        return line.write(bytes, offset, minOf(length, available))
    }
    override fun close() { try { line.stop(); line.flush() } finally { line.close() } }
    companion object {
        fun open(): AudioSink {
            for (encoding in listOf(SinkEncoding.FLOAT32, SinkEncoding.PCM16)) {
                val format = AudioFormat(if (encoding == SinkEncoding.FLOAT32) AudioFormat.Encoding.PCM_FLOAT else AudioFormat.Encoding.PCM_SIGNED,
                    48_000f, encoding.bytesPerSample * 8, 2, encoding.bytesPerSample * 2, 48_000f, false)
                val info = DataLine.Info(SourceDataLine::class.java, format)
                if (!AudioSystem.isLineSupported(info)) continue
                var line: SourceDataLine? = null
                try {
                    line = AudioSystem.getLine(info) as SourceDataLine
                    line.open(format, 1024 * format.frameSize)
                    line.start()
                    return JavaSoundSink(line, encoding)
                } catch (_: Exception) { try { line?.close() } catch (_: Exception) { } }
            }
            throw LineUnavailableException("No compatible output")
        }
    }
}
