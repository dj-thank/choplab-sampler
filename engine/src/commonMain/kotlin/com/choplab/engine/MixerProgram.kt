package com.choplab.engine

import kotlin.math.*

/** Durable values only; coefficients are prepared separately, outside render. */
data class MixEq(val lowDb: Float = 0f, val midDb: Float = 0f, val highDb: Float = 0f) {
    init { require(listOf(lowDb, midDb, highDb).all { it.isFinite() && it in -18f..18f }) }
    val bypass: Boolean get() = lowDb == 0f && midDb == 0f && highDb == 0f
}

enum class MixFilterMode { OFF, LOW_PASS, HIGH_PASS }
data class MixFilter(val mode: MixFilterMode = MixFilterMode.OFF, val cutoffHz: Float = 16_000f) {
    init { require(cutoffHz.isFinite() && cutoffHz in 20f..20_000f) }
}

/** Linked stereo peak detector; attack/release smooth its envelope. No lookahead/extra latency. */
data class MixCompressor(val enabled: Boolean = false, val thresholdDb: Float = -18f,
    val ratio: Float = 4f, val attackMs: Float = 10f, val releaseMs: Float = 100f, val makeupDb: Float = 0f) {
    init {
        require(thresholdDb.isFinite() && thresholdDb in -60f..0f)
        require(ratio.isFinite() && ratio in 1f..20f)
        require(attackMs.isFinite() && attackMs in 0.1f..200f)
        require(releaseMs.isFinite() && releaseMs in 5f..2000f)
        require(makeupDb.isFinite() && makeupDb in 0f..18f)
    }
}

data class MixInsert(val eq: MixEq = MixEq(), val filter: MixFilter = MixFilter(),
    val compressor: MixCompressor = MixCompressor()) {
    val bypass: Boolean get() = eq.bypass && filter.mode == MixFilterMode.OFF && !compressor.enabled
}

data class TrackFx(val insert: MixInsert = MixInsert(), val delaySend: Float = 0f, val reverbSend: Float = 0f) {
    init { require(delaySend.isFinite() && delaySend in 0f..1f && reverbSend.isFinite() && reverbSend in 0f..1f) }
}

/** Feedback cannot reach unity. The full declared decay fits the bounded 60-second export tail. */
data class MixDelay(val enabled: Boolean = false, val frames: Int = 12_000,
    val feedback: Float = .25f, val returnGain: Float = .5f) {
    init { require(frames in 1..96_000 && feedback.isFinite() && feedback in 0f..0.6f &&
        returnGain.isFinite() && returnGain in 0f..2f) }
    val tailFrames: Int get() = if (!enabled || returnGain == 0f) 0 else
        frames * (if (feedback == 0f) 1 else ceil(ln(1e-6) / ln(feedback.toDouble())).toInt() + 1)
}

data class MixReverb(val enabled: Boolean = false, val decaySeconds: Float = 1.2f,
    val damping: Float = .4f, val returnGain: Float = .3f) {
    init { require(decaySeconds.isFinite() && decaySeconds in .1f..3f && damping.isFinite() && damping in 0f..0.95f &&
        returnGain.isFinite() && returnGain in 0f..2f) }
    val tailFrames: Int get() = if (!enabled || returnGain == 0f) 0 else ceil(decaySeconds * 96_000.0).toInt() + 4096
}

data class MixSettings(val delay: MixDelay = MixDelay(), val reverb: MixReverb = MixReverb(),
    val master: MixInsert = MixInsert(), val masterGain: Float = 1f) {
    init { require(masterGain.isFinite() && masterGain in 0f..8f) }
}

/** Coefficients are immutable. This constructor is a worker/control operation, never a render action. */
class MixerProgram(channels: List<TrackFx> = emptyList(), val settings: MixSettings = MixSettings(),
                   busIds: List<String> = channels.indices.map { "bus-$it" }) {
    init { require(channels.size <= MAX_BUSES && busIds.size == channels.size && busIds.distinct().size == busIds.size) }
    private val values = channels.toTypedArray()
    private val ids = busIds.toTypedArray()
    fun busId(index: Int): String? = ids.getOrNull(index)
    internal fun busIndex(id: String?): Int = if (id == null) UNROUTED_BUS else ids.indexOf(id).let { if (it < 0) UNROUTED_BUS else it }
    internal val prepared = Array(MAX_BUSES) { PreparedInsert(values.getOrNull(it)?.insert ?: MixInsert()) }
    internal val master = PreparedInsert(settings.master)
    internal val delaySends = FloatArray(MAX_BUSES) { values.getOrNull(it)?.delaySend ?: 0f }
    internal val reverbSends = FloatArray(MAX_BUSES) { values.getOrNull(it)?.reverbSend ?: 0f }
    internal val reverbFeedback = DoubleArray(8) { index ->
        10.0.pow(-3.0 * MixerDsp.REVERB_LENGTHS[index] / (48_000 * settings.reverb.decaySeconds))
    }
    val bypass = values.all { it.insert.bypass && (!settings.delay.enabled || it.delaySend == 0f) &&
        (!settings.reverb.enabled || it.reverbSend == 0f) } && settings.master.bypass && settings.masterGain == 1f
    /** Includes settling before/after sends; output is explicitly faded to zero at this finite endpoint. */
    internal val effectTailFrames: Int = if (bypass) 0 else 48_000 + maxOf(
        if (values.any { it.delaySend > 0f }) settings.delay.tailFrames else 0,
        if (values.any { it.reverbSend > 0f }) settings.reverb.tailFrames else 0)
    val tailFrames: Int = if (bypass) 0 else effectTailFrames + EngineCore.STEAL_FADE_FRAMES
    init { require(tailFrames <= MAX_TAIL_FRAMES) }

    companion object {
        const val UNROUTED_BUS = Arrangement.MAX_TRACKS
        const val MAX_BUSES = Arrangement.MAX_TRACKS + 1
        const val DELAY_RETURN = MAX_BUSES
        const val REVERB_RETURN = MAX_BUSES + 1
        const val STEM_COUNT = MAX_BUSES + 2
        const val MAX_TAIL_FRAMES = 48_000 * 60
        val BYPASS = MixerProgram()
    }
}

internal data class MixBiquad(val b0: Double = 1.0, val b1: Double = 0.0, val b2: Double = 0.0,
    val a1: Double = 0.0, val a2: Double = 0.0) {
    val bypass: Boolean get() = b0 == 1.0 && b1 == 0.0 && b2 == 0.0 && a1 == 0.0 && a2 == 0.0
    companion object {
        // RBJ biquad equations, normalized a0: https://www.w3.org/TR/audio-eq-cookbook/
        fun peak(hz: Double, db: Float): MixBiquad {
            if (db == 0f) return MixBiquad()
            val w = 2 * PI * hz / 48_000
            val a = 10.0.pow(db / 40.0)
            val alpha = sin(w) / (2 * .7071067811865476)
            val a0 = 1 + alpha / a
            return MixBiquad((1 + alpha * a) / a0, -2 * cos(w) / a0,
                (1 - alpha * a) / a0, -2 * cos(w) / a0, (1 - alpha / a) / a0)
        }
        fun filter(value: MixFilter): MixBiquad {
            if (value.mode == MixFilterMode.OFF) return MixBiquad()
            val w = 2 * PI * value.cutoffHz / 48_000
            val c = cos(w)
            val alpha = sin(w) / (2 * .7071067811865476)
            val a0 = 1 + alpha
            val high = value.mode == MixFilterMode.HIGH_PASS
            val edge = if (high) 1 + c else 1 - c
            return MixBiquad(edge / (2 * a0), (if (high) -edge else edge) / a0,
                edge / (2 * a0), -2 * c / a0, (1 - alpha) / a0)
        }
    }
}

internal class PreparedInsert(val spec: MixInsert) {
    val filters = arrayOf(MixBiquad.peak(160.0, spec.eq.lowDb), MixBiquad.peak(1000.0, spec.eq.midDb),
        MixBiquad.peak(8000.0, spec.eq.highDb), MixBiquad.filter(spec.filter))
    val threshold = 10.0.pow(spec.compressor.thresholdDb / 20.0)
    val exponent = 1.0 / spec.compressor.ratio - 1
    val attack = exp(-1.0 / (48.0 * spec.compressor.attackMs))
    val release = exp(-1.0 / (48.0 * spec.compressor.releaseMs))
    val makeup = 10.0.pow(spec.compressor.makeupDb / 20.0)
}
