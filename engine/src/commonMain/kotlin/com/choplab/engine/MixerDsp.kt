package com.choplab.engine

import kotlin.concurrent.Volatile
import kotlin.math.*

class MixerSnapshot {
    var frame = 0L
    /** Bus IDs belong to the same publication as the levels, even across a Program swap. */
    var program: MixerProgram = MixerProgram.BYPASS
        internal set
    val peak = FloatArray(MixerProgram.STEM_COUNT * 2)
    val rms = FloatArray(MixerProgram.STEM_COUNT * 2)
}

/** One render writer, worker/UI readers; readers supply storage, like the engine transport readout. */
class MixerReadout internal constructor() {
    @Volatile private var version = 0L
    private var frame = 0L
    private var program = MixerProgram.BYPASS
    private val peak = FloatArray(MixerProgram.STEM_COUNT * 2)
    private val rms = FloatArray(MixerProgram.STEM_COUNT * 2)
    internal fun publish(at: Long, value: MixerProgram, peaks: DoubleArray, sums: DoubleArray, frames: Int) {
        version++
        frame = at
        program = value
        for (i in peak.indices) {
            peak[i] = peaks[i].coerceAtMost(Float.MAX_VALUE.toDouble()).toFloat()
            rms[i] = (if (frames == 0) 0.0 else sqrt(sums[i] / frames)).coerceAtMost(Float.MAX_VALUE.toDouble()).toFloat()
        }
        version++
    }
    fun copyInto(target: MixerSnapshot): Boolean {
        repeat(3) {
            val before = version
            if (before and 1L == 0L) {
                val at = frame
                val value = program
                for (i in peak.indices) { target.peak[i] = peak[i]; target.rms[i] = rms[i] }
                if (before == version) { target.frame = at; target.program = value; return true }
            }
        }
        return false
    }
}

/**
 * Fixed music buses. Host reserves [PCM_BYTES] before construction. No allocation, I/O, lock or
 * parameter coefficient calculation in process. SOURCE/HAND/metronome never enter these buses.
 * Track gain/pan/mute/solo have already been applied by the compiler, preserving old clip pan rules.
 */
class MixerDsp(initial: MixerProgram = MixerProgram.BYPASS) {
    private var program = initial
    private val inserts = Array(MixerProgram.MAX_BUSES) { InsertState() }
    private val master = InsertState()
    private val input = DoubleArray(MixerProgram.MAX_BUSES * 2)
    private val stems = DoubleArray(MixerProgram.STEM_COUNT * 2)
    private val peak = DoubleArray(MixerProgram.STEM_COUNT * 2)
    private val squares = DoubleArray(MixerProgram.STEM_COUNT * 2)
    private val delay = DelayState()
    private val reverb = ReverbState()
    private var hasInput = false
    private var silentFrames = 0
    private var dormant = true
    private var stopping = -1
    val readout = MixerReadout()
    var outputLeft = 0.0
        private set
    var outputRight = 0.0
        private set
    var preMasterLeft = 0.0
        private set
    var preMasterRight = 0.0
        private set

    fun use(value: MixerProgram) { program = value; reset() }
    fun beginBlock() { peak.fill(0.0); squares.fill(0.0) }
    fun endBlock(frame: Long, frames: Int) { readout.publish(frame, program, peak, squares, frames) }
    fun beginFrame() { input.fill(0.0); hasInput = false }
    fun add(bus: Int, left: Double, right: Double) {
        require(bus in 0 until MixerProgram.MAX_BUSES)
        input[bus * 2] += left; input[bus * 2 + 1] += right
        hasInput = true
    }
    /** A new accepted PAD trigger reopens the graph after an emergency stop. */
    fun resume() { stopping = -1 }
    fun stop(panic: Boolean) { if (panic) reset() else if (!program.bypass) stopping = EngineCore.STEAL_FADE_FRAMES }
    fun reset() {
        for (insert in inserts) insert.reset()
        master.reset(); delay.reset(); reverb.reset()
        input.fill(0.0); stems.fill(0.0)
        outputLeft = 0.0; outputRight = 0.0; preMasterLeft = 0.0; preMasterRight = 0.0
        silentFrames = 0; dormant = true; stopping = -1
    }
    /** Owner shutdown, outside render. Old EngineView receipts cannot keep large PCM rings alive. */
    fun close() { reset(); delay.close(); reverb.close() }

    /** [dryLeft]/[dryRight] preserve the exact old summation order in a completely bypassed graph. */
    fun process(dryLeft: Double, dryRight: Double) {
        stems.fill(0.0)
        if (program.bypass) {
            for (i in input.indices) stems[i] = input[i]
            outputLeft = dryLeft; outputRight = dryRight
            preMasterLeft = dryLeft; preMasterRight = dryRight
            meter()
            return
        }
        if (hasInput) { silentFrames = 0; dormant = false }
        else if (!dormant) silentFrames++
        if (!program.bypass && !hasInput && (dormant || silentFrames >= program.effectTailFrames)) {
            if (!dormant) reset()
            outputLeft = 0.0; outputRight = 0.0; preMasterLeft = 0.0; preMasterRight = 0.0
            return
        }
        var delayL = 0.0; var delayR = 0.0; var reverbL = 0.0; var reverbR = 0.0
        var left = 0.0; var right = 0.0
        val naturalFade = if (hasInput || silentFrames < program.effectTailFrames - TAIL_FADE_FRAMES) 1.0
            else smoothUnit((program.effectTailFrames - silentFrames).toDouble() / TAIL_FADE_FRAMES)
        val stopFade = if (stopping < 0) 1.0 else smoothUnit(stopping.toDouble() / EngineCore.STEAL_FADE_FRAMES)
        val fade = naturalFade * stopFade
        for (bus in inserts.indices) {
            val at = bus * 2
            val insert = inserts[bus]
            insert.process(program.prepared[bus], input[at], input[at + 1])
            val l = insert.left * fade; val r = insert.right * fade
            stems[at] = l; stems[at + 1] = r
            left += l; right += r
            delayL += l * program.delaySends[bus]; delayR += r * program.delaySends[bus]
            reverbL += l * program.reverbSends[bus]; reverbR += r * program.reverbSends[bus]
        }
        val settings = program.settings
        if (settings.delay.enabled) {
            delay.process(settings.delay, delayL, delayR)
            val at = MixerProgram.DELAY_RETURN * 2
            stems[at] = delay.left * settings.delay.returnGain * fade
            stems[at + 1] = delay.right * settings.delay.returnGain * fade
            left += stems[at]; right += stems[at + 1]
        }
        if (settings.reverb.enabled) {
            reverb.process(program, reverbL, reverbR)
            val at = MixerProgram.REVERB_RETURN * 2
            stems[at] = reverb.left * settings.reverb.returnGain * fade
            stems[at + 1] = reverb.right * settings.reverb.returnGain * fade
            left += stems[at]; right += stems[at + 1]
        }
        preMasterLeft = if (program.bypass) dryLeft * fade else left
        preMasterRight = if (program.bypass) dryRight * fade else right
        master.process(program.master, preMasterLeft, preMasterRight)
        // Fading only its input leaves a low-cutoff master's stored response nonzero at reset.
        // Include that response in the finite stop/tail fade; a bypassed master has no such history.
        val masterFade = if (settings.master.bypass) 1.0 else fade
        outputLeft = master.left * settings.masterGain * masterFade
        outputRight = master.right * settings.masterGain * masterFade
        meter()
        if (stopping >= 0 && --stopping <= 0) reset()
    }
    private fun meter() {
        for (i in stems.indices) { peak[i] = max(peak[i], abs(stems[i])); squares[i] += stems[i] * stems[i] }
    }

    /** Worker-allocated frame-major interleaved stems. Includes separate shared FX returns. */
    fun writeStems(output: FloatArray, offset: Int) {
        for (i in stems.indices) output[offset + i] = stems[i].toFloat()
    }

    private class BiquadState {
        private var l1 = 0.0; private var l2 = 0.0; private var r1 = 0.0; private var r2 = 0.0
        var left = 0.0; var right = 0.0
        fun process(c: MixBiquad, l: Double, r: Double) {
            if (c.bypass) { left = l; right = r; return }
            left = c.b0 * l + l1; right = c.b0 * r + r1
            l1 = c.b1 * l - c.a1 * left + l2; l2 = c.b2 * l - c.a2 * left
            r1 = c.b1 * r - c.a1 * right + r2; r2 = c.b2 * r - c.a2 * right
        }
        fun reset() { l1 = 0.0; l2 = 0.0; r1 = 0.0; r2 = 0.0; left = 0.0; right = 0.0 }
    }
    private class InsertState {
        private val filters = Array(4) { BiquadState() }
        private var envelope = 0.0
        var left = 0.0; var right = 0.0
        fun process(prepared: PreparedInsert, l: Double, r: Double) {
            left = l; right = r
            if (prepared.spec.bypass) return
            for (i in filters.indices) {
                val filter = filters[i]
                filter.process(prepared.filters[i], left, right)
                left = filter.left; right = filter.right
            }
            if (prepared.spec.compressor.enabled) {
                val peak = max(abs(left), abs(right))
                val rate = if (peak > envelope) prepared.attack else prepared.release
                envelope = peak + rate * (envelope - peak)
                val gain = (if (envelope <= prepared.threshold) 1.0 else
                    (envelope / prepared.threshold).pow(prepared.exponent)) * prepared.makeup
                left *= gain; right *= gain
            }
        }
        fun reset() { for (filter in filters) filter.reset(); envelope = 0.0; left = 0.0; right = 0.0 }
    }

    /** Reset invalidates history in O(1); it never clears a large ring on the render thread. */
    private class DelayState {
        private var samples = DoubleArray(96_000 * 2)
        private var write = 0; private var filled = 0
        var left = 0.0; var right = 0.0
        fun process(spec: MixDelay, l: Double, r: Double) {
            val at = write * 2
            left = if (filled >= spec.frames) samples[at] else 0.0
            right = if (filled >= spec.frames) samples[at + 1] else 0.0
            samples[at] = l + left * spec.feedback; samples[at + 1] = r + right * spec.feedback
            write++; if (write == spec.frames) write = 0
            if (filled < spec.frames) filled++
        }
        fun reset() { write = 0; filled = 0; left = 0.0; right = 0.0 }
        fun close() { samples = EMPTY }
    }

    private class Comb(private val length: Int) {
        private var samples = DoubleArray(length)
        private var write = 0; private var filled = 0; private var damped = 0.0
        fun process(input: Double, feedback: Double, damping: Float): Double {
            val delayed = if (filled >= length) samples[write] else 0.0
            damped = delayed * (1 - damping) + damped * damping
            samples[write] = input + damped * feedback
            write++; if (write == length) write = 0
            if (filled < length) filled++
            return delayed
        }
        fun reset() { write = 0; filled = 0; damped = 0.0 }
        fun close() { samples = EMPTY }
    }
    private class AllPass(private val length: Int) {
        private var samples = DoubleArray(length)
        private var write = 0; private var filled = 0
        fun process(input: Double): Double {
            val delayed = if (filled >= length) samples[write] else 0.0
            val output = delayed - .5 * input
            samples[write] = input + .5 * output
            write++; if (write == length) write = 0
            if (filled < length) filled++
            return output
        }
        fun reset() { write = 0; filled = 0 }
        fun close() { samples = EMPTY }
    }
    private class ReverbState {
        private val combs = Array(8) { Comb(REVERB_LENGTHS[it]) }
        private val allPass = arrayOf(AllPass(211), AllPass(79), AllPass(223), AllPass(83))
        var left = 0.0; var right = 0.0
        fun process(program: MixerProgram, l: Double, r: Double) {
            left = 0.0; right = 0.0
            for (i in 0 until 4) {
                left += combs[i].process(l, program.reverbFeedback[i], program.settings.reverb.damping) * .25
                right += combs[i + 4].process(r, program.reverbFeedback[i + 4], program.settings.reverb.damping) * .25
            }
            left = allPass[1].process(allPass[0].process(left))
            right = allPass[3].process(allPass[2].process(right))
        }
        fun reset() { for (comb in combs) comb.reset(); for (pass in allPass) pass.reset(); left = 0.0; right = 0.0 }
        fun close() { for (comb in combs) comb.close(); for (pass in allPass) pass.close() }
    }

    companion object {
        internal val REVERB_LENGTHS = intArrayOf(1429, 1601, 1867, 2053, 1451, 1637, 1879, 2081)
        private val EMPTY = DoubleArray(0)
        private const val TAIL_FADE_FRAMES = 480
        /** PCM rings plus sample accumulators/meters; filter coefficients/state are small fixed DSP state. */
        const val PCM_BYTES = (96_000L * 2 + 13_998 + 596) * 8 +
            (MixerProgram.MAX_BUSES * 2L + MixerProgram.STEM_COUNT * 6L) * 8 + MixerProgram.STEM_COUNT * 16L + 8192
    }
}
