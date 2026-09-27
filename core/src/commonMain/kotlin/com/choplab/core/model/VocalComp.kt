package com.choplab.core.model

/** A non-destructive choice of a take at absolute 48 kHz song frames, [startFrame, endFrame). */
data class VocalCompSegment(
    val id: String,
    val takeId: String,
    val startFrame: Long,
    val endFrame: Long,
    /** Historical lyric link. Later lyric edits may remove the line without deleting the recording. */
    val lyricLineId: String? = null,
) {
    init {
        requireId(id); requireId(takeId); lyricLineId?.let(::requireId)
        require(startFrame >= 0 && endFrame > startFrame && endFrame <= ProjectLimits.MAX_TIMELINE_FRAMES)
    }
}

/** Saved recipe and float render. Moving, trimming or deleting an ordinary Clip never deletes its source choices. */
data class VocalComp(val id: String, val renderedAssetHash: String, val segments: FrozenList<VocalCompSegment>) {
    init { requireId(id); requireHash(renderedAssetHash); validateCompSegments(segments) }
    val startFrame: Long get() = segments.first().startFrame
    val endFrame: Long get() = segments.last().endFrame
}

fun validateCompSegments(segments: List<VocalCompSegment>) {
    require(segments.size in 1..4096 && segments.map { it.id }.distinct().size == segments.size)
    require(segments.zipWithNext().all { (a, b) -> a.endFrame <= b.startFrame }) { "Overlapping or unordered comp lines" }
    require(segments.last().endFrame - segments.first().startFrame <= ProjectLimits.MAX_FRAMES)
}

/** Same ceil mapping on both source boundaries as ProgramCompiler. */
fun takeSourceFrame48(frame: Long, rate: Int): Long = (frame * 48_000 + rate - 1) / rate
fun Take.correctedStartFrame(): Long = timelineStartFrame - compensationFrames.toLong()
fun Take.correctedEndFrame(asset: Asset): Long = correctedStartFrame() +
    takeSourceFrame48(range.end, asset.sampleRate) - takeSourceFrame48(range.start, asset.sampleRate)

internal fun validateVocalComps(project: Project) {
    require(project.vocalComps.size <= 64 && project.vocalComps.map { it.id }.distinct().size == project.vocalComps.size)
    val takes = project.takes.associateBy { it.id }
    for (comp in project.vocalComps) {
        val rendered = project.asset(comp.renderedAssetHash)
        require(rendered.required && rendered.role == AssetRole.RENDERED && rendered.sampleRate == 48_000 && rendered.channels == 2)
        require(rendered.frames == comp.endFrame - comp.startFrame)
        for (segment in comp.segments) {
            val take = requireNotNull(takes[segment.takeId]) { "Missing comp take" }
            require(segment.startFrame >= take.correctedStartFrame() && segment.endFrame <= take.correctedEndFrame(project.asset(take.assetHash))) {
                "Comp line exceeds its take"
            }
        }
    }
}
