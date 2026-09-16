package com.choplab.sampler.midi

import kotlin.math.abs
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

/** Runtime monitor mix only. Never edits PADs, project snapshots or offline exports. */
data class DdjMixerSettings(
    val enabled: Boolean = false,
    val crossfader: Float = 0.5f,
    val leftFader: Float = 1f,
    val rightFader: Float = 1f,
    val leftLow: Float = 0.5f,
    val leftMid: Float = 0.5f,
    val leftHigh: Float = 0.5f,
    val rightLow: Float = 0.5f,
    val rightMid: Float = 0.5f,
    val rightHigh: Float = 0.5f,
    val cueLeft: Boolean = false,
    val cueRight: Boolean = false,
    val cueMaster: Boolean = false,
    val splitCue: Boolean = false,
) {
    init {
        require(listOf(crossfader, leftFader, rightFader, leftLow, leftMid, leftHigh,
            rightLow, rightMid, rightHigh).all { it.isFinite() && it in 0f..1f })
    }
    // Derived on the control thread, never exp/pow or allocation in the audio callback.
    val gainLeft = leftFader * min(1f, 2f * (1f - crossfader))
    val gainRight = rightFader * min(1f, 2f * crossfader)
    val lowLeft = eqGain(leftLow)
    val midLeft = eqGain(leftMid)
    val highLeft = eqGain(leftHigh)
    val lowRight = eqGain(rightLow)
    val midRight = eqGain(rightMid)
    val highRight = eqGain(rightHigh)

    companion object {
        /** Center is 0 dB; either half spans -26..0 or 0..+6 dB. */
        fun eqGain(normalized: Float): Float {
            require(normalized.isFinite() && normalized in 0f..1f)
            val db = if (normalized <= 0.5f) (normalized - 0.5f) * 52f else (normalized - 0.5f) * 12f
            return 10.0.pow(db / 20.0).toFloat()
        }
    }
}

/** Stable routing across banks: PADs 1-8/17-24 left, 9-16/25-32 right; previews left. */
fun ddjPadBus(padIndex: Int): Int = if (padIndex < 0) 0 else (padIndex % 32 / 8) % 2

/** Separate parser so unmapped mixer messages cannot change a selected PAD's saved gain. */
class DdjMixerMidi(private val publish: (DdjMixerSettings) -> Unit) {
    var settings = DdjMixerSettings()
        private set
    private val msb = IntArray(16 * 32) { -1 }
    private val lsb = IntArray(16 * 32) { -1 }
    private val pickup = Array(9) { MidiPickup() }
    private val heldCue = BooleanArray(3)
    val stream = MidiByteStream(::message)

    fun reset(enabled: Boolean = false) {
        msb.fill(-1); lsb.fill(-1); heldCue.fill(false)
        pickup.forEach { it.reset() }; stream.reset()
        update(DdjMixerSettings(enabled = enabled))
    }

    fun setSplitCue(enabled: Boolean) {
        // Enabling monitoring must be an explicit screen action, not a MIDI press.
        if (settings.enabled) update(settings.copy(splitCue = enabled))
    }

    private fun update(next: DdjMixerSettings) { settings = next; publish(next) }

    private fun message(status: Int, number: Int, value: Int) {
        if (!settings.enabled) return
        val channel = status and 15
        val type = status and 0xf0
        if (type == 0x90 || type == 0x80) {
            val cue = when {
                channel in 0..1 && number in listOf(0x54, 0x68) -> channel
                channel == 6 && number in listOf(0x63, 0x78) -> 2
                else -> return
            }
            val down = type == 0x90 && value > 0
            if (!down) { heldCue[cue] = false; return }
            if (heldCue[cue]) return
            heldCue[cue] = true
            update(when (cue) {
                0 -> settings.copy(cueLeft = !settings.cueLeft)
                1 -> settings.copy(cueRight = !settings.cueRight)
                else -> settings.copy(cueMaster = !settings.cueMaster)
            })
            return
        }
        if (type != 0xb0 || number !in 0..63) return
        val low = number >= 32
        val base = number and 31
        val slot = when {
            channel == 6 && base == 0x1f -> 0
            channel in 0..1 && base == 0x13 -> 1 + channel
            channel in 0..1 && base == 0x0f -> 3 + channel * 3
            channel in 0..1 && base == 0x0b -> 4 + channel * 3
            channel in 0..1 && base == 0x07 -> 5 + channel * 3
            else -> return
        }
        val key = channel * 32 + base
        if (low) lsb[key] = value else msb[key] = value
        if (msb[key] < 0 || lsb[key] < 0) return
        val normalized = ((msb[key] shl 7) or lsb[key]) / 16383f
        msb[key] = -1; lsb[key] = -1
        val current = when (slot) {
            0 -> settings.crossfader; 1 -> settings.leftFader; 2 -> settings.rightFader
            3 -> settings.leftLow; 4 -> settings.leftMid; 5 -> settings.leftHigh
            6 -> settings.rightLow; 7 -> settings.rightMid; else -> settings.rightHigh
        }
        if (!pickup[slot].accept(normalized, current)) return
        update(when (slot) {
            0 -> settings.copy(crossfader = normalized)
            1 -> settings.copy(leftFader = normalized)
            2 -> settings.copy(rightFader = normalized)
            3 -> settings.copy(leftLow = normalized)
            4 -> settings.copy(leftMid = normalized)
            5 -> settings.copy(leftHigh = normalized)
            6 -> settings.copy(rightLow = normalized)
            7 -> settings.copy(rightMid = normalized)
            else -> settings.copy(rightHigh = normalized)
        })
    }
}

/**
 * Four independent stereo-band states, two buses, no per-frame allocations/locks.
 * Broad LOW/MID/HIGH tone EQ (250 Hz / 4 kHz one-pole decomposition), not an
 * emulation of a manufacturer's filter. Cue is post-EQ, pre-channel/crossfader.
 * Output is PRE-limiter; the existing Android master limiter remains authoritative.
 */
class DdjMixerDsp(sampleRate: Int) {
    init { require(sampleRate in 8000..384000) }
    private val lowCoefficient = (1.0 - exp(-2.0 * PI * 250.0 / sampleRate)).toFloat()
    private val highCoefficient = (1.0 - exp(-2.0 * PI * 4000.0 / sampleRate)).toFloat()
    private val smoothing = (1.0 - exp(-1.0 / (sampleRate * 0.005))).toFloat()
    private val lowState = FloatArray(4)
    private val highState = FloatArray(4)
    private val gains = FloatArray(8) { 1f }
    private val cues = FloatArray(3)
    var left: Float = 0f
        private set
    var right: Float = 0f
        private set

    /** Reset only at an idle boundary or connection change; preserve current mute/cue values. */
    fun reset(s: DdjMixerSettings) {
        lowState.fill(0f); highState.fill(0f)
        gains[0] = s.gainLeft; gains[1] = s.gainRight
        gains[2] = s.lowLeft; gains[3] = s.midLeft; gains[4] = s.highLeft
        gains[5] = s.lowRight; gains[6] = s.midRight; gains[7] = s.highRight
        cues[0] = if (s.cueLeft) 1f else 0f
        cues[1] = if (s.cueRight) 1f else 0f
        cues[2] = if (s.cueMaster) 1f else 0f
        left = 0f; right = 0f
    }

    private fun smooth(current: Float, target: Float): Float {
        val next = current + smoothing * (target - current)
        // Arrive at exact zero/neutral; avoid endless residuals at mute endpoints.
        return if (abs(next - target) < 0.000001f) target else next
    }

    fun process(aLeft: Float, aRight: Float, bLeft: Float, bRight: Float, s: DdjMixerSettings) {
        if (!s.enabled) { left = aLeft + bLeft; right = aRight + bRight; return }
        gains[0] = smooth(gains[0], s.gainLeft)
        gains[1] = smooth(gains[1], s.gainRight)
        gains[2] = smooth(gains[2], s.lowLeft)
        gains[3] = smooth(gains[3], s.midLeft)
        gains[4] = smooth(gains[4], s.highLeft)
        gains[5] = smooth(gains[5], s.lowRight)
        gains[6] = smooth(gains[6], s.midRight)
        gains[7] = smooth(gains[7], s.highRight)
        val al = band(aLeft, 0, 2); val ar = band(aRight, 1, 2)
        val bl = band(bLeft, 2, 5); val br = band(bRight, 3, 5)
        val masterLeft = al * gains[0] + bl * gains[1]
        val masterRight = ar * gains[0] + br * gains[1]
        cues[0] = smooth(cues[0], if (s.cueLeft) 1f else 0f)
        cues[1] = smooth(cues[1], if (s.cueRight) 1f else 0f)
        cues[2] = smooth(cues[2], if (s.cueMaster) 1f else 0f)
        val masterMono = (masterLeft + masterRight) * 0.5f
        val cueMono = ((al + ar) * cues[0] + (bl + br) * cues[1]) * 0.5f + masterMono * cues[2]
        // UI stops playback when switching. Do not crossfade the routing matrix:
        // at split=true the right output must NEVER contain unselected master audio.
        left = if (s.splitCue) masterMono else masterLeft
        right = if (s.splitCue) cueMono else masterRight
    }

    private fun band(input: Float, channel: Int, gain: Int): Float {
        val value = if (input.isFinite()) input else 0f
        lowState[channel] += lowCoefficient * (value - lowState[channel])
        highState[channel] += highCoefficient * (value - highState[channel])
        return value * gains[gain + 2] +
            lowState[channel] * (gains[gain] - gains[gain + 1]) +
            highState[channel] * (gains[gain + 1] - gains[gain + 2])
    }
}
