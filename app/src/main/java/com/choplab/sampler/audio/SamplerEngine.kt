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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

internal const val PREVIEW_PAD_INDEX = -1

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
    private val commands = RealtimeCommandMailbox<EngineCommand, Long>(COMMAND_CAPACITY)
    private val commandOverflowReported = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val runtimeCommandAdmission = RuntimeCommandAdmission(lifecycleLock, running::get)
    private val padUpdateLock = Any()
    private val padKit = arrayOfNulls<PadSnapshot>(SamplerConfig.PAD_COUNT)
    private val controlPadKit = AtomicReferenceArray<PadSnapshot?>(SamplerConfig.PAD_COUNT)
    private val pendingPadUpdates = LatestIndexedMailbox<PadSnapshot>(SamplerConfig.PAD_COUNT)
    private val voices = Array(MAX_POLYPHONY) { Voice() }
    private val sourceVoice = Voice()
    private var sourceVoiceGeneration = 0L
    private var nextVoiceStartOrder = 0L
    private var scratchVoice: ScratchVoice? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var audioThread: Thread? = null

    private var pattern: Array<IntArray> = Array(SamplerConfig.STEP_COUNT) { IntArray(0) }
    private var bpm = 92f
    private var swing = 54f
    private val transportState = TransportRuntimeState()
    private var nextPatternStep = 0
    private var framesUntilNextStep = 0.0

    override val currentStep: Int
        get() = transportState.currentStep
    private val currentSourceFrameValue = AtomicInteger(-1)
    override val currentSourceFrame: Int
        get() = currentSourceFrameValue.get()
    private val sourcePlaybackState = SourcePlaybackState()
    override val sourcePlaying: Boolean
        get() = sourcePlaybackState.isPlaying
    private val currentLoopPadValue = AtomicInteger(-1)
    override val currentLoopPad: Int
        get() = currentLoopPadValue.get()
    private val currentLoopFrameValue = AtomicInteger(-1)
    override val currentLoopFrame: Int
        get() = currentLoopFrameValue.get()
    private val currentScratchPadValue = AtomicInteger(-1)
    override val currentScratchPad: Int
        get() = currentScratchPadValue.get()
    private val currentScratchFrameValue = AtomicInteger(-1)
    override val currentScratchFrame: Int
        get() = currentScratchFrameValue.get()
    private val scratchSpeedBits = AtomicInteger(0f.toBits())
    override var outputSampleRate: Int = 48_000
        private set

    override fun start(): Result<Unit> = synchronized(lifecycleLock) {
        if (running.get()) return@synchronized Result.success(Unit)
        if (audioThread?.isAlive == true) {
            return@synchronized Result.failure(
                IllegalStateException("オーディオ出力の停止処理中です"),
            )
        }
        audioThread = null
        if (!running.compareAndSet(false, true)) return@synchronized Result.success(Unit)
        runCatching {
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

            val thread = Thread(
                { renderLoop(track, blockFrames) },
                "ChopLab-SamplerEngine",
            )
            audioThread = thread
            thread.start()
        }.onFailure { throwable ->
            running.set(false)
            sourcePlaybackState.forceStopped()
            runCatching { audioTrack?.release() }
            audioTrack = null
            if (audioThread?.isAlive != true) audioThread = null
            onError(throwable.message ?: "オーディオエンジンを開始できません")
        }
    }

    override fun updatePad(pad: PadModel) {
        if (pad.globalIndex !in 0 until SamplerConfig.PAD_COUNT) return
        val snapshot = pad.takeIf(PadModel::isAssigned)?.let(PadSnapshot::from)
        synchronized(padUpdateLock) {
            controlPadKit.set(pad.globalIndex, snapshot)
            pendingPadUpdates.publish(pad.globalIndex, snapshot)
        }
    }

    override fun updateAllPads(pads: List<PadModel>) {
        pads.forEach(::updatePad)
    }

    override fun triggerPad(globalIndex: Int) {
        enqueue(EngineCommand.Trigger(globalIndex))
    }

    override fun startPadLoop(globalIndex: Int) {
        enqueuePrepared {
            EngineCommand.StartPadLoop(
                padIndex = globalIndex,
                sourceStopGeneration = sourcePlaybackState.issueStop(),
            )
        }
    }

    override fun stopPad(globalIndex: Int) {
        enqueue(EngineCommand.StopPad(globalIndex))
    }

    override fun beginScratch(globalIndex: Int, startFrame: Int) {
        if (globalIndex !in 0 until SamplerConfig.PAD_COUNT) return
        val pad = controlPadKit.get(globalIndex) ?: return
        scratchSpeedBits.set(0f.toBits())
        enqueue(
            EngineCommand.BeginScratch(
                padIndex = globalIndex,
                voice = ScratchVoice(pad, startFrame),
            ),
        )
    }

    override fun beginSourceScratch(audio: PcmAudio, startFrame: Int, endFrame: Int) {
        if (audio.frameCount < 2) return
        val safeStart = startFrame.coerceIn(0, audio.frameCount - 2)
        val safeEnd = endFrame.coerceIn(safeStart + 1, audio.frameCount)
        val snapshot = PadSnapshot(
            padIndex = SOURCE_SCRATCH_PAD_INDEX,
            audio = audio,
            startFrame = safeStart,
            endFrame = safeEnd,
            pitchSemitones = 0f,
            tone = 1f,
            gain = 0.9f,
            reverse = false,
            playMode = PadPlayMode.ONE_SHOT,
            chokeGroup = 0,
        )
        scratchSpeedBits.set(0f.toBits())
        enqueue(
            EngineCommand.BeginScratch(
                padIndex = SOURCE_SCRATCH_PAD_INDEX,
                voice = ScratchVoice(snapshot, safeStart),
            ),
        )
    }

    override fun updateScratchSpeed(speed: Float) {
        scratchSpeedBits.set(normalizeScratchSpeed(speed).toBits())
    }

    override fun endScratch() {
        scratchSpeedBits.set(0f.toBits())
        enqueue(EngineCommand.EndScratch)
    }

    override fun releasePad(globalIndex: Int) {
        enqueue(EngineCommand.Release(globalIndex))
    }

    override fun preview(audio: PcmAudio, startFrame: Int, endFrame: Int) {
        enqueue(
            EngineCommand.Preview(
                PadSnapshot(
                    padIndex = PREVIEW_PAD_INDEX,
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
            sourcePlaybackState.forceStopped()
            return
        }
        val generation = sourcePlaybackState.issuePlay()
        val safeStart = startFrame.coerceIn(0, audio.frameCount - 1)
        enqueue(
            EngineCommand.PlaySource(
                source = PadSnapshot(
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
                generation = generation,
            ),
        )
    }

    override fun stopSource() {
        val generation = sourcePlaybackState.issueStop()
        enqueue(EngineCommand.StopSource(generation))
    }

    override fun setPattern(activeSteps: Set<Int>, bpm: Float, swing: Float) {
        val steps = Array(SamplerConfig.STEP_COUNT) { step ->
            (0 until SamplerConfig.PAD_COUNT)
                .filter { pad -> stepKey(pad, step) in activeSteps }
                .toIntArray()
        }
        enqueue(
            EngineCommand.SetPattern(
                steps = steps,
                bpm = SamplerDspPrimitives.bpm(bpm),
                swing = SamplerDspPrimitives.swing(swing),
            ),
        )
    }

    override fun startTransport() {
        enqueue(EngineCommand.StartTransport)
    }

    override fun stopTransport() {
        enqueue(EngineCommand.StopTransport)
    }

    override fun stopAllVoices() {
        val sourceGeneration = sourcePlaybackState.issueStop()
        commands.requestStop(sourceGeneration)
    }

    override fun shutdown() = synchronized(lifecycleLock) {
        running.set(false)
        commands.clear()
        commandOverflowReported.set(false)
        val track = audioTrack
        val thread = audioThread
        runCatching { track?.pause() }
        runCatching { track?.flush() }
        if (thread !== Thread.currentThread()) runCatching { thread?.join(1_500L) }
        if (audioTrack === track) {
            runCatching { track?.stop() }
            runCatching { track?.release() }
            audioTrack = null
        }
        if (thread !== Thread.currentThread() && thread?.isAlive == true) {
            runCatching { thread.join(500L) }
        }
        if (thread?.isAlive != true) {
            if (audioThread === thread) audioThread = null
            clearPlaybackVoices()
        }
        resetPublishedPlaybackState()
    }

    private fun renderLoop(track: AudioTrack, blockFrames: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val output = FloatArray(blockFrames * 2)

        try {
            while (running.get()) {
                drainCommands()
                output.fill(0f)
                var latestSourceFrame = -1
                var latestLoopFrame = -1
                var latestScratchFrame = -1
                val monitoredLoopPad = currentLoopPadValue.get()
                val scratchTargetSpeed = normalizeScratchSpeed(Float.fromBits(scratchSpeedBits.get()))

                for (frame in 0 until blockFrames) {
                    if (transportState.running) processTransportFrame()

                    var monoMix = 0f
                    if (sourceVoice.active) {
                        monoMix += sourceVoice.render(outputSampleRate)
                        latestSourceFrame = sourceVoice.currentFrame
                        if (sourceVoice.finished) {
                            val completedGeneration = sourceVoiceGeneration
                            sourceVoice.deactivate()
                            sourceVoiceGeneration = 0L
                            sourcePlaybackState.complete(completedGeneration)
                        }
                    }
                    scratchVoice?.let { scratch ->
                        monoMix += scratch.render(
                            outputSampleRate = outputSampleRate,
                            targetSpeed = scratchTargetSpeed,
                        )
                        latestScratchFrame = scratch.currentFrame
                    }
                    var voiceIndex = 0
                    while (voiceIndex < voices.size) {
                        val voice = voices[voiceIndex]
                        if (voice.active) {
                            val monitorsLoop = voice.padIndex == monitoredLoopPad &&
                                voice.playMode == PadPlayMode.LOOP
                            monoMix = mixVoiceSampleAndRetire(
                                voice = voice,
                                outputSampleRate = outputSampleRate,
                                monoMix = monoMix,
                            )
                            if (monitorsLoop && voice.active) {
                                latestLoopFrame = voice.currentFrame
                            }
                        }
                        voiceIndex++
                    }

                    // Smooth saturating limiter protects against polyphonic overload.
                    val limited = SamplerDspPrimitives.softLimit(monoMix)
                    val outputIndex = frame * 2
                    output[outputIndex] = limited
                    output[outputIndex + 1] = limited
                }
                if (latestSourceFrame >= 0) currentSourceFrameValue.set(latestSourceFrame)
                if (monitoredLoopPad >= 0) {
                    if (latestLoopFrame >= 0) {
                        currentLoopFrameValue.set(latestLoopFrame)
                    } else {
                        currentLoopPadValue.compareAndSet(monitoredLoopPad, -1)
                        currentLoopFrameValue.set(-1)
                    }
                }
                if (latestScratchFrame >= 0) currentScratchFrameValue.set(latestScratchFrame)

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
            if (running.getAndSet(false)) {
                onError(throwable.message ?: "オーディオ再生中にエラーが発生しました")
            }
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
            if (audioTrack === track) audioTrack = null
            if (audioThread === Thread.currentThread()) {
                clearPlaybackVoices()
                resetPublishedPlaybackState()
                audioThread = null
                running.set(false)
            }
        }
    }

    private fun drainCommands() {
        applyPendingStopAllVoices()
        applyPendingPadUpdates()

        var inspectedCommands = 0
        while (inspectedCommands < MAX_COMMANDS_PER_BLOCK) {
            val entry = commands.pollEntry() ?: break
            inspectedCommands++
            applyPendingStopAllVoices()
            if (!commands.shouldProcess(entry)) continue
            when (val command = entry.value) {
                is EngineCommand.Trigger -> {
                    applyPendingPadUpdates()
                    val pad = padKit.getOrNull(command.padIndex)
                    if (pad != null) startVoice(pad)
                }
                is EngineCommand.StartPadLoop -> {
                    applyPendingPadUpdates()
                    if (
                        retireSourceVoiceForLoopStart(
                            sourcePlaybackState = sourcePlaybackState,
                            sourceVoice = sourceVoice,
                            stopGeneration = command.sourceStopGeneration,
                        )
                    ) {
                        sourceVoiceGeneration = 0L
                    }
                    val pad = padKit.getOrNull(command.padIndex)
                    if (pad?.playMode == PadPlayMode.LOOP) {
                        retireConflictingVoicesForLoopStart(voices, command.padIndex)
                        currentLoopPadValue.set(command.padIndex)
                        currentLoopFrameValue.set(if (pad.reverse) pad.endFrame - 1 else pad.startFrame)
                        startVoice(pad)
                    }
                }
                is EngineCommand.StopPad -> {
                    releasePadVoices(command.padIndex, frames = FAST_RELEASE_FRAMES)
                    if (currentLoopPadValue.compareAndSet(command.padIndex, -1)) {
                        currentLoopFrameValue.set(-1)
                    }
                }
                is EngineCommand.BeginScratch -> {
                    scratchVoice = command.voice
                    currentScratchPadValue.set(command.padIndex)
                    currentScratchFrameValue.set(command.voice.currentFrame)
                }
                EngineCommand.EndScratch -> {
                    scratchVoice = null
                    currentScratchPadValue.set(-1)
                    currentScratchFrameValue.set(-1)
                }
                is EngineCommand.Release -> releasePadVoices(
                    command.padIndex,
                    playMode = PadPlayMode.GATE,
                    frames = RELEASE_FRAMES,
                )
                is EngineCommand.Preview -> startVoice(command.pad)
                is EngineCommand.PlaySource -> {
                    if (sourcePlaybackState.applyPlay(command.generation)) {
                        sourceVoice.start(command.source, outputSampleRate)
                        sourceVoiceGeneration = command.generation
                        currentSourceFrameValue.set(command.source.startFrame)
                    }
                }
                is EngineCommand.StopSource -> {
                    if (sourcePlaybackState.applyStopBoundary(command.generation)) {
                        sourceVoice.deactivate()
                        sourceVoiceGeneration = 0L
                    }
                }
                is EngineCommand.SetPattern -> {
                    pattern = command.steps
                    bpm = command.bpm
                    swing = command.swing
                }
                EngineCommand.StartTransport -> {
                    transportState.start()
                    nextPatternStep = 0
                    framesUntilNextStep = 0.0
                }
                EngineCommand.StopTransport -> {
                    transportState.stop()
                }
            }
        }
        if (commands.size <= COMMAND_OVERFLOW_RESET_SIZE) commandOverflowReported.set(false)
    }

    private fun applyPendingStopAllVoices() {
        val stop = commands.takeLatestStop()
        if (stop != null) applyStopAllVoices(stop.payload)
    }

    private fun applyPendingPadUpdates() {
        var wordIndex = 0
        while (wordIndex < pendingPadUpdates.wordCount) {
            var dirty = pendingPadUpdates.takeDirtyWord(wordIndex)
            while (dirty != 0L) {
                val bitIndex = java.lang.Long.numberOfTrailingZeros(dirty)
                val padIndex = wordIndex * Long.SIZE_BITS + bitIndex
                val update = pendingPadUpdates.take(padIndex)
                if (update != null) applyPadUpdate(padIndex, update.value)
                dirty = dirty and (dirty - 1L)
            }
            wordIndex++
        }
    }

    private fun applyPadUpdate(padIndex: Int, pad: PadSnapshot?) {
        padKit[padIndex] = pad
        var voiceIndex = 0
        while (voiceIndex < voices.size) {
            val voice = voices[voiceIndex]
            if (voice.active && pad == null && voice.padIndex == padIndex) {
                voice.deactivate()
            } else if (voice.active && pad != null) {
                voice.updateLiveParameters(pad, outputSampleRate)
            }
            voiceIndex++
        }
    }

    private fun enqueue(command: EngineCommand): Boolean {
        var attempted = false
        val accepted = runtimeCommandAdmission.offer {
            attempted = true
            commands.offer(command)
        }
        if (attempted) reportCommandAdmission(accepted)
        return accepted
    }

    private fun enqueuePrepared(commandFactory: () -> EngineCommand): Boolean {
        var attempted = false
        val accepted = runtimeCommandAdmission.offer {
            attempted = true
            commands.offerPrepared(commandFactory)
        }
        if (attempted) reportCommandAdmission(accepted)
        return accepted
    }

    private fun reportCommandAdmission(accepted: Boolean) {
        if (!accepted && commandOverflowReported.compareAndSet(false, true)) {
            onError("操作が集中しています。停止操作を優先し、一部の連続入力を省略しました")
        }
    }

    private fun applyStopAllVoices(sourceGeneration: Long) {
        var voiceIndex = 0
        while (voiceIndex < voices.size) {
            val voice = voices[voiceIndex]
            if (voice.active) voice.release(FAST_RELEASE_FRAMES)
            voiceIndex++
        }
        sourceVoice.deactivate()
        sourceVoiceGeneration = 0L
        sourcePlaybackState.applyStopBoundary(sourceGeneration)
        currentLoopPadValue.set(-1)
        currentLoopFrameValue.set(-1)
        scratchVoice = null
        currentScratchPadValue.set(-1)
        currentScratchFrameValue.set(-1)
        transportState.stop()
    }

    private fun clearPlaybackVoices() {
        var voiceIndex = 0
        while (voiceIndex < voices.size) {
            voices[voiceIndex].deactivate()
            voiceIndex++
        }
        sourceVoice.deactivate()
        sourceVoiceGeneration = 0L
        scratchVoice = null
        transportState.stop()
    }

    private fun resetPublishedPlaybackState() {
        transportState.stop()
        currentSourceFrameValue.set(-1)
        sourcePlaybackState.forceStopped()
        currentLoopPadValue.set(-1)
        currentLoopFrameValue.set(-1)
        currentScratchPadValue.set(-1)
        currentScratchFrameValue.set(-1)
    }

    private fun processTransportFrame() {
        if (framesUntilNextStep <= 0.0) {
            val stepToPlay = nextPatternStep
            transportState.publishStep(stepToPlay)
            val stepPads = pattern[stepToPlay]
            var stepPadIndex = 0
            while (stepPadIndex < stepPads.size) {
                val pad = padKit.getOrNull(stepPads[stepPadIndex])
                if (pad != null) startVoice(pad)
                stepPadIndex++
            }

            framesUntilNextStep += stepLengthFrames(stepToPlay)
            nextPatternStep = (stepToPlay + 1) % SamplerConfig.STEP_COUNT
        }
        framesUntilNextStep -= 1.0
    }

    private fun stepLengthFrames(step: Int): Double {
        return SamplerDspPrimitives.stepLengthFrames(outputSampleRate, bpm, swing, step)
    }

    private fun startVoice(pad: PadSnapshot) {
        if (pad.endFrame <= pad.startFrame || pad.audio.samples.isEmpty()) return

        if (pad.playMode == PadPlayMode.LOOP) {
            releasePadVoices(
                pad.padIndex,
                playMode = PadPlayMode.LOOP,
                frames = FAST_RELEASE_FRAMES,
            )
        }
        if (pad.chokeGroup > 0) {
            releaseChokeGroup(pad.chokeGroup, FAST_RELEASE_FRAMES)
        }
        var selectedVoice = voices[0]
        var voiceIndex = 0
        while (voiceIndex < voices.size) {
            val voice = voices[voiceIndex]
            if (!voice.active) {
                selectedVoice = voice
                break
            }
            if (voice.startOrder < selectedVoice.startOrder) selectedVoice = voice
            voiceIndex++
        }
        nextVoiceStartOrder++
        selectedVoice.start(pad, outputSampleRate, startOrder = nextVoiceStartOrder)
    }

    private fun releasePadVoices(
        padIndex: Int,
        playMode: PadPlayMode? = null,
        frames: Int,
    ) {
        var index = 0
        while (index < voices.size) {
            val voice = voices[index]
            if (
                voice.active &&
                voice.padIndex == padIndex &&
                (playMode == null || voice.playMode == playMode)
            ) {
                voice.release(frames)
            }
            index++
        }
    }

    private fun releaseChokeGroup(chokeGroup: Int, frames: Int) {
        var index = 0
        while (index < voices.size) {
            val voice = voices[index]
            if (voice.active && voice.chokeGroup == chokeGroup) voice.release(frames)
            index++
        }
    }

    private sealed interface EngineCommand {
        data class Trigger(val padIndex: Int) : EngineCommand
        data class StartPadLoop(
            val padIndex: Int,
            val sourceStopGeneration: Long,
        ) : EngineCommand
        data class StopPad(val padIndex: Int) : EngineCommand
        data class BeginScratch(val padIndex: Int, val voice: ScratchVoice) : EngineCommand
        data object EndScratch : EngineCommand
        data class Release(val padIndex: Int) : EngineCommand
        data class Preview(val pad: PadSnapshot) : EngineCommand
        data class PlaySource(val source: PadSnapshot, val generation: Long) : EngineCommand
        data class StopSource(val generation: Long) : EngineCommand
        data class SetPattern(
            val steps: Array<IntArray>,
            val bpm: Float,
            val swing: Float,
        ) : EngineCommand
        data object StartTransport : EngineCommand
        data object StopTransport : EngineCommand
    }

    internal data class PadSnapshot(
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
                    pitchSemitones = SamplerDspPrimitives.pitchSemitones(pad.pitchSemitones),
                    tone = SamplerDspPrimitives.tone(pad.tone),
                    gain = SamplerDspPrimitives.gain(pad.gain),
                    reverse = pad.reverse,
                    playMode = pad.playMode,
                    chokeGroup = pad.chokeGroup.coerceIn(0, 4),
                )
            }
        }
    }

    internal class Voice() {
        constructor(pad: PadSnapshot, outputSampleRate: Int) : this() {
            start(pad, outputSampleRate)
        }

        var active: Boolean = false
            private set
        var padIndex: Int = -1
            private set
        var playMode: PadPlayMode = PadPlayMode.ONE_SHOT
            private set
        var chokeGroup: Int = 0
            private set
        var startOrder: Long = 0L
            private set
        private var audioId: Long? = null
        private var samples: ShortArray = EMPTY_SAMPLES
        private var startFrame = 0
        private var endFrame = 1
        private var reverse = false
        private var gain = 0f
        private var tone = 1f
        private var filterAlpha = 1f
        private var sourceStep = 1.0
        private val cursor = VoicePlaybackCursor(
            startFrame = 0,
            endFrame = 1,
            reverse = false,
            playMode = PadPlayMode.ONE_SHOT,
        )
        private var filterState = 0f
        private var releaseFramesRemaining = -1
        private var releaseFramesTotal = 1

        var finished: Boolean = true
            private set
        val currentFrame: Int
            get() = if (active) {
                cursor.position.toInt().coerceIn(startFrame, endFrame - 1)
            } else {
                -1
            }
        internal val liveSourceStep: Double
            get() = sourceStep
        internal val liveTone: Float
            get() = tone
        internal val liveGain: Float
            get() = gain

        fun start(pad: PadSnapshot, outputSampleRate: Int, startOrder: Long = 0L) {
            require(pad.endFrame > pad.startFrame) { "Playback range must not be empty" }
            require(pad.audio.samples.isNotEmpty()) { "Playback audio must not be empty" }
            active = true
            finished = false
            padIndex = pad.padIndex
            playMode = pad.playMode
            chokeGroup = pad.chokeGroup
            this.startOrder = startOrder
            audioId = pad.audio.id
            samples = pad.audio.samples
            startFrame = pad.startFrame
            endFrame = pad.endFrame
            reverse = pad.reverse
            gain = pad.gain
            tone = pad.tone
            filterAlpha = SamplerDspPrimitives.toneFilterAlpha(pad.tone, outputSampleRate)
            sourceStep = sourceStepFor(pad, outputSampleRate)
            cursor.reset(startFrame, endFrame, reverse, playMode)
            filterState = 0f
            releaseFramesRemaining = -1
            releaseFramesTotal = 1
        }

        fun deactivate() {
            active = false
            finished = true
            padIndex = -1
            playMode = PadPlayMode.ONE_SHOT
            chokeGroup = 0
            startOrder = 0L
            audioId = null
            samples = EMPTY_SAMPLES
            startFrame = 0
            endFrame = 1
            reverse = false
            gain = 0f
            tone = 1f
            filterAlpha = 1f
            sourceStep = 1.0
            filterState = 0f
            releaseFramesRemaining = -1
            releaseFramesTotal = 1
        }

        fun updateLiveParameters(pad: PadSnapshot, outputSampleRate: Int) {
            if (!active || pad.padIndex != padIndex || pad.audio.id != audioId) return
            sourceStep = sourceStepFor(pad, outputSampleRate)
            tone = pad.tone
            filterAlpha = SamplerDspPrimitives.toneFilterAlpha(pad.tone, outputSampleRate)
            gain = pad.gain
        }

        fun release(frames: Int) {
            if (!active) return
            val safeFrames = frames.coerceAtLeast(1)
            if (releaseFramesRemaining < 0 || safeFrames < releaseFramesRemaining) {
                releaseFramesRemaining = safeFrames
                releaseFramesTotal = safeFrames
            }
        }

        fun render(@Suppress("UNUSED_PARAMETER") outputSampleRate: Int): Float {
            if (!active || finished) return 0f
            val position = cursor.position

            val lower = floor(position).toInt().coerceIn(startFrame, endFrame - 1)
            val upper = (lower + 1).coerceAtMost(endFrame - 1)
            val fraction = (position - lower).toFloat()
            val lowerSample = samples[lower] / 32_768f
            val upperSample = samples[upper] / 32_768f
            val raw = lowerSample + (upperSample - lowerSample) * fraction

            val boundaryEnvelope = SamplerDspPrimitives.boundaryEnvelope(
                position = position,
                startFrame = startFrame,
                endFrame = endFrame,
                reverse = reverse,
            )

            val filtered = if (filterAlpha >= 1f) {
                filterState = raw
                raw
            } else {
                filterState += filterAlpha * (raw - filterState)
                filterState
            }

            var releaseEnvelope = 1f
            if (releaseFramesRemaining >= 0) {
                releaseEnvelope = releaseFramesRemaining.toFloat() / releaseFramesTotal
                releaseFramesRemaining--
                if (releaseFramesRemaining <= 0) finished = true
            }

            cursor.advance(sourceStep)
            if (cursor.finished) finished = true
            return filtered * gain * boundaryEnvelope * releaseEnvelope
        }

        private fun sourceStepFor(pad: PadSnapshot, outputSampleRate: Int): Double {
            return SamplerDspPrimitives.sourceStep(
                pitchSemitones = pad.pitchSemitones,
                sourceSampleRate = pad.audio.sampleRate,
                outputSampleRate = outputSampleRate,
            )
        }

        private companion object {
            val EMPTY_SAMPLES = ShortArray(0)
        }
    }

    private class ScratchVoice(
        pad: PadSnapshot,
        initialFrame: Int,
    ) {
        private val samples = pad.audio.samples
        private val startFrame = pad.startFrame
        private val endFrame = pad.endFrame
        private val sourceSampleRate = pad.audio.sampleRate
        private val gain = pad.gain
        private val cursor = ScratchPlaybackCursor(startFrame, endFrame, initialFrame.toDouble())
        private val speedSmoother = ScratchSpeedSmoother(SCRATCH_SMOOTHING)

        val currentFrame: Int
            get() = cursor.position.toInt().coerceIn(startFrame, endFrame - 1)

        fun render(outputSampleRate: Int, targetSpeed: Float): Float {
            val smoothedSpeed = speedSmoother.next(targetSpeed)
            val lower = floor(cursor.position).toInt().coerceIn(startFrame, endFrame - 1)
            val upper = (lower + 1).let { if (it >= endFrame) startFrame else it }
            val fraction = (cursor.position - lower).toFloat()
            val lowerSample = samples[lower] / 32_768f
            val upperSample = samples[upper] / 32_768f
            val interpolated = lowerSample + (upperSample - lowerSample) * fraction
            val motionEnvelope = (abs(smoothedSpeed) * 2.5).coerceIn(0.0, 1.0).toFloat()
            cursor.advance(smoothedSpeed * sourceSampleRate / outputSampleRate.toDouble())
            return interpolated * gain * motionEnvelope
        }
    }

    private companion object {
        const val COMMAND_CAPACITY = 512
        const val MAX_COMMANDS_PER_BLOCK = 64
        const val COMMAND_OVERFLOW_RESET_SIZE = COMMAND_CAPACITY / 2
        const val MAX_POLYPHONY = 32
        const val RELEASE_FRAMES = 192
        const val FAST_RELEASE_FRAMES = 48
        const val SOURCE_PAD_INDEX = -2
        const val SOURCE_SCRATCH_PAD_INDEX = -3
        const val SCRATCH_SMOOTHING = 0.025
    }
}

/** Mixes the sample returned by a pooled voice before retiring its finished slot. */
internal fun mixVoiceSampleAndRetire(
    voice: SamplerEngine.Voice,
    outputSampleRate: Int,
    monoMix: Float,
): Float {
    val mixed = monoMix + voice.render(outputSampleRate)
    if (voice.finished) voice.deactivate()
    return mixed
}

/**
 * A loop replaces only accidental audition voices. Other PAD voices are intentional layers.
 * This runs on the audio thread and must stay allocation-free.
 */
internal fun retireConflictingVoicesForLoopStart(
    voices: Array<SamplerEngine.Voice>,
    loopPadIndex: Int,
) {
    var voiceIndex = 0
    while (voiceIndex < voices.size) {
        val voice = voices[voiceIndex]
        if (
            voice.active &&
            (voice.padIndex == loopPadIndex || voice.padIndex == PREVIEW_PAD_INDEX)
        ) {
            voice.deactivate()
        }
        voiceIndex++
    }
}

/** Applies the source stop generation at the same audio-thread boundary as loop start. */
internal fun retireSourceVoiceForLoopStart(
    sourcePlaybackState: SourcePlaybackState,
    sourceVoice: SamplerEngine.Voice,
    stopGeneration: Long,
): Boolean {
    if (!sourcePlaybackState.applyStopBoundary(stopGeneration)) return false
    sourceVoice.deactivate()
    return true
}
