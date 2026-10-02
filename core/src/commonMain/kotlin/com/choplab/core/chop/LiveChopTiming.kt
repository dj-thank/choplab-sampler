package com.choplab.core.chop

import com.choplab.core.model.FrameRange
import kotlin.math.pow
import kotlin.math.roundToLong

/** Ephemeral identities only. Never serialize these tokens or substitute a platform device identifier. */
data class LiveChopRoute(
    val outputSession: Any,
    val engineClock: Any,
    val clockEpoch: Long,
    val sampleRate: Int,
    val channels: Int,
    val floatOutput: Boolean,
    val bufferFrames: Int?,
    val blockFrames: Int,
) {
    init {
        require(sampleRate > 0 && channels > 0 && blockFrames > 0)
        require(bufferFrames == null || bufferFrames > 0)
    }
}

/** A coherent control-side reading. SOURCE and engine frames are in the engine's 48 kHz clock.
 * [eventNanos] is when the UI callback samples the output, not a hardware touch timestamp.
 * [estimatedDelayNanos] includes queued output, the rendered unwritten block and limiter lookahead.
 * It is an estimate, never a physical round-trip measurement. Null means the route did not report timing.
 */
data class LiveChopOutput(
    val route: LiveChopRoute,
    val eventNanos: Long,
    val engineFrame: Long,
    val sourceFrame: Long,
    val sourcePlaying: Boolean,
    val estimatedDelayNanos: Long?,
)

/** A contended frame snapshot has a verified route, but no position from which to make a cut. */
sealed interface LiveChopProbe {
    val route: LiveChopRoute?
    data class Ready(val output: LiveChopOutput) : LiveChopProbe {
        override val route get() = output.route
    }
    data class Contended(override val route: LiveChopRoute) : LiveChopProbe
    data object Unavailable : LiveChopProbe { override val route: LiveChopRoute? = null }
}

enum class LiveChopTimingMode { ESTIMATED, MANUAL }

/** Total output delay in manual mode, local to the current route and editor session. */
data class LiveChopCorrection(val mode: LiveChopTimingMode = LiveChopTimingMode.ESTIMATED, val manualMillis: Int = 0) {
    init { require(manualMillis in 0..1_000) }
    fun delayNanos(output: LiveChopOutput): Long? = when (mode) {
        LiveChopTimingMode.ESTIMATED -> output.estimatedDelayNanos
        LiveChopTimingMode.MANUAL -> manualMillis * 1_000_000L
    }
}

/** Requested and applied native cut frames are distinct from an EngineCommand's scheduling receipt. */
data class LiveChopCut(
    val eventNanos: Long,
    val engineFrame: Long,
    val observedSourceFrame: Long,
    val requestedSourceFrame: Long,
    val appliedSourceFrame: Long,
    val delayNanos: Long,
    val mode: LiveChopTimingMode,
)

fun liveChopCut(output: LiveChopOutput, correction: LiveChopCorrection, sampleRate: Int,
                pitchSemitones: Double, range: FrameRange): LiveChopCut? {
    require(sampleRate in 8_000..192_000 && pitchSemitones.isFinite() && pitchSemitones in -24.0..24.0)
    if (!output.sourcePlaying || output.sourceFrame < 0) return null
    val delay = correction.delayNanos(output)?.takeIf { it in 0L..1_000_000_000L } ?: return null
    val native = output.sourceFrame * sampleRate / 48_000L
    val requested = native - (delay * (sampleRate / 1_000_000_000.0) * 2.0.pow(pitchSemitones / 12.0)).roundToLong()
    if (requested >= range.end) return null
    return LiveChopCut(output.eventNanos, output.engineFrame, native, requested, requested.coerceAtLeast(range.start), delay, correction.mode)
}
