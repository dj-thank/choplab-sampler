package com.choplab.ui

import com.choplab.core.ProgramCompiler
import com.choplab.core.edit.Intent
import com.choplab.core.model.*

/** Converts one finished UI gesture into one Studio/Undo edit. Never mutates a document. */
object ContinuousClipEdits {
    const val MAX_TIMELINE_FRAMES = 48_000L * 60 * 30

    /** An edit the song cannot take: playback holds 30 minutes, 1024 clips and 32 of them sounding at once. */
    class SongFull : IllegalArgumentException("The song cannot take this edit")
    /** Frames × milli-BPM × PPQ over this is ticks. */
    private const val FRAME_TICK_SCALE = 48_000L * 60_000
    /** A 4/4 bar. */
    private const val BAR_TICKS = 4L * ProjectLimits.PPQ

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

    /**
     * The [grid] line nearest [frame] at [milliBpm], in ticks: where something let go at [frame] starts, keeping its beat
     * when the tempo changes. Null on a free grid.
     */
    fun snapTick(frame: Long, milliBpm: Int, grid: ContinuousGrid): Long? {
        if (grid == ContinuousGrid.FREE) return null
        val step = grid.ticks.toLong()
        val scale = FRAME_TICK_SCALE * step
        return (frame.coerceIn(0, MAX_TIMELINE_FRAMES) * milliBpm * ProjectLimits.PPQ + scale / 2) / scale * step
    }

    /**
     * The [grid] line after the one at or before [frame] ([forward]), or the one before [frame] otherwise, in ticks;
     * null before the first line.
     */
    fun adjacentTick(frame: Long, milliBpm: Int, grid: ContinuousGrid, forward: Boolean): Long? {
        require(grid != ContinuousGrid.FREE)
        val step = grid.ticks.toLong()
        val at = frame.coerceIn(0, MAX_TIMELINE_FRAMES)
        val line = lineAtOrBefore(at, milliBpm, step)
        val target = if (forward) line + 1 else if (ProgramCompiler.tickToFrame(line * step, milliBpm) < at) line else line - 1
        return if (target < 0) null else target * step
    }

    /** The bar holding [frame], counted from 0. */
    fun barAt(frame: Long, milliBpm: Int): Long = lineAtOrBefore(frame.coerceIn(0, MAX_TIMELINE_FRAMES), milliBpm, BAR_TICKS)

    /** The last line of [step] ticks that sounds at or before [at]. */
    private fun lineAtOrBefore(at: Long, milliBpm: Int, step: Long): Long {
        // A line sounds at the floor of its exact frame, as playback maps ticks, so the exact division can be one short.
        var line = at * milliBpm * ProjectLimits.PPQ / (FRAME_TICK_SCALE * step)
        while (ProgramCompiler.tickToFrame((line + 1) * step, milliBpm) <= at) line++
        return line
    }

    /** [clip] starting at [frame]: on a grid at its nearest line, where it keeps that beat as the tempo changes. */
    private fun startingAt(clip: Clip, frame: Long, milliBpm: Int, grid: ContinuousGrid): Clip =
        snapTick(frame, milliBpm, grid)?.let { clip.copy(startTick = it, timelineStartFrame = null) } ?: clip.copy(timelineStartFrame = frame)

    /** Pitch, reverse or tone change how a PAD sounds; the song plays placed sounds as they are, so such a PAD is rendered first. */
    fun transformed(pad: Pad): Boolean = pad.pitchSemitones != 0.0 || pad.reverse || pad.tone < com.choplab.engine.Pad.TONE_BYPASS

    /**
     * [rendered] is the transformed PAD's sound, for a [ContinuousEditorAction.PlacePad] of such a PAD. Placing, moving,
     * nudging and duplicating go by [grid]; trims and splits stay exactly where they are asked.
     */
    fun intent(project: Project, action: ContinuousEditorAction, freshId: (String) -> String, rendered: Asset? = null,
               grid: ContinuousGrid = ContinuousGrid.FREE): Intent.SetArrangement {
        val tempo = project.tempo.milliBpm
        var tracks: List<Track> = project.tracks
        var clips: List<Clip> = project.clips
        // Clips this gesture creates or reshapes must be audible. A zero-length clip saved by an
        // earlier build stays movable and deletable instead of blocking every other edit.
        val reshaped = mutableSetOf<String>()
        fun selected(id: String) = requireNotNull(clips.firstOrNull { it.id == id }) { "Clip no longer exists" }
        fun replace(clip: Clip) { clips = clips.map { if (it.id == clip.id) clip else it } }
        /** The PAD's sound as placed ([rendered] for a transformed PAD) and the track it goes on. */
        fun placing(padId: Int, trackId: String?): Triple<Pad, Clip, Track> {
            val pad = project.pads.getOrNull(padId) ?: error("Unknown PAD")
            require(transformed(pad) == (rendered != null)) { "Render the transformed PAD before placement" }
            val sound = requireNotNull(pad.assetHash) { "Empty PAD" }
            val hash = rendered?.hash ?: sound
            val range = if (rendered != null) FrameRange(0, rendered.frames) else requireNotNull(pad.range)
            val track = trackId?.let { id -> requireNotNull(tracks.firstOrNull { it.id == id }) }
                ?: tracks.firstOrNull { it.kind == TrackKind.BANK }
                ?: Track(freshId("track"), project.banks[pad.id / 16].name, TrackKind.BANK).also { tracks = tracks + it }
            return Triple(pad, Clip("pad-${pad.id}", track.id, hash, range, gain = pad.gain, pan = pad.pan), track)
        }
        when (action) {
            is ContinuousEditorAction.PlacePad -> {
                val (_, sound, _) = placing(action.padId, action.trackId)
                val placed = startingAt(sound.copy(id = freshId("clip")), action.timelineFrame, tempo, grid)
                clips = clips + placed
                reshaped += placed.id
            }
            is ContinuousEditorAction.FillPad -> {
                require(action.spacing != ContinuousGrid.FREE && action.bars in 1..8)
                val (_, sound, track) = placing(action.padId, action.trackId)
                val first = barAt(action.timelineFrame, tempo) * BAR_TICKS
                val end = first + action.bars * BAR_TICKS
                val from = ProgramCompiler.tickToFrame(first, tempo)
                val to = ProgramCompiler.tickToFrame(end, tempo)
                // The same sound already on that track in those bars makes way; other sounds and tracks stay.
                clips = clips.filterNot { it.trackId == track.id && it.assetHash == sound.assetHash && it.range == sound.range &&
                    startFrame(project, it) in from until to }
                val placed = (first until end step action.spacing.ticks.toLong()).map { tick ->
                    sound.copy(id = freshId("clip"), startTick = tick, timelineStartFrame = null)
                }
                clips = clips + placed
                reshaped += placed.map { it.id }
            }
            is ContinuousEditorAction.MoveClip -> {
                require(tracks.any { it.id == action.trackId })
                val old = selected(action.clipId)
                val moved = old.copy(trackId = action.trackId)
                // Moved only to another track, a clip stays where it was, on the beat or not.
                replace(if (action.timelineStartFrame == startFrame(project, old)) moved else startingAt(moved, action.timelineStartFrame, tempo, grid))
            }
            is ContinuousEditorAction.NudgeClip -> {
                val old = selected(action.clipId)
                val start = startFrame(project, old)
                if (grid == ContinuousGrid.FREE) {
                    val second = if (action.forward) CONTINUOUS_TIMELINE_RATE.toLong() else -CONTINUOUS_TIMELINE_RATE.toLong()
                    val frame = (start + second).coerceAtLeast(0)
                    if (frame != start) replace(old.copy(timelineStartFrame = frame))
                } else adjacentTick(start, tempo, grid, action.forward)?.let { replace(old.copy(startTick = it, timelineStartFrame = null)) }
            }
            is ContinuousEditorAction.TrimClip -> {
                val old = selected(action.clipId)
                val trimmed = old.copy(range = FrameRange(action.sourceStartFrame, action.sourceEndFrame))
                // Trimming only the end leaves the start, on the beat or not, where it was.
                replace(if (action.sourceStartFrame == old.range.start && action.timelineStartFrame == startFrame(project, old)) trimmed
                    else trimmed.copy(timelineStartFrame = action.timelineStartFrame))
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
                val start = startFrame(project, old)
                val end = start + durationFrames(project, old)
                // On a grid the copy starts on the line nearest the original's end, one line after its start at the
                // earliest: a short hit repeats on the next beat, a loop whole beats long follows end to end.
                val copy = if (grid == ContinuousGrid.FREE) old.copy(id = freshId("clip"), timelineStartFrame = end)
                    else old.copy(id = freshId("clip"), timelineStartFrame = null, startTick = maxOf(requireNotNull(snapTick(end, tempo, grid)),
                        requireNotNull(adjacentTick(start, tempo, grid, forward = true))))
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
        require(tracks.size <= 16)
        if (clips.size > 1024) throw SongFull()
        // A rendered PAD's sound joins the document with this edit: check its clip as if it had.
        val known = if (rendered == null || project.assets.any { it.hash == rendered.hash }) project
            else project.copy(assets = (project.assets + rendered).sortedBy { it.hash }.frozen())
        clips.forEach { clip ->
            require(clip.range.end <= known.asset(clip.assetHash).frames)
            if (startFrame(known, clip) + durationFrames(known, clip) > MAX_TIMELINE_FRAMES) throw SongFull()
            if (clip.id in reshaped) require(durationFrames(known, clip) > 0) { "Clip is shorter than one timeline frame" }
        }
        // An edit may not turn a song playback takes into one it cannot, such as more than 32 clips sounding at once.
        // A song an earlier build let past that stays editable, so it can be thinned out.
        if (!ProgramCompiler.songFits(known.copy(tracks = tracks.frozen(), clips = clips.frozen())) && ProgramCompiler.songFits(project))
            throw SongFull()
        return Intent.SetArrangement(tracks.frozen(), clips.frozen(), project.takes, listOfNotNull(rendered).frozen())
    }
}
