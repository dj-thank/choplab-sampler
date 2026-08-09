package com.choplab.sampler.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.stepKey
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow

/**
 * Low-latency streaming sampler and sample-accurate 16-step sequencer.
 *
 * The MVP uses AudioTrack's low-latency path and performs mixing, pitch resampling,
 * a per-voice low-pass tone control, choke groups, and sequencing on one audio thread.
 */
class SamplerEngine(
    context: Context,
    private val onError: (String) -> Unit = {},
) : SamplerPlaybackEngine {
    private val appContext = context.applicationContext
    private val commands = ConcurrentLinkedQueue<EngineCommand>()
    private val running = AtomicBoolean(false)
    private val padKit = arrayOfNulls<PadSnapshot>(SamplerConfig.PAD_COUNT)
    private val voices = mutableListOf<Voice>()
    private var sourceVoice: Voice? = null
    private var audioTrack: AudioTrack? = null
    private var audioThread: Thread? = null

    private var pattern: Array<IntArray> = Array(SamplerConfig.STEP_COUNT) { IntArray(0) }
    private var bpm = 92f
    private var swing = 54f
    private var transportRunning = false
    private var nextPatternStep = 0
    private var framesUntilNextStep = 0.0

    private val currentStepValue = AtomicInteger(-1)
    override val currentStep: Int
        get() = currentStepValue.get()
    private val currentSourceFrameValue = AtomicInteger(-1)
    override val currentSourceFrame: Int
        get() = currentSourceFrameValue.get()
    private val sourcePlayingValue = AtomicBoolean(false)
    override val sourcePlaying: Boolean
        get() = sourcePlayingValue.get()
    override var outputSampleRate: Int = 48_000
        private set

    override fun start(): Result<Unit> {
        if (!running.compareAndSet(false, true)) return Result.success(Unit)
        return runCatching {
            val manager = requireNotNull(appContext.getSystemService(AudioManager::class.java)) {
                "AudioManagerを取得できません"
            }
            outputSampleRate = manager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
                ?.toIntOrNull()
                ?.coerceIn(8_000, 192_000)
                ?: AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
                    .takeIf { it in 8_000..192_000 }
                ?: 48_000

            val framesPerBuffer = manager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
                ?.toIntOrNull()
                ?.coerceIn(64, 1_024)
                ?: 192

            val minimumBytes = AudioTrack.getMinBufferSize(
                outputSampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT,
            )
            check(minimumBytes > 0) { "低遅延オーディオ出力を作成できません" }

            val blockFrames = framesPerBuffer.coerceIn(96, 512)
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(outputSampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(max(minimumBytes, blockFrames * 2 * Float.SIZE_BYTES * 4))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
            check(track.state == AudioTrack.STATE_INITIALIZED) { "オーディオ出力を初期化できません" }
            audioTrack = track
            track.play()

            audioThread = Thread(
                { renderLoop(track, blockFrames) },
                "ChopLab-SamplerEngine",
            ).apply { start() }
        }.onFailure { throwable ->
            running.set(false)
            runCatching { audioTrack?.release() }
            audioTrack = null
            onError(throwable.message ?: "オーディオエンジンを開始できません")
        }
    }

    override fun updatePad(pad: PadModel) {
        if (pad.isAssigned) {
            commands.offer(EngineCommand.SetPad(PadSnapshot.from(pad)))
        } else {
            commands.offer(EngineCommand.ClearPad(pad.globalIndex))
        }
    }

    override fun updateAllPads(pads: List<PadModel>) {
        pads.forEach(::updatePad)
    }

    override fun triggerPad(globalIndex: Int) {
        commands.offer(EngineCommand.Trigger(globalIndex))
    }

    override fun releasePad(globalIndex: Int) {
        commands.offer(EngineCommand.Release(globalIndex))
    }

    override fun preview(audio: PcmAudio, startFrame: Int, endFrame: Int) {
        commands.offer(
            EngineCommand.Preview(
                PadSnapshot(
                    padIndex = -1,
                    audio = audio,
                    startFrame = startFrame.coerceIn(0, audio.frameCount - 1),
                    endFrame = endFrame.coerceIn(1, audio.frameCount),
                    pitchSemitones = 0f,
                    tone = 1f,
                    gain = 0.9f,
                    reverse = false,
                    playMode = PadPlayMode.ONE_SHOT,
                    chokeGroup = 0,
                ),
            ),
        )
    }

    override fun playSource(audio: PcmAudio, startFrame: Int, pitchSemitones: Float) {
        if (!running.get() || audio.frameCount < 1) {
            sourcePlayingValue.set(false)
            return
        }
        val safeStart = startFrame.coerceIn(0, audio.frameCount - 1)
        currentSourceFrameValue.set(safeStart)
        sourcePlayingValue.set(true)
        commands.offer(
            EngineCommand.PlaySource(
                PadSnapshot(
                    padIndex = SOURCE_PAD_INDEX,
                    audio = audio,
                    startFrame = safeStart,
                    endFrame = audio.frameCount,
                    pitchSemitones = pitchSemitones.coerceIn(-12f, 12f),
                    tone = 1f,
                    gain = 0.9f,
                    reverse = false,
                    playMode = PadPlayMode.ONE_SHOT,
                    chokeGroup = 0,
                ),
            ),
        )
    }

    override fun stopSource() {
        sourcePlayingValue.set(false)
        commands.offer(EngineCommand.StopSource)
    }

    override fun setPattern(activeSteps: Set<Int>, bpm: Float, swing: Float) {
        val steps = Array(SamplerConfig.STEP_COUNT) { step ->
            (0 until SamplerConfig.PAD_COUNT)
                .filter { pad -> stepKey(pad, step) in activeSteps }
                .toIntArray()
        }
        commands.offer(
            EngineCommand.SetPattern(
                steps = steps,
                bpm = bpm.coerceIn(40f, 240f),
                swing = swing.coerceIn(50f, 75f),
            ),
        )
    }

    override fun startTransport() {
        commands.offer(EngineCommand.StartTransport)
    }

    override fun stopTransport() {
        commands.offer(EngineCommand.StopTransport)
    }

    override fun stopAllVoices() {
        commands.offer(EngineCommand.StopAllVoices)
    }

    override fun shutdown() {
        if (!running.getAndSet(false)) return
        commands.clear()
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioThread?.join(1_500L) }
        audioThread = null
        runCatching { audioTrack?.stop() }
        runCatching { audioTrack?.release() }
        audioTrack = null
        currentStepValue.set(-1)
        currentSourceFrameValue.set(-1)
        sourcePlayingValue.set(false)
    }

    private fun renderLoop(track: AudioTrack, blockFrames: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val output = FloatArray(blockFrames * 2)

        try {
            while (running.get()) {
                drainCommands()
                output.fill(0f)
                var latestSourceFrame = -1

                for (frame in 0 until blockFrames) {
                    if (transportRunning) processTransportFrame()

                    var monoMix = 0f
                    sourceVoice?.let { source ->
                        monoMix += source.render(outputSampleRate)
                        latestSourceFrame = source.currentFrame
                        if (source.finished) {
                            sourceVoice = null
                            sourcePlayingValue.set(false)
                        }
                    }
                    var voiceIndex = 0
                    while (voiceIndex < voices.size) {
                        val voice = voices[voiceIndex]
                        val value = voice.render(outputSampleRate)
                        if (voice.finished) {
                            voices.removeAt(voiceIndex)
                        } else {
                            monoMix += value
                            voiceIndex++
                        }
                    }

                    // Smooth saturating limiter protects against polyphonic overload.
                    val limited = monoMix / (1f + abs(monoMix))
                    val outputIndex = frame * 2
                    output[outputIndex] = limited
                    output[outputIndex + 1] = limited
                }
                if (latestSourceFrame >= 0) currentSourceFrameValue.set(latestSourceFrame)

                var writeOffset = 0
                while (running.get() && writeOffset < output.size) {
                    val written = track.write(
                        output,
                        writeOffset,
                        output.size - writeOffset,
                        AudioTrack.WRITE_BLOCKING,
                    )
                    if (written < 0) error("オーディオ出力エラー: $written")
                    if (written == 0) continue
                    writeOffset += written
                }
            }
        } catch (throwable: Throwable) {
            if (running.get()) {
                onError(throwable.message ?: "オーディオ再生中にエラーが発生しました")
            }
        } finally {
            running.set(false)
            voices.clear()
            sourceVoice = null
            currentStepValue.set(-1)
            sourcePlayingValue.set(false)
        }
    }

    private fun drainCommands() {
        while (true) {
            when (val command = commands.poll() ?: break) {
                is EngineCommand.SetPad -> padKit[command.pad.padIndex] = command.pad
                is EngineCommand.ClearPad -> {
                    padKit[command.padIndex] = null
                    voices.removeAll { it.padIndex == command.padIndex }
                }
                is EngineCommand.Trigger -> padKit.getOrNull(command.padIndex)?.let(::startVoice)
                is EngineCommand.Release -> voices
                    .filter { it.padIndex == command.padIndex && it.playMode == PadPlayMode.GATE }
                    .forEach { it.release(RELEASE_FRAMES) }
                is EngineCommand.Preview -> startVoice(command.pad)
                is EngineCommand.PlaySource -> {
                    sourceVoice = Voice(command.source, outputSampleRate)
                    currentSourceFrameValue.set(command.source.startFrame)
                }
                EngineCommand.StopSource -> sourceVoice = null
                is EngineCommand.SetPattern -> {
                    pattern = command.steps
                    bpm = command.bpm
                    swing = command.swing
                }
                EngineCommand.StartTransport -> {
                    transportRunning = true
                    nextPatternStep = 0
                    framesUntilNextStep = 0.0
                }
                EngineCommand.StopTransport -> {
                    transportRunning = false
                    currentStepValue.set(-1)
                }
                EngineCommand.StopAllVoices -> {
                    voices.forEach { it.release(FAST_RELEASE_FRAMES) }
                    sourceVoice = null
                    sourcePlayingValue.set(false)
                }
            }
        }
    }

    private fun processTransportFrame() {
        if (framesUntilNextStep <= 0.0) {
            val stepToPlay = nextPatternStep
            currentStepValue.set(stepToPlay)
            pattern[stepToPlay].forEach { padIndex ->
                padKit.getOrNull(padIndex)?.let(::startVoice)
            }

            framesUntilNextStep += stepLengthFrames(stepToPlay)
            nextPatternStep = (stepToPlay + 1) % SamplerConfig.STEP_COUNT
        }
        framesUntilNextStep -= 1.0
    }

    private fun stepLengthFrames(step: Int): Double {
        val straightSixteenth = outputSampleRate * 60.0 / bpm.toDouble() / 4.0
        val longRatio = swing.toDouble() / 50.0
        return if (step % 2 == 0) {
            straightSixteenth * longRatio
        } else {
            straightSixteenth * (2.0 - longRatio)
        }
    }

    private fun startVoice(pad: PadSnapshot) {
        if (pad.endFrame <= pad.startFrame || pad.audio.samples.isEmpty()) return

        if (pad.chokeGroup > 0) {
            voices
                .filter { it.chokeGroup == pad.chokeGroup }
                .forEach { it.release(FAST_RELEASE_FRAMES) }
        }
        while (voices.size >= MAX_POLYPHONY) voices.removeAt(0)
        voices += Voice(pad, outputSampleRate)
    }

    private sealed interface EngineCommand {
        data class SetPad(val pad: PadSnapshot) : EngineCommand
        data class ClearPad(val padIndex: Int) : EngineCommand
        data class Trigger(val padIndex: Int) : EngineCommand
        data class Release(val padIndex: Int) : EngineCommand
        data class Preview(val pad: PadSnapshot) : EngineCommand
        data class PlaySource(val source: PadSnapshot) : EngineCommand
        data object StopSource : EngineCommand
        data class SetPattern(
            val steps: Array<IntArray>,
            val bpm: Float,
            val swing: Float,
        ) : EngineCommand
        data object StartTransport : EngineCommand
        data object StopTransport : EngineCommand
        data object StopAllVoices : EngineCommand
    }

    private data class PadSnapshot(
        val padIndex: Int,
        val audio: PcmAudio,
        val startFrame: Int,
        val endFrame: Int,
        val pitchSemitones: Float,
        val tone: Float,
        val gain: Float,
        val reverse: Boolean,
        val playMode: PadPlayMode,
        val chokeGroup: Int,
    ) {
        companion object {
            fun from(pad: PadModel): PadSnapshot {
                val audio = requireNotNull(pad.audio)
                return PadSnapshot(
                    padIndex = pad.globalIndex,
                    audio = audio,
                    startFrame = pad.startFrame.coerceIn(0, audio.frameCount - 1),
                    endFrame = pad.endFrame.coerceIn(1, audio.frameCount),
                    pitchSemitones = pad.pitchSemitones.coerceIn(-24f, 24f),
                    tone = pad.tone.coerceIn(0f, 1f),
                    gain = pad.gain.coerceIn(0f, 1.5f),
                    reverse = pad.reverse,
                    playMode = pad.playMode,
                    chokeGroup = pad.chokeGroup.coerceIn(0, 4),
                )
            }
        }
    }

    private class Voice(
        pad: PadSnapshot,
        outputSampleRate: Int,
    ) {
        val padIndex = pad.padIndex
        val playMode = pad.playMode
        val chokeGroup = pad.chokeGroup
        private val samples = pad.audio.samples
        private val startFrame = pad.startFrame
        private val endFrame = pad.endFrame
        private val reverse = pad.reverse
        private val gain = pad.gain
        private val tone = pad.tone
        private val signedStep: Double
        private var position: Double
        private var filterState = 0f
        private var releaseFramesRemaining = -1
        private var releaseFramesTotal = 1

        var finished: Boolean = false
            private set
        val currentFrame: Int
            get() = position.toInt().coerceIn(startFrame, endFrame)

        init {
            val pitchRatio = 2.0.pow(pad.pitchSemitones.toDouble() / 12.0)
            val resampleStep = pitchRatio * pad.audio.sampleRate / outputSampleRate.toDouble()
            signedStep = if (reverse) -resampleStep else resampleStep
            position = if (reverse) endFrame - 1.0 else startFrame.toDouble()
        }

        fun release(frames: Int) {
            val safeFrames = frames.coerceAtLeast(1)
            if (releaseFramesRemaining < 0 || safeFrames < releaseFramesRemaining) {
                releaseFramesRemaining = safeFrames
                releaseFramesTotal = safeFrames
            }
        }

        fun render(outputSampleRate: Int): Float {
            if (finished) return 0f
            if (position < startFrame || position >= endFrame) {
                finished = true
                return 0f
            }

            val lower = floor(position).toInt().coerceIn(startFrame, endFrame - 1)
            val upper = (lower + 1).coerceAtMost(endFrame - 1)
            val fraction = (position - lower).toFloat()
            val lowerSample = samples[lower] / 32_768f
            val upperSample = samples[upper] / 32_768f
            val raw = lowerSample + (upperSample - lowerSample) * fraction

            val framesFromStart = if (reverse) {
                (endFrame - 1.0) - position
            } else {
                position - startFrame
            }
            val framesToEnd = if (reverse) {
                position - startFrame
            } else {
                (endFrame - 1.0) - position
            }
            val boundaryEnvelope = minOf(
                1.0,
                framesFromStart / CLICK_FADE_SOURCE_FRAMES,
                framesToEnd / CLICK_FADE_SOURCE_FRAMES,
            ).coerceAtLeast(0.0).toFloat()

            val filtered = if (tone >= 0.995f) {
                raw
            } else {
                val cutoffHz = 80.0 * 225.0.pow(tone.toDouble())
                val alpha = (1.0 - exp(-2.0 * PI * cutoffHz / outputSampleRate)).toFloat()
                filterState += alpha * (raw - filterState)
                filterState
            }

            var releaseEnvelope = 1f
            if (releaseFramesRemaining >= 0) {
                releaseEnvelope = releaseFramesRemaining.toFloat() / releaseFramesTotal
                releaseFramesRemaining--
                if (releaseFramesRemaining <= 0) finished = true
            }

            position += signedStep
            if (position < startFrame || position >= endFrame) finished = true
            return filtered * gain * boundaryEnvelope * releaseEnvelope
        }
    }

    private companion object {
        const val MAX_POLYPHONY = 32
        const val RELEASE_FRAMES = 192
        const val FAST_RELEASE_FRAMES = 48
        const val CLICK_FADE_SOURCE_FRAMES = 48.0
        const val SOURCE_PAD_INDEX = -2
    }
}
