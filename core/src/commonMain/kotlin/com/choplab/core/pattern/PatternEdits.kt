package com.choplab.core.pattern

import com.choplab.core.ProgramCompiler
import com.choplab.core.edit.Intent
import com.choplab.core.edit.Reducer
import com.choplab.core.model.*
import com.choplab.engine.EngineCore
import com.choplab.engine.PadRender
import com.choplab.engine.PlayMode
import com.choplab.engine.SequenceClock
import com.choplab.engine.Tempo
import kotlin.math.ceil
import kotlin.math.pow

enum class PatternProblem { INVALID_INPUT, EMPTY_PAD, PATTERN_LIMIT, TRIM_REQUIRED, EMPTY_SEQUENCE, NO_NOTES, SONG_FULL, NO_ROOM,
    RENDER_FAILED, UNSAVED_PATTERN, STALE_DOCUMENT, BUSY, RECORDING, APPLY_FAILED, CLOSED }
class PatternEditException(val problem: PatternProblem) : IllegalArgumentException(problem.name)
private fun reject(problem: PatternProblem): Nothing = throw PatternEditException(problem)

/** Pure 4/4 edits. View width is deliberately absent from the durable Pattern. */
object PatternEdits {
    const val STEP_TICKS = ProjectLimits.PPQ / 4
    const val BAR_TICKS = ProjectLimits.PPQ * 4
    val QUANTIZE_TICKS = listOf(ProjectLimits.PPQ, ProjectLimits.PPQ / 2, STEP_TICKS)

    fun create(project: Project, name: String, copy: Pattern? = null): Pattern {
        if (project.patterns.size >= ProjectLimits.MAX_PATTERNS) reject(PatternProblem.PATTERN_LIMIT)
        val id = (1..ProjectLimits.MAX_PATTERNS + 1).map { "pattern-$it" }.first { candidate -> project.patterns.none { it.id == candidate } }
        return copy?.copy(id = id, name = name) ?: Pattern(id, name)
    }
    fun resize(pattern: Pattern, bars: Int, trim: Boolean = false): Pattern {
        if (bars !in 1..8) reject(PatternProblem.INVALID_INPUT)
        val length = bars * BAR_TICKS
        if (!trim && pattern.notes.any { it.tick >= length }) reject(PatternProblem.TRIM_REQUIRED)
        return pattern.copy(bars = bars, notes = pattern.notes.filter { it.tick < length }.frozen())
    }
    fun toggle(project: Project, pattern: Pattern, padId: Int, step: Int, velocity: Float): Pattern {
        if (padId !in 0..127 || step !in 0 until pattern.bars * 16 || !velocity.isFinite() || velocity !in 0f..1f) reject(PatternProblem.INVALID_INPUT)
        if (project.pads[padId].assetHash == null) reject(PatternProblem.EMPTY_PAD)
        val tick = step * STEP_TICKS
        val existing = pattern.notes.any { it.padId == padId && it.tick == tick }
        val notes = pattern.notes.filterNot { it.padId == padId && it.tick == tick } + if (existing) emptyList() else listOf(Note(tick, padId, velocity))
        return pattern.copy(notes = notes.sortedWith(compareBy(Note::tick, Note::padId)).frozen())
    }
    /** Nearest grid, ties forward. A note at the exclusive end stays on the final in-range line, never wraps to zero. */
    fun quantize(pattern: Pattern, padId: Int, gridTicks: Int): Pattern {
        if (padId !in 0..127 || gridTicks !in QUANTIZE_TICKS) reject(PatternProblem.INVALID_INPUT)
        val moved = pattern.notes.map { note -> if (note.padId != padId) note else note.copy(
            tick = (((note.tick + gridTicks / 2) / gridTicks) * gridTicks).coerceAtMost(pattern.lengthTicks - gridTicks)) }
        val merged = moved.groupBy { it.tick to it.padId }.values.map { same -> same.maxBy { it.velocity } }
        return pattern.copy(notes = merged.sortedWith(compareBy(Note::tick, Note::padId)).frozen())
    }
    fun clear(pattern: Pattern, padId: Int): Pattern {
        if (padId !in 0..127) reject(PatternProblem.INVALID_INPUT)
        return pattern.copy(notes = pattern.notes.filterNot { it.padId == padId }.frozen())
    }
    fun put(project: Project, pattern: Pattern): Intent.PutPattern {
        if (project.patterns.none { it.id == pattern.id } && project.patterns.size >= ProjectLimits.MAX_PATTERNS) reject(PatternProblem.PATTERN_LIMIT)
        if (pattern.notes.any { project.pads[it.padId].assetHash == null }) reject(PatternProblem.EMPTY_PAD)
        return Intent.PutPattern(pattern)
    }
    /** Nearest audible sixteenth in exact frame*milli-BPM units, including swing; ties go forward. */
    fun nearestStepTick(frame: Long, tempo: Tempo): Long {
        if (frame !in 0..ProjectLimits.MAX_TIMELINE_FRAMES) reject(PatternProblem.INVALID_INPUT)
        val at = frame * tempo.milliBpm
        var line = at / (SequenceClock.UNITS_PER_TICK * STEP_TICKS)
        fun time(index: Long) = SequenceClock.targetNumerator(index * STEP_TICKS, tempo.swingPermille)
        if (time(line) > at) line--
        return (if (at - time(line) < time(line + 1) - at) line else line + 1) * STEP_TICKS
    }
}

/** One reusable performed PAD voice, before the master limiter. Gain, pan, envelope and tone are baked by the host. */
data class PatternVoiceRender(val padId: Int, val releaseAt: Int?, val limitFrames: Int, val stopAt: Int?)
data class PatternVoicePlacement(val startTick: Long, val velocity: Float, val render: PatternVoiceRender)
class PatternPlacementPlan(val firstTick: Long, val endTick: Long, val voices: FrozenList<PatternVoicePlacement>) {
    val renders: List<PatternVoiceRender> get() = voices.map { it.render }.distinct()
}

/** Expands committed patterns into real arrangement clips. SongSection alone is not a playback target in schema 10. */
object PatternPlacement {
    fun plan(project: Project, sequence: List<SongSection>, firstTick: Long): PatternPlacementPlan {
        if (sequence.isEmpty()) reject(PatternProblem.EMPTY_SEQUENCE)
        if (sequence.size > 128 || firstTick !in 0..ProjectLimits.MAX_TIMELINE_TICKS || firstTick % PatternEdits.BAR_TICKS != 0L)
            reject(PatternProblem.INVALID_INPUT)
        var tick = firstTick
        val events = ArrayList<Pair<Long, Note>>()
        for (section in sequence) {
            val pattern = project.patterns.firstOrNull { it.id == section.patternId } ?: reject(PatternProblem.INVALID_INPUT)
            if (events.size.toLong() + pattern.notes.count { it.velocity > 0f }.toLong() * section.repeats > 1024) reject(PatternProblem.SONG_FULL)
            repeat(section.repeats) {
                for (note in pattern.notes.sortedWith(compareBy(Note::tick, Note::padId))) if (note.velocity > 0f) events += tick + note.tick to note
                tick += pattern.lengthTicks
                if (tick > ProjectLimits.MAX_TIMELINE_TICKS) reject(PatternProblem.SONG_FULL)
            }
        }
        if (events.isEmpty()) reject(PatternProblem.NO_NOTES)
        val endFrame = ProgramCompiler.clipTickToFrame(tick, project.tempo)
        if (endFrame + EngineCore.STEAL_FADE_FRAMES > ProjectLimits.MAX_TIMELINE_FRAMES) reject(PatternProblem.SONG_FULL)
        val voices = events.mapIndexed { index, (atTick, note) ->
            val pad = project.pads[note.padId]
            val source = pad.assetHash?.let(project::asset) ?: reject(PatternProblem.EMPTY_PAD)
            val range = requireNotNull(pad.range)
            val atFrame = ProgramCompiler.clipTickToFrame(atTick, project.tempo)
            val boundary = endFrame - atFrame
            val choke = if (pad.chokeGroup == 0) null else events.asSequence().drop(index + 1)
                .firstOrNull { project.pads[it.second.padId].chokeGroup == pad.chokeGroup }
                ?.let { ProgramCompiler.clipTickToFrame(it.first, project.tempo) - atFrame }
            val nativeLength = ProgramCompiler.sourceFrameTo48k(range.end, source.sampleRate) - range.start * 48_000 / source.sampleRate
            // One extra frame bounds fractional-pitch roundoff; the real Voice decides its natural end.
            val natural = if (pad.mode == PlayMode.LOOP) Long.MAX_VALUE else ceil(nativeLength / 2.0.pow(pad.pitchSemitones / 12)).toLong() + 1
            val length = minOf(natural, boundary + EngineCore.STEAL_FADE_FRAMES, choke?.let { it + pad.releaseFrames } ?: Long.MAX_VALUE)
            if (length !in 1..PadRender.MAX_FRAMES.toLong()) reject(PatternProblem.NO_ROOM)
            PatternVoicePlacement(atTick, note.velocity, PatternVoiceRender(pad.id, choke?.takeIf { it < length }?.toInt(), length.toInt(), boundary.takeIf { it < length }?.toInt()))
        }
        val plan = PatternPlacementPlan(firstTick, tick, voices.frozen())
        if (ProgramCompiler.residentFrames(project) + plan.renders.sumOf { it.limitFrames.toLong() } > ProgramCompiler.RESIDENT_FRAME_LIMIT)
            reject(PatternProblem.NO_ROOM)
        return plan
    }

    fun intent(project: Project, plan: PatternPlacementPlan, rendered: Map<PatternVoiceRender, Asset>, freshId: (String) -> String,
               trackName: String): Intent.SetArrangement {
        val sounds = plan.renders.map { request ->
            val asset = rendered[request] ?: reject(PatternProblem.RENDER_FAILED)
            if (asset.sampleRate != 48_000 || asset.channels != 2 || asset.frames !in 1..request.limitFrames.toLong() ||
                asset.role != AssetRole.RENDERED || asset.derivedFrom != project.pads[request.padId].assetHash || !asset.required)
                reject(PatternProblem.RENDER_FAILED)
            asset
        }.distinctBy { it.hash }
        val track = project.tracks.firstOrNull { it.kind == TrackKind.BANK }
            ?: Track(freshId("track"), trackName, TrackKind.BANK)
        val tracks = if (project.tracks.any { it.id == track.id }) project.tracks else (project.tracks + track).frozen()
        val clips = (project.clips + plan.voices.map { voice ->
            val asset = rendered.getValue(voice.render)
            Clip(freshId("clip"), track.id, asset.hash, FrameRange(0, asset.frames), startTick = voice.startTick,
                gain = voice.velocity, pan = 0f)
        }).frozen()
        val intent = Intent.SetArrangement(tracks, clips, project.takes, sounds.frozen())
        val after = try { Reducer.reduce(project, intent).project } catch (_: IllegalArgumentException) { reject(PatternProblem.NO_ROOM) }
        if (ProgramCompiler.residentFrames(after) > ProgramCompiler.RESIDENT_FRAME_LIMIT) reject(PatternProblem.NO_ROOM)
        if (!ProgramCompiler.songFits(after)) reject(PatternProblem.SONG_FULL)
        return intent
    }
}
