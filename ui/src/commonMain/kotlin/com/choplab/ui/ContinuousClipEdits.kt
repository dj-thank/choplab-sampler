package com.choplab.ui

import com.choplab.core.ProgramCompiler
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.engine.SequenceClock
import com.choplab.engine.Tempo

/** The song's tempo and swing the editor shows, as the grid and the clips on it follow them. */
internal val ContinuousEditorState.tempo: Tempo get() = Tempo(milliBpm, swingPermille)

/** Converts one finished UI gesture into one Studio/Undo edit. Never mutates a document. */
object ContinuousClipEdits {
    const val MAX_TIMELINE_FRAMES = 48_000L * 60 * 30

    /** An edit the song cannot take: playback holds 30 minutes, 1024 clips and 32 of them sounding at once. */
    class SongFull : IllegalArgumentException("The song cannot take this edit")

    /** A 4/4 bar. */
    private const val BAR_TICKS = 4L * ProjectLimits.PPQ

    /** Same floor mapping as playback/export, swing included, so the drawn start is the audible start. */
    fun startFrame(project: Project, clip: Clip): Long = clip.timelineStartFrame
        ?: ProgramCompiler.clipTickToFrame(clip.startTick, project.tempo)

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
     * The [grid] line nearest [frame] at [tempo], in ticks: where something let go at [frame] starts, keeping its beat
     * when the tempo changes. Swing moves the off sixteenths' lines as it moves what sounds on them. Null on a free grid.
     */
    fun snapTick(frame: Long, tempo: Tempo, grid: ContinuousGrid): Long? {
        if (grid == ContinuousGrid.FREE) return null
        val step = grid.ticks.toLong()
        // The exact time in the sequencer's frame × milli-BPM units, so a tie does not depend on how frames round.
        val at = frame.coerceIn(0, MAX_TIMELINE_FRAMES) * tempo.milliBpm
        var line = at / (SequenceClock.UNITS_PER_TICK * step)
        // Swing only delays a line, by less than a sixteenth, so the last line at or before is this or the one before.
        if (exactTime(line * step, tempo) > at) line--
        return (if (at - exactTime(line * step, tempo) < exactTime((line + 1) * step, tempo) - at) line else line + 1) * step
    }

    private fun exactTime(tick: Long, tempo: Tempo): Long = SequenceClock.targetNumerator(tick, tempo.swingPermille)

    /** Where something let go at [frame] starts on [grid], as placing or moving puts it; shown while it is dragged. */
    fun landingFrame(frame: Long, tempo: Tempo, grid: ContinuousGrid): Long =
        snapTick(frame, tempo, grid)?.let { ProgramCompiler.clipTickToFrame(it, tempo) } ?: frame

    /**
     * The [grid] line after the one at or before [frame] ([forward]), or the one before [frame] otherwise, in ticks;
     * null before the first line.
     */
    fun adjacentTick(frame: Long, tempo: Tempo, grid: ContinuousGrid, forward: Boolean): Long? {
        require(grid != ContinuousGrid.FREE)
        val step = grid.ticks.toLong()
        val at = frame.coerceIn(0, MAX_TIMELINE_FRAMES)
        val line = lineAtOrBefore(at, tempo, step)
        val target = if (forward) line + 1 else if (ProgramCompiler.clipTickToFrame(line * step, tempo) < at) line else line - 1
        return if (target < 0) null else target * step
    }

    /** The bar holding [frame], counted from 0. */
    fun barAt(frame: Long, tempo: Tempo): Long = lineAtOrBefore(frame.coerceIn(0, MAX_TIMELINE_FRAMES), tempo, BAR_TICKS)

    /**
     * The frames of the [section]th run of [bars] bars counted from the bar holding [frame] (section 0 is the run it
     * starts), as playback maps their ticks.
     */
    fun barsFrames(frame: Long, tempo: Tempo, bars: Int, section: Int = 0): LongRange {
        val first = (barAt(frame, tempo) + section.toLong() * bars) * BAR_TICKS
        return ProgramCompiler.clipTickToFrame(first, tempo) until ProgramCompiler.clipTickToFrame(first + bars * BAR_TICKS, tempo)
    }

    /** Half-open audible interval intersection, shared by the placement explanation and edit guard. */
    fun overlapsFrames(startFrame: Long, durationFrames: Long, range: LongRange): Boolean =
        durationFrames > 0 && !range.isEmpty() && startFrame <= range.last && startFrame + durationFrames > range.first

    /** The last line of [step] ticks that sounds at or before [at]. */
    private fun lineAtOrBefore(at: Long, tempo: Tempo, step: Long): Long {
        // A line sounds at the floor of its exact frame, as playback maps ticks, so the straight division can be one
        // short; swing delays a line, so it can also be one past.
        var line = at * tempo.milliBpm / (SequenceClock.UNITS_PER_TICK * step)
        while (ProgramCompiler.clipTickToFrame((line + 1) * step, tempo) <= at) line++
        while (line > 0 && ProgramCompiler.clipTickToFrame(line * step, tempo) > at) line--
        return line
    }

    /** [clip] starting at [frame]: on a grid at its nearest line, where it keeps that beat as the tempo changes. */
    private fun startingAt(clip: Clip, frame: Long, tempo: Tempo, grid: ContinuousGrid): Clip =
        snapTick(frame, tempo, grid)?.let { clip.copy(startTick = it, timelineStartFrame = null) } ?: clip.copy(timelineStartFrame = frame)

    /** Whether [clip] sounds at all: one saved by an earlier build can be shorter than a timeline frame. */
    fun sounds(clip: ContinuousClip): Boolean =
        ProgramCompiler.sourceFrameTo48k(clip.sourceEndFrame, clip.sourceRate) > ProgramCompiler.sourceFrameTo48k(clip.sourceStartFrame, clip.sourceRate)

    /** Pitch, reverse or tone change how a PAD sounds; the song plays placed sounds as they are, so such a PAD is rendered first. */
    fun transformed(pad: Pad): Boolean = pad.pitchSemitones != 0.0 || pad.reverse || pad.tone < com.choplab.engine.Pad.TONE_BYPASS || pad.pan != 0f

    /**
     * [rendered] holds each transformed PAD's sound, by PAD, for placing such a PAD. Placing, moving, nudging and
     * duplicating go by [grid]; trims and splits stay exactly where they are asked.
     */
    fun intent(project: Project, action: ContinuousEditorAction, freshId: (String) -> String, rendered: Map<Int, Asset> = emptyMap(),
               grid: ContinuousGrid = ContinuousGrid.FREE, performances: Map<ContinuousHit, Asset> = emptyMap()): Intent.SetArrangement {
        val tempo = project.tempo
        val routes = com.choplab.core.BankPlacementRoutes(project, freshId)
        var tracks: List<Track> = project.tracks
        fun bankTrack(padId: Int): Track = routes.trackForPad(padId).also { tracks = routes.tracks }
        var clips: List<Clip> = project.clips
        val pitchCorrections = project.pitchCorrections.toMutableList()
        val beatStretches = project.beatStretches.toMutableList()
        fun copyRecipes(original: Clip, copy: Clip): Clip {
            project.pitchCorrections.firstOrNull { it.clipId == original.id }?.let {
                var recipeId: String
                do { recipeId = freshId("pitch") } while (pitchCorrections.any { saved -> saved.id == recipeId })
                pitchCorrections += it.copy(id = recipeId, clipId = copy.id)
            }
            project.beatStretches.firstOrNull { it.target == StretchTarget(StretchKind.CLIP, original.id) }?.let {
                beatStretches += it.copy(target = StretchTarget(StretchKind.CLIP, copy.id))
            }
            return copy
        }
        // Clips this gesture creates or reshapes must be audible. A zero-length clip saved by an
        // earlier build stays movable and deletable instead of blocking every other edit.
        val reshaped = mutableSetOf<String>()
        fun selected(id: String) = requireNotNull(clips.firstOrNull { it.id == id }) { "Clip no longer exists" }
        fun replace(clip: Clip) { clips = clips.map { if (it.id == clip.id) clip else it } }
        /** The PAD's sound as placed ([rendered] for a transformed PAD) and the track it goes on. */
        fun placing(padId: Int, trackId: String?): Triple<Pad, Clip, Track> {
            val pad = project.pads.getOrNull(padId) ?: error("Unknown PAD")
            val made = rendered[padId]
            require(transformed(pad) == (made != null)) { "Render the transformed PAD before placement" }
            val sound = requireNotNull(pad.assetHash) { "Empty PAD" }
            val hash = made?.hash ?: sound
            val range = if (made != null) FrameRange(0, made.frames) else requireNotNull(pad.range)
            val track = trackId?.let { id -> requireNotNull(tracks.firstOrNull { it.id == id }) }
                ?: bankTrack(padId)
            return Triple(pad, Clip("pad-${pad.id}", track.id, hash, range, gain = pad.gain, pan = if (made != null) 0f else pad.pan), track)
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
                val from = ProgramCompiler.clipTickToFrame(first, tempo)
                val to = ProgramCompiler.clipTickToFrame(end, tempo)
                // The same sound already on that track in those bars makes way; other sounds and tracks stay.
                clips = clips.filterNot { it.trackId == track.id && it.assetHash == sound.assetHash && it.range == sound.range &&
                    startFrame(project, it) in from until to }
                val placed = (first until end step action.spacing.ticks.toLong()).map { tick ->
                    sound.copy(id = freshId("clip"), startTick = tick, timelineStartFrame = null)
                }
                clips = clips + placed
                reshaped += placed.map { it.id }
            }
            is ContinuousEditorAction.PlaceHits -> {
                require(action.hits.size in 1..1024)
                action.hits.forEach { hit ->
                    val made = performances[hit]
                    val sound = if (hit.performed) {
                        requireNotNull(made) { "Render the recorded voice before placement" }
                        val track = bankTrack(hit.padId)
                        Clip("hit", track.id, made.hash, FrameRange(0, made.frames), gain = 1f, pan = 0f)
                    } else placing(hit.padId, null).second
                    val placed = startingAt(sound.copy(id = freshId("clip")), hit.timelineFrame, tempo, grid)
                    // Two hits on one line, or one where that sound already starts, would sound twice there: one is enough.
                    if (clips.none { it.trackId == placed.trackId && it.assetHash == placed.assetHash && it.range == placed.range &&
                            startFrame(project, it) == startFrame(project, placed) }) {
                        clips = clips + placed
                        reshaped += placed.id
                    }
                }
            }
            is ContinuousEditorAction.SetClipPosition -> {
                val old = selected(action.clipId)
                require(action.timelineStartFrame in 0..MAX_TIMELINE_FRAMES - durationFrames(project, old))
                // Explicit frame placement is independent of the visual snap grid and source trim.
                replace(old.copy(timelineStartFrame = action.timelineStartFrame))
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
                clips = clips + copyRecipes(old, copy)
                reshaped += copy.id
            }
            is ContinuousEditorAction.RepeatBars -> {
                require(action.bars in setOf(1, 2, 4, 8) && action.times in setOf(1, 2, 4, 8))
                val section = barsFrames(action.timelineFrame, tempo, action.bars)
                // A silent clip saved by an earlier build is not repeated: what a gesture creates must sound.
                val copied = clips.filter { startFrame(project, it) in section && durationFrames(project, it) > 0 }
                require(copied.isNotEmpty()) { "Nothing to repeat" }
                // A clip longer than the bars would play over its own repeat.
                require(copied.none { durationFrames(project, it) > section.last + 1 - section.first }) { "A clip outlasts the bars" }
                val after = barsFrames(action.timelineFrame, tempo, action.bars, 1).first..barsFrames(action.timelineFrame, tempo, action.bars, action.times).last
                // Repeats fill empty bars only: layered over other clips they would double what is there.
                require(clips.none { overlapsFrames(startFrame(project, it), durationFrames(project, it), after) }) { "The bars after are not empty" }
                clips = clips + (1..action.times).flatMap { time ->
                    val bars = barsFrames(action.timelineFrame, tempo, action.bars, time)
                    copied.map { clip ->
                        // On the beat a copy keeps to the beat. A clip placed freely keeps its place from the bars' start,
                        // within its copy of them: bars can be a frame shorter than the ones copied.
                        val at = clip.timelineStartFrame
                        copyRecipes(clip, if (at == null) clip.copy(id = freshId("clip"), startTick = clip.startTick + time.toLong() * action.bars * BAR_TICKS)
                        else clip.copy(id = freshId("clip"), timelineStartFrame = minOf(at - section.first + bars.first, bars.last)))
                    }
                }
            }
            is ContinuousEditorAction.DeleteClip -> { selected(action.clipId); clips = clips.filterNot { it.id == action.clipId } }
            is ContinuousEditorAction.SetClipGain -> replace(selected(action.clipId).copy(gain = action.gain))
            is ContinuousEditorAction.SetTrackMuted -> {
                require(tracks.any { it.id == action.trackId })
                tracks = tracks.map { if (it.id == action.trackId) it.copy(mute = action.muted) else it }
            }
            else -> error("Not an arrangement edit")
        }
        if (tracks.size > ProjectLimits.MAX_TRACKS || (clips.size > 1024 && clips.size > project.clips.size)) throw SongFull()
        // A rendered PAD's sound joins the document with this edit: check its clips as if it had.
        val produced = rendered.values + performances.filterKeys { action is ContinuousEditorAction.PlaceHits && it in action.hits }.values
        val added = produced.distinctBy { it.hash }.filter { made -> project.assets.none { it.hash == made.hash } }
        val known = if (added.isEmpty()) project else project.copy(assets = (project.assets + added).sortedBy { it.hash }.frozen())
        clips.forEach { clip ->
            require(clip.range.end <= known.asset(clip.assetHash).frames)
            if (startFrame(known, clip) + durationFrames(known, clip) > MAX_TIMELINE_FRAMES) throw SongFull()
            if (clip.id in reshaped) require(durationFrames(known, clip) > 0) { "Clip is shorter than one timeline frame" }
        }
        // An edit may not turn a song playback takes into one it cannot, such as more than 32 clips sounding at once.
        // A song an earlier build let past that stays editable, so it can be thinned out.
        if (!ProgramCompiler.songFits(known.copy(tracks = tracks.frozen(), clips = clips.frozen())) && ProgramCompiler.songFits(project))
            throw SongFull()
        return Intent.SetArrangement(tracks.frozen(), clips.frozen(), project.takes, produced.distinctBy { it.hash }.frozen(),
            banks = routes.banks.frozen(), pitchCorrections = pitchCorrections.frozen(), beatStretches = beatStretches.frozen())
    }
}
