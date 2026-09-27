package com.choplab.jvm

import com.choplab.core.VoiceTake
import java.nio.file.Path
import kotlin.math.roundToLong

/**
 * A capture input: blocking reads of interleaved float samples at [sampleRate]. Microphones default to mono.
 * Only the recording thread
 * reads and closes it; [stop] may come from any thread.
 */
interface MicInput : AutoCloseable {
    val sampleRate: Int
    val channels: Int get() = 1
    /** Runs first on the recording thread, for a platform that gives audio threads their own priority. */
    fun onCaptureThread() {}
    /** Blocks until samples arrive; returns how many were read, or a negative number once the input is gone. */
    fun read(buffer: FloatArray): Int
    /** Ends capture: a read blocked on the recording thread returns. */
    fun stop()
    /** Releases the input once the recording thread no longer reads. */
    override fun close()
}

/**
 * Records one take from [input] on its own thread into a private scratch file, up to [maxSeconds]; that thread owns
 * the input and releases it when it ends, also at the limit. [cue] marks when the song started; what was captured
 * before it is the take's lead-in. Times come from System.nanoTime on this host, and the first frame's time is when
 * its buffer arrived minus that buffer's length: the microphone's own delay is not measured here.
 */
class VoiceRecorder(private val input: MicInput, scratch: Path, maxSeconds: Int) {
    private val rate = input.sampleRate
    private val channels = input.channels
    private val take = TakeFile(scratch, rate, channels, maxSeconds.toLong() * rate)
    @Volatile private var firstFrameNanos = -1L
    @Volatile private var cueNanos = -1L
    @Volatile private var running = true
    @Volatile private var ended = false
    private val thread = Thread(::capture, "ChopLab-NEXT-voice").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1; start() }

    /** The take reached its length limit and records nothing more. */
    val full: Boolean get() = take.full
    val recordedMillis: Long get() = take.frames * 1_000 / rate
    /** Recording stopped by itself before its limit: the input went away or the take could not be written. */
    val interrupted: Boolean get() = ended

    private fun capture() {
        val buffer = FloatArray(2048)
        try {
            input.onCaptureThread()
            while (running && !take.full) {
                val count = input.read(buffer)
                if (count < 0) { ended = running; break }
                if (count == 0) continue
                require(count <= buffer.size && count % channels == 0)
                if (firstFrameNanos < 0) firstFrameNanos = System.nanoTime() - (count / channels) * 1_000_000_000L / rate
                take.write(buffer, count)
            }
        } catch (_: Exception) {
            // A failed input or a full disk ends the take with what it holds.
            ended = running
        } finally {
            try { input.close() } catch (_: Exception) { }
        }
    }

    /** The song started now. */
    fun cue() { cueNanos = System.nanoTime() }

    /**
     * Stops recording and stores the take named [name]; null when nothing usable was recorded. The lead-in is negative
     * when the microphone delivered its first frame only after the song had started.
     */
    fun finish(store: FileAssetStore, name: String): VoiceTake? {
        stop()
        val asset = take.finish(store, name) ?: return null
        val first = firstFrameNanos
        val cue = cueNanos
        val lead = if (first < 0 || cue < 0) 0L else ((cue - first) / 1e9 * rate).roundToLong().coerceIn(-asset.frames, asset.frames)
        return VoiceTake(asset, lead)
    }

    /** Stops recording and drops the take. */
    fun discard() { try { stop() } finally { take.discard() } }

    private fun stop() {
        running = false
        try { input.stop() } catch (_: Exception) { }
        thread.join(2_000)
        check(!thread.isAlive) { "The microphone did not stop within its deadline" }
    }
}
