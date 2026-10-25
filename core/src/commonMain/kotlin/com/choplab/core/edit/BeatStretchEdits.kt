package com.choplab.core.edit

import com.choplab.core.DocumentState
import com.choplab.core.ProgramCompiler
import com.choplab.core.model.*

enum class StretchProblem { NO_TARGET, INVALID_INPUT, STALE, TRIMMED, LIMIT, BUSY, RECORDING, FAILED, PREVIEW_FAILED }
class StretchException(val problem: StretchProblem) : IllegalArgumentException(problem.name)
data class StretchDraft(val revision: Long, val target: StretchTarget, val expectedPad: Pad?, val expectedClip: Clip?,
                        val sourceAssetHash: String, val sourceRange: FrameRange, val sourceMilliBpm: Int, val targetMilliBpm: Int,
                        val crop: StretchCrop? = null) {
    fun auditionRange(project: Project, original: Boolean): FrameRange {
        val source = project.asset(sourceAssetHash)
        val frames = if (original) stretchInputFrames(source, sourceRange) else
            stretchFrames(source, sourceRange, sourceMilliBpm, targetMilliBpm)
        return crop?.at(frames) ?: FrameRange(0, frames)
    }
}

object BeatStretchEdits {
    fun draft(document: DocumentState, target: StretchTarget, sourceMilliBpm: Int): StretchDraft {
        if (sourceMilliBpm !in 40_000..240_000) throw StretchException(StretchProblem.INVALID_INPUT)
        val project = document.project
        val pad = if (target.kind == StretchKind.PAD) project.pads[target.id.toInt()] else null
        val clip = if (target.kind == StretchKind.CLIP) project.clips.firstOrNull { it.id == target.id } else null
        var hash = pad?.assetHash ?: clip?.assetHash ?: throw StretchException(StretchProblem.NO_TARGET)
        var range = pad?.range ?: clip!!.range
        val old = project.beatStretches.firstOrNull { it.target == target }
        var crop: StretchCrop? = null
        if (old != null && hash == old.renderedAssetHash) {
            if (!old.contains(hash, range)) throw StretchException(StretchProblem.TRIMMED)
            crop = try { old.cropFor(project.asset(old.sourceAssetHash), range) }
                catch (_: IllegalArgumentException) { throw StretchException(StretchProblem.INVALID_INPUT) }
            hash = old.sourceAssetHash; range = old.sourceRange
        }
        return StretchDraft(document.revision, target, pad, clip, hash, range, sourceMilliBpm, project.tempo.milliBpm, crop).also { validate(project, it) }
    }

    fun validate(project: Project, draft: StretchDraft) {
        if (project.tempo.milliBpm != draft.targetMilliBpm ||
            (draft.target.kind == StretchKind.PAD && project.pads[draft.target.id.toInt()] != draft.expectedPad) ||
            (draft.target.kind == StretchKind.CLIP && project.clips.firstOrNull { it.id == draft.target.id } != draft.expectedClip))
            throw StretchException(StretchProblem.STALE)
        require((draft.expectedPad == null) != (draft.expectedClip == null))
        if (draft.sourceMilliBpm !in 40_000..240_000) throw StretchException(StretchProblem.INVALID_INPUT)
        val source = project.asset(draft.sourceAssetHash)
        require(source.required && draft.sourceRange.end <= source.frames)
        val input = stretchInputFrames(source, draft.sourceRange)
        val output = stretchFrames(source, draft.sourceRange, draft.sourceMilliBpm, draft.targetMilliBpm)
        if (input < 1 || output !in 1..ProjectLimits.MAX_FRAMES || 44 + output * 8 > ProjectLimits.MAX_ASSET_BYTES)
            throw StretchException(StretchProblem.LIMIT)
        if (draft.sourceMilliBpm != draft.targetMilliBpm && (minOf(input, output) < 128 || input == output))
            throw StretchException(StretchProblem.INVALID_INPUT)
        val currentHash = draft.expectedPad?.assetHash ?: draft.expectedClip!!.assetHash
        val currentRange = draft.expectedPad?.range ?: draft.expectedClip!!.range
        val old = project.beatStretches.firstOrNull { it.target == draft.target }
        require((currentHash == source.hash && currentRange == draft.sourceRange && draft.crop == null) ||
            (old != null && old.contains(currentHash, currentRange) &&
                old.sourceAssetHash == source.hash && old.sourceRange == draft.sourceRange && old.cropFor(source, currentRange) == draft.crop))
    }

    fun apply(document: DocumentState, draft: StretchDraft, rendered: Asset): Intent.ApplyBeatStretch {
        if (document.revision != draft.revision) throw StretchException(StretchProblem.STALE)
        return Intent.ApplyBeatStretch(draft, rendered).also { reduce(document.project, it) }
    }

    internal fun reduce(project: Project, intent: Intent.ApplyBeatStretch): Project {
        val draft = intent.draft
        validate(project, draft)
        val source = project.asset(draft.sourceAssetHash)
        val rendered = intent.rendered
        val fullRange = if (draft.sourceMilliBpm == draft.targetMilliBpm && draft.crop == null) draft.sourceRange else FrameRange(0, rendered.frames)
        val range = draft.crop?.at(rendered.frames) ?: fullRange
        val recipe = BeatStretch(draft.target, source.hash, draft.sourceRange, rendered.hash, fullRange, draft.sourceMilliBpm, draft.targetMilliBpm, crop = draft.crop)
        val existing = project.assets.firstOrNull { it.hash == rendered.hash }
        require(existing == null || existing == rendered)
        if ((existing == null && (project.assets.size == ProjectLimits.MAX_ASSETS ||
                project.assets.sumOf { it.byteCount } + rendered.byteCount > ProjectLimits.MAX_TOTAL_BYTES)) ||
            (project.beatStretches.size == 256 && project.beatStretches.none { it.target == draft.target }))
            throw StretchException(StretchProblem.LIMIT)
        val after = project.copy(assets = (if (existing == null) project.assets + rendered else project.assets).frozen(),
            pads = project.pads.map { if (it == draft.expectedPad) it.copy(assetHash = rendered.hash, range = range) else it }.frozen(),
            clips = project.clips.map { if (it == draft.expectedClip) it.copy(assetHash = rendered.hash, range = range) else it }.frozen(),
            beatStretches = (project.beatStretches.filterNot { it.target == draft.target } + recipe).frozen())
        if (!ProgramCompiler.songFits(after)) throw StretchException(StretchProblem.LIMIT)
        return after
    }
}
