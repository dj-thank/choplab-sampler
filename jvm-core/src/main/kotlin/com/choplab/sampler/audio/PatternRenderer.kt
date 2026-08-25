package com.choplab.sampler.audio

import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.samePadVoiceConflictsForRetrigger
import com.choplab.sampler.model.stepKey
import java.io.File
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

data class PatternRenderSummary(
    val bars: Int,
    val sampleRate: Int,
    val frameCount: Int,
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
    ): PatternRenderSummary {
        require(pads.size == SamplerConfig.PAD_COUNT) { "Expected ${SamplerConfig.PAD_COUNT} pads" }
        require(bars in 1..64) { "bars must be between 1 and 64" }
        require(outputSampleRate in 8_000..192_000) { "Unsupported output sample rate" }

        val safeBpm = SamplerDspPrimitives.bpm(bpm)
        val safeSwing = SamplerDspPrimitives.swing(swing)
        val stepStarts = calculateStepStarts(outputSampleRate, safeBpm, safeSwing)
        val barFrames = ceil(stepStarts.last()).toInt().coerceAtLeast(1)
        val totalFrames = barFrames * bars

        val events = HashMap<Int, MutableList<PadSnapshot>>()
        pads.asSequence()
            .filter { it.playMode == PadPlayMode.LOOP }
            .mapNotNull(PadSnapshot::from)
            .forEach { loop -> events.getOrPut(0) { mutableListOf() } += loop }
        pads.asSequence()
            .filter { it.contentKind == PadContentKind.VOCAL && it.playMode != PadPlayMode.LOOP }
            .mapNotNull(PadSnapshot::from)
            .forEach { vocal -> events.getOrPut(0) { mutableListOf() } += vocal }
        repeat(bars) { bar ->
            val barOffset = bar * barFrames
            repeat(SamplerConfig.STEP_COUNT) { step ->
                val eventFrame = (barOffset + stepStarts[step]).toInt().coerceIn(0, totalFrames - 1)
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

        val voices = mutableListOf<OfflineVoice>()
        val pcmBuffer = ShortArray(BUFFER_FRAMES)
        var bufferIndex = 0
        var peak = 0f

        WavFileWriter(outputFile, outputSampleRate, 1).use { writer ->
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

                var mix = 0f
                var voiceIndex = 0
                while (voiceIndex < voices.size) {
                    val voice = voices[voiceIndex]
                    val value = voice.render()
                    mix += value
                    if (voice.finished) {
                        voices.removeAt(voiceIndex)
                    } else {
                        voiceIndex++
                    }
                }

                val limited = SamplerDspPrimitives.softLimit(mix)
                peak = maxOf(peak, abs(limited))
                pcmBuffer[bufferIndex++] = (limited.coerceIn(-1f, 1f) * Short.MAX_VALUE)
                    .toInt()
                    .toShort()

                if (bufferIndex == pcmBuffer.size) {
                    writer.writePcm16(pcmBuffer, bufferIndex)
                    bufferIndex = 0
                }
            }
            if (bufferIndex > 0) writer.writePcm16(pcmBuffer, bufferIndex)
        }

        return PatternRenderSummary(
            bars = bars,
            sampleRate = outputSampleRate,
            frameCount = totalFrames,
            peak = peak,
        )
    }

    private fun calculateStepStarts(
        sampleRate: Int,
        bpm: Float,
        swing: Float,
    ): DoubleArray {
        val starts = DoubleArray(SamplerConfig.STEP_COUNT + 1)
        repeat(SamplerConfig.STEP_COUNT) { step ->
            val length = SamplerDspPrimitives.stepLengthFrames(sampleRate, bpm, swing, step)
            starts[step + 1] = starts[step] + length
        }
        return starts
    }

    private data class PadSnapshot(
        val padIndex: Int,
        val audioSamples: ShortArray,
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
                val start = pad.startFrame.coerceIn(0, audio.samples.lastIndex)
                val end = pad.endFrame.coerceIn(start + 1, audio.samples.size)
                return PadSnapshot(
                    padIndex = pad.globalIndex,
                    audioSamples = audio.samples,
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
        private val samples = pad.audioSamples
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
        private var filterState = 0f
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

        fun render(): Float {
            if (finished) {
                finished = true
                return 0f
            }
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
    }

    private const val BUFFER_FRAMES = 4_096
    private const val MAX_POLYPHONY = 32
    private const val FAST_RELEASE_FRAMES = 48
}
