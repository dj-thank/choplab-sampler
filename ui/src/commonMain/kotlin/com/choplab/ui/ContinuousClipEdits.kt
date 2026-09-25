package com.choplab.ui

import com.choplab.core.edit.Intent
import com.choplab.core.model.*

/** Converts one finished UI gesture into one Studio/Undo edit. Never mutates a document. */
object ContinuousClipEdits {
    const val MAX_TIMELINE_FRAMES = 48_000L * 60 * 30

    fun startFrame(project: Project, clip: Clip): Long = clip.timelineStartFrame
        ?: ((clip.startTick * 3_000_000L + project.tempo.milliBpm - 1) / project.tempo.milliBpm)

    fun durationFrames(project: Project, clip: Clip): Long {
        val rate = project.asset(clip.assetHash).sampleRate
        return (clip.range.end * 48_000 + rate - 1) / rate - (clip.range.start * 48_000 + rate - 1) / rate
    }

    fun intent(project: Project, action: ContinuousEditorAction, freshId: (String) -> String): Intent.SetArrangement {
        var tracks: List<Track> = project.tracks
        var clips: List<Clip> = project.clips
        fun selected(id: String) = requireNotNull(clips.firstOrNull { it.id == id }) { "Clip no longer exists" }
        fun replace(clip: Clip) { clips = clips.map { if (it.id == clip.id) clip else it } }
        when (action) {
            is ContinuousEditorAction.PlacePad -> {
                val pad = project.pads.getOrNull(action.padId) ?: error("Unknown PAD")
                require(pad.pitchSemitones == 0.0 && !pad.reverse) { "Render the transformed PAD before placement" }
                val hash = requireNotNull(pad.assetHash) { "Empty PAD" }
                val range = requireNotNull(pad.range)
                val track = action.trackId?.let { id -> requireNotNull(tracks.firstOrNull { it.id == id }) }
                    ?: tracks.firstOrNull { it.kind == TrackKind.BANK }
                    ?: Track(freshId("track"), project.banks[pad.id / 16].name, TrackKind.BANK).also { tracks = tracks + it }
                clips = clips + Clip(freshId("clip"), track.id, hash, range,
                    timelineStartFrame = action.timelineFrame, gain = pad.gain, pan = pad.pan)
            }
            is ContinuousEditorAction.MoveClip -> {
                require(tracks.any { it.id == action.trackId })
                replace(selected(action.clipId).copy(trackId = action.trackId, timelineStartFrame = action.timelineStartFrame))
            }
            is ContinuousEditorAction.TrimClip -> replace(selected(action.clipId).copy(
                range = FrameRange(action.sourceStartFrame, action.sourceEndFrame), timelineStartFrame = action.timelineStartFrame))
            is ContinuousEditorAction.SplitClip -> {
                val old = selected(action.clipId)
                val delta = action.timelineFrame - startFrame(project, old)
                require(delta in 1 until durationFrames(project, old))
                val sourceCut = old.range.start + delta * project.asset(old.assetHash).sampleRate / 48_000
                require(sourceCut > old.range.start && sourceCut < old.range.end)
                val left = old.copy(range = FrameRange(old.range.start, sourceCut))
                val right = old.copy(id = freshId("clip"), range = FrameRange(sourceCut, old.range.end),
                    timelineStartFrame = startFrame(project, old) + durationFrames(project, left))
                clips = clips.flatMap { if (it.id == old.id) listOf(left, right) else listOf(it) }
            }
            is ContinuousEditorAction.DuplicateClip -> {
                val old = selected(action.clipId)
                clips = clips + old.copy(id = freshId("clip"), timelineStartFrame = startFrame(project, old) + durationFrames(project, old))
            }
            is ContinuousEditorAction.DeleteClip -> { selected(action.clipId); clips = clips.filterNot { it.id == action.clipId } }
            is ContinuousEditorAction.SetClipGain -> replace(selected(action.clipId).copy(gain = action.gain))
            is ContinuousEditorAction.SetTrackMuted -> {
                require(tracks.any { it.id == action.trackId })
                tracks = tracks.map { if (it.id == action.trackId) it.copy(mute = action.muted) else it }
            }
            else -> error("Not an arrangement edit")
        }
        require(tracks.size <= 16 && clips.size <= 1024)
        clips.forEach { clip ->
            require(clip.range.end <= project.asset(clip.assetHash).frames)
            require(startFrame(project, clip) + durationFrames(project, clip) <= MAX_TIMELINE_FRAMES)
        }
        return Intent.SetArrangement(tracks.frozen(), clips.frozen(), project.takes)
    }
}
