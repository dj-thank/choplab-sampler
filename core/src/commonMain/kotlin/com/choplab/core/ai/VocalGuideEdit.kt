package com.choplab.core.ai

import com.choplab.core.edit.Intent
import com.choplab.core.model.*

/** Called only on the preview's captured document, followed by Studio's expectedRevision guard. */
fun FlowPlan.guideEdit(project: Project, prepared: List<PreparedVocalLine>, idPrefix: String): TtsResult<Intent.ApplyVocalGuide> {
    if (prepared.size != rows.size || prepared.map { it.line.id }.distinct().size != prepared.size) return ttsFailure(TtsProblem.INVALID_INPUT)
    if (rows.any { row -> project.lyrics.none { it.id == row.line.id && it.text == row.line.text } })
        return ttsFailure(TtsProblem.STALE_DOCUMENT)
    return try {
        requireId(idPrefix)
        require(idPrefix.length <= 48)
        val track = Track("$idPrefix-track", "Vocal guide", TrackKind.GUIDE)
        require(project.tracks.none { it.id == track.id })
        val byId = prepared.associateBy { it.line.id }
        val clips = rows.mapIndexed { index, row ->
            val entry = requireNotNull(byId[row.line.id])
            require(entry.line.text == row.line.text && entry.line.startTick == row.line.startTick && entry.line.endTick == row.line.endTick)
            require(entry.asset.role == AssetRole.RENDERED && entry.asset.required && entry.asset.sampleRate == 48_000 && entry.asset.channels == 2)
            Clip("$idPrefix-$index", track.id, entry.asset.hash, FrameRange(0, entry.asset.frames), row.line.startTick)
        }
        val lines = project.lyrics.map { byId[it.id]?.line ?: it }.sortedBy { it.startTick }.frozen()
        TtsResult.Success(Intent.ApplyVocalGuide((project.tracks + track).frozen(), (project.clips + clips).frozen(), prepared.map { it.asset }.distinctBy { it.hash }.frozen(), lines, structure))
    } catch (_: IllegalArgumentException) { ttsFailure(TtsProblem.INVALID_INPUT) }
}
