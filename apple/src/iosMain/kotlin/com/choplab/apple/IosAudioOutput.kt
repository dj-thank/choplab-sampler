package com.choplab.apple

import com.choplab.engine.EngineFormat
import kotlinx.cinterop.*
import platform.AVFAudio.*
import platform.Foundation.NSError
import platform.darwin.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong

/**
 * One output route: AVAudioEngine with a player node fed with reusable 48 kHz stereo float buffers. The owner thread
 * renders EngineCore blocks and queues them here; AVFoundation plays them and calls back when a buffer is consumed.
 * No Kotlin code runs on the real-time render thread. Writes are non-blocking: a full queue returns false.
 */
internal class IosAudioOutput private constructor(private val blockFrames: Int, private val queueBlocks: Int) : AutoCloseable {
    private val engine = AVAudioEngine()
    private val player = AVAudioPlayerNode()
    private val format = requireNotNull(AVAudioFormat(standardFormatWithSampleRate = EngineFormat.SAMPLE_RATE.toDouble(), channels = 2u))
    private val buffers = List(queueBlocks) { requireNotNull(AVAudioPCMBuffer(pCMFormat = format, frameCapacity = blockFrames.toUInt())) }
    private val free = AtomicInt(queueBlocks)
    private val consumedFrames = AtomicLong(0)
    private val emptyEvents = AtomicInt(0)
    private val failed = AtomicInt(0)
    private var next = 0
    /** Frames handed to the player by the owner thread. */
    var scheduledFrames = 0L
        private set
    private val space = dispatch_semaphore_create(0)
    private var closed = false

    val bufferFrames: Int get() = blockFrames * queueBlocks
    /** Frames queued but not yet consumed by the player, plus the device's own reported latency. */
    fun pendingFrames(): Long {
        val queued = (scheduledFrames - consumedFrames.load()).coerceAtLeast(0)
        val session = AVAudioSession.sharedInstance()
        val device = ((session.outputLatency + session.IOBufferDuration) * EngineFormat.SAMPLE_RATE).toLong().coerceIn(0, 48_000)
        return queued + device
    }
    /** Times the queue ran dry while the route was playing: an audible gap. */
    fun underruns(): Int = emptyEvents.load()
    /** True once AVFoundation reported a stopped engine (route or configuration change, interruption). */
    val faulted: Boolean get() = failed.load() != 0 || !engine.running

    /** Copies [frames] interleaved stereo samples into the next free buffer and queues it; false when the queue is full. */
    fun write(interleaved: FloatArray, frames: Int): Boolean {
        check(!closed)
        require(frames in 1..blockFrames && interleaved.size >= frames * 2)
        if (free.load() == 0) return false
        free.decrementAndFetch()
        val buffer = buffers[next]
        next = (next + 1) % queueBlocks
        val channels = requireNotNull(buffer.floatChannelData)
        val left = requireNotNull(channels[0])
        val right = requireNotNull(channels[1])
        for (i in 0 until frames) { left[i] = interleaved[i * 2]; right[i] = interleaved[i * 2 + 1] }
        buffer.frameLength = frames.toUInt()
        scheduledFrames += frames
        // Published before scheduling: a callback that catches up with this count found the queue drained.
        published.store(scheduledFrames)
        player.scheduleBuffer(buffer, completionCallbackType = AVAudioPlayerNodeCompletionDataConsumed) { _ ->
            val consumed = consumedFrames.addAndFetch(frames.toLong())
            if (!closed && consumed >= published.load()) emptyEvents.incrementAndFetch()
            free.incrementAndFetch()
            dispatch_semaphore_signal(space)
        }
        return true
    }

    private val published = AtomicLong(0)

    fun freeBuffers(): Int = free.load()

    /** Waits until a buffer is free or [timeoutNanos] passes. */
    fun awaitSpace(timeoutNanos: Long) {
        if (free.load() > 0) return
        dispatch_semaphore_wait(space, dispatch_time(DISPATCH_TIME_NOW, timeoutNanos))
    }

    override fun close() {
        if (closed) return
        closed = true
        player.stop()
        engine.stop()
        engine.detachNode(player)
    }

    companion object {
        /** Activates the playback session and starts a route, or returns null when no output is available. */
        fun open(blockFrames: Int = 256, queueBlocks: Int = 4): IosAudioOutput? {
            if (!AudioSessionControl.activate()) return null
            val output = IosAudioOutput(blockFrames, queueBlocks)
            return try {
                output.engine.attachNode(output.player)
                output.engine.connect(output.player, to = output.engine.mainMixerNode, format = output.format)
                output.engine.prepare()
                val started = memScoped {
                    val error = alloc<ObjCObjectVar<NSError?>>()
                    output.engine.startAndReturnError(error.ptr) && error.value == null
                }
                if (!started) { output.close(); return null }
                output.player.play()
                output
            } catch (_: Throwable) { output.close(); null }
        }
    }
}

/** The app's single audio session: playback, low IO buffer, activated before any route opens. */
internal object AudioSessionControl {
    fun activate(): Boolean = memScoped {
        val session = AVAudioSession.sharedInstance()
        val error = alloc<ObjCObjectVar<NSError?>>()
        if (!session.setCategory(AVAudioSessionCategoryPlayback, error.ptr)) return@memScoped false
        session.setPreferredIOBufferDuration(0.005, null)
        session.setActive(true, error.ptr)
    }
}
