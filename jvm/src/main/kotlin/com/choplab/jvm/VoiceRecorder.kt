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
    /** Actual platform capture buffer, unknown until the native input has opened. */
    val bufferFrames: Int? get() = null
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
 * its buffer arrived minus that buffer's length: the microphone's own delay is not measured here. An optional
 * [window] ends capture on the recording thread at the cue-relative exclusive frame boundary, not a UI timer.
 */
class VoiceRecorder(private val input: MicInput, scratch: Path, maxSeconds: Int,
                    private val waitForCue: Boolean = false,
                    private val nanoTime: () -> Long = System::nanoTime,
                    private val window: VoiceCaptureWindow? = null,
                    reserved: PcmMemoryBudget.Reservation? = null) {
    init { require(window == null || (waitForCue && window.frames48k <= maxSeconds * 48_000L)) }
    private val rate = input.sampleRate
    private val channels = input.channels
    private val take = TakeFile(scratch, rate, channels, maxSeconds.toLong() * rate,
        reserved ?: kotlinx.coroutines.runBlocking { PcmMemoryBudget.shared.reserve(MEMORY_BYTES) })
    @Volatile private var firstFrameNanos = UNSET
    @Volatile private var cueNanos = UNSET
    private val openedNanos = nanoTime()
    private val armingSeconds = window?.armingSeconds ?: MAX_ARMING_SECONDS
    @Volatile private var armingExpired = false
    /** The input reached the requested end; a wholly late or silent window can still return no take. */
    @Volatile var windowComplete: Boolean = false
        private set
    val armingTimedOut: Boolean get() = armingExpired || (waitForCue && firstFrameNanos == UNSET &&
        nanoTime() - openedNanos > armingSeconds * 1_000_000_000L)
    @Volatile private var running = true
    @Volatile private var ended = false
    @Volatile private var abandoned = false
    private val thread = try { Thread(::capture, "ChopLab-NEXT-voice").apply { isDaemon = true; priority = Thread.MAX_PRIORITY - 1; start() } }
        catch (failure: Throwable) { try { input.close() } finally { take.discard() }; throw failure }
    val terminated: Boolean get() = !thread.isAlive

    /** The take reached its length limit and records nothing more. */
    val full: Boolean get() = take.full
    val recordedMillis: Long get() = take.frames * 1_000 / rate
    /** Recording stopped by itself before its limit: the input went away or the take could not be written. */
    val interrupted: Boolean get() = ended

    private fun capture() {
        var captureFirstNanos = UNSET
        var capturedFrames = 0L
        try {
            val buffer = FloatArray(2048)
            input.onCaptureThread()
            while (running && !take.full) {
                val count = input.read(buffer)
                if (count < 0) { ended = running; break }
                if (count == 0) continue
                require(count <= buffer.size && count % channels == 0)
                val now = nanoTime()
                val frames = count / channels
                if (captureFirstNanos == UNSET) captureFirstNanos = now - frames * 1_000_000_000L / rate
                val cue = cueNanos
                var skip = 0
                var windowEnd: Long? = null
                if (window != null && cue != UNSET) {
                    windowEnd = window.nativeEnd(cue - captureFirstNanos, rate)
                }
                if (waitForCue && firstFrameNanos == UNSET) {
                    if (now - openedNanos > armingSeconds * 1_000_000_000L ||
                        capturedFrames > armingSeconds * rate.toLong()) {
                        armingExpired = true; ended = running; break
                    }
                    if (cue == UNSET) skip = frames
                    else {
                        // Ceil chooses the first input frame at or after the cue; no count-in samples enter the file.
                        val relative = cue - captureFirstNanos
                        val cueFrame = if (relative <= 0) 0L else (relative * rate + 999_999_999L) / 1_000_000_000L
                        skip = (cueFrame - capturedFrames).coerceIn(0L, frames.toLong()).toInt()
                    }
                }
                val kept = minOf((frames - skip).toLong(), windowEnd?.let { (it - capturedFrames - skip).coerceAtLeast(0) } ?: Long.MAX_VALUE).toInt()
                if (kept > 0) {
                    if (firstFrameNanos == UNSET) firstFrameNanos = captureFirstNanos + (capturedFrames + skip) * 1_000_000_000L / rate
                    take.write(buffer, kept * channels, skip * channels)
                }
                capturedFrames += frames
                if (windowEnd != null && capturedFrames >= windowEnd) { windowComplete = true; running = false }
            }
        } catch (_: Exception) {
            // A failed input or a full disk ends the take with what it holds.
            ended = running
        } finally {
            try { input.close() } catch (_: Exception) { }
            finally { if (abandoned) take.discard() }
        }
    }

    /** The song started now. */
    fun cue() { cueAt(nanoTime()) }

    /** Armed capture accepts one bounded host-clock cue. It spends none of its recording limit while waiting. */
    fun cueAt(atNanos: Long): Boolean {
        if (!running || ended || take.full || armingTimedOut || (waitForCue && cueNanos != UNSET)) return false
        if (waitForCue && (atNanos - openedNanos !in 0..armingSeconds * 1_000_000_000L)) return false
        cueNanos = atNanos
        return true
    }

    /**
     * Stops recording and stores the take named [name]; null when nothing usable was recorded. The lead-in is negative
     * when the microphone delivered its first frame only after the song had started.
     */
    fun finish(store: FileAssetStore, name: String): VoiceTake? {
        try { stop() } catch (failure: Throwable) { abandon(); throw failure }
        val asset = take.finish(store, name) ?: return null
        val first = firstFrameNanos
        val cue = cueNanos
        val lead = if (first == UNSET || cue == UNSET) 0L else ((cue - first) / 1e9 * rate).roundToLong()
            .coerceIn(-300L * rate, asset.frames)
        return VoiceTake(asset, lead)
    }

    /** Stops recording and drops the take. */
    fun discard() {
        abandoned = true
        try { stop() } finally { if (terminated) take.discard() }
    }
    private fun abandon() { abandoned = true; if (terminated) take.discard() }

    private fun stop() {
        running = false
        try { input.stop() } catch (_: Exception) { }
        thread.join(2_000)
        check(!thread.isAlive) { "The microphone did not stop within its deadline" }
    }

    companion object {
        /** Two bars at 40 BPM use 12 seconds; the remaining time bounds command/arming delays. */
        const val MAX_ARMING_SECONDS = 20
        const val MEMORY_BYTES = TakeFile.MEMORY_BYTES + 2048 * 4
        private const val UNSET = Long.MIN_VALUE
    }
}
