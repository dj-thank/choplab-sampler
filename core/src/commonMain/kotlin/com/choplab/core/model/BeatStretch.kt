package com.choplab.core.model

import com.choplab.engine.WindowedWsola

enum class StretchKind { PAD, CLIP }
data class StretchTarget(val kind: StretchKind, val id: String) {
    init { requireId(id); if (kind == StretchKind.PAD) require(id.toIntOrNull()?.let { it in 0..127 && it.toString() == id } == true) }
}

/** Immutable recipe. Changing song tempo never rerenders or rewrites an existing asset. */
data class BeatStretch(
    val target: StretchTarget,
    val sourceAssetHash: String,
    val sourceRange: FrameRange,
    val renderedAssetHash: String,
    val renderedRange: FrameRange,
    val sourceMilliBpm: Int,
    val targetMilliBpm: Int,
    val algorithmVersion: Int = WindowedWsola.VERSION,
) {
    init {
        requireHash(sourceAssetHash); requireHash(renderedAssetHash)
        require(sourceMilliBpm in 40_000..240_000 && targetMilliBpm in 40_000..240_000)
        require(algorithmVersion == WindowedWsola.VERSION)
    }
}

internal fun validateBeatStretches(project: Project) {
    require(project.beatStretches.size <= 256 && project.beatStretches.map { it.target }.distinct().size == project.beatStretches.size)
    for (recipe in project.beatStretches) {
        val source = project.asset(recipe.sourceAssetHash)
        val rendered = project.asset(recipe.renderedAssetHash)
        require(source.required && recipe.sourceRange.end <= source.frames && rendered.required && recipe.renderedRange.end <= rendered.frames)
        if (recipe.sourceMilliBpm == recipe.targetMilliBpm) {
            require(rendered == source && recipe.renderedRange == recipe.sourceRange)
        } else {
            require(rendered.role == AssetRole.RENDERED && rendered.derivedFrom == source.hash && rendered.sampleRate == 48_000 && rendered.channels == 2)
            require(recipe.renderedRange == FrameRange(0, stretchFrames(source, recipe.sourceRange, recipe.sourceMilliBpm, recipe.targetMilliBpm)))
            require(rendered.frames == recipe.renderedRange.end)
        }
    }
}

fun stretchFirstFrame(source: Asset, range: FrameRange): Long = (range.start * 48_000 + source.sampleRate - 1) / source.sampleRate
fun stretchInputFrames(source: Asset, range: FrameRange): Long = range.end * 48_000 / source.sampleRate - stretchFirstFrame(source, range)
fun stretchFrames(source: Asset, range: FrameRange, sourceMilliBpm: Int, targetMilliBpm: Int): Long =
    (stretchInputFrames(source, range) * sourceMilliBpm + targetMilliBpm / 2) / targetMilliBpm
