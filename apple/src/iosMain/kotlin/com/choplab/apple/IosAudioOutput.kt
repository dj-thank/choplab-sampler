package com.choplab.apple

import com.choplab.engine.EngineFormat
import kotlinx.cinterop.*
import platform.AVFAudio.*
import platform.Foundation.NSError
import platform.darwin.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.Volatile

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
    private var playerAttached = false
    /** Frames handed to the player by the owner thread. */
    var scheduledFrames = 0L
        private set
    private val space = dispatch_semaphore_create(0)
    @Volatile private var closed = false

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
    fun consumedFrameCount(): Long = consumedFrames.load()
    /** True once AVFoundation reported a stopped engine (route or configuration change, interruption). */
    val faulted: Boolean get() = failed.load() != 0 || !engine.running

    /** Copies [frames] interleaved stereo samples into the next free buffer and queues it; false when the queue is full. */
    fun write(interleaved: FloatArray, frames: Int): Boolean {
        check(!closed)
        require(frames in 1..blockFrames && interleaved.size >= frames * 2)
        if (free.load() == 0) return false
        free.addAndFetch(-1)
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
            if (!closed && consumed >= published.load()) emptyEvents.addAndFetch(1)
            free.addAndFetch(1)
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
        if (playerAttached) engine.detachNode(player)
    }

    companion object {
        /** Activates playback and records exactly why a real output route could not be opened. */
        fun open(blockFrames: Int = 256, queueBlocks: Int = 4): AudioOutputOpen {
            AudioSessionControl.activate()?.let { return AudioOutputOpen(null, it) }
            var output: IosAudioOutput? = null
            return try {
                val opened = IosAudioOutput(blockFrames, queueBlocks)
                output = opened
                val hardware = opened.engine.outputNode.outputFormatForBus(0u)
                // Apple defines zero sample rate/channel count as disabled hardware output.
                if (!hardware.sampleRate.isFinite() || hardware.sampleRate <= 0 || hardware.channelCount == 0u) {
                    val capability = AudioOutputCapability.capture(AudioOutputStage.OUTPUT_FORMAT, hardware = hardware)
                    opened.close()
                    return AudioOutputOpen(null, capability)
                }
                opened.engine.attachNode(opened.player)
                opened.playerAttached = true
                opened.engine.connect(opened.player, to = opened.engine.mainMixerNode, format = opened.format)
                opened.engine.prepare()
                val failure = memScoped {
                    val error = alloc<ObjCObjectVar<NSError?>>().apply { value = null }
                    if (opened.engine.startAndReturnError(error.ptr) && error.value == null) null
                    else AudioOutputCapability.capture(AudioOutputStage.ENGINE_START, error.value, hardware)
                }
                if (failure != null) { opened.close(); return AudioOutputOpen(null, failure) }
                opened.player.play()
                AudioOutputOpen(opened, AudioOutputCapability.capture(AudioOutputStage.READY, hardware = hardware))
            } catch (_: Throwable) {
                output?.close()
                AudioOutputOpen(null, AudioOutputCapability.capture(AudioOutputStage.UNEXPECTED_FAILURE))
            }
        }
    }
}

/** The app's single audio session: playback, low IO buffer, activated before any route opens. */
internal object AudioSessionControl {
    /** Null means the session activated; a failure carries sanitized platform evidence. */
    fun activate(): AudioOutputCapability? = memScoped {
        val session = AVAudioSession.sharedInstance()
        val error = alloc<ObjCObjectVar<NSError?>>().apply { value = null }
        if (!session.setCategory(AVAudioSessionCategoryPlayback, error.ptr) || error.value != null)
            return@memScoped AudioOutputCapability.capture(AudioOutputStage.SESSION_CATEGORY, error.value)
        session.setPreferredIOBufferDuration(0.005, null)
        error.value = null
        if (!session.setActive(true, error.ptr) || error.value != null)
            AudioOutputCapability.capture(AudioOutputStage.SESSION_ACTIVATE, error.value) else null
    }
}
