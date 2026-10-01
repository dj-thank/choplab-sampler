package com.choplab.engine

import kotlin.math.*

/**
 * Worker-only YIN analysis and time-domain pitch-synchronous overlap-add. Source grains retain their
 * waveform/time scale; correction changes their spacing and repeats/drops grains, not the song duration.
 * No original samples are changed. Ambiguous/unvoiced spans are copied, including stereo polarity.
 */
object OfflinePitchCorrection {
    const val VERSION = 1
    const val SAMPLE_RATE = 48_000
    const val HOP_FRAMES = 480
    const val BLOCK_FRAMES = 4096
    const val MAX_FRAMES = 30_000_000
    private const val RING_FRAMES = 8192
    private const val WINDOW = 384
    private const val MAX_LAG = 300
    private const val DATA = WINDOW + MAX_LAG
    private const val TAPS = 64
    private const val EDGE = 2048
    private const val MIN_SHIFT = .005 // half a cent: preserve exact unity instead of running an identity OLA.

    /** All PCM/work arrays, including contour and input/output overlap, before the worker allocates them. */
    fun workspaceBytes(frames: Int): Long {
        require(frames in 1..MAX_FRAMES)
        return 2L * BLOCK_FRAMES * 2 * 4 + 3L * RING_FRAMES * 8 + BLOCK_FRAMES * 2L * 4 +
            DATA * 2L * 4 + (MAX_LAG + 1L) * 8 + TAPS * 8L + (frames / HOP_FRAMES + 1L) * 14 + 4096
    }

    fun process(
        reader: PitchPcmReader,
        frames: Int,
        settings: PitchCorrectionSettings,
        write: (FloatArray, Int) -> Unit,
        checkCancelled: () -> Unit = {},
        progress: (PitchCorrectionPhase, Int, Int) -> Unit = { _, _, _ -> },
    ): PitchCorrectionReport {
        val bytes = workspaceBytes(frames)
        checkCancelled()
        val source = SampleCache(reader, frames, checkCancelled)
        val contour = contour(source, frames, settings.amount > 0f, checkCancelled, progress)
        val frequency = contour.frequency
        val reasons = contour.reasons
        val channels = contour.channels
        val hops = frequency.size
        val shifts = FloatArray(hops)
        var baseline = 0.0
        var offset = 0.0
        var previousNote: Int? = null
        val tuneAlpha = if (settings.retuneMs == 0f) 1.0 else 1 - exp(-10.0 / settings.retuneMs)
        for (i in 0 until hops) {
            if (reasons[i].toInt() != PitchBypass.NONE.ordinal) { baseline = 0.0; offset = 0.0; previousNote = null; continue }
            val midi = 69 + 12 * log2(frequency[i] / 440.0)
            baseline = if (baseline == 0.0) midi else baseline + (midi - baseline) * (1 - exp(-.01 / .12))
            val note = settings.nearest(baseline, previousNote).also { previousNote = it }
            val wanted = (note + settings.vibrato * (midi - baseline) - midi) * settings.amount
            offset += (wanted - offset) * tuneAlpha
            shifts[i] = offset.coerceIn(-2.0, 2.0).toFloat()
        }

        val left = DoubleArray(RING_FRAMES)
        val right = DoubleArray(RING_FRAMES)
        val weights = DoubleArray(RING_FRAMES)
        val output = FloatArray(BLOCK_FRAMES * 2)
        var nextHop = 0
        var segment: GrainRun? = null
        fun nextRun(): GrainRun? {
            while (nextHop < hops && reasons[nextHop].toInt() != PitchBypass.NONE.ordinal) nextHop++
            if (nextHop >= hops) return null
            val from = nextHop
            while (nextHop < hops && reasons[nextHop].toInt() == PitchBypass.NONE.ordinal) nextHop++
            return GrainRun(from, nextHop, frequency, shifts, channels[from].toInt(), source,
                left, right, weights, frames)
        }
        segment = nextRun()
        var corrected = 0
        var first = 0
        while (first < frames) {
            checkCancelled()
            val end = minOf(frames, first + BLOCK_FRAMES)
            while (segment != null) {
                val run = segment
                run.addThrough(end, checkCancelled)
                if (!run.finished || run.end >= end) break
                segment = nextRun()
            }
            for (frame in first until end) {
                val index = frame % RING_FRAMES
                var l = source.sample(frame, 0).toDouble()
                var r = source.sample(frame, 1).toDouble()
                val weight = weights[index]
                if (weight > 1e-8) {
                    val hop = minOf(hops - 1, frame / HOP_FRAMES)
                    if (reasons[hop].toInt() == PitchBypass.NONE.ordinal && abs(shifts[hop]) >= MIN_SHIFT) {
                        // Ten ms on each valid span edge; unsupported spans remain sample-exact original.
                        var edge = 1.0
                        for (distance in 0..1) {
                            if (hop - distance - 1 < 0 || reasons[hop - distance - 1].toInt() != PitchBypass.NONE.ordinal)
                                edge = minOf(edge, (frame - (hop - distance) * HOP_FRAMES) / 480.0)
                            if (hop + distance + 1 >= hops || reasons[hop + distance + 1].toInt() != PitchBypass.NONE.ordinal)
                                edge = minOf(edge, ((hop + distance + 1) * HOP_FRAMES - 1 - frame) / 480.0)
                        }
                        val wet = edge.coerceIn(0.0, 1.0)
                        if (wet > 0) {
                            l += (left[index] / weight - l) * wet
                            r += (right[index] / weight - r) * wet
                            corrected++
                        }
                    }
                }
                require(l.isFinite() && r.isFinite())
                output[(frame - first) * 2] = l.toFloat()
                output[(frame - first) * 2 + 1] = r.toFloat()
                require(output[(frame - first) * 2].isFinite() && output[(frame - first) * 2 + 1].isFinite())
                left[index] = 0.0; right[index] = 0.0; weights[index] = 0.0
            }
            write(output, end - first)
            first = end
            progress(PitchCorrectionPhase.RENDER, first, frames)
        }
        checkCancelled()
        val counts = IntArray(PitchBypass.entries.size)
        reasons.forEach { counts[it.toInt()]++ }
        return PitchCorrectionReport(frames, corrected, counts[PitchBypass.NONE.ordinal], counts.toList(), bytes)
    }

    /** Worker-only observations from the same conservative YIN gate as correction. No score or note label. */
    fun observe(reader: PitchPcmReader, frames: Int, checkCancelled: () -> Unit = {},
                observation: (frame: Int, frequencyHz: Float, confidence: Float, bypass: PitchBypass) -> Unit) {
        workspaceBytes(frames) // Validate the same bounded input before allocation.
        val data = contour(SampleCache(reader, frames, checkCancelled), frames, true, checkCancelled) { _, _, _ -> }
        for (hop in data.frequency.indices) {
            checkCancelled()
            observation(hop * HOP_FRAMES, data.frequency[hop], data.confidence[hop], PitchBypass.entries[data.reasons[hop].toInt()])
        }
    }

    private class Contour(val frequency: FloatArray, val confidence: FloatArray, val reasons: ByteArray, val channels: ByteArray)
    private fun contour(source: SampleCache, frames: Int, enabled: Boolean, checkCancelled: () -> Unit,
                        progress: (PitchCorrectionPhase, Int, Int) -> Unit): Contour {
        val hops = frames / HOP_FRAMES + 1
        val frequency = FloatArray(hops)
        val confidence = FloatArray(hops)
        val reasons = ByteArray(hops) { PitchBypass.EDGE.ordinal.toByte() }
        val channels = ByteArray(hops)
        val analyzer = Yin(source)
        if (enabled) for (i in 0 until hops) {
            checkCancelled()
            val center = i * HOP_FRAMES
            if (center >= EDGE && center < frames - EDGE) {
                analyzer.analyze(center)
                frequency[i] = analyzer.frequency.toFloat()
                confidence[i] = analyzer.confidence.toFloat()
                reasons[i] = analyzer.reason.ordinal.toByte()
                channels[i] = analyzer.channel.toByte()
            }
            if (i % 10 == 0) progress(PitchCorrectionPhase.ANALYZE, minOf(center, frames), frames)
        }
        progress(PitchCorrectionPhase.ANALYZE, frames, frames)
        // Require neighboring reliable periods. A jump/transient never borrows the previous note's correction.
        for (i in 1 until hops - 1) if (frequency[i] > 0f) {
            if (frequency[i - 1] == 0f || frequency[i + 1] == 0f ||
                abs(12 * log2(frequency[i].toDouble() / frequency[i - 1])) > .8 ||
                abs(12 * log2(frequency[i + 1].toDouble() / frequency[i])) > .8)
                reasons[i] = PitchBypass.UNSTABLE.ordinal.toByte()
        }
        return Contour(frequency, confidence, reasons, channels)
    }

    private class SampleCache(val reader: PitchPcmReader, val frames: Int, val cancelled: () -> Unit) {
        private val a = FloatArray(BLOCK_FRAMES * 2)
        private val b = FloatArray(BLOCK_FRAMES * 2)
        private var firstA = -1
        private var firstB = -1
        private var lastWasA = false
        fun sample(frame: Int, channel: Int): Float {
            val clamped = frame.coerceIn(0, frames - 1)
            val first = clamped / BLOCK_FRAMES * BLOCK_FRAMES
            val data = when (first) {
                firstA -> { lastWasA = true; a }
                firstB -> { lastWasA = false; b }
                else -> {
                    cancelled()
                    val data = if (lastWasA) b else a
                    val count = minOf(BLOCK_FRAMES, frames - first)
                    require(reader.read(first, count, data) == count) { "Incomplete pitch PCM window" }
                    for (i in 0 until count * 2) require(data[i].isFinite()) { "Non-finite pitch input" }
                    if (lastWasA) firstB = first else firstA = first
                    lastWasA = !lastWasA
                    data
                }
            }
            return data[(clamped - first) * 2 + channel]
        }
        fun at(frame: Double, channel: Int): Double {
            val first = floor(frame).toInt()
            val fraction = frame - first
            val a = sample(first, channel).toDouble()
            return a + (sample(first + 1, channel) - a) * fraction
        }
    }

    private class Yin(val source: SampleCache) {
        private val l = FloatArray(DATA)
        private val r = FloatArray(DATA)
        private val difference = DoubleArray(MAX_LAG + 1)
        private val fir = DoubleArray(TAPS) { i ->
            val x = i - (TAPS - 1) / 2.0
            val cutoff = 4500.0 / SAMPLE_RATE
            sin(2 * PI * cutoff * x) / (PI * x) * (.42 - .5 * cos(2 * PI * i / (TAPS - 1)) + .08 * cos(4 * PI * i / (TAPS - 1)))
        }.also { values -> val sum = values.sum(); for (i in values.indices) values[i] /= sum }
        var frequency = 0.0
        var confidence = 0.0
        var channel = 0
        var reason = PitchBypass.UNVOICED
        fun analyze(center: Int) {
            frequency = 0.0; confidence = 0.0; reason = PitchBypass.UNVOICED
            val start = center - ((DATA - 1) * 4 + TAPS) / 2
            var ll = 0.0; var rr = 0.0; var lr = 0.0
            for (i in 0 until DATA) {
                var a = 0.0; var b = 0.0
                for (tap in 0 until TAPS) {
                    a += source.sample(start + i * 4 + tap, 0) * fir[tap]
                    b += source.sample(start + i * 4 + tap, 1) * fir[tap]
                }
                l[i] = a.toFloat(); r[i] = b.toFloat()
                ll += a * a; rr += b * b; lr += a * b
            }
            channel = if (ll >= rr) 0 else 1
            if (maxOf(ll, rr) / DATA < 1e-12) return
            val result = estimate(if (channel == 0) l else r)
            if (result == 0.0) { reason = PitchBypass.LOW_CONFIDENCE; return }
            if (result !in 55.0..1000.0) { reason = PitchBypass.OUT_OF_RANGE; return }
            var period = SAMPLE_RATE / result
            // Refine the decimated estimate at the native sample rate. Otherwise a perfectly
            // tuned high note can receive a tiny correction from the analysis grid alone.
            val before = residual(center, period - .5, channel)
            val at = residual(center, period, channel)
            val after = residual(center, period + .5, channel)
            val curvature = before - 2 * at + after
            if (curvature > 1e-15) period += (.25 * (before - after) / curvature).coerceIn(-.5, .5)
            val refined = SAMPLE_RATE / period
            val residual = residual(center, period, channel)
            if (residual > .10) { reason = PitchBypass.LOW_CONFIDENCE; return }
            if (result >= 110 && residual > .0001 && residual(center, period * 2, channel) < residual * .15) {
                reason = PitchBypass.OCTAVE_AMBIGUOUS; return
            }
            if (minOf(ll, rr) > maxOf(ll, rr) * .1 && abs(lr) < sqrt(ll * rr) * .98) {
                val other = estimate(if (channel == 0) r else l)
                if (other == 0.0 || abs(12 * log2(other / result)) > .35) { reason = PitchBypass.STEREO_AMBIGUOUS; return }
            }
            frequency = refined; confidence = 1 - residual; reason = PitchBypass.NONE
        }
        private fun estimate(data: FloatArray): Double {
            var cumulative = 0.0
            difference[0] = 1.0
            for (lag in 1..MAX_LAG) {
                var sum = 0.0
                for (j in 0 until WINDOW) { val d = data[j].toDouble() - data[j + lag]; sum += d * d }
                cumulative += sum
                difference[lag] = if (cumulative <= 1e-20) 1.0 else sum * lag / cumulative
            }
            fun offset(lag: Int): Double {
                val a = difference[lag - 1]; val b = difference[lag]; val c = difference[lag + 1]
                return if (abs(a - 2 * b + c) < 1e-20) 0.0 else (.5 * (a - c) / (a - 2 * b + c)).coerceIn(-.5, .5)
            }
            fun minimum(lag: Int): Double = (difference[lag] - .25 * (difference[lag - 1] - difference[lag + 1]) * offset(lag)).coerceAtLeast(0.0)
            // A narrow formant can make an early carrier-period dip even though the full voice
            // repeats much more reliably later. Compare refined minima, so coarse high-note
            // sampling cannot make a multiple of the true period look artificially preferable.
            var best = 1.0
            for (lag in 4 until MAX_LAG) if (difference[lag] <= difference[lag - 1] && difference[lag] <= difference[lag + 1])
                best = minOf(best, minimum(lag))
            val threshold = minOf(.12, best + .01)
            var lag = 4
            while (lag < MAX_LAG - 1) {
                if (difference[lag] <= difference[lag - 1] && difference[lag] <= difference[lag + 1] && minimum(lag) < threshold)
                    return 12_000.0 / (lag + offset(lag))
                lag++
            }
            return 0.0
        }
        private fun residual(center: Int, period: Double, channel: Int): Double {
            var difference = 0.0; var power = 0.0
            for (j in -512 until 512) {
                val a = source.at(center + j - period / 2, channel)
                val b = source.at(center + j + period / 2, channel)
                difference += (a - b) * (a - b); power += a * a + b * b
            }
            return if (power < 1e-15) 1.0 else difference / power
        }
    }

    private class GrainRun(
        val from: Int, val until: Int, val frequency: FloatArray, val shifts: FloatArray, val channel: Int,
        val source: SampleCache, val left: DoubleArray, val right: DoubleArray, val weights: DoubleArray, val frames: Int,
    ) {
        val start = from * HOP_FRAMES
        val end = minOf(frames, until * HOP_FRAMES)
        private var direction = 1.0
        private var input = start
        private var nextInput = start
        private var synth = start.toDouble()
        var finished = false
            private set
        init {
            val radius = (SAMPLE_RATE / frequency[from]).toInt()
            var strongest = 0.0
            for (frame in start - radius / 2..start + radius / 2) {
                val slope = source.sample(frame + 1, channel) - source.sample(frame - 1, channel)
                if (abs(slope) > abs(strongest)) { strongest = slope.toDouble(); input = frame }
            }
            direction = if (strongest < 0) -1.0 else 1.0
            nextInput = nextMark(input)
            synth = input.toDouble()
        }
        private fun value(values: FloatArray, frame: Double): Double {
            val position = (frame / HOP_FRAMES).coerceIn(from.toDouble(), (until - 1).toDouble())
            val index = floor(position).toInt()
            return values[index] + (values[minOf(index + 1, until - 1)] - values[index]) * (position - index)
        }
        private fun nextMark(previous: Int): Int {
            val period = SAMPLE_RATE / value(frequency, previous.toDouble())
            val center = previous + period
            val spread = maxOf(2, (period * .20).toInt())
            var best = center.roundToInt()
            var score = Double.NEGATIVE_INFINITY
            for (frame in center.roundToInt() - spread..center.roundToInt() + spread) {
                val slope = direction * (source.sample(frame + 1, channel) - source.sample(frame - 1, channel))
                if (slope > score) { score = slope; best = frame }
            }
            return maxOf(previous + 1, best)
        }
        fun addThrough(outputEnd: Int, cancelled: () -> Unit) {
            while (!finished && synth < outputEnd + 1024) {
                cancelled()
                if (synth > end + 1024) { finished = true; break }
                while (nextInput < synth) { input = nextInput; nextInput = nextMark(input) }
                val mark = if (abs(input - synth) <= abs(nextInput - synth)) input else nextInput
                val radius = SAMPLE_RATE / value(frequency, mark.toDouble())
                val first = maxOf(start, ceil(synth - radius).toInt())
                val last = minOf(end, ceil(synth + radius).toInt())
                for (frame in first until last) {
                    val delta = frame - synth
                    val window = .5 + .5 * cos(PI * delta / radius)
                    val index = frame % RING_FRAMES
                    left[index] += source.at(mark + delta, 0) * window
                    right[index] += source.at(mark + delta, 1) * window
                    weights[index] += window
                }
                val hz = value(frequency, synth) * 2.0.pow(value(shifts, synth) / 12)
                synth += SAMPLE_RATE / hz
            }
        }
    }
}
