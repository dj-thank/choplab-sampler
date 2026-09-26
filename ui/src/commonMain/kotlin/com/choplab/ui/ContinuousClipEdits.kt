package com.choplab.ui

import com.choplab.core.ProgramCompiler
import com.choplab.core.edit.Intent
import com.choplab.core.model.*

/** Converts one finished UI gesture into one Studio/Undo edit. Never mutates a document. */
object ContinuousClipEdits {
    const val MAX_TIMELINE_FRAMES = 48_000L * 60 * 30

    /** Same floor mapping as playback/export, so the drawn start is the audible start. */
    fun startFrame(project: Project, clip: Clip): Long = clip.timelineStartFrame
        ?: ProgramCompiler.tickToFrame(clip.startTick, project.tempo.milliBpm)

    /** Exact 48 kHz length used by playback/export. It is 0 for a sub-frame range above 48 kHz. */
    fun durationFrames(project: Project, clip: Clip): Long {
        val rate = project.asset(clip.assetHash).sampleRate
        return ProgramCompiler.sourceFrameTo48k(clip.range.end, rate) - ProgramCompiler.sourceFrameTo48k(clip.range.start, rate)
    }

    /** Smallest source length that always spans at least one 48 kHz timeline frame. */
    fun minimumSourceFrames(sourceRate: Int): Long {
        require(sourceRate > 0)
        return (sourceRate.toLong() + CONTINUOUS_TIMELINE_RATE - 1) / CONTINUOUS_TIMELINE_RATE
    }

    /** Where a trimmed start edge may go: inside the source, leaving at least one timeline frame. */
    fun trimStartRange(clip: ContinuousClip): LongRange =
        0L..maxOf(0L, clip.sourceEndFrame - minimumSourceFrames(clip.sourceRate))

    /** Where a trimmed end edge may go: inside the source, leaving at least one timeline frame. */
    fun trimEndRange(clip: ContinuousClip): LongRange =
        minOf(clip.sourceTotalFrames, clip.sourceStartFrame + minimumSourceFrames(clip.sourceRate))..clip.sourceTotalFrames

    fun intent(project: Project, action: ContinuousEditorAction, freshId: (String) -> String): Intent.SetArrangement {
        var tracks: List<Track> = project.tracks
        var clips: List<Clip> = project.clips
        // Clips this gesture creates or reshapes must be audible. A zero-length clip saved by an
        // earlier build stays movable and deletable instead of blocking every other edit.
        val reshaped = mutableSetOf<String>()
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
                val placed = Clip(freshId("clip"), track.id, hash, range,
                    timelineStartFrame = action.timelineFrame, gain = pad.gain, pan = pad.pan)
                clips = clips + placed
                reshaped += placed.id
            }
            is ContinuousEditorAction.MoveClip -> {
                require(tracks.any { it.id == action.trackId })
                replace(selected(action.clipId).copy(trackId = action.trackId, timelineStartFrame = action.timelineStartFrame))
            }
            is ContinuousEditorAction.TrimClip -> {
                replace(selected(action.clipId).copy(
                    range = FrameRange(action.sourceStartFrame, action.sourceEndFrame), timelineStartFrame = action.timelineStartFrame))
                reshaped += action.clipId
            }
            is ContinuousEditorAction.SplitClip -> {
                val old = selected(action.clipId)
                val start = startFrame(project, old)
                val delta = action.timelineFrame - start
                require(delta in 1 until durationFrames(project, old))
                val sourceCut = old.range.start + delta * project.asset(old.assetHash).sampleRate / 48_000
                require(sourceCut > old.range.start && sourceCut < old.range.end)
                // Both halves are absolute. A tick-anchored left half would follow a later tempo
                // change while the right half stayed put, opening a gap between them.
                val left = old.copy(range = FrameRange(old.range.start, sourceCut), timelineStartFrame = start)
                val right = old.copy(id = freshId("clip"), range = FrameRange(sourceCut, old.range.end),
                    timelineStartFrame = start + durationFrames(project, left))
                clips = clips.flatMap { if (it.id == old.id) listOf(left, right) else listOf(it) }
                reshaped += left.id
                reshaped += right.id
            }
            is ContinuousEditorAction.DuplicateClip -> {
                val old = selected(action.clipId)
                val copy = old.copy(id = freshId("clip"), timelineStartFrame = startFrame(project, old) + durationFrames(project, old))
                clips = clips + copy
                reshaped += copy.id
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
            if (clip.id in reshaped) require(durationFrames(project, clip) > 0) { "Clip is shorter than one timeline frame" }
        }
        return Intent.SetArrangement(tracks.frozen(), clips.frozen(), project.takes)
    }
}
