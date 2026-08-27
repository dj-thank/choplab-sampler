package com.choplab.desktop.audio

import com.choplab.sampler.audio.ScratchSpeedSmoother
import com.choplab.sampler.audio.normalizeScratchSpeed
import com.choplab.sampler.model.PcmAudio
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow

/** Streaming bidirectional scratch voice for one bounded PCM range. */
class DesktopScratchPlayer : AutoCloseable {
    private val running = AtomicBoolean(false)
    @Volatile private var targetSpeed = 0f
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var worker: Thread? = null
    @Volatile var currentFrame: Int = -1
        private set

    @Synchronized
    fun start(
        audio: PcmAudio,
        startFrame: Int,
        endFrame: Int,
        initialFrame: Int,
        pitchSemitones: Float = 0f,
        tone: Float = 1f,
        gain: Float = 1f,
        reverse: Boolean = false,
    ) {
        stop()
        val start = startFrame.coerceIn(0, (audio.frameCount - 1).coerceAtLeast(0))
        val end = endFrame.coerceIn(start + 1, audio.frameCount)
        val initial = initialFrame.coerceIn(start, end - 1)
        val format = AudioFormat(audio.sampleRate.toFloat(), 16, audio.channelCount, true, false)
        val output = AudioSystem.getSourceDataLine(format)
        output.open(format, BUFFER_FRAMES * audio.channelCount * Short.SIZE_BYTES * 2)
        output.start()
        line = output
        targetSpeed = 0f
        currentFrame = initial
        running.set(true)
        val thread = Thread(
            {
                renderLoop(
                    output,
                    Target(audio, audio.sampleRate, start, end, initial, pitchSemitones, tone, gain, reverse),
                )
            },
            "ChopLab-Windows-Scratch",
        ).apply { isDaemon = true }
        worker = thread
        thread.start()
    }

    fun updateSpeed(speed: Float) {
        targetSpeed = normalizeScratchSpeed(speed)
    }

    fun stop() {
        running.set(false)
        targetSpeed = 0f
        val activeLine = line
        runCatching { activeLine?.stop() }
        runCatching { activeLine?.flush() }
        runCatching { activeLine?.close() }
        val activeWorker = worker
        if (activeWorker != null && activeWorker !== Thread.currentThread()) {
            activeWorker.interrupt()
            runCatching { activeWorker.join(500L) }
        }
        line = null
        worker = null
    }

    private fun renderLoop(output: SourceDataLine, target: Target) {
        val bytes = ByteArray(BUFFER_FRAMES * target.audio.channelCount * Short.SIZE_BYTES)
        val smoother = ScratchSpeedSmoother()
        var position = target.initial.toDouble()
        val filterState = FloatArray(target.audio.channelCount)
        val tone = target.tone.coerceIn(0f, 1f)
        val alpha = if (tone >= 0.995f) 1f else {
            val cutoff = 80.0 * 225.0.pow(tone.toDouble())
            (1.0 - exp(-2.0 * PI * cutoff / target.sampleRate)).toFloat()
        }
        val pitchRatio = 2.0.pow(target.pitch.coerceIn(-24f, 24f) / 12.0)
        try {
            while (running.get()) {
                for (frame in 0 until BUFFER_FRAMES) {
                    var speed = smoother.next(targetSpeed) * pitchRatio
                    if (target.reverse) speed = -speed
                    val lower = floor(position).toInt().coerceIn(target.start, target.end - 1)
                    val upper = (lower + 1).coerceAtMost(target.end - 1)
                    val fraction = (position - lower).toFloat().coerceIn(0f, 1f)
                    val motionGain = (abs(speed) * 10.0).coerceIn(0.0, 1.0).toFloat()
                    repeat(target.audio.channelCount) { channel ->
                        val first = target.audio.sampleAt(lower, channel) / 32_768f
                        val second = target.audio.sampleAt(upper, channel) / 32_768f
                        val raw = first + (second - first) * fraction
                        val filtered = if (tone >= 0.995f) {
                            filterState[channel] = raw
                            raw
                        } else {
                            filterState[channel] += alpha * (raw - filterState[channel])
                            filterState[channel]
                        }
                        val sample = (filtered * target.gain.coerceIn(0f, 1.5f) * motionGain)
                            .coerceIn(-1f, 1f)
                            .times(Short.MAX_VALUE)
                            .toInt()
                            .toShort()
                        val sampleIndex = frame * target.audio.channelCount + channel
                        bytes[sampleIndex * 2] = (sample.toInt() and 0xFF).toByte()
                        bytes[sampleIndex * 2 + 1] = (sample.toInt() shr 8).toByte()
                    }
                    position += speed
                    if (position < target.start) {
                        position = target.start.toDouble()
                        targetSpeed = 0f
                    } else if (position >= target.end) {
                        position = (target.end - 1).toDouble()
                        targetSpeed = 0f
                    }
                }
                output.write(bytes, 0, bytes.size)
                currentFrame = position.toInt().coerceIn(target.start, target.end - 1)
            }
        } finally {
            running.set(false)
            runCatching { output.stop() }
            runCatching { output.close() }
        }
    }

    override fun close() = stop()

    private data class Target(
        val audio: PcmAudio,
        val sampleRate: Int,
        val start: Int,
        val end: Int,
        val initial: Int,
        val pitch: Float,
        val tone: Float,
        val gain: Float,
        val reverse: Boolean,
    )

    private companion object {
        const val BUFFER_FRAMES = 256
    }
}
