package com.choplab.core.model

import com.choplab.engine.OfflinePitchCorrection
import com.choplab.engine.PitchCorrectionSettings

/** Original native range and deterministic float render. The clip link survives move/delete like a comp recipe. */
data class VocalPitchCorrection(
    val id: String,
    val clipId: String,
    val sourceAssetHash: String,
    val sourceRange: FrameRange,
    val renderedAssetHash: String,
    val settings: PitchCorrectionSettings,
    val algorithmVersion: Int = OfflinePitchCorrection.VERSION,
) {
    init {
        requireId(id); requireId(clipId); requireHash(sourceAssetHash); requireHash(renderedAssetHash)
        require(sourceAssetHash != renderedAssetHash && algorithmVersion == OfflinePitchCorrection.VERSION)
    }
    fun firstFrame48(source: Asset): Long = takeSourceFrame48(sourceRange.start, source.sampleRate)
    fun frames48(source: Asset): Long = takeSourceFrame48(sourceRange.end, source.sampleRate) - firstFrame48(source)

    /** A saved selection in the correction's output coordinates. Native crops are exact only at 48 kHz. */
    internal fun outputRange(source: Asset, clip: Clip): FrameRange? = when {
        clip.assetHash == renderedAssetHash && clip.range.end <= frames48(source) -> clip.range
        clip.assetHash != sourceAssetHash -> null
        clip.range == sourceRange -> FrameRange(0, frames48(source))
        source.sampleRate == 48_000 && clip.range.start >= sourceRange.start && clip.range.end <= sourceRange.end ->
            FrameRange(clip.range.start - sourceRange.start, clip.range.end - sourceRange.start)
        else -> null
    }

    /** Never approximate a native endpoint by inverting the resampling ratio. */
    internal fun originalRange(source: Asset, output: FrameRange): FrameRange? = when {
        output.end > frames48(source) -> null
        output == FrameRange(0, frames48(source)) -> sourceRange
        source.sampleRate == 48_000 -> FrameRange(sourceRange.start + output.start, sourceRange.start + output.end)
        else -> null
    }
}

internal fun validateVocalPitchCorrections(project: Project) {
    require(project.pitchCorrections.size <= 64 && project.pitchCorrections.map { it.id }.distinct().size == project.pitchCorrections.size)
    require(project.pitchCorrections.map { it.clipId }.distinct().size == project.pitchCorrections.size)
    for (correction in project.pitchCorrections) {
        val source = project.asset(correction.sourceAssetHash)
        val rendered = project.asset(correction.renderedAssetHash)
        require(source.required && correction.sourceRange.end <= source.frames)
        require(rendered.required && rendered.role == AssetRole.RENDERED && rendered.sampleRate == 48_000 && rendered.channels == 2)
        require(rendered.derivedFrom == source.hash && rendered.frames == correction.frames48(source))
    }
}
