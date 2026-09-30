package com.choplab.core.vocal

import com.choplab.core.edit.Intent
import com.choplab.core.edit.Reducer
import com.choplab.core.model.*

/** One explicit recording session. Native input remains owned across all passes. Nothing here is calibration. */
data class VocalPunchRequest(val startFrame: Long, val endFrame: Long, val preRollBars: Int = 1,
                             val countInBars: Int = 1, val passes: Int = 1, val manualFrames48k: Int = 0) {
    init {
        require(startFrame >= 0 && endFrame > startFrame && endFrame <= ProjectLimits.MAX_TIMELINE_FRAMES)
        require(preRollBars in 0..2 && countInBars in 0..2 && passes in 1..8 && manualFrames48k in -480_000..480_000)
    }
    fun plan(project: Project): VocalPunchPlan = VocalPunchPlan.create(startFrame, endFrame,
        maxOf(endFrame, project.clips.maxOfOrNull { clip ->
            (clip.timelineStartFrame ?: com.choplab.core.ProgramCompiler.clipTickToFrame(clip.startTick, project.tempo)) +
                takeSourceFrame48(clip.range.end, project.asset(clip.assetHash).sampleRate) - takeSourceFrame48(clip.range.start, project.asset(clip.assetHash).sampleRate)
        } ?: endFrame), preRollBars, countInBars, project.tempo).also {
        require(it.captureStart + manualFrames48k >= 0 && it.framesBeforeCapture + manualFrames48k >= 0 &&
            it.captureEnd + manualFrames48k.coerceAtLeast(0) <= ProjectLimits.MAX_TIMELINE_FRAMES)
        require(it.captureFrames * passes <= 300L * 48_000) { "A session holds at most five minutes of captured voice" }
    }
}

/** Each candidate references its own interval of the one immutable recorded original; no PCM is copied. */
data class VocalCapturedPass(val range: FrameRange, val lateFrames48k: Long) {
    init { require(lateFrames48k in 0..300L * 48_000) }
}
data class VocalCapturedSession(val asset: Asset, val passes: List<VocalCapturedPass>) {
    init {
        require(passes.size in 1..8 && passes.all { it.range.end <= asset.frames })
        require(passes.zipWithNext().all { (a, b) -> a.range.end <= b.range.start })
    }
}

object VocalPunchRecording {
    /** All passes from Record are one Undo. Audible placements and previous candidates remain unchanged. */
    fun retain(project: Project, captured: VocalCapturedSession, plan: VocalPunchPlan,
               compensationFrames: Int, captureShiftFrames: Int = 0, id: (String) -> String): Intent.SetArrangement {
        require(project.takes.size + captured.passes.size <= 1024)
        val track = project.tracks.firstOrNull { it.kind == TrackKind.VOCAL } ?: Track(id("track"), "VOICE", TrackKind.VOCAL)
        val candidates = captured.passes.map { pass ->
            Take(id("take"), track.id, captured.asset.hash, pass.range, plan.captureStart + captureShiftFrames + pass.lateFrames48k, compensationFrames)
        }
        return Intent.SetArrangement(if (track in project.tracks) project.tracks else (project.tracks + track).frozen(),
            project.clips, (project.takes + candidates).frozen(), frozenListOf(captured.asset)).also { Reducer.reduce(project, it) }
    }
}
