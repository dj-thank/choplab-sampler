package com.choplab.core.vocal

import com.choplab.core.PlaybackTarget
import com.choplab.core.ProgramCompiler
import com.choplab.core.edit.Intent
import com.choplab.core.edit.Reducer
import com.choplab.core.model.*

enum class VocalProblem { INVALID_INPUT, NO_TAKES, NO_TIMED_LINES, TAKE_TOO_SHORT, LIMIT, BUSY, RECORDING, STALE, RENDER_FAILED, PREVIEW_FAILED, APPLY_FAILED, CLOSED }
class VocalEditException(val problem: VocalProblem) : IllegalArgumentException(problem.name)

data class VocalCompDraft(val id: String, val segments: FrozenList<VocalCompSegment>, val replacing: String? = null) {
    init { requireId(id); validateCompSegments(segments); replacing?.let(::requireId) }
    val startFrame: Long get() = segments.first().startFrame
    val endFrame: Long get() = segments.last().endFrame
}

object VocalCompEdits {
    /** A full candidate remains a candidate, without modifying its source bytes or automatically mixing all takes. */
    fun retain(project: Project, asset: Asset, take: Take, track: Track? = null, pad: Pad? = null, clip: Clip? = null): Intent.AddVoiceTake {
        if (project.takes.size >= 1024) throw VocalEditException(VocalProblem.LIMIT)
        val intent = Intent.AddVoiceTake(asset, pad, clip, track, take)
        Reducer.reduce(project, intent)
        return intent
    }

    fun fullTake(project: Project, takeId: String, id: String): VocalCompDraft {
        val take = project.takes.firstOrNull { it.id == takeId } ?: throw VocalEditException(VocalProblem.NO_TAKES)
        val start = take.correctedStartFrame().coerceAtLeast(0)
        val end = take.correctedEndFrame(project.asset(take.assetHash))
        if (end <= start) throw VocalEditException(VocalProblem.TAKE_TOO_SHORT)
        return VocalCompDraft(id, frozenListOf(VocalCompSegment("whole", takeId, start, end)))
    }

    /** Lyrics use the song's tick clock, not swung PAD-event timing. Saved choices then stay at absolute frames. */
    fun lines(project: Project, takeId: String, id: String): VocalCompDraft {
        if (project.lyrics.isEmpty()) throw VocalEditException(VocalProblem.NO_TIMED_LINES)
        val segments = project.lyrics.sortedBy { it.startTick }.map { line ->
            VocalCompSegment(line.id, takeId, ProgramCompiler.tickToFrame(line.startTick, project.tempo.milliBpm),
                ProgramCompiler.tickToFrame(line.endTick, project.tempo.milliBpm), line.id)
        }.frozen()
        return VocalCompDraft(id, segments).also { validate(project, it) }
    }

    fun choose(project: Project, draft: VocalCompDraft, segmentId: String, takeId: String): VocalCompDraft {
        require(draft.segments.any { it.id == segmentId })
        return draft.copy(segments = draft.segments.map { if (it.id == segmentId) it.copy(takeId = takeId) else it }.frozen())
            .also { validate(project, it) }
    }

    fun validate(project: Project, draft: VocalCompDraft) {
        validateCompSegments(draft.segments)
        if (44 + (draft.endFrame - draft.startFrame) * 8 > ProjectLimits.MAX_ASSET_BYTES) throw VocalEditException(VocalProblem.LIMIT)
        for (line in draft.segments) {
            val take = project.takes.firstOrNull { it.id == line.takeId } ?: throw VocalEditException(VocalProblem.NO_TAKES)
            if (line.startFrame < take.correctedStartFrame() || line.endFrame > take.correctedEndFrame(project.asset(take.assetHash)))
                throw VocalEditException(VocalProblem.TAKE_TOO_SHORT)
        }
    }

    /** The caller chooses exactly which old placements are replaced; unrelated song clips always survive. */
    fun apply(project: Project, draft: VocalCompDraft, rendered: Asset, track: Track, clipId: String,
              replaceClipIds: Set<String> = emptySet()): Intent.SetArrangement {
        validate(project, draft)
        require(replaceClipIds.all { id -> project.clips.any { it.id == id } })
        val selectedHashes = draft.segments.map { segment -> project.takes.first { it.id == segment.takeId }.assetHash }.toSet()
        val oldComp = draft.replacing?.let { id -> requireNotNull(project.vocalComps.firstOrNull { it.id == id }) }
        require(project.clips.filter { it.id in replaceClipIds }.all { it.assetHash in selectedHashes || it.assetHash == oldComp?.renderedAssetHash })
        require(project.clips.none { it.id == clipId && it.id !in replaceClipIds })
        require(track.kind == TrackKind.VOCAL)
        require(rendered.sampleRate == 48_000 && rendered.channels == 2 && rendered.role == AssetRole.RENDERED && rendered.required)
        require(rendered.frames == draft.endFrame - draft.startFrame)
        val comp = VocalComp(draft.id, rendered.hash, draft.segments)
        val comps = project.vocalComps.filterNot { it.id == draft.replacing } + comp
        require(comps.size <= 64 && comps.map { it.id }.distinct().size == comps.size)
        val clip = Clip(clipId, track.id, rendered.hash, FrameRange(0, rendered.frames), timelineStartFrame = draft.startFrame)
        val tracks = if (project.tracks.any { it.id == track.id }) project.tracks else (project.tracks + track).frozen()
        val intent = Intent.SetArrangement(tracks, (project.clips.filterNot { it.id in replaceClipIds } + clip).frozen(),
            project.takes, frozenListOf(rendered), comps.frozen())
        Reducer.reduce(project, intent)
        return intent
    }

    /** An ephemeral preview, never a document edit. The explicit target uses the existing take compiler. */
    fun audition(project: Project, takeId: String): Pair<Project, PlaybackTarget.Arrangement> {
        require(project.takes.any { it.id == takeId })
        val voiceTracks = project.tracks.filter { it.kind == TrackKind.VOCAL }.map { it.id }.toSet()
        return project.copy(clips = project.clips.filterNot { it.trackId in voiceTracks }.frozen()) to
            PlaybackTarget.Arrangement(frozenListOf(takeId))
    }
}
