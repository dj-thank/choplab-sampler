package com.choplab.core.vocal

import com.choplab.core.model.*

enum class CoachMode { SINGING, RAP }
/** Accompaniment contamination cannot be established from monophonic periodicity alone. */
enum class CoachVoiceInput { UNCONFIRMED, VOICE_ONLY, ACCOMPANIMENT_PRESENT }
enum class CoachExclusion { NO_REFERENCE, RAP_PITCH, UNCONFIRMED_VOICE, ACCOMPANIMENT, UNVOICED_OR_UNCERTAIN, MISSING_TAKE_RANGE, PARTIAL_LINE }
enum class CoachProblem { INVALID_INPUT, LIMIT, PCM_UNAVAILABLE, STALE, RECORDING, BUSY, CANCELLED, NO_GUIDE, PREVIEW_FAILED }

data class VocalCoachRequest(val takeId: String, val referenceTakeId: String? = null,
    val startFrame: Long, val endFrame: Long, val mode: CoachMode = CoachMode.SINGING,
    val voiceInput: CoachVoiceInput = CoachVoiceInput.UNCONFIRMED) {
    init {
        requireId(takeId); referenceTakeId?.let(::requireId)
        require(referenceTakeId != takeId)
        require(startFrame >= 0 && endFrame > startFrame && endFrame <= ProjectLimits.MAX_TIMELINE_FRAMES)
        require(endFrame - startFrame <= VocalPracticeRequest.MAX_FRAMES)
    }
}

/** Values are observations, never lyric correctness, a singing score, or a percentage of correct notes. */
data class VocalCoachLine(val lineId: String?, val text: String, val startFrame: Long, val endFrame: Long,
    val onsetFromLineMillis: Int? = null, val onsetDifferenceMillis: Int? = null,
    val observedPitchHz: Int? = null, val pitchDifferenceCents: Int? = null, val meanAbsolutePitchCents: Int? = null,
    val comparedPitchHops: Int = 0, val observedPitchHops: Int = 0, val totalHops: Int = 0,
    val exclusions: FrozenList<CoachExclusion> = frozenListOf()) {
    /** Only a measured reference difference can suggest a line. Missing evidence never ranks as poor performance. */
    val differencePriority: Int get() = maxOf(kotlin.math.abs(onsetDifferenceMillis ?: 0), meanAbsolutePitchCents ?: 0)
}

data class VocalCoachReport(val revision: Long, val request: VocalCoachRequest, val lines: FrozenList<VocalCoachLine>) {
    val suggestedLine: VocalCoachLine? get() = lines.filter { it.differencePriority >= 50 }
        .maxByOrNull { it.differencePriority }
}
sealed interface CoachResult<out T> {
    data class Success<T>(val value: T) : CoachResult<T>
    data class Failure(val problem: CoachProblem) : CoachResult<Nothing>
}
interface VocalCoachAnalyzer {
    /** Worker-only local PCM observations. This operation must never edit the project or rewrite a take. */
    suspend fun analyze(project: Project, revision: Long, request: VocalCoachRequest): CoachResult<VocalCoachReport>
}
