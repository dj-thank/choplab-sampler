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
internal class JavaSoundSink(private val line: SourceDataLine, override val encoding: SinkEncoding) : AudioSink {
    private val frameBytes = encoding.bytesPerSample * 2
    /** Frames handed to the line, and the times it was found empty after it had been fed: the owner writes, a reader reads. */
    @Volatile private var framesWritten = 0L
    @Volatile private var dry = 0
    override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
        val free = line.available()
        // Java Sound counts no underruns; a fed line with its whole buffer free has run out of audio.
        if (framesWritten > 0 && free >= line.bufferSize) dry++
        val available = free / frameBytes * frameBytes
        if (available <= 0) return 0
        return line.write(bytes, offset, minOf(length, available)).also { framesWritten += it / frameBytes }
    }
    private val timingLock = Any()
    private var timingGeneration = 0L
    private var previousPosition = -1L
    override fun timingEpoch(): Long = synchronized(timingLock) { check(line.isOpen); timingGeneration }
    override fun timingSampleRate(): Int = line.format.sampleRate.toInt()
    override fun timingChannels(): Int = line.format.channels
    override fun bufferFrames(): Int = line.bufferSize / frameBytes
    override fun underruns(): Int = dry
    override fun pendingFrames(): Long = synchronized(timingLock) {
        check(line.isOpen)
        val position = line.longFramePosition
        if (previousPosition >= 0 && position < previousPosition) timingGeneration++
        previousPosition = position
        (framesWritten - position).coerceAtLeast(0)
    }
    override fun close() { try { line.stop(); line.flush() } finally { line.close() } }
    companion object {
        fun open(): AudioSink = open(AudioSystem::isLineSupported) { AudioSystem.getLine(it) as SourceDataLine }
        fun open(supported: (DataLine.Info) -> Boolean, create: (DataLine.Info) -> SourceDataLine): AudioSink {
            for (encoding in listOf(SinkEncoding.FLOAT32, SinkEncoding.PCM16)) {
                val format = AudioFormat(if (encoding == SinkEncoding.FLOAT32) AudioFormat.Encoding.PCM_FLOAT else AudioFormat.Encoding.PCM_SIGNED,
                    48_000f, encoding.bytesPerSample * 8, 2, encoding.bytesPerSample * 2, 48_000f, false)
                val info = DataLine.Info(SourceDataLine::class.java, format)
                if (!supported(info)) continue
                var line: SourceDataLine? = null
                try {
                    line = create(info)
                    line.open(format, 1024 * format.frameSize)
                    line.start()
                    return JavaSoundSink(line, encoding)
                } catch (_: Exception) { try { line?.close() } catch (_: Exception) { } }
            }
            throw LineUnavailableException("No compatible output")
        }
    }
}
