package com.choplab.desktop.next

import com.choplab.desktop.audio.DesktopMicrophoneRecorder
import com.choplab.jvm.MicInput
import com.choplab.jvm.PcmMemoryBudget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

/** 16-bit 48/44.1 kHz input, averaging native stereo to the existing mono voice recorder. */
internal class JavaSoundMicInput private constructor(private val line: TargetDataLine,
    private val reserved: PcmMemoryBudget.Reservation) : MicInput {
    private val inputChannels = line.format.channels
    private val frameBytes = 2 * inputChannels
    private var bytes: ByteArray? = ByteArray((CONVERSION_BYTES / frameBytes * frameBytes).toInt())
    private val released = AtomicBoolean()
    override val sampleRate: Int = line.format.sampleRate.toInt()
    override val bufferFrames: Int = line.bufferSize / frameBytes

    override fun read(buffer: FloatArray): Int {
        val bytes = this.bytes ?: return -1
        val frames = minOf(buffer.size, bytes.size / frameBytes)
        val count = line.read(bytes, 0, frames * frameBytes) / frameBytes
        if (count <= 0) return -1
        for (frame in 0 until count) {
            var sum = 0
            for (channel in 0 until inputChannels) {
                val at = (frame * inputChannels + channel) * 2
                sum += (bytes[at].toInt() and 0xff) or (bytes[at + 1].toInt() shl 8)
            }
            buffer[frame] = sum / (32_768f * inputChannels)
        }
        return count
    }

    override fun stop() { line.stop(); line.flush() }
    override fun close() {
        if (!released.compareAndSet(false, true)) return
        try { line.close(); bytes = null; reserved.close() }
        catch (failure: Throwable) { released.set(false); throw failure }
    }

    companion object {
        internal const val CONVERSION_BYTES = 4096L
        // Reserve the explicit two-second ceiling before open; request half a second and return the unused bytes.
        private const val MAX_BUFFER_SECONDS = 2
        suspend fun open(memory: PcmMemoryBudget = PcmMemoryBudget.shared,
                         formats: List<AudioFormat> = DesktopMicrophoneRecorder.microphoneFormats(),
                         supported: (DataLine.Info) -> Boolean = AudioSystem::isLineSupported,
                         create: (DataLine.Info) -> TargetDataLine = { AudioSystem.getLine(it) as TargetDataLine }): MicInput? {
            for (format in formats) {
                require(format.sampleSizeInBits == 16 && !format.isBigEndian && format.channels in 1..2 &&
                    format.sampleRate.toInt() in 8_000..48_000 && format.frameSize == format.channels * 2)
                val info = DataLine.Info(TargetDataLine::class.java, format)
                if (!supported(info)) continue
                val ceiling = format.frameSize * format.sampleRate.toInt() * MAX_BUFFER_SECONDS
                val reserved = memory.reserve(CONVERSION_BYTES + ceiling)
                var line: TargetDataLine? = null
                var transferred = false
                try {
                    currentCoroutineContext().ensureActive()
                    line = create(info)
                    line.open(format, format.frameSize * (format.sampleRate.toInt() / 2))
                    require(line.format.matches(format))
                    require(line.bufferSize in format.frameSize..ceiling && line.bufferSize % format.frameSize == 0)
                    currentCoroutineContext().ensureActive()
                    line.start()
                    val input = JavaSoundMicInput(line, reserved)
                    reserved.shrinkTo(CONVERSION_BYTES + line.bufferSize)
                    transferred = true
                    return input
                } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
                catch (denied: SecurityException) { throw denied }
                catch (_: Exception) { }
                finally { if (!transferred) { line?.close(); reserved.close() } }
            }
            return null
        }
    }
}
