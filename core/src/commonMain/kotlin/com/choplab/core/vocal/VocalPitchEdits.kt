package com.choplab.core.vocal

import com.choplab.core.DocumentState
import com.choplab.core.ProgramCompiler
import com.choplab.core.edit.Intent
import com.choplab.core.edit.Reducer
import com.choplab.core.model.*
import com.choplab.engine.PitchCorrectionSettings

enum class VocalPitchProblem { NO_VOICE_CLIP, STALE, TRIMMED_SELECTION, LIMIT, INVALID_INPUT, RENDER_FAILED }
class VocalPitchException(val problem: VocalPitchProblem) : IllegalArgumentException(problem.name)

data class VocalPitchDraft(
    val id: String,
    val revision: Long,
    val expectedClip: Clip,
    val sourceAssetHash: String,
    val sourceRange: FrameRange,
    val settings: PitchCorrectionSettings,
) {
    init { requireId(id); require(revision >= 0); requireHash(sourceAssetHash) }
    fun firstFrame48(source: Asset): Long = takeSourceFrame48(sourceRange.start, source.sampleRate)
    fun frames48(source: Asset): Long = takeSourceFrame48(sourceRange.end, source.sampleRate) - firstFrame48(source)
    /** Pitch preserves duration. Keep a rendered crop in its exact output domain; never infer native endpoints. */
    fun auditionRange(project: Project): FrameRange {
        if (expectedClip.assetHash != sourceAssetHash) return expectedClip.range
        if (expectedClip.range == sourceRange) return FrameRange(0, frames48(project.asset(sourceAssetHash)))
        require(project.asset(sourceAssetHash).sampleRate == 48_000 &&
            expectedClip.range.start >= sourceRange.start && expectedClip.range.end <= sourceRange.end)
        return FrameRange(expectedClip.range.start - sourceRange.start, expectedClip.range.end - sourceRange.start)
    }
}

object VocalPitchEdits {
    fun draft(document: DocumentState, clipId: String, id: String, settings: PitchCorrectionSettings): VocalPitchDraft {
        val project = document.project
        val clip = project.clips.firstOrNull { it.id == clipId } ?: throw VocalPitchException(VocalPitchProblem.NO_VOICE_CLIP)
        requireVoice(project, clip)
        val old = project.pitchCorrections.firstOrNull { it.clipId == clipId }
        // Always rerender the full original context, even for a cropped correction. Apply preserves the output crop.
        if (old != null && old.outputRange(project.asset(old.sourceAssetHash), clip) != null) {
            return VocalPitchDraft(old.id, document.revision, clip, old.sourceAssetHash, old.sourceRange, settings).also { validate(project, it) }
        }
        return VocalPitchDraft(old?.id ?: id, document.revision, clip, clip.assetHash, clip.range, settings).also { validate(project, it) }
    }

    fun validate(project: Project, draft: VocalPitchDraft) {
        if (project.clips.firstOrNull { it.id == draft.expectedClip.id } != draft.expectedClip) throw VocalPitchException(VocalPitchProblem.STALE)
        requireVoice(project, draft.expectedClip)
        val source = project.asset(draft.sourceAssetHash)
        require(source.required && draft.sourceRange.end <= source.frames)
        val frames = draft.frames48(source)
        if (frames !in 1..ProjectLimits.MAX_FRAMES || 44 + frames * 8 > ProjectLimits.MAX_ASSET_BYTES)
            throw VocalPitchException(VocalPitchProblem.LIMIT)
        val current = project.pitchCorrections.firstOrNull { it.clipId == draft.expectedClip.id }
        require(project.pitchCorrections.none { it.id == draft.id && it.clipId != draft.expectedClip.id })
        require(current == null || current.id == draft.id)
        require((draft.expectedClip.assetHash == draft.sourceAssetHash && draft.expectedClip.range == draft.sourceRange) ||
            (current != null && current.sourceAssetHash == draft.sourceAssetHash && current.sourceRange == draft.sourceRange &&
                current.outputRange(source, draft.expectedClip) != null))
    }

    /** Call through Action.Edit(intent, draft.revision); the actor rechecks revision at commit. */
    fun apply(document: DocumentState, draft: VocalPitchDraft, rendered: Asset): Intent.ApplyVocalPitch {
        if (document.revision != draft.revision) throw VocalPitchException(VocalPitchProblem.STALE)
        validate(document.project, draft)
        val old = document.project.pitchCorrections.firstOrNull { it.clipId == draft.expectedClip.id }
        val original = old?.takeIf { it.sourceAssetHash == draft.sourceAssetHash && it.sourceRange == draft.sourceRange }?.normalizedOriginalHash
        val correction = VocalPitchCorrection(draft.id, draft.expectedClip.id, draft.sourceAssetHash, draft.sourceRange, rendered.hash, draft.settings,
            normalizedOriginalHash = original)
        return Intent.ApplyVocalPitch(correction, rendered, draft.expectedClip).also {
            val after = Reducer.reduce(document.project, it).project
            if (!ProgramCompiler.songFits(after)) throw VocalPitchException(VocalPitchProblem.LIMIT)
        }
    }

    fun select(document: DocumentState, correctionId: String, original: Boolean, normalizedOriginal: Asset? = null): Intent.SelectVocalPitch {
        val correction = requireNotNull(document.project.pitchCorrections.firstOrNull { it.id == correctionId })
        val clip = document.project.clips.firstOrNull { it.id == correction.clipId } ?: throw VocalPitchException(VocalPitchProblem.NO_VOICE_CLIP)
        val source = document.project.asset(correction.sourceAssetHash)
        val output = correction.outputRange(source, clip)
        if (normalizedOriginal != null) {
            correction.validateNormalizedOriginal(source, normalizedOriginal)
            require(correction.normalizedOriginalHash == null || correction.normalizedOriginalHash == normalizedOriginal.hash)
            if (document.project.assets.none { it.hash == normalizedOriginal.hash } &&
                (document.project.assets.size >= ProjectLimits.MAX_ASSETS ||
                    document.project.assets.sumOf { it.byteCount } + normalizedOriginal.byteCount > ProjectLimits.MAX_TOTAL_BYTES))
                throw VocalPitchException(VocalPitchProblem.LIMIT)
        }
        if (output == null || (original && normalizedOriginal == null && correction.originalRange(source, output) == null))
            throw VocalPitchException(VocalPitchProblem.TRIMMED_SELECTION)
        return Intent.SelectVocalPitch(correctionId, clip, original, normalizedOriginal).also { Reducer.reduce(document.project, it) }
    }

    private fun requireVoice(project: Project, clip: Clip) {
        if (project.tracks.firstOrNull { it.id == clip.trackId }?.kind != TrackKind.VOCAL)
            throw VocalPitchException(VocalPitchProblem.NO_VOICE_CLIP)
    }
}
