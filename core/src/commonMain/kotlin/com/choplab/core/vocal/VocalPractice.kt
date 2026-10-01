package com.choplab.core.vocal

import com.choplab.core.PlaybackTarget
import com.choplab.core.model.Asset
import com.choplab.core.model.Project
import com.choplab.core.model.ProjectLimits
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.floor

/** A transient audition request. Song frame ranges are start-inclusive/end-exclusive at 48 kHz. */
data class VocalPracticeRequest(val startFrame: Long, val endFrame: Long, val speed: Double = 1.0,
                               val target: PlaybackTarget.Arrangement = PlaybackTarget.Arrangement()) {
    init {
        require(startFrame >= 0 && endFrame > startFrame && endFrame <= ProjectLimits.MAX_TIMELINE_FRAMES)
        require(speed.isFinite() && speed in .6..1.6)
        require(endFrame - startFrame <= MAX_FRAMES && target.minimumFrames == 0L)
        require(outputFrames in 1..MAX_FRAMES)
        require(speed == 1.0 || minOf(inputFrames, outputFrames) >= 128)
    }
    val inputFrames: Int get() = (endFrame - startFrame).toInt()
    val outputFrames: Int get() = (inputFrames / speed).roundToInt().coerceIn(ceil(inputFrames / 1.6).toInt(), floor(inputFrames / .6).toInt())
    companion object { const val MAX_FRAMES = 48_000 * 30 }
}
enum class PracticeProblem { INVALID_RANGE, NO_AUDIO, LIMIT, PCM_UNAVAILABLE, FAILED, STALE, RECORDING, BUSY, CANCELLED, NO_OUTPUT, RESTORE_FAILED }
sealed interface PracticeResult<out T> {
    data class Success<T>(val value: T) : PracticeResult<T>
    data class Failure(val problem: PracticeProblem) : PracticeResult<Nothing>
}
data class PracticeProgress(val renderedFrames: Long, val totalFrames: Long)
interface VocalPracticeRenderer {
    /** Worker-only; shares the production mix/export graph and does not edit the document. */
    suspend fun render(project: Project, revision: Long, request: VocalPracticeRequest,
                       progress: (PracticeProgress) -> Unit = {}): PracticeResult<Asset>
}
