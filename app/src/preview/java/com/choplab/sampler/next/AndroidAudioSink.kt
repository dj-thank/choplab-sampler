package com.choplab.sampler.next

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.choplab.jvm.AudioSink
import com.choplab.jvm.SinkEncoding
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Preview-only output adapter. The shared driver is its sole write/close owner.
 * The host owns audio focus and closes the driver on focus/route loss.
 * This adapter alone does not claim device timing or audible playback validation.
 */
class AndroidAudioSink private constructor(
    private val track: AudioTrack,
    override val encoding: SinkEncoding,
) : AudioSink {
    private val scratch = ByteBuffer.allocateDirect(2048 * 8).order(ByteOrder.LITTLE_ENDIAN)
    @Volatile private var closed = false
    @Volatile private var routeChanged = false
    private var routeId: Int? = track.routedDevice?.id
    private val routingListener = AudioRouting.OnRoutingChangedListener { routing ->
        if (!closed) {
            val current = routing.routedDevice?.id
            if (routeId != null && current != routeId) routeChanged = true
            if (current != null) routeId = current
        }
    }
    init { track.addOnRoutingChangedListener(routingListener, Handler(Looper.getMainLooper())) }

    override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
        check(!closed)
        val frameBytes = encoding.bytesPerSample * 2
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length && length % frameBytes == 0)
        check(!routeChanged) { "Audio output route changed" }
        val count = minOf(length, scratch.capacity()) / frameBytes * frameBytes
        scratch.clear()
        scratch.put(bytes, offset, count)
        scratch.flip()
        val written = track.write(scratch, count, AudioTrack.WRITE_NON_BLOCKING)
        check(written >= 0 && written % frameBytes == 0) { "AudioTrack write failed" }
        return written
    }

    override fun close() {
        if (closed) return
        closed = true
        try { track.removeOnRoutingChangedListener(routingListener); track.pause(); track.flush(); track.stop() }
        finally { track.release() }
    }

    companion object {
        fun open(): AudioSink {
            var failure: Exception? = null
            for (encoding in listOf(SinkEncoding.FLOAT32, SinkEncoding.PCM16)) {
                var candidate: AudioTrack? = null
                try {
                    val formatCode = if (encoding == SinkEncoding.FLOAT32) AudioFormat.ENCODING_PCM_FLOAT else AudioFormat.ENCODING_PCM_16BIT
                    val minimum = AudioTrack.getMinBufferSize(48_000, AudioFormat.CHANNEL_OUT_STEREO, formatCode)
                    check(minimum > 0)
                    val frameBytes = encoding.bytesPerSample * 2
                    val requested = maxOf(minimum, 1024 * frameBytes)
                    candidate = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(AudioFormat.Builder().setSampleRate(48_000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(formatCode).build())
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .setBufferSizeInBytes((requested + frameBytes - 1) / frameBytes * frameBytes)
                        .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                        .build()
                    check(candidate.state == AudioTrack.STATE_INITIALIZED)
                    candidate.play()
                    return AndroidAudioSink(candidate, encoding)
                } catch (error: Exception) {
                    failure = error
                    candidate?.release()
                }
            }
            throw IllegalStateException("No compatible Android output", failure)
        }
    }
}
