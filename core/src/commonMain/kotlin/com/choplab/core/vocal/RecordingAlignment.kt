package com.choplab.core.vocal

/** Clock names describe domains, never a physical device identifier. All of these values are session-only. */
enum class AudioClockDomain { HOST_MONOTONIC, INPUT_HARDWARE, OUTPUT_HARDWARE, ENGINE_RENDER }

data class AudioRouteIdentity(
    /** Host increments on every route replacement/interruption, even when the numeric format is unchanged. */
    val generation: Long,
    val inputRate: Int,
    val outputRate: Int,
    val inputBufferFrames: Int?,
    val outputBufferFrames: Int?,
    val inputClock: AudioClockDomain,
    val outputClock: AudioClockDomain,
) {
    init {
        require(generation >= 0 && inputRate in 8_000..192_000 && outputRate in 8_000..192_000)
        require(inputBufferFrames == null || inputBufferFrames in 1..192_000)
        require(outputBufferFrames == null || outputBufferFrames in 1..192_000)
    }
}

enum class AlignmentStatus { UNMEASURED, ESTIMATED, MANUAL, MEASURED, INVALIDATED }
enum class ClockCorrelation { SAME_DOMAIN, MEASURED_RELATION }

/**
 * An external route-specific observation, not a promise made by a sample counter or an estimated output queue.
 * Error and drift accompany the offset; UI must not present MEASURED alone as the +/-5 ms acceptance result.
 */
data class AlignmentMeasurement(
    val correctionFrames48k: Int,
    val repetitions: Int,
    val p95AbsoluteErrorFrames48k: Double,
    val maximumAbsoluteErrorFrames48k: Double,
    val clockCorrelationErrorFrames48k: Double,
    val driftPpm: Double,
    val correlation: ClockCorrelation,
) {
    init {
        require(correctionFrames48k in -480_000..480_000 && repetitions in 2..100_000)
        require(p95AbsoluteErrorFrames48k.isFinite() && p95AbsoluteErrorFrames48k >= 0)
        require(maximumAbsoluteErrorFrames48k.isFinite() && maximumAbsoluteErrorFrames48k >= p95AbsoluteErrorFrames48k)
        require(clockCorrelationErrorFrames48k.isFinite() && clockCorrelationErrorFrames48k >= 0)
        require(driftPpm.isFinite() && driftPpm in -100_000.0..100_000.0)
    }
}

/** The applied offset is zero after route/format/buffer/clock change until a new explicit adjustment is supplied. */
data class RecordingAlignment private constructor(
    val route: AudioRouteIdentity?,
    val status: AlignmentStatus,
    val correctionFrames48k: Int,
    val measurement: AlignmentMeasurement? = null,
) {
    init {
        require(correctionFrames48k in -480_000..480_000)
        when (status) {
            AlignmentStatus.UNMEASURED, AlignmentStatus.INVALIDATED -> require(correctionFrames48k == 0 && measurement == null)
            AlignmentStatus.ESTIMATED, AlignmentStatus.MANUAL -> require(route != null && measurement == null)
            AlignmentStatus.MEASURED -> {
                require(route != null && route.inputBufferFrames != null && route.outputBufferFrames != null)
                require(measurement != null && correctionFrames48k == measurement.correctionFrames48k)
                require(measurement.correlation != ClockCorrelation.SAME_DOMAIN || route.inputClock == route.outputClock)
            }
        }
    }
    fun routeChanged(current: AudioRouteIdentity?): RecordingAlignment = when {
        current == route -> this
        status == AlignmentStatus.UNMEASURED -> unmeasured(current)
        else -> RecordingAlignment(current, AlignmentStatus.INVALIDATED, 0)
    }

    fun manual(current: AudioRouteIdentity, frames48k: Int): RecordingAlignment {
        require(frames48k in -480_000..480_000)
        return RecordingAlignment(current, AlignmentStatus.MANUAL, frames48k)
    }

    /** A host/device estimate stays distinguishable from both a user adjustment and a route measurement. */
    fun estimated(current: AudioRouteIdentity, frames48k: Int): RecordingAlignment =
        RecordingAlignment(current, AlignmentStatus.ESTIMATED, frames48k)

    fun measured(current: AudioRouteIdentity, evidence: AlignmentMeasurement): RecordingAlignment {
        require(current.inputBufferFrames != null && current.outputBufferFrames != null) { "Measurement needs the route's buffer identity" }
        require(evidence.correlation != ClockCorrelation.SAME_DOMAIN || current.inputClock == current.outputClock)
        return RecordingAlignment(current, AlignmentStatus.MEASURED, evidence.correctionFrames48k, evidence)
    }

    /** Recheck identity at the capture/apply boundary, not just when the settings panel last recomposed. */
    fun framesFor(current: AudioRouteIdentity?): Int = if (route == current && status in setOf(AlignmentStatus.ESTIMATED, AlignmentStatus.MANUAL, AlignmentStatus.MEASURED))
        correctionFrames48k else 0

    companion object { fun unmeasured(route: AudioRouteIdentity? = null) = RecordingAlignment(route, AlignmentStatus.UNMEASURED, 0) }
}
