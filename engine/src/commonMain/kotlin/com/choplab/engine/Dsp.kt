package com.choplab.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

internal fun smoothUnit(x: Double): Double {
    val t = x.coerceIn(0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)
}

/** Pure ADS stage; Voice holds the actual current level on note-off and applies its release. */
object AdsrEnvelope {
    fun heldLevel(ageFrames: Int, attackFrames: Int, decayFrames: Int, sustainLevel: Float): Double {
        require(ageFrames >= 0 && attackFrames >= 0 && decayFrames >= 0 && sustainLevel.isFinite() && sustainLevel >= 0f && sustainLevel <= 1f)
        if (attackFrames > 0 && ageFrames < attackFrames) return smoothUnit(ageFrames.toDouble() / attackFrames)
        val afterAttack = ageFrames.toLong() - attackFrames
        if (decayFrames > 0 && afterAttack < decayFrames) {
            return 1.0 + (sustainLevel - 1.0) * smoothUnit(afterAttack.toDouble() / decayFrames)
        }
        return sustainLevel.toDouble()
    }
}

/** Source-domain crossfade precedes pitch filtering. No source frames are removed or added. */
internal fun loopSample(asset: PcmAsset, frame: Int, channel: Int, start: Int, end: Int, crossfadeFrames: Int): Double {
    val width = minOf(crossfadeFrames, (end - start) / 2)
    if (width == 0) return asset.at(frame, channel).toDouble()
    val a: Double
    val b: Double
    val phase: Double
    if (frame >= end - width) {
        a = asset.at(frame, channel).toDouble()
        b = asset.at(start, channel).toDouble()
        phase = if (width == 1) .5 else .5 * (frame - (end - width)) / (width - 1)
    } else if (frame < start + width) {
        a = asset.at(end - 1, channel).toDouble()
        b = asset.at(frame, channel).toDouble()
        phase = if (width == 1) .5 else .5 + .5 * (frame - start) / (width - 1)
    } else return asset.at(frame, channel).toDouble()
    return a + (b - a) * smoothUnit(phase)
}

/** Exact frame/tick integration: one tick is 3,000,000 units at 48 kHz and milli-BPM. */
class SequenceClock(tempo: Tempo = Tempo()) {
    var tempo = tempo
        private set
    var tickNumerator = 0L
        private set
    fun reset() { tickNumerator = 0 }
    /** Arrangement seek uses the current tempo only; it does not invent a historical tempo map. */
    fun seekFrame(frame: Long) {
        require(frame >= 0 && frame <= Long.MAX_VALUE / tempo.milliBpm)
        tickNumerator = frame * tempo.milliBpm
    }
    fun setTempo(value: Tempo) { tempo = value }
    fun advance(frames: Int) {
        require(frames >= 0)
        tickNumerator += frames.toLong() * tempo.milliBpm
    }
    fun framesUntil(tick: Long): Long {
        val distance = targetNumerator(tick, tempo.swingPermille) - tickNumerator
        return if (distance <= 0) 0 else (distance + tempo.milliBpm - 1) / tempo.milliBpm
    }
    companion object {
        const val UNITS_PER_TICK = 3_000_000L
        /** Swing delays odd sixteenths within each eighth; arbitrary ticks remain monotonic. */
        fun targetNumerator(tick: Long, swingPermille: Int): Long {
            require(tick in 0..1_000_000_000L && swingPermille in 500..750)
            val pair = tick / 480
            val part = tick % 480
            val swung = if (part <= 240) part * 2 * swingPermille
                else 480L * swingPermille + (part - 240) * 2 * (1000 - swingPermille)
            return (pair * 480_000 + swung) * 3000
        }
    }
}

/** Linear control ramp with no block-size dependence. */
class ParameterSmoother(initial: Float = 0f) {
    init { require(initial.isFinite()) }
    var value = initial
        private set
    private var target = initial
    private var increment = 0.0
    private var remaining = 0
    private var precise = initial.toDouble()
    fun set(value: Float, frames: Int) {
        require(value.isFinite() && frames >= 0)
        target = value
        remaining = frames
        if (frames == 0) { this.value = value; precise = value.toDouble(); increment = 0.0 }
        else increment = (value - precise) / frames
    }
    fun next(): Float {
        if (remaining > 0) {
            precise += increment
            remaining--
            if (remaining == 0) precise = target.toDouble()
            value = precise.toFloat()
        }
        return value
    }
}

/** Prepared Kaiser-windowed polyphase FIR. Table interpolation is linear, accumulation double. */
internal class SincTable(val taps: Int, val phases: Int, cutoff: Double, beta: Double) {
    private val coefficients = FloatArray((phases + 1) * taps)
    private val left = taps / 2 - 1
    init {
        require(taps % 2 == 0 && cutoff > 0 && cutoff <= 0.5)
        val denominator = besselI0(beta)
        for (phase in 0..phases) {
            val fraction = phase.toDouble() / phases
            var sum = 0.0
            for (i in 0 until taps) {
                val x = i - left - fraction
                val radius = x / (taps / 2.0)
                val window = if (abs(radius) >= 1.0) 0.0 else besselI0(beta * sqrt(1 - radius * radius)) / denominator
                val coefficient = if (abs(x) < 1e-15) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
                val weight = coefficient * window
                coefficients[phase * taps + i] = weight.toFloat()
                sum += weight
            }
            for (i in 0 until taps) coefficients[phase * taps + i] = (coefficients[phase * taps + i] / sum).toFloat()
        }
    }
    fun read(asset: PcmAsset, position: Double, channel: Int, start: Int, end: Int, wrap: Boolean,
             crossfadeFrames: Int = 0): Double {
        val base = floor(position).toInt()
        val phase = (position - base) * phases
        val p = phase.toInt().coerceIn(0, phases - 1)
        val blend = phase - p
        val a = p * taps
        val b = a + taps
        val length = end - start
        var result = 0.0
        for (i in 0 until taps) {
            var frame = base + i - left
            if (wrap) {
                frame = start + ((frame - start) % length + length) % length
            } else if (frame < start || frame >= end) continue
            val weight = coefficients[a + i] + (coefficients[b + i] - coefficients[a + i]) * blend
            val value = if (wrap) loopSample(asset, frame, channel, start, end, crossfadeFrames)
                else asset.at(frame, channel).toDouble()
            result += value * weight
        }
        return result
    }
    private fun besselI0(x: Double): Double {
        val y = x * x / 4
        var sum = 1.0
        var term = 1.0
        for (i in 1..40) {
            term *= y / (i * i)
            sum += term
            if (term < sum * 1e-16) break
        }
        return sum
    }
}

/**
 * Worker-only resampling. 512 taps, 4096 fractional phases, beta 14, zero extension.
 * 44.1↔48: pass through 20 kHz, transition to min(Nyquist); 96→48: 20–24 kHz transition.
 * Centered FIR removes group delay; output length is ceil(inputFrames * outRate / inRate).
 */
object OfflineResampler {
    fun resample(input: FloatArray, inputRate: Int, outputRate: Int = EngineFormat.SAMPLE_RATE): FloatArray {
        require(inputRate in 8_000..192_000 && outputRate in 8_000..192_000)
        require(input.isNotEmpty() && input.size % 2 == 0 && input.all { it.isFinite() })
        if (inputRate == outputRate) return input.copyOf()
        val asset = PcmAsset.fromInterleaved(input)
        val outputFrames = (asset.frameCount.toLong() * outputRate + inputRate - 1) / inputRate
        require(outputFrames * 8 <= EngineFormat.MAX_RESIDENT_BYTES)
        val nyquist = minOf(inputRate, outputRate) / 2.0
        val pass = minOf(20_000.0, nyquist * 0.90)
        val table = SincTable(512, 4096, (pass + nyquist) / (2 * inputRate), 14.0)
        val result = FloatArray(outputFrames.toInt() * 2)
        for (frame in 0 until outputFrames.toInt()) {
            val position = frame.toDouble() * inputRate / outputRate
            result[frame * 2] = table.read(asset, position, 0, 0, asset.frameCount, false).toFloat()
            result[frame * 2 + 1] = table.read(asset, position, 1, 0, asset.frameCount, false).toFloat()
        }
        return result
    }
}

/**
 * Runtime interpolation. Conservative upper-speed bands prevent a changing rate from aliasing.
 * At +12 st: 128-tap FIR, input pass band ≤9 kHz, 10.8 kHz cutoff, stop band ≥12 kHz.
 * Integer unity-rate positions are transparent. This is resampling (pitch changes duration).
 */
class PitchInterpolator {
    // Resolve the immutable shared tables now, before entering render (including first use).
    private val speeds = PitchTables.speeds
    private val tables = PitchTables.tables
    fun sample(asset: PcmAsset, position: Double, speed: Double, channel: Int, startFrame: Int = 0,
               endFrame: Int = asset.frameCount, loop: Boolean = false): Float {
        require(position.isFinite() && speed.isFinite() && abs(speed) <= 8.0 && channel in 0..1)
        require(startFrame >= 0 && endFrame <= asset.frameCount && endFrame > startFrame)
        return read(asset, position, speed, channel, startFrame, endFrame, loop).toFloat()
    }
    internal fun read(asset: PcmAsset, position: Double, speed: Double, channel: Int, start: Int, end: Int, loop: Boolean,
                      crossfadeFrames: Int = 0): Double {
        if (abs(speed) <= 1.0 && position == floor(position)) {
            var frame = position.toInt()
            if (loop) frame = start + ((frame - start) % (end - start) + end - start) % (end - start)
            return if (frame in start until end) {
                if (loop) loopSample(asset, frame, channel, start, end, crossfadeFrames) else asset.at(frame, channel).toDouble()
            } else 0.0
        }
        var band = 0
        while (band < speeds.lastIndex && abs(speed) > speeds[band]) band++
        return tables[band].read(asset, position, channel, start, end, loop, crossfadeFrames)
    }
}

private object PitchTables {
    val speeds = doubleArrayOf(1.0, 1.125, 1.25, 1.5, 2.0, 2.5, 3.0, 4.0, 6.0, 8.0)
    val tables = Array(speeds.size) { SincTable(128, 512, 0.45 / speeds[it], 10.0) }
}

/** Linked stereo, sample-peak limiter. Exactly 72 frames latency; no signal tail after delay flush. */
class MasterLimiter {
    private val left = DoubleArray(LOOKAHEAD_FRAMES + 1)
    private val right = DoubleArray(LOOKAHEAD_FRAMES + 1)
    private var write = 0
    private var gain = 1.0
    private val release = exp(-1.0 / (0.050 * EngineFormat.SAMPLE_RATE))
    var outputLeft = 0f
        private set
    var outputRight = 0f
        private set
    fun process(inputLeft: Double, inputRight: Double) {
        left[write] = if (inputLeft.isFinite()) inputLeft else 0.0
        right[write] = if (inputRight.isFinite()) inputRight else 0.0
        var peak = 0.0
        for (i in left.indices) peak = max(peak, max(abs(left[i]), abs(right[i])))
        val target = if (peak > CEILING) CEILING / peak else 1.0
        gain = if (target < gain) target else target + release * (gain - target)
        val read = (write + 1) % left.size
        outputLeft = (left[read] * gain).coerceIn(-CEILING, CEILING).toFloat()
        outputRight = (right[read] * gain).coerceIn(-CEILING, CEILING).toFloat()
        write = read
    }
    fun reset() {
        left.fill(0.0); right.fill(0.0); write = 0; gain = 1.0; outputLeft = 0f; outputRight = 0f
    }
    companion object {
        const val LOOKAHEAD_FRAMES = 72
        const val CEILING = 0.8912509381337456
    }
}

class StereoMeter {
    var peakLeft = 0f
        private set
    var peakRight = 0f
        private set
    var rmsLeft = 0.0
        private set
    var rmsRight = 0.0
        private set
    fun measure(samples: FloatArray, offsetFrames: Int = 0, frameCount: Int = samples.size / 2 - offsetFrames) {
        require(offsetFrames >= 0 && frameCount > 0 && (offsetFrames.toLong() + frameCount) * 2 <= samples.size)
        var leftSum = 0.0; var rightSum = 0.0
        peakLeft = 0f; peakRight = 0f
        for (frame in offsetFrames until offsetFrames + frameCount) {
            val l = samples[frame * 2]; val r = samples[frame * 2 + 1]
            require(l.isFinite() && r.isFinite())
            peakLeft = max(peakLeft, abs(l)); peakRight = max(peakRight, abs(r))
            leftSum += l.toDouble() * l; rightSum += r.toDouble() * r
        }
        rmsLeft = sqrt(leftSum / frameCount); rmsRight = sqrt(rightSum / frameCount)
    }
}

/** Worker-only analysis; uses both channels, so an anti-phase stereo source does not disappear. */
object SampleAnalysis {
    fun nearestZeroCrossing(asset: PcmAsset, aroundFrame: Int, radius: Int): Int {
        require(aroundFrame in 0 until asset.frameCount && radius >= 0)
        var best = aroundFrame
        var bestScore = Double.POSITIVE_INFINITY
        val start = maxOf(1, aroundFrame - radius)
        val end = minOf(asset.frameCount - 1L, aroundFrame.toLong() + radius).toInt()
        for (f in start..end) {
            val l = asset.at(f, 0); val r = asset.at(f, 1)
            if (l * asset.at(f - 1, 0) <= 0f || r * asset.at(f - 1, 1) <= 0f) {
                val score = abs(l.toDouble()) + abs(r.toDouble()) + abs(f - aroundFrame) * 1e-7
                if (score < bestScore) { best = f; bestScore = score }
            }
        }
        return best
    }
    fun onsetFrames(asset: PcmAsset, windowFrames: Int = 240, thresholdRatio: Double = 3.0,
                    minimumGapFrames: Int = 2400): IntArray {
        require(windowFrames in 1..48_000 && thresholdRatio.isFinite() && thresholdRatio > 1 && minimumGapFrames >= 0)
        val onsets = ArrayList<Int>()
        var baseline = 1e-10
        var last = -minimumGapFrames
        var frame = 0
        while (frame < asset.frameCount) {
            val end = minOf(frame + windowFrames, asset.frameCount)
            var energy = 0.0
            for (f in frame until end) {
                val l = asset.at(f, 0).toDouble(); val r = asset.at(f, 1).toDouble()
                energy += (l * l + r * r) / 2
            }
            energy /= end - frame
            if (energy > 1e-7 && energy > baseline * thresholdRatio && frame - last >= minimumGapFrames) {
                onsets.add(frame); last = frame
            }
            baseline = baseline * 0.8 + energy * 0.2
            frame = end
        }
        return onsets.toIntArray()
    }
}
