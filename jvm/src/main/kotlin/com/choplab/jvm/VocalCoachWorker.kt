package com.choplab.jvm

import com.choplab.core.ProgramCompiler
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import com.choplab.engine.*
import kotlinx.coroutines.*
import kotlin.math.*

/** At most 30 seconds, one PCM lease at a time, no derived file, network, transcript or document edit. */
class VocalCoachWorker(private val pcm: WavPcmPort) : VocalCoachAnalyzer {
    override suspend fun analyze(project: Project, revision: Long, request: VocalCoachRequest): CoachResult<VocalCoachReport> = withContext(Dispatchers.IO) {
        try {
            require(revision >= 0)
            val take = project.takes.first { it.id == request.takeId }
            val reference = request.referenceTakeId?.let { id -> project.takes.first { it.id == id } }
            for (entry in listOfNotNull(take, reference)) require(project.tracks.any { it.id == entry.trackId && it.kind == TrackKind.VOCAL })
            val frames = (request.endFrame - request.startFrame).toInt()
            val rows = project.lyrics.filter { ProgramCompiler.tickToFrame(it.startTick, project.tempo.milliBpm) < request.endFrame &&
                ProgramCompiler.tickToFrame(it.endTick, project.tempo.milliBpm) > request.startFrame }
            require(rows.size <= 64)
            val ranges = if (rows.isEmpty()) listOf(VocalCoachLine(null, "", request.startFrame, request.endFrame)) else rows.map { line ->
                val start = ProgramCompiler.tickToFrame(line.startTick, project.tempo.milliBpm)
                val end = ProgramCompiler.tickToFrame(line.endTick, project.tempo.milliBpm)
                VocalCoachLine(line.id, line.text, maxOf(start, request.startFrame), minOf(end, request.endFrame),
                    exclusions = if (start < request.startFrame || end > request.endFrame) frozenListOf(CoachExclusion.PARTIAL_LINE) else frozenListOf())
            }
            val scope = currentCoroutineContext()
            scope.ensureActive()
            if (request.voiceInput != CoachVoiceInput.VOICE_ONLY) return@withContext CoachResult.Success(VocalCoachReport(revision, request,
                ranges.map { it.copy(exclusions = (it.exclusions + if (request.voiceInput == CoachVoiceInput.ACCOMPANIMENT_PRESENT)
                    CoachExclusion.ACCOMPANIMENT else CoachExclusion.UNCONFIRMED_VOICE).frozen()) }.frozen()))
            pcm.memory.reserve(workspaceBytes(frames)).use {
                val observed = observe(project, take, request, scope)
                val expected = reference?.let { observe(project, it, request, scope) }
                scope.ensureActive()
                CoachResult.Success(VocalCoachReport(revision, request, ranges.map { line ->
                    scope.ensureActive()
                    summarize(line, request, take, reference, project, observed, expected)
                }.frozen()))
            }
        } catch (cancel: CancellationException) { throw cancel }
          catch (_: PcmMemoryLimit) { CoachResult.Failure(CoachProblem.LIMIT) }
          catch (_: IllegalArgumentException) { CoachResult.Failure(CoachProblem.INVALID_INPUT) }
          catch (_: NoSuchElementException) { CoachResult.Failure(CoachProblem.INVALID_INPUT) }
          catch (_: Exception) { CoachResult.Failure(CoachProblem.PCM_UNAVAILABLE) }
    }

    private class Observation(val pitch: FloatArray, val rms: FloatArray)
    private suspend fun observe(project: Project, take: Take, request: VocalCoachRequest,
                                context: kotlin.coroutines.CoroutineContext): Observation {
        val asset = project.asset(take.assetHash)
        val frames = (request.endFrame - request.startFrame).toInt()
        val hops = frames / HOP + 1
        val pitch = FloatArray(hops)
        val rms = FloatArray(hops)
        pcm.acquire(asset).use { lease ->
            val reader = PitchPcmReader { first, count, destination ->
                context.ensureActive()
                destination.fill(0f, 0, count * 2)
                val from = maxOf(request.startFrame + first, take.correctedStartFrame())
                val until = minOf(request.startFrame + first + count, take.correctedEndFrame(asset))
                if (until > from) {
                    val sourceAt = takeSourceFrame48(take.range.start, asset.sampleRate) + from - take.correctedStartFrame()
                    val samples = runBlocking(context) { pcm.readWindow(lease.pcm, sourceAt.toInt(), (sourceAt + until - from).toInt()) }
                    samples.copyInto(destination, ((from - request.startFrame - first) * 2).toInt())
                }
                count
            }
            // Rap has no pitch observations or pitch points. Energy onset still uses the same bounded PCM route.
            if (request.mode == CoachMode.SINGING) OfflinePitchCorrection.observe(reader, frames, context::ensureActive) { frame, hz, confidence, reason ->
                if (frame < frames && reason == PitchBypass.NONE && confidence >= .9f) pitch[frame / HOP] = hz
            }
            val buffer = FloatArray(HOP * 2)
            for (hop in 0 until hops) {
                context.ensureActive()
                val count = minOf(HOP, frames - hop * HOP)
                if (count <= 0) break
                reader.read(hop * HOP, count, buffer)
                var energy = 0.0
                for (i in 0 until count * 2) { require(buffer[i].isFinite()); energy += buffer[i] * buffer[i] }
                rms[hop] = sqrt(energy / (count * 2)).toFloat()
            }
        }
        return Observation(pitch, rms)
    }

    private fun summarize(line: VocalCoachLine, request: VocalCoachRequest, take: Take, reference: Take?, project: Project,
                          observed: Observation, expected: Observation?): VocalCoachLine {
        val exclusions = line.exclusions.toMutableList()
        if (request.mode == CoachMode.RAP) exclusions += CoachExclusion.RAP_PITCH
        if (reference == null) exclusions += CoachExclusion.NO_REFERENCE
        fun covers(candidate: Take): Boolean = line.startFrame >= candidate.correctedStartFrame() &&
            line.endFrame <= candidate.correctedEndFrame(project.asset(candidate.assetHash))
        if (!covers(take) || (reference != null && !covers(reference))) return line.copy(
            exclusions = (exclusions + CoachExclusion.MISSING_TAKE_RANGE).frozen())
        val first = ((line.startFrame - request.startFrame + HOP - 1) / HOP).toInt()
        val end = ((line.endFrame - request.startFrame) / HOP).toInt().coerceAtMost(observed.pitch.size)
        val count = (end - first).coerceAtLeast(0)
        val start = onset(observed, first, end, request.mode)
        val targetStart = expected?.let { onset(it, first, end, request.mode) }
        var frequency = 0.0; var voiced = 0; var difference = 0.0; var absolute = 0.0; var compared = 0
        for (i in first until end) {
            val hz = observed.pitch[i]
            if (hz > 0) { frequency += hz; voiced++ }
            val ref = expected?.pitch?.get(i) ?: 0f
            if (hz > 0 && ref > 0) { val cents = 1200 * log2(hz.toDouble() / ref); difference += cents; absolute += abs(cents); compared++ }
        }
        val reliablePitch = voiced >= 5
        val comparablePitch = compared >= 5
        if (start == null || (request.mode == CoachMode.SINGING && !reliablePitch) ||
            (expected != null && (targetStart == null || request.mode == CoachMode.SINGING && !comparablePitch)))
            exclusions += CoachExclusion.UNVOICED_OR_UNCERTAIN
        return line.copy(onsetFromLineMillis = start?.let { ((request.startFrame + it * HOP - line.startFrame) / 48).toInt() },
            onsetDifferenceMillis = if (start != null && targetStart != null) (start - targetStart) * 10 else null,
            observedPitchHz = if (reliablePitch) (frequency / voiced).roundToInt() else null,
            pitchDifferenceCents = if (comparablePitch) (difference / compared).roundToInt() else null,
            meanAbsolutePitchCents = if (comparablePitch) (absolute / compared).roundToInt() else null,
            comparedPitchHops = compared, observedPitchHops = voiced, totalHops = count, exclusions = exclusions.distinct().frozen())
    }

    /** Three sustained 10 ms windows; without periodicity, require a clear energy rise (including rap). */
    private fun onset(value: Observation, first: Int, end: Int, mode: CoachMode): Int? {
        if (end - first < 3) return null
        var peak = 0f; var floor = Float.MAX_VALUE
        for (i in first until end) { peak = maxOf(peak, value.rms[i]); floor = minOf(floor, value.rms[i]) }
        if (peak < .003f) return null
        val threshold = maxOf(.003f, peak * .1f)
        val periodic = mode == CoachMode.SINGING && (first until end).count { value.pitch[it] > 0 } >= 5
        if (!periodic && floor > peak * .35f) return null
        for (i in first until end - 2) if (value.rms[i] >= threshold && value.rms[i + 1] >= threshold && value.rms[i + 2] >= threshold) return i
        return null
    }

    companion object {
        private const val HOP = OfflinePitchCorrection.HOP_FRAMES
        fun workspaceBytes(frames: Int): Long = OfflinePitchCorrection.workspaceBytes(frames) +
            (frames / HOP + 1L) * 16 + 65_536 // Both contours, RMS windows and the bounded returned PCM window.
    }
}
