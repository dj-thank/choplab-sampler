package com.choplab.core

import com.choplab.core.model.FrozenList
import com.choplab.core.model.Project
import com.choplab.core.model.ProjectLimits
import com.choplab.engine.MixerProgram

enum class StemSampleFormat(val bits: Int, val bytes: Int) { FLOAT32(32, 4), PCM24(24, 3), PCM16(16, 2) }
/** Typed pre-master clipping refusal; the caller can offer FLOAT32 without changing production gain. */
class StemHeadroomExceeded(val bus: Int, val frame: Long, val peak: Float) : IllegalArgumentException("Integer stem would clip; choose float32")

enum class StemOutputPoint { POST_FADER_POST_INSERT_PRE_MASTER }
data class StemExportRequest(val location: Location, val frames: Int, val tailFrames: Int = 0,
    val format: StemSampleFormat = StemSampleFormat.FLOAT32, val seed: Int = 1,
    val tailMode: ExportTailMode = ExportTailMode.INCLUDE_GRAPH_TAIL) {
    init { require(frames.toLong() in 1..ProjectLimits.MAX_TIMELINE_FRAMES && tailFrames in 0..MixerProgram.MAX_TAIL_FRAMES) }
}
data class StemFile(val busId: String, val fileName: String)
data class StemExportReceipt(val files: FrozenList<StemFile>, val frames: Long, val format: StemSampleFormat,
    val outputPoint: StemOutputPoint = StemOutputPoint.POST_FADER_POST_INSERT_PRE_MASTER)

enum class StemExportPhase { RENDERING, PUBLISHING, COMPLETE }
/** Each stem needs one full shared-graph pass. UI can show both pass count and within-pass frames. */
data class StemExportProgress(val phase: StemExportPhase, val completedStems: Int, val totalStems: Int,
    val busId: String?, val renderedFrames: Long, val framesPerStem: Long)

interface StemExportPort {
    /** The same graph for each pass; returns are separate stems. An integer headroom failure preserves the destination. */
    suspend fun export(project: Project, target: PlaybackTarget, request: StemExportRequest,
        progress: (StemExportProgress) -> Unit = {}): StemExportReceipt
}
