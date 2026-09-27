package com.choplab.desktop.next

import com.choplab.desktop.audio.DesktopMicrophoneRecorder
import com.choplab.jvm.MicInput
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/**
 * The microphone for voice takes through Java Sound: a 16-bit input line at 48 or 44.1 kHz, as the earlier desktop
 * recorder opened it, with both channels of a stereo line averaged to mono. The line is open and started.
 */
internal class JavaSoundMicInput(private val line: TargetDataLine) : MicInput {
    private val channels = line.format.channels
    private val frameBytes = 2 * channels
    private val bytes = ByteArray(4096 / frameBytes * frameBytes)
    override val sampleRate: Int = line.format.sampleRate.toInt()

    init { require(line.format.sampleSizeInBits == 16 && !line.format.isBigEndian && channels in 1..2) }

    override fun read(buffer: FloatArray): Int {
        val frames = minOf(buffer.size, bytes.size / frameBytes)
        // Blocks until the frames arrive; a stopped or closed line returns what it had, then nothing.
        val count = line.read(bytes, 0, frames * frameBytes) / frameBytes
        if (count <= 0) return -1
        for (frame in 0 until count) {
            var sum = 0
            for (channel in 0 until channels) {
                val at = (frame * channels + channel) * 2
                sum += (bytes[at].toInt() and 0xff) or (bytes[at + 1].toInt() shl 8)
            }
            buffer[frame] = sum / (32_768f * channels)
        }
        return count
    }

    override fun stop() { line.stop(); line.flush() }
    override fun close() = line.close()

    companion object {
        /** The first input line that opens in one of the earlier recorder's formats; null when there is none. */
        fun open(): MicInput? {
            for (format in DesktopMicrophoneRecorder.microphoneFormats()) {
                val info = DataLine.Info(TargetDataLine::class.java, format)
                if (!AudioSystem.isLineSupported(info)) continue
                var line: TargetDataLine? = null
                try {
                    line = AudioSystem.getLine(info) as TargetDataLine
                    // Half a second of buffer, as Java Sound gives by default: rides out a busy moment or a slow disk
                    // write on the recording thread. A stop still ends a waiting read at once.
                    line.open(format, format.frameSize * (format.sampleRate.toInt() / 2))
                    line.start()
                    return JavaSoundMicInput(line)
                } catch (_: Exception) { try { line?.close() } catch (_: Exception) { } }
            }
            return null
        }
    }
}
