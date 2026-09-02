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
    try {
        conflicts.forEach(retireConflict)
    } catch (failure: Throwable) {
        // The candidate is already audible. If retirement cannot complete, fail closed by
        // abandoning the new owner before reporting the old-owner failure.
        try {
            abandonCandidate(candidate)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
        throw failure
    }
}

/**
 * Preserves sequential CHOKE ownership without letting a companion immediately
 * silence the selected loop owner.
 */
internal fun exclusiveLoopCompanionPads(
    loopPad: PadModel,
    companionPads: List<PadModel>,
): List<PadModel> {
    val ownerChokeGroup = loopPad.chokeGroup.takeIf { it > 0 }
    val candidates = companionPads
        .asSequence()
        .filter(PadModel::isAssigned)
        .filter { pad -> ownerChokeGroup == null || pad.chokeGroup != ownerChokeGroup }
        .distinctBy(PadModel::globalIndex)
        .toList()
    val finalOwnerByChoke = candidates
        .withIndex()
        .filter { it.value.chokeGroup > 0 }
        .associate { it.value.chokeGroup to it.index }
    return candidates.filterIndexed { index, pad ->
        pad.chokeGroup <= 0 || finalOwnerByChoke[pad.chokeGroup] == index
    }
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
    private val candidateVoices = mutableListOf<ActiveVoice>()
    private val pendingVoices = mutableListOf<PreparedVoice>()

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
        val voice = prepareVoice(pad, forceLoop)
        startReplacementBeforeRetiringConflicts(
            candidate = voice,
            conflicts = conflicts,
            startCandidate = ::startVoice,
            abandonCandidate = ::closeVoice,
            retireConflict = ::closeVoice,
        )
        return voice.ownership
    }

    override fun prepareExclusiveLoopSession(
        loopPad: PadModel,
        companionPads: List<PadModel>,
    ): DesktopPreparedLoopSession {
        require(loopPad.isAssigned) { "Beat loop PAD has no audio" }
        val requests = buildList {
            add(LoopSessionRequest(loopPad, forceLoop = true))
            exclusiveLoopCompanionPads(loopPad, companionPads)
                .asSequence()
                .filter { it.globalIndex != loopPad.globalIndex }
                .forEach { add(LoopSessionRequest(it, forceLoop = false)) }
        }
        val prepared = ArrayList<PreparedVoice>(requests.size)
        try {
            requests.forEach { request -> prepared += prepareDetachedVoice(request.pad, request.forceLoop) }
        } catch (failure: Throwable) {
            val cleanupFailure = abandonPreparedVoices(prepared)
            if (cleanupFailure != null) {
                cleanupFailure.addSuppressed(failure)
                throw cleanupFailure
            }
            if (failure is Exception) throw DesktopLoopSessionStartupException(failure)
            throw failure
        }
        return PreparedLoopSession(prepared)
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
        var failure: Throwable? = null
        try {
            sourceClip?.stop()
        } catch (stopFailure: Throwable) {
            failure = appendFailure(failure, stopFailure)
        }
        activeVoices.toList().forEach { voice ->
            try {
                closeVoice(voice)
            } catch (closeFailure: Throwable) {
                failure = appendFailure(failure, closeFailure)
            }
        }
        candidateVoices.toList().forEach { voice ->
            try {
                closeVoice(voice)
            } catch (closeFailure: Throwable) {
                failure = appendFailure(failure, closeFailure)
            }
        }
        pendingVoices.toList().forEach { voice ->
            try {
                closePreparedVoice(voice)
            } catch (closeFailure: Throwable) {
                failure = appendFailure(failure, closeFailure)
            }
        }
        failure?.let { throw it }
    }

    @Synchronized
    override fun close() {
        var failure = runCatching(::stopAll).exceptionOrNull()
        try {
            sourceClip?.close()
            sourceClip = null
            sourceOriginalFrames = 0
        } catch (closeFailure: Throwable) {
            failure = appendFailure(failure, closeFailure)
        }
        failure?.let { throw it }
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

    private fun prepareVoice(pad: PadModel, forceLoop: Boolean): ActiveVoice =
        publishPreparedVoice(prepareDetachedVoice(pad, forceLoop), activeVoices)

    private fun prepareDetachedVoice(pad: PadModel, forceLoop: Boolean): PreparedVoice {
        val audio = requireNotNull(pad.audio)
        val mode = if (forceLoop) PadPlayMode.LOOP else pad.playMode
        val renderingPad = if (pad.playMode == mode) pad else pad.copy(playMode = mode)
        val rendered = PadPcmRenderer.renderInterleaved(renderingPad)
        val clip = createClip(rendered.samples, audio.sampleRate, rendered.channelCount)
        val prepared = PreparedVoice(pad, mode, clip)
        synchronized(this) { pendingVoices += prepared }
        return prepared
    }

    private fun publishPreparedVoice(
        prepared: PreparedVoice,
        destination: MutableList<ActiveVoice>,
    ): ActiveVoice {
        check(pendingVoices.remove(prepared)) { "Prepared voice is no longer owned by this player" }
        val voice = ActiveVoice(prepared.pad, prepared.mode, acquireVoiceOwnership(), prepared.clip)
        destination += voice
        try {
            voice.clip.addLineListener { event ->
                if (event.type == LineEvent.Type.STOP && voice.clip.framePosition >= voice.clip.frameLength) {
                    synchronized(this) { closeVoice(voice) }
                }
            }
        } catch (failure: Throwable) {
            try {
                closeVoice(voice)
            } catch (cleanupFailure: Throwable) {
                cleanupFailure.addSuppressed(failure)
                throw cleanupFailure
            }
            throw failure
        }
        return voice
    }

    private fun startPreparedLoopCandidates(prepared: List<PreparedVoice>): DesktopStartedLoopSession {
        val candidates = ArrayList<ActiveVoice>(prepared.size)
        try {
            synchronized(this) {
                prepared.forEach { candidates += publishPreparedVoice(it, candidateVoices) }
            }
            candidates.forEach(::startVoice)
            synchronized(this) {
                check(candidates.all { it in candidateVoices }) {
                    "A started loop candidate completed before startup finished"
                }
            }
        } catch (failure: Throwable) {
            val cleanupFailure = abandonCandidateSession(candidates, prepared)
            if (cleanupFailure != null) {
                cleanupFailure.addSuppressed(failure)
                throw cleanupFailure
            }
            if (failure is Exception) throw DesktopLoopSessionStartupException(failure)
            throw failure
        }
        return StartedLoopSession(candidates)
    }

    @Synchronized
    private fun retirePriorPlayback(candidates: List<ActiveVoice>) {
        check(candidates.all { it in candidateVoices }) {
            "A started loop candidate completed before the handoff"
        }
        val priorVoices = activeVoices.toList()
        sourceClip?.stop()
        priorVoices.forEach(::closeVoice)
        candidateVoices.removeAll(candidates.toSet())
        activeVoices.addAll(candidates)
    }

    private fun startVoice(voice: ActiveVoice) {
        if (voice.mode == PadPlayMode.LOOP) {
            voice.clip.loop(Clip.LOOP_CONTINUOUSLY)
        } else {
            voice.clip.start()
        }
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
        } while (
            activeVoices.any { it.ownership == nextVoiceOwnership } ||
            candidateVoices.any { it.ownership == nextVoiceOwnership }
        )
        return nextVoiceOwnership
    }

    @Synchronized
    private fun closeVoice(voice: ActiveVoice) {
        if (voice !in activeVoices && voice !in candidateVoices) return
        var stopFailure: Throwable? = null
        try {
            voice.clip.stop()
        } catch (failure: Throwable) {
            stopFailure = failure
        }
        try {
            voice.clip.close()
        } catch (closeFailure: Throwable) {
            stopFailure?.let(closeFailure::addSuppressed)
            throw closeFailure
        }
        activeVoices.remove(voice)
        candidateVoices.remove(voice)
    }

    @Synchronized
    private fun closePreparedVoice(voice: PreparedVoice) {
        if (voice !in pendingVoices) return
        voice.clip.close()
        pendingVoices.remove(voice)
    }

    private fun abandonPreparedVoices(voices: List<PreparedVoice>): Throwable? {
        var failure: Throwable? = null
        voices.forEach { voice ->
            try {
                closePreparedVoice(voice)
            } catch (cleanupFailure: Throwable) {
                failure = appendFailure(failure, cleanupFailure)
            }
        }
        return failure
    }

    private fun abandonCandidateSession(
        activeCandidates: List<ActiveVoice>,
        prepared: List<PreparedVoice>,
    ): Throwable? {
        var failure: Throwable? = null
        activeCandidates.forEach { voice ->
            try {
                closeVoice(voice)
            } catch (cleanupFailure: Throwable) {
                failure = appendFailure(failure, cleanupFailure)
            }
        }
        prepared.forEach { voice ->
            try {
                closePreparedVoice(voice)
            } catch (cleanupFailure: Throwable) {
                failure = appendFailure(failure, cleanupFailure)
            }
        }
        return failure
    }

    private fun appendFailure(current: Throwable?, next: Throwable): Throwable =
        current?.also { it.addSuppressed(next) } ?: next

    private inner class PreparedLoopSession(
        private val prepared: List<PreparedVoice>,
    ) : DesktopPreparedLoopSession {
        private var resolved = false

        override fun startCandidates(): DesktopStartedLoopSession {
            synchronized(this@JavaSoundWavPlayer) {
                check(!resolved) { "Prepared loop session was already resolved" }
                resolved = true
            }
            return startPreparedLoopCandidates(prepared)
        }
    }

    private inner class StartedLoopSession(
        private val candidates: List<ActiveVoice>,
    ) : DesktopStartedLoopSession {
        private var resolved = false

        override fun retirePriorPlayback() {
            synchronized(this@JavaSoundWavPlayer) {
                check(!resolved) { "Started loop session was already resolved" }
                retirePriorPlayback(candidates)
                resolved = true
            }
        }

        override fun abandonCandidates() {
            synchronized(this@JavaSoundWavPlayer) {
                check(!resolved) { "Started loop session was already resolved" }
                val cleanupFailure = abandonCandidateSession(candidates, emptyList())
                resolved = true
                if (cleanupFailure != null) throw cleanupFailure
            }
        }
    }

    private data class PreparedVoice(
        val pad: PadModel,
        val mode: PadPlayMode,
        val clip: Clip,
    )

    private data class ActiveVoice(
        val pad: PadModel,
        val mode: PadPlayMode,
        val ownership: Long,
        val clip: Clip,
    )

    private data class LoopSessionRequest(
        val pad: PadModel,
        val forceLoop: Boolean,
    )
}

internal fun renderDesktopPadPcm(pad: PadModel, mode: PadPlayMode): ShortArray {
    val renderingPad = if (pad.playMode == mode) pad else pad.copy(playMode = mode)
    return PadPcmRenderer.renderInterleaved(renderingPad).samples
}
