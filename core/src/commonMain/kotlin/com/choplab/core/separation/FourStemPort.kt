package com.choplab.core.separation

import com.choplab.core.edit.Intent
import com.choplab.core.model.*

enum class StemPart { DRUMS, BASS, OTHER, VOCALS }
enum class StemMix { ALL, ACAPELLA, INSTRUMENTAL }
enum class SeparationProblem {
    MODEL_MISSING, MODEL_INVALID, DOWNLOAD_FAILED, INVALID_INPUT, INVALID_OUTPUT, TOO_LONG,
    RAM_UNAVAILABLE, LOW_MEMORY, PCM_LIMIT, NO_SPACE, CANCELLED, CLOSED, BUSY, FAILED, STALE_DOCUMENT, RECORDING,
}
data class SeparationFailure(val problem: SeparationProblem)
sealed interface SeparationResult<out T> {
    data class Success<T>(val value: T) : SeparationResult<T>
    data class Failure(val failure: SeparationFailure) : SeparationResult<Nothing>
}
fun separationFailure(problem: SeparationProblem) = SeparationResult.Failure(SeparationFailure(problem))
data class SeparatedStem(val part: StemPart, val asset: Asset)
data class PreparedFourStems(val sourceHash: String, val modelSha256: String, val stems: FrozenList<SeparatedStem>) {
    init {
        requireHash(sourceHash); requireHash(modelSha256)
        require(stems.map { it.part } == StemPart.entries)
        val first = stems.first().asset
        require(stems.all { ((it.asset.role == AssetRole.RENDERED && it.asset.derivedFrom == sourceHash) || it.asset.hash == sourceHash) &&
            it.asset.sampleRate == 44_100 && it.asset.channels == 2 && it.asset.frames == first.frames && it.asset.required })
    }
    /** Existing source/lyric/take/comp state stays intact; all four assets join the song in one explicit edit. */
    fun placement(project: Project, startTick: Long, mix: StemMix, idPrefix: String): SeparationResult<Intent.SetArrangement> {
        if (project.source?.assetHash != sourceHash) return separationFailure(SeparationProblem.STALE_DOCUMENT)
        return try {
            requireId(idPrefix); require(idPrefix.length <= 48)
            val tracks = stems.map { stem ->
                val muted = when (mix) { StemMix.ALL -> false; StemMix.ACAPELLA -> stem.part != StemPart.VOCALS; StemMix.INSTRUMENTAL -> stem.part == StemPart.VOCALS }
                Track("$idPrefix-${stem.part.name.lowercase()}", stem.part.name.lowercase(), TrackKind.STEM, mute = muted)
            }
            require(tracks.none { next -> project.tracks.any { it.id == next.id } })
            val clips = stems.mapIndexed { index, stem -> Clip("$idPrefix-$index", tracks[index].id, stem.asset.hash,
                FrameRange(0, stem.asset.frames), startTick) }
            SeparationResult.Success(Intent.SetArrangement((project.tracks + tracks).frozen(), (project.clips + clips).frozen(), project.takes,
                stems.map { it.asset }.distinctBy { it.hash }.frozen()))
        } catch (_: IllegalArgumentException) { separationFailure(SeparationProblem.INVALID_INPUT) }
    }
}

data class SeparationProgress(val completedFrames: Long, val totalFrames: Long) {
    init { require(totalFrames > 0 && completedFrames in 0..totalFrames) }
}
/** One worker session; cancellation requests stop native inference but do not release its memory before it exits. */
interface FourStemPort {
    suspend fun prepare(source: Asset, allowModelDownload: Boolean = false,
                        progress: (SeparationProgress) -> Unit = {}): SeparationResult<PreparedFourStems>
    fun cancel()
    fun close()
}
