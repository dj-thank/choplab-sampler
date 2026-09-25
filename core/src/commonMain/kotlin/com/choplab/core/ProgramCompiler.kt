package com.choplab.core

import com.choplab.core.model.*
import com.choplab.engine.Arrangement
import com.choplab.engine.ArrangementClip
import com.choplab.engine.EngineFormat
import com.choplab.engine.EngineProgram
import com.choplab.engine.PcmAsset
import com.choplab.engine.SequenceNote

/** Runs on the control/worker side. Each asset is loaded at most once, shared by all of its PADs. */
class ProgramCompiler(private val pcm: PcmPort) {
    suspend fun compile(project: Project, patternId: String, revision: Long): EngineProgram = compile(project, PlaybackTarget.Pattern(patternId), revision)

    suspend fun compile(project: Project, target: PlaybackTarget, revision: Long): EngineProgram {
        val pattern = if (target is PlaybackTarget.Pattern) requireNotNull(project.patterns.firstOrNull { it.id == target.id }) else null
        val timeline = if (target is PlaybackTarget.Arrangement) planArrangement(project, target) else null
        val resident = mutableMapOf<String, PcmAsset>()
        var bytes = 0L
        // Audition PADs remain available in arrangement mode. Admit the union, never drop PADs.
        val required = (project.pads.mapNotNull { it.assetHash } + timeline?.audible.orEmpty().map { it.asset.hash }).distinct().map(project::asset)
        require(required.sumOf { normalizedFrames(it) * 8 } <= EngineFormat.MAX_RESIDENT_BYTES) { "PAD and timeline PCM exceeds resident budget" }
        suspend fun load(metadata: Asset): PcmAsset = resident[metadata.hash] ?: pcm.load(metadata).also {
                val frames = normalizedFrames(metadata)
                require(it.frameCount.toLong() == frames) { "PCM metadata mismatch" }
                bytes += it.residentBytes
                require(bytes <= EngineFormat.MAX_RESIDENT_BYTES)
                resident[metadata.hash] = it
            }
        val pads = project.pads.filter { it.assetHash != null }.map { pad ->
            val metadata = project.asset(requireNotNull(pad.assetHash))
            val data = load(metadata)
            val range = requireNotNull(pad.range)
            val (start, end) = normalizedRange(range, metadata.sampleRate)
            com.choplab.engine.Pad(pad.id, data, start, end, pad.mode, pad.pitchSemitones, pad.gain,
                pad.pan, pad.reverse, pad.chokeGroup, pad.attackFrames, pad.releaseFrames, pad.loopCrossfadeFrames, pad.decayFrames, pad.sustainLevel)
        }
        val arrangement = timeline?.let { plan ->
            val tracks = plan.audible.map { it.track.id }.distinct()
            Arrangement(plan.audible.map { clip ->
                ArrangementClip(clip.id, load(clip.asset), clip.start, clip.sourceStart, clip.sourceEnd, clip.gain, clip.pan, tracks.indexOf(clip.track.id))
            }, plan.duration)
        }
        return EngineProgram(pads, pattern?.let { com.choplab.engine.Pattern(it.lengthTicks, it.notes.map { note -> SequenceNote(note.tick, note.padId, note.velocity) }) },
            project.tempo, revision, arrangement)
    }

    private data class PlannedClip(val id: String, val asset: Asset, val sourceStart: Int, val sourceEnd: Int, val start: Long, val track: Track,
                                   val gain: Float = track.gain, val pan: Float = track.pan) {
        val end: Long get() = start + sourceEnd - sourceStart
    }
    private data class TimelinePlan(val audible: List<PlannedClip>, val duration: Long)

    private fun planArrangement(project: Project, target: PlaybackTarget.Arrangement): TimelinePlan {
        val takes = target.takeIds.map { id -> requireNotNull(project.takes.firstOrNull { it.id == id }) { "Unknown selected take" } }
        val tracks = project.tracks.associateBy { it.id }
        val candidates = buildList {
            project.clips.forEach { clip ->
                val asset = project.asset(clip.assetHash)
                val (sourceStart, sourceEnd) = normalizedRange(clip.range, asset.sampleRate, exactAdjacency = true)
                val start = clip.timelineStartFrame ?: tickToFrame(clip.startTick, project.tempo.milliBpm)
                val track = requireNotNull(tracks[clip.trackId])
                add(PlannedClip("clip-${clip.id}", asset, sourceStart, sourceEnd, start, track, track.gain * clip.gain, (track.pan + clip.pan).coerceIn(-1f, 1f)))
            }
            takes.forEach { take ->
                val asset = project.asset(take.assetHash)
                val (originalStart, end) = normalizedRange(take.range, asset.sampleRate, exactAdjacency = true)
                val corrected = take.timelineStartFrame - take.compensationFrames.toLong()
                // Trimming the already normalized view preserves exact 48 kHz compensation, without
                // rounding to a native sample and then rounding back a second time.
                val trim = (-corrected).coerceAtLeast(0)
                if (trim < end - originalStart) add(PlannedClip("take-${take.id}", asset, (originalStart + trim).toInt(), end,
                    corrected.coerceAtLeast(0), requireNotNull(tracks[take.trackId])))
            }
        }
        require(candidates.all { it.start in 0..Arrangement.MAX_DURATION_FRAMES && it.end <= Arrangement.MAX_DURATION_FRAMES }) { "Timeline exceeds 30 minutes" }
        val duration = candidates.maxOfOrNull { it.end } ?: 0L
        val anySolo = project.tracks.any { it.solo }
        val audible = candidates.filter { it.sourceEnd > it.sourceStart && !it.track.mute && it.gain > 0f && (!anySolo || it.track.solo) }
        require(audible.all { it.gain.isFinite() && it.gain <= 8f }) { "Combined track and clip gain exceeds engine limit" }
        require(audible.size <= Arrangement.MAX_CLIPS && audible.map { it.track.id }.distinct().size <= Arrangement.MAX_TRACKS)
        val edges = audible.flatMap { listOf(it.start to 1, it.end to -1) }.sortedWith(compareBy<Pair<Long, Int>> { it.first }.thenBy { it.second })
        var overlap = 0
        edges.forEach { (_, delta) -> overlap += delta; require(overlap <= Arrangement.MAX_SIMULTANEOUS_CLIPS) { "More than 32 overlapping timeline clips" } }
        return TimelinePlan(audible, duration)
    }

    companion object {
        /** Absolute conversion, so rounding never accumulates across clips or tempo changes. */
        fun tickToFrame(tick: Long, milliBpm: Int): Long {
            require(tick in 0..ProjectLimits.MAX_TIMELINE_TICKS && milliBpm in 40_000..240_000)
            return tick * (48_000L * 60_000) / (milliBpm.toLong() * ProjectLimits.PPQ)
        }
        /** Both clip boundaries use this same ceil mapping, preserving adjacent mixed-rate cuts. */
        fun sourceFrameTo48k(frame: Long, sampleRate: Int): Long {
            require(frame in 0..ProjectLimits.MAX_FRAMES && sampleRate in 8_000..192_000)
            return (frame * 48_000 + sampleRate - 1) / sampleRate
        }
        private fun normalizedFrames(asset: Asset): Long = (asset.frames * 48_000 + asset.sampleRate - 1) / asset.sampleRate
        private fun normalizedRange(range: FrameRange, rate: Int, exactAdjacency: Boolean = false): Pair<Int, Int> =
            (if (exactAdjacency) sourceFrameTo48k(range.start, rate) else range.start * 48_000 / rate).toInt() to sourceFrameTo48k(range.end, rate).toInt()
    }
}
