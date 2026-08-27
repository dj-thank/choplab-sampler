package com.choplab.sampler.audio

import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.samePadVoiceConflictsForRetrigger
import com.choplab.sampler.model.stepKey
import com.choplab.sampler.model.vocalCompanionPadIndicesForLoopStart
import java.io.File
import kotlin.math.abs
import kotlin.math.floor

internal fun calculateContinuousPatternEventFrames(
    sampleRate: Int,
    bpm: Float,
    swing: Float,
    bars: Int,
): IntArray = calculateContinuousPatternBoundaries(sampleRate, bpm, swing, bars)
    .copyOf(SamplerConfig.STEP_COUNT * bars)

private fun calculateContinuousPatternBoundaries(
    sampleRate: Int,
    bpm: Float,
    swing: Float,
    bars: Int,
): IntArray {
    require(bars in 1..64) { "bars must be between 1 and 64" }
    val safeBpm = SamplerDspPrimitives.bpm(bpm)
    val safeSwing = SamplerDspPrimitives.swing(swing)
    val eventCount = SamplerConfig.STEP_COUNT * bars
    val boundaries = IntArray(eventCount + 1)
    var frame = 0
    var framesUntilNextStep = 0.0
    repeat(eventCount) { eventIndex ->
        boundaries[eventIndex] = frame
        framesUntilNextStep += SamplerDspPrimitives.stepLengthFrames(
            sampleRate = sampleRate,
            bpm = safeBpm,
            swing = safeSwing,
            step = eventIndex % SamplerConfig.STEP_COUNT,
        )
        val framesToAdvance = SamplerDspPrimitives.scheduledFrameAtOrAfter(framesUntilNextStep)
            .coerceAtLeast(1)
        framesUntilNextStep -= framesToAdvance
        frame += framesToAdvance
    }
    boundaries[eventCount] = frame.coerceAtLeast(1)
    return boundaries
}

data class PatternRenderSummary(
    val bars: Int,
    val sampleRate: Int,
    val frameCount: Int,
    val channelCount: Int,
    val peak: Float,
) {
    val durationSeconds: Double
        get() = frameCount.toDouble() / sampleRate
}

interface PatternRenderService {
    fun renderToWav(
        outputFile: File,
        pads: List<PadModel>,
        activeSteps: Set<Int>,
        bpm: Float,
        swing: Float,
        bars: Int = 4,
        outputSampleRate: Int = 48_000,
    ): PatternRenderSummary
}

/**
 * Offline renderer for a 16-step pattern. It mirrors the real-time engine's
 * pitch resampling, tone filter, gain, reverse, choke groups, and soft limiter.
 */
object PatternRenderer : PatternRenderService {
    override fun renderToWav(
        outputFile: File,
        pads: List<PadModel>,
        activeSteps: Set<Int>,
        bpm: Float,
        swing: Float,
        bars: Int,
        outputSampleRate: Int,
    ): PatternRenderSummary = renderSequenceToWav(
        outputFile = outputFile,
        pads = pads,
        patternSequence = List(bars) { activeSteps },
        bpm = bpm,
        swing = swing,
        outputSampleRate = outputSampleRate,
    )

    fun renderSequenceToWav(
        outputFile: File,
        pads: List<PadModel>,
        patternSequence: List<Set<Int>>,
        bpm: Float,
        swing: Float,
        outputSampleRate: Int = 48_000,
    ): PatternRenderSummary {
        require(pads.size == SamplerConfig.PAD_COUNT) { "Expected ${SamplerConfig.PAD_COUNT} pads" }
        require(patternSequence.size in 1..64) { "pattern sequence must contain between 1 and 64 bars" }
        require(outputSampleRate in 8_000..192_000) { "Unsupported output sample rate" }

        val safeBpm = SamplerDspPrimitives.bpm(bpm)
        val safeSwing = SamplerDspPrimitives.swing(swing)
        val boundaries = calculateContinuousPatternBoundaries(
            sampleRate = outputSampleRate,
            bpm = safeBpm,
            swing = safeSwing,
            bars = patternSequence.size,
        )
        val totalFrames = boundaries.last()

        val events = HashMap<Int, MutableList<PadSnapshot>>()
        pads.asSequence()
            .filter { it.playMode == PadPlayMode.LOOP }
            .mapNotNull(PadSnapshot::from)
            .forEach { loop -> events.getOrPut(0) { mutableListOf() } += loop }
        val frameZeroVocalPadIndices = frameZeroVocalPadIndicesForRender(pads)
        pads.asSequence()
            .filter { it.globalIndex in frameZeroVocalPadIndices }
            .mapNotNull(PadSnapshot::from)
            .forEach { vocal -> events.getOrPut(0) { mutableListOf() } += vocal }
        patternSequence.forEachIndexed { bar, activeSteps ->
            repeat(SamplerConfig.STEP_COUNT) { step ->
                val eventFrame = boundaries[bar * SamplerConfig.STEP_COUNT + step]
                    .coerceIn(0, totalFrames - 1)
                repeat(SamplerConfig.PAD_COUNT) { padIndex ->
                    if (
                        pads[padIndex].playMode != PadPlayMode.LOOP &&
                        pads[padIndex].contentKind != PadContentKind.VOCAL &&
                        stepKey(padIndex, step) in activeSteps
                    ) {
                        PadSnapshot.from(pads[padIndex])?.let { snapshot ->
                            events.getOrPut(eventFrame) { mutableListOf() } += snapshot
                        }
                    }
                }
            }
        }

        val outputChannelCount = events.values.asSequence()
            .flatten()
            .maxOfOrNull { snapshot -> snapshot.audio.channelCount }
            ?: 1
        val voices = mutableListOf<OfflineVoice>()
        val renderedFrame = MutableStereoFrame()
        val pcmBuffer = ShortArray(BUFFER_FRAMES * outputChannelCount)
        var bufferIndex = 0
        var peak = 0f

        WavFileWriter(outputFile, outputSampleRate, outputChannelCount).use { writer ->
            repeat(totalFrames) { frame ->
                events[frame]?.forEach { pad ->
                    // Match live playback: one PAD retriggers one voice, while
                    // different PADs remain polyphonic layers.
                    voices.removeAll { voice ->
                        samePadVoiceConflictsForRetrigger(voice.padIndex, pad.padIndex)
                    }
                    if (pad.chokeGroup > 0) {
                        voices
                            .filter { it.chokeGroup == pad.chokeGroup }
                            .forEach { it.release(FAST_RELEASE_FRAMES) }
                    }
                    while (voices.size >= MAX_POLYPHONY) voices.removeAt(0)
                    voices += OfflineVoice(pad, outputSampleRate)
                }

                var mixLeft = 0f
                var mixRight = 0f
                var voiceIndex = 0
                while (voiceIndex < voices.size) {
                    val voice = voices[voiceIndex]
                    voice.render(renderedFrame)
                    mixLeft += renderedFrame.left
                    mixRight += renderedFrame.right
                    if (voice.finished) {
                        voices.removeAt(voiceIndex)
                    } else {
                        voiceIndex++
                    }
                }

                val limitedLeft = SamplerDspPrimitives.softLimit(mixLeft)
                val limitedRight = SamplerDspPrimitives.softLimit(mixRight)
                peak = maxOf(peak, abs(limitedLeft), abs(limitedRight))
                pcmBuffer[bufferIndex++] = (limitedLeft.coerceIn(-1f, 1f) * Short.MAX_VALUE)
                    .toInt()
                    .toShort()
                if (outputChannelCount == 2) {
                    pcmBuffer[bufferIndex++] = (limitedRight.coerceIn(-1f, 1f) * Short.MAX_VALUE)
                        .toInt()
                        .toShort()
                }

                if (bufferIndex == pcmBuffer.size) {
                    writer.writePcm16(pcmBuffer, bufferIndex)
                    bufferIndex = 0
                }
            }
            if (bufferIndex > 0) writer.writePcm16(pcmBuffer, bufferIndex)
        }

        return PatternRenderSummary(
            bars = patternSequence.size,
            sampleRate = outputSampleRate,
            frameCount = totalFrames,
            channelCount = outputChannelCount,
            peak = peak,
        )
    }

    private data class PadSnapshot(
        val padIndex: Int,
        val audio: PcmAudio,
        val sourceSampleRate: Int,
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
            fun from(pad: PadModel): PadSnapshot? {
                val audio = pad.audio ?: return null
                if (!pad.isAssigned || audio.samples.isEmpty()) return null
                val start = pad.startFrame.coerceIn(0, audio.frameCount - 1)
                val end = pad.endFrame.coerceIn(start + 1, audio.frameCount)
                return PadSnapshot(
                    padIndex = pad.globalIndex,
                    audio = audio,
                    sourceSampleRate = audio.sampleRate,
                    startFrame = start,
                    endFrame = end,
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

    private class OfflineVoice(
        pad: PadSnapshot,
        outputSampleRate: Int,
    ) {
        val padIndex = pad.padIndex
        val chokeGroup = pad.chokeGroup
        private val audio = pad.audio
        private val startFrame = pad.startFrame
        private val endFrame = pad.endFrame
        private val reverse = pad.reverse
        private val playMode = pad.playMode
        private val gain = pad.gain
        private val tone = pad.tone
        private val sourceStep: Double
        private val filterAlpha: Float
        private val cursor = VoicePlaybackCursor(
            startFrame = startFrame,
            endFrame = endFrame,
            reverse = reverse,
            playMode = playMode,
        )
        private var filterStateLeft = 0f
        private var filterStateRight = 0f
        private var releaseFramesRemaining = -1
        private var releaseFramesTotal = 1

        var finished: Boolean = false
            private set

        init {
            sourceStep = SamplerDspPrimitives.sourceStep(
                pitchSemitones = pad.pitchSemitones,
                sourceSampleRate = pad.sourceSampleRate,
                outputSampleRate = outputSampleRate,
            )
            filterAlpha = SamplerDspPrimitives.toneFilterAlpha(tone, outputSampleRate)
        }

        fun release(frames: Int) {
            val safeFrames = frames.coerceAtLeast(1)
            if (releaseFramesRemaining < 0 || safeFrames < releaseFramesRemaining) {
                releaseFramesRemaining = safeFrames
                releaseFramesTotal = safeFrames
            }
        }

        fun render(destination: MutableStereoFrame) {
            if (finished) {
                destination.clear()
                return
            }
            val position = cursor.position

            val lower = floor(position).toInt().coerceIn(startFrame, endFrame - 1)
            val upper = (lower + 1).coerceAtMost(endFrame - 1)
            val fraction = (position - lower).toFloat()
            val lowerLeft = audio.playbackSampleAt(lower, 0) / 32_768f
            val upperLeft = audio.playbackSampleAt(upper, 0) / 32_768f
            val rawLeft = lowerLeft + (upperLeft - lowerLeft) * fraction
            val lowerRight = audio.playbackSampleAt(lower, 1) / 32_768f
            val upperRight = audio.playbackSampleAt(upper, 1) / 32_768f
            val rawRight = lowerRight + (upperRight - lowerRight) * fraction

            val boundaryEnvelope = SamplerDspPrimitives.boundaryEnvelope(
                position = position,
                startFrame = startFrame,
                endFrame = endFrame,
                reverse = reverse,
            )

            val filteredLeft: Float
            val filteredRight: Float
            if (filterAlpha >= 1f) {
                filterStateLeft = rawLeft
                filterStateRight = rawRight
                filteredLeft = rawLeft
                filteredRight = rawRight
            } else {
                filterStateLeft += filterAlpha * (rawLeft - filterStateLeft)
                filterStateRight += filterAlpha * (rawRight - filterStateRight)
                filteredLeft = filterStateLeft
                filteredRight = filterStateRight
            }

            var releaseEnvelope = 1f
            if (releaseFramesRemaining >= 0) {
                releaseEnvelope = releaseFramesRemaining.toFloat() / releaseFramesTotal
                releaseFramesRemaining--
                if (releaseFramesRemaining <= 0) finished = true
            }

            cursor.advance(sourceStep)
            if (cursor.finished) finished = true
            val envelope = gain * boundaryEnvelope * releaseEnvelope
            destination.set(filteredLeft * envelope, filteredRight * envelope)
        }
    }

    private const val BUFFER_FRAMES = 4_096
    private const val MAX_POLYPHONY = 32
    private const val FAST_RELEASE_FRAMES = 48
}

/**
 * Mirrors live loop-start ownership when one loop owner is unambiguous.
 *
 * With no loop or multiple assigned loops, export preserves the historical
 * all-vocal behavior instead of silently choosing an owner the live UI cannot
 * represent.
 */
internal fun frameZeroVocalPadIndicesForRender(pads: List<PadModel>): Set<Int> {
    val loopOwnerIndex = pads.asSequence()
        .filter { it.isAssigned && it.playMode == PadPlayMode.LOOP }
        .map(PadModel::globalIndex)
        .singleOrNull()
    val vocalIndices = if (loopOwnerIndex == null) {
        pads.asSequence()
            .filter { it.isAssigned && it.contentKind == PadContentKind.VOCAL && it.playMode != PadPlayMode.LOOP }
            .map(PadModel::globalIndex)
            .toList()
    } else {
        pads.vocalCompanionPadIndicesForLoopStart(loopOwnerIndex)
    }
    return vocalIndices.toCollection(linkedSetOf())
}
