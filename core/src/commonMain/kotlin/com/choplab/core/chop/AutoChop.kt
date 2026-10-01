package com.choplab.core.chop

import com.choplab.core.model.*
import kotlin.math.pow

enum class AutoChopMode { EQUAL, ATTACK }
data class AutoChopSettings(val mode: AutoChopMode = AutoChopMode.EQUAL, val slices: Int = 16,
                            val thresholdDb: Int = -36, val minimumGapMs: Int = 50) {
    init {
        require(slices in 1..128 && thresholdDb in -60..-6 && minimumGapMs in 10..500)
    }
}
enum class AutoChopProblem { TOO_SHORT, NO_ATTACKS, TOO_DENSE, INVALID_AUDIO, NO_MEMORY, FAILED, BUSY, RECORDING, STALE, CLOSED, UNAVAILABLE }
sealed interface AutoChopResult {
    data class Ready(val markers: FrozenList<Long>) : AutoChopResult
    data class Refused(val problem: AutoChopProblem) : AutoChopResult
}

/** Worker-only proposal. It never edits the document, PCM, original bytes or PADs. Positions are native frames. */
fun interface AutoChopPort {
    suspend fun prepare(asset: Asset, range: FrameRange, settings: AutoChopSettings): AutoChopResult
}

object AutoChop {
    fun equal(range: FrameRange, count: Int): AutoChopResult {
        require(count in 1..128)
        if (range.length < count) return AutoChopResult.Refused(AutoChopProblem.TOO_SHORT)
        return AutoChopResult.Ready((1 until count).map { range.start + range.length * it / count }.frozen())
    }
}

/**
 * Streaming energy-rise detector for normalized 48 kHz stereo. 1 ms hops, preceding 32 ms energy floor,
 * 6 dB rise over the preceding hop and floor. Stereo power uses the louder channel; opposite polarities
 * cannot cancel an attack. Threshold is an absolute dBFS floor, not a probability or quality score.
 * A refractory interval merges close attacks; excess remaining cuts refuse the whole proposal.
 */
class AttackChopDetector(private val firstFrame: Long, private val endFrame: Long, private val settings: AutoChopSettings) {
    private val threshold = 10.0.pow(settings.thresholdDb / 10.0)
    private val gap = settings.minimumGapMs * 48L
    private val floor = DoubleArray(32)
    private var floorSum = 0.0
    private var floorAt = 0
    private var previous = 0.0
    private var position = firstFrame
    private var hopStart = firstFrame
    private var energy = 0.0
    private var samples = 0
    private var firstLoud = -1L
    private var lastAttack = firstFrame
    private val markers = ArrayList<Long>(127)
    private var overflow = false
    private var invalid = false

    init { require(firstFrame >= 0 && endFrame > firstFrame && settings.mode == AutoChopMode.ATTACK) }

    fun accept(stereo: FloatArray, checkCancelled: () -> Unit = {}) {
        require(stereo.size % 2 == 0 && position + stereo.size / 2 <= endFrame)
        for (at in stereo.indices step 2) {
            if (at % 512 == 0) checkCancelled()
            val left = stereo[at].toDouble()
            val right = stereo[at + 1].toDouble()
            if (!left.isFinite() || !right.isFinite()) invalid = true
            val power = maxOf(left * left, right * right)
            energy += power
            if (firstLoud < 0 && power >= threshold) firstLoud = position
            position++; samples++
            if (samples == 48) hop()
        }
    }

    private fun hop() {
        val mean = energy / samples
        val background = floorSum / floor.size
        val candidate = if (firstLoud >= 0) firstLoud else hopStart
        if (mean >= threshold && mean > previous * 4.0 && mean > background * 4.0 &&
            candidate >= firstFrame + gap && candidate <= endFrame - gap && candidate - lastAttack >= gap) {
            lastAttack = candidate
            if (markers.size >= settings.slices - 1) overflow = true else markers.add(candidate)
        }
        floorSum += mean - floor[floorAt]
        floor[floorAt] = mean; floorAt = (floorAt + 1) % floor.size
        previous = mean
        energy = 0.0; samples = 0; firstLoud = -1; hopStart = position
    }

    fun finish(): AutoChopResult {
        require(position == endFrame)
        if (samples > 0) hop()
        return when {
            invalid -> AutoChopResult.Refused(AutoChopProblem.INVALID_AUDIO)
            endFrame - firstFrame < 2 * gap -> AutoChopResult.Refused(AutoChopProblem.TOO_SHORT)
            overflow -> AutoChopResult.Refused(AutoChopProblem.TOO_DENSE)
            markers.isEmpty() -> AutoChopResult.Refused(AutoChopProblem.NO_ATTACKS)
            else -> AutoChopResult.Ready(markers.frozen())
        }
    }
}
