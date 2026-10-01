package com.choplab.core.vocal

import com.choplab.core.DocumentState
import com.choplab.core.ProgramCompiler
import com.choplab.core.edit.Intent
import com.choplab.core.edit.Reducer
import com.choplab.core.model.*
import com.choplab.engine.PitchCorrectionSettings

enum class VocalPitchProblem { NO_VOICE_CLIP, STALE, LIMIT, INVALID_INPUT, RENDER_FAILED }
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
}

object VocalPitchEdits {
    fun draft(document: DocumentState, clipId: String, id: String, settings: PitchCorrectionSettings): VocalPitchDraft {
        val project = document.project
        val clip = project.clips.firstOrNull { it.id == clipId } ?: throw VocalPitchException(VocalPitchProblem.NO_VOICE_CLIP)
        requireVoice(project, clip)
        val old = project.pitchCorrections.firstOrNull { it.clipId == clipId }
        // Re-edit an intact correction from its original, never compound another pitch process by accident.
        if (old != null && clip.assetHash == old.renderedAssetHash) {
            if (clip.range != FrameRange(0, project.asset(old.renderedAssetHash).frames)) throw VocalPitchException(VocalPitchProblem.STALE)
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
                draft.expectedClip.assetHash == current.renderedAssetHash && draft.expectedClip.range == FrameRange(0, project.asset(current.renderedAssetHash).frames)))
    }

    /** Call through Action.Edit(intent, draft.revision); the actor rechecks revision at commit. */
    fun apply(document: DocumentState, draft: VocalPitchDraft, rendered: Asset): Intent.ApplyVocalPitch {
        if (document.revision != draft.revision) throw VocalPitchException(VocalPitchProblem.STALE)
        validate(document.project, draft)
        val correction = VocalPitchCorrection(draft.id, draft.expectedClip.id, draft.sourceAssetHash, draft.sourceRange, rendered.hash, draft.settings)
        return Intent.ApplyVocalPitch(correction, rendered, draft.expectedClip).also {
            val after = Reducer.reduce(document.project, it).project
            if (!ProgramCompiler.songFits(after)) throw VocalPitchException(VocalPitchProblem.LIMIT)
        }
    }

    fun select(document: DocumentState, correctionId: String, original: Boolean): Intent.SelectVocalPitch {
        val correction = requireNotNull(document.project.pitchCorrections.firstOrNull { it.id == correctionId })
        val clip = document.project.clips.firstOrNull { it.id == correction.clipId } ?: throw VocalPitchException(VocalPitchProblem.NO_VOICE_CLIP)
        return Intent.SelectVocalPitch(correctionId, clip, original).also { Reducer.reduce(document.project, it) }
    }

    private fun requireVoice(project: Project, clip: Clip) {
        if (project.tracks.firstOrNull { it.id == clip.trackId }?.kind != TrackKind.VOCAL)
            throw VocalPitchException(VocalPitchProblem.NO_VOICE_CLIP)
    }
}
