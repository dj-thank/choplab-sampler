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
    /** Musical interval relative to the complete source context, retained across rerenders without repeated rounding. */
    val crop: StretchCrop? = null,
) {
    init {
        requireHash(sourceAssetHash); requireHash(renderedAssetHash)
        require(sourceMilliBpm in 40_000..240_000 && targetMilliBpm in 40_000..240_000)
        require(algorithmVersion == WindowedWsola.VERSION)
    }

    fun contains(hash: String?, range: FrameRange?): Boolean = hash == renderedAssetHash && range != null &&
        range.start >= renderedRange.start && range.end <= renderedRange.end

    fun cropFor(source: Asset, range: FrameRange): StretchCrop? {
        require(contains(renderedAssetHash, range))
        if (crop != null && crop.at(renderedRange.length) == range) return crop
        if (renderedAssetHash == sourceAssetHash) {
            if (range == sourceRange) return null
            val first = stretchFirstFrame(source, sourceRange)
            return StretchCrop(FrameRange(stretchFirstFrame(source, range) - first,
                range.end * 48_000 / source.sampleRate - first), stretchInputFrames(source, sourceRange))
        }
        return if (range == renderedRange) null else StretchCrop(range, renderedRange.length)
    }
}

/** Exact reference fractions. Mapping rounds endpoints to the nearest frame (ties upward); a nonempty
 * interval that would collapse occupies the nearest one frame. The fractions, not rounded endpoints,
 * survive a later length change. No native source endpoint is inferred from these output coordinates. */
data class StretchCrop(val range: FrameRange, val referenceFrames: Long) {
    init { require(referenceFrames in 1..ProjectLimits.MAX_FRAMES && range.end <= referenceFrames) }
    fun at(frames: Long): FrameRange {
        require(frames in 1..ProjectLimits.MAX_FRAMES)
        val start = ((range.start * frames + referenceFrames / 2) / referenceFrames).coerceAtMost(frames - 1)
        val end = ((range.end * frames + referenceFrames / 2) / referenceFrames).coerceIn(start + 1, frames)
        return FrameRange(start, end)
    }
}

internal fun validateBeatStretches(project: Project) {
    require(project.beatStretches.size <= 256 && project.beatStretches.map { it.target }.distinct().size == project.beatStretches.size)
    for (recipe in project.beatStretches) {
        val source = project.asset(recipe.sourceAssetHash)
        val rendered = project.asset(recipe.renderedAssetHash)
        require(source.required && recipe.sourceRange.end <= source.frames && rendered.required && recipe.renderedRange.end <= rendered.frames)
        if (recipe.sourceMilliBpm == recipe.targetMilliBpm && rendered == source) {
            require(recipe.renderedRange == recipe.sourceRange)
            require(recipe.crop == null || (source.sampleRate == 48_000 && recipe.sourceRange == FrameRange(0, source.frames)))
        } else {
            require(recipe.sourceMilliBpm != recipe.targetMilliBpm || recipe.crop != null)
            require(rendered.role == AssetRole.RENDERED && rendered.derivedFrom == source.hash && rendered.sampleRate == 48_000 && rendered.channels == 2)
            require(recipe.renderedRange == FrameRange(0, stretchFrames(source, recipe.sourceRange, recipe.sourceMilliBpm, recipe.targetMilliBpm)))
            require(rendered.frames == recipe.renderedRange.end)
        }
        recipe.crop?.at(rendered.frames)
    }
}

fun stretchFirstFrame(source: Asset, range: FrameRange): Long = (range.start * 48_000 + source.sampleRate - 1) / source.sampleRate
fun stretchInputFrames(source: Asset, range: FrameRange): Long = range.end * 48_000 / source.sampleRate - stretchFirstFrame(source, range)
fun stretchFrames(source: Asset, range: FrameRange, sourceMilliBpm: Int, targetMilliBpm: Int): Long =
    (stretchInputFrames(source, range) * sourceMilliBpm + targetMilliBpm / 2) / targetMilliBpm
