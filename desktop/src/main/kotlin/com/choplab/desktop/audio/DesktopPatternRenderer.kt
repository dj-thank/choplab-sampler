package com.choplab.desktop.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.stepKey
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow

/** Offline four-bar renderer matching the Android sampler's core voice rules. */
object DesktopPatternRenderer {
    fun renderFourBars(file: File, pads: List<PadModel>, steps: Set<Int>, bpm: Float, swing: Float) {
        require(pads.size == SamplerConfig.PAD_COUNT)
        val sampleRate = 48_000
        val safeBpm = bpm.coerceIn(40f, 240f)
        val safeSwing = swing.coerceIn(50f, 75f)
        val starts = DoubleArray(SamplerConfig.STEP_COUNT + 1)
        val straight = sampleRate * 60.0 / safeBpm / 4.0
        repeat(SamplerConfig.STEP_COUNT) { step ->
            val ratio = if (step % 2 == 0) safeSwing / 50.0 else 2.0 - safeSwing / 50.0
            starts[step + 1] = starts[step] + straight * ratio
        }
        val barFrames = ceil(starts.last()).toInt().coerceAtLeast(1)
        val totalFrames = barFrames * 4
        val events = HashMap<Int, MutableList<Voice>>()
        repeat(4) { bar ->
            repeat(SamplerConfig.STEP_COUNT) { step ->
                val frame = (bar * barFrames + starts[step]).toInt().coerceIn(0, totalFrames - 1)
                repeat(SamplerConfig.PAD_COUNT) { padIndex ->
                    if (stepKey(padIndex, step) in steps) pads[padIndex].toVoice()?.let { events.getOrPut(frame) { mutableListOf() } += it }
                }
            }
        }
        val active = mutableListOf<VoiceState>()
        val buffer = ShortArray(4_096)
        var buffered = 0
        DesktopWavFileWriter(file, sampleRate).use { writer ->
            repeat(totalFrames) { frame ->
                events[frame].orEmpty().forEach { voice ->
                    active.filter { it.voice.chokeGroup > 0 && it.voice.chokeGroup == voice.chokeGroup }.forEach { it.release = 48 }
                    if (active.size >= 32) active.removeAt(0)
                    active += VoiceState(voice)
                }
                var mix = 0f
                var index = 0
                while (index < active.size) {
                    val voice = active[index]
                    val value = voice.next(sampleRate)
                    if (voice.finished) active.removeAt(index) else { mix += value; index++ }
                }
                buffer[buffered++] = (mix / (1f + abs(mix))).coerceIn(-1f, 1f).times(Short.MAX_VALUE).toInt().toShort()
                if (buffered == buffer.size) {
                    writer.writePcm16(buffer)
                    buffered = 0
                }
            }
            if (buffered > 0) writer.writePcm16(buffer, buffered)
        }
    }

    private fun PadModel.toVoice(): Voice? {
        val source = audio ?: return null
        if (!isAssigned || source.samples.isEmpty()) return null
        return Voice(source.samples, source.sampleRate, startFrame.coerceIn(0, source.samples.lastIndex), endFrame.coerceIn(startFrame + 1, source.samples.size), pitchSemitones, tone, gain, reverse, playMode, chokeGroup)
    }

    private data class Voice(val samples: ShortArray, val sourceRate: Int, val start: Int, val end: Int, val pitch: Float, val tone: Float, val gain: Float, val reverse: Boolean, val mode: PadPlayMode, val chokeGroup: Int)
    private class VoiceState(val voice: Voice) {
        private var position = if (voice.reverse) voice.end - 1.0 else voice.start.toDouble()
        private var filter = 0f
        var release = -1
        var finished = false
        fun next(rate: Int): Float {
            if (finished) return 0f
            val lower = floor(position).toInt().coerceIn(voice.start, voice.end - 1)
            val upper = (lower + 1).coerceAtMost(voice.end - 1)
            val fraction = (position - lower).toFloat()
            val raw = voice.samples[lower] / 32_768f + (voice.samples[upper] - voice.samples[lower]) / 32_768f * fraction
            val filtered = if (voice.tone >= .995f) raw else {
                val cutoff = 80.0 * 225.0.pow(voice.tone.toDouble())
                filter += (1.0 - exp(-2.0 * PI * cutoff / rate)).toFloat() * (raw - filter)
                filter
            }
            val distance = minOf(
                if (voice.reverse) voice.end - 1.0 - position else position - voice.start,
                if (voice.reverse) position - voice.start else voice.end - 1.0 - position,
            )
            val fade = (distance / 48.0).coerceIn(0.0, 1.0).toFloat()
            val releaseGain = if (release >= 0) (release-- / 48f).also { if (release <= 0) finished = true } else 1f
            val advance = 2.0.pow(voice.pitch.toDouble() / 12.0) * voice.sourceRate / rate
            position += if (voice.reverse) -advance else advance
            if ((!voice.reverse && position >= voice.end) || (voice.reverse && position < voice.start)) {
                if (voice.mode == PadPlayMode.LOOP) position = if (voice.reverse) voice.end - 1.0 else voice.start.toDouble() else finished = true
            }
            return filtered * voice.gain * fade * releaseGain
        }
    }
}
