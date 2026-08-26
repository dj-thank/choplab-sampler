package com.choplab.desktop.audio

import com.choplab.sampler.audio.PadPcmRenderer
import com.choplab.sampler.audio.RenderedPcm
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.samePadVoiceConflictsForRetrigger
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent

internal fun interface DesktopClipFactory {
    fun create(): Clip
}

internal fun pcm16AudioInputStream(
    samples: ShortArray,
    sampleRate: Int,
    channelCount: Int,
): AudioInputStream {
    require(samples.isNotEmpty()) { "再生するPCMがありません" }
    require(channelCount in 1..2 && samples.size % channelCount == 0) {
        "再生PCMのチャンネル構成が不正です"
    }
    val bytes = ByteArray(samples.size * Short.SIZE_BYTES)
    for (index in samples.indices) {
        val sample = samples[index].toInt()
        bytes[index * 2] = (sample and 0xFF).toByte()
        bytes[index * 2 + 1] = (sample shr 8).toByte()
    }
    val format = AudioFormat(sampleRate.toFloat(), 16, channelCount, true, false)
    return AudioInputStream(
        ByteArrayInputStream(bytes),
        format,
        (samples.size / channelCount).toLong(),
    )
}

/**
 * Prepares a newly owned resource and abandons it if preparation fails.
 */
internal fun <T> prepareCandidateOrAbandon(
    candidate: T,
    prepareCandidate: (T) -> Unit,
    abandonCandidate: (T) -> Unit,
): T {
    try {
        prepareCandidate(candidate)
    } catch (failure: Throwable) {
        try {
            abandonCandidate(candidate)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
    return candidate
}

/**
 * Starts a prepared replacement before retiring voices it supersedes.
 * The candidate owner must make both cleanup callbacks idempotent.
 */
internal fun <T> startReplacementBeforeRetiringConflicts(
    candidate: T,
    conflicts: List<T>,
    startCandidate: (T) -> Unit,
    abandonCandidate: (T) -> Unit,
    retireConflict: (T) -> Unit,
) {
    try {
        startCandidate(candidate)
    } catch (failure: Throwable) {
        try {
            abandonCandidate(candidate)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
    conflicts.forEach(retireConflict)
}

/**
 * Java Sound engine for the Windows deck.
 *
 * Source playback and PAD voices have separate ownership so a PAD hit never
 * destroys the loaded source. PAD PCM is rendered with the shared JVM voice
 * controls before it reaches a Clip.
 */
class JavaSoundWavPlayer internal constructor(
    private val clipFactory: DesktopClipFactory,
) : DesktopSamplerAudioEngine {
    constructor() : this(DesktopClipFactory { AudioSystem.getClip() })

    private var sourceClip: Clip? = null
    private var sourceOriginalFrames: Int = 0
    private var nextVoiceOwnership: Long = 0L
    private val activeVoices = mutableListOf<ActiveVoice>()

    override val isSourcePlaying: Boolean
        @Synchronized get() = sourceClip?.isRunning == true

    @Synchronized
    override fun loadPcm(audio: PcmAudio, pitchSemitones: Float) {
        val rendered = if (pitchSemitones == 0f) {
            RenderedPcm(audio.samples.copyOf(), audio.channelCount)
        } else {
            PadPcmRenderer.renderInterleaved(
                PadModel(
                    globalIndex = 0,
                    audio = audio,
                    startFrame = 0,
                    endFrame = audio.frameCount,
                    pitchSemitones = pitchSemitones,
                    gain = 1f,
                ),
            )
        }
        replaceSource(createClip(rendered.samples, audio.sampleRate, rendered.channelCount))
        sourceOriginalFrames = audio.frameCount
    }

    @Synchronized
    override fun playFrom(frame: Int) {
        val active = sourceClip ?: error("先に音声を読み込んでください")
        active.stop()
        active.framePosition = sourceToRenderedFrame(frame, active)
        active.start()
    }

    @Synchronized
    override fun seekSource(frame: Int) {
        sourceClip?.let { it.framePosition = sourceToRenderedFrame(frame, it) }
    }

    @Synchronized
    override fun sourceFramePosition(): Int {
        val clip = sourceClip ?: return 0
        if (clip.frameLength <= 0 || sourceOriginalFrames <= 0) return 0
        return (clip.framePosition.toDouble() / clip.frameLength * sourceOriginalFrames)
            .toInt()
            .coerceIn(0, sourceOriginalFrames)
    }

    @Synchronized
    override fun padFramePosition(index: Int): Int? {
        val voice = activeVoices.lastOrNull { it.pad.globalIndex == index && it.mode == PadPlayMode.LOOP }
            ?: return null
        val clipLength = voice.clip.frameLength.coerceAtLeast(1)
        val progress = voice.clip.framePosition.toDouble() / clipLength
        val sourceLength = voice.pad.endFrame - voice.pad.startFrame
        return if (voice.pad.reverse) {
            (voice.pad.endFrame - 1 - progress * sourceLength).toInt()
                .coerceIn(voice.pad.startFrame, voice.pad.endFrame - 1)
        } else {
            (voice.pad.startFrame + progress * sourceLength).toInt()
                .coerceIn(voice.pad.startFrame, voice.pad.endFrame - 1)
        }
    }

    @Synchronized
    override fun triggerPad(pad: PadModel, forceLoop: Boolean): Long {
        if (!pad.isAssigned) return 0L
        val conflicts = activeVoices
            .filter { voice ->
                samePadVoiceConflictsForRetrigger(voice.pad.globalIndex, pad.globalIndex) ||
                    (pad.chokeGroup > 0 && voice.pad.chokeGroup == pad.chokeGroup)
            }
            .toList()
        val audio = requireNotNull(pad.audio)
        val mode = if (forceLoop) PadPlayMode.LOOP else pad.playMode
        val renderingPad = if (pad.playMode == mode) pad else pad.copy(playMode = mode)
        val rendered = PadPcmRenderer.renderInterleaved(renderingPad)
        val clip = createClip(rendered.samples, audio.sampleRate, rendered.channelCount)
        val voice = ActiveVoice(pad, mode, acquireVoiceOwnership(), clip)
        activeVoices += voice
        try {
            clip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP && clip.framePosition >= clip.frameLength) {
                    synchronized(this) { closeVoice(voice) }
                }
            }
        } catch (failure: Throwable) {
            closeVoice(voice)
            throw failure
        }
        startReplacementBeforeRetiringConflicts(
            candidate = voice,
            conflicts = conflicts,
            startCandidate = { replacement ->
                if (replacement.mode == PadPlayMode.LOOP) {
                    replacement.clip.loop(Clip.LOOP_CONTINUOUSLY)
                } else {
                    replacement.clip.start()
                }
            },
            abandonCandidate = ::closeVoice,
            retireConflict = ::closeVoice,
        )
        return voice.ownership
    }

    @Synchronized
    override fun releasePad(index: Int) {
        activeVoices
            .filter { it.pad.globalIndex == index && it.mode == PadPlayMode.GATE }
            .toList()
            .forEach(::closeVoice)
    }

    @Synchronized
    override fun releasePadIfOwned(index: Int, ownership: Long) {
        activeVoices
            .filter {
                it.pad.globalIndex == index &&
                    it.mode == PadPlayMode.GATE &&
                    it.ownership == ownership
            }
            .toList()
            .forEach(::closeVoice)
    }

    @Synchronized
    override fun stopPad(index: Int) {
        activeVoices.filter { it.pad.globalIndex == index }.toList().forEach(::closeVoice)
    }

    @Synchronized
    override fun stop() {
        sourceClip?.stop()
    }

    @Synchronized
    override fun stopAll() {
        sourceClip?.stop()
        activeVoices.toList().forEach(::closeVoice)
    }

    @Synchronized
    override fun close() {
        stopAll()
        sourceClip?.close()
        sourceClip = null
        sourceOriginalFrames = 0
    }

    private fun createClip(samples: ShortArray, sampleRate: Int, channelCount: Int): Clip {
        val clip = clipFactory.create()
        return prepareCandidateOrAbandon(
            candidate = clip,
            prepareCandidate = { candidate ->
                pcm16AudioInputStream(samples, sampleRate, channelCount).use(candidate::open)
            },
            abandonCandidate = Clip::close,
        )
    }

    private fun replaceSource(newClip: Clip) {
        sourceClip?.stop()
        sourceClip?.close()
        sourceClip = newClip
    }

    private fun sourceToRenderedFrame(sourceFrame: Int, clip: Clip): Int {
        if (sourceOriginalFrames <= 0) return 0
        val progress = sourceFrame.coerceIn(0, sourceOriginalFrames).toDouble() / sourceOriginalFrames
        return (progress * clip.frameLength).toInt().coerceIn(0, clip.frameLength)
    }

    private fun acquireVoiceOwnership(): Long {
        do {
            nextVoiceOwnership = if (nextVoiceOwnership == Long.MAX_VALUE) 1L else nextVoiceOwnership + 1L
        } while (activeVoices.any { it.ownership == nextVoiceOwnership })
        return nextVoiceOwnership
    }

    private fun closeVoice(voice: ActiveVoice) {
        if (!activeVoices.remove(voice)) return
        runCatching { voice.clip.stop() }
        runCatching { voice.clip.close() }
    }

    private data class ActiveVoice(
        val pad: PadModel,
        val mode: PadPlayMode,
        val ownership: Long,
        val clip: Clip,
    )
}

internal fun renderDesktopPadPcm(pad: PadModel, mode: PadPlayMode): ShortArray {
    val renderingPad = if (pad.playMode == mode) pad else pad.copy(playMode = mode)
    return PadPcmRenderer.renderInterleaved(renderingPad).samples
}
