package com.choplab.sampler.next

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import com.choplab.engine.EngineFormat
import com.choplab.jvm.MicInput
import com.choplab.jvm.PcmMemoryBudget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The microphone for voice takes: 48 kHz mono, in float where the device captures it (16-bit otherwise), from the
 * input meant for live singing when the device has one, then the unprocessed one, then the plain microphone.
 */
internal class AndroidMicInput private constructor(private val record: AudioRecord, private val floats: Boolean,
    private val reserved: PcmMemoryBudget.Reservation) : MicInput {
    private var shorts: ShortArray? = if (floats) null else ShortArray(SHORT_BUFFER)
    private val released = AtomicBoolean()
    override val sampleRate: Int = SAMPLE_RATE
    override val bufferFrames: Int = record.bufferSizeInFrames

    /** Audio priority for the recording thread, as the earlier app's recorder had. */
    override fun onCaptureThread() = Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

    override fun read(buffer: FloatArray): Int {
        if (released.get()) return -1
        val shorts = this.shorts
        val count = if (floats) record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            else record.read(requireNotNull(shorts), 0, minOf(buffer.size, shorts.size), AudioRecord.READ_BLOCKING).also { read ->
                for (index in 0 until read) buffer[index] = shorts[index] / 32_768f
            }
        // A negative count is an error or a lost input (another app took the microphone): the take ends there.
        return if (count < 0) -1 else count
    }

    override fun stop() { try { record.stop() } catch (_: IllegalStateException) { } }
    override fun close() {
        if (!released.compareAndSet(false, true)) return
        try { record.release(); shorts = null; reserved.close() }
        catch (failure: Throwable) { released.set(false); throw failure }
    }

    companion object {
        private const val SAMPLE_RATE = EngineFormat.SAMPLE_RATE
        private const val SHORT_BUFFER = 2_048

        /** Opens and starts the first input that works; null without permission or when none opens. */
        suspend fun open(context: Context, memory: PcmMemoryBudget = PcmMemoryBudget.shared): MicInput? {
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return null
            val unprocessed = context.getSystemService(AudioManager::class.java)
                ?.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
            val sources = listOfNotNull(MediaRecorder.AudioSource.VOICE_PERFORMANCE,
                MediaRecorder.AudioSource.UNPROCESSED.takeIf { unprocessed }, MediaRecorder.AudioSource.MIC)
            for (source in sources) for (encoding in listOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)) {
                start(source, encoding, memory)?.let { return it }
            }
            return null
        }

        /** Only [open] calls this, after it found the permission granted; a revoked one fails as a SecurityException. */
        @SuppressLint("MissingPermission")
        private suspend fun start(source: Int, encoding: Int, memory: PcmMemoryBudget): MicInput? {
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, encoding)
            if (minimum <= 0) return null
            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            val ceilingFrames = SAMPLE_RATE * 2
            val ceilingBytes = ceilingFrames * bytesPerSample
            val requestedBytes = maxOf(minimum * 2L, SAMPLE_RATE / 2L * bytesPerSample)
            if (requestedBytes > ceilingBytes) return null
            val conversionBytes = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 0L else SHORT_BUFFER * 2L
            val reserved = memory.reserve(ceilingBytes + conversionBytes)
            var record: AudioRecord? = null
            var transferred = false
            try {
                currentCoroutineContext().ensureActive()
                record = AudioRecord.Builder().setAudioSource(source)
                    .setAudioFormat(AudioFormat.Builder().setEncoding(encoding).setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(requestedBytes.toInt()).build()
                if (record.state != AudioRecord.STATE_INITIALIZED || record.sampleRate != SAMPLE_RATE ||
                    record.channelCount != 1 || record.audioFormat != encoding || record.bufferSizeInFrames !in 1..ceilingFrames) return null
                currentCoroutineContext().ensureActive()
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return null
                val input = AndroidMicInput(record, encoding == AudioFormat.ENCODING_PCM_FLOAT, reserved)
                reserved.shrinkTo(record.bufferSizeInFrames * bytesPerSample.toLong() + conversionBytes)
                transferred = true
                return input
            } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
            catch (_: SecurityException) { return null }
            catch (_: UnsupportedOperationException) { return null }
            catch (_: IllegalArgumentException) { return null }
            catch (_: IllegalStateException) { return null }
            finally { if (!transferred) { record?.release(); reserved.close() } }
        }
    }
}
