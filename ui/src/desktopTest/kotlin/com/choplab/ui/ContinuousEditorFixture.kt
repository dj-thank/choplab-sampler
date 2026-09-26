package com.choplab.ui

import kotlin.math.exp
import kotlin.math.sin

/** Synthetic visual fixture matching the supplied linked-workspace arrangement; no bundled media. */
internal object ContinuousEditorFixture {
    private fun peaks(count: Int, level: Float = 1f) = List(count) { index ->
        (level * (.18 + .75 * exp(-(index % 80) / 24.0)) * (.85 + .15 * sin(index * .47))).toFloat()
    }
    private val originalPeaks = peaks(720, .22f)
    private val padPeaks = peaks(120, .8f)
    private fun frames(seconds: Double) = (seconds * CONTINUOUS_TIMELINE_RATE).toLong()
    private fun clip(id: String, track: String, title: String, start: Double, duration: Double) = ContinuousClip(
        id, track, title, frames(start), frames(duration), 0, frames(duration), frames(duration + 2), peaks = padPeaks)

    fun state(stage: ContinuousStage = ContinuousStage.BEAT) = ContinuousEditorState(
        stage = stage, projectTitle = "Linked workspace",
        original = ContinuousSource("original-warm-keys", "Warm Keys", frames(6.0), peaks = originalPeaks),
        pads = (0..127).map { id -> ContinuousPad(id,
            name = when (id) { 0 -> "Piano"; 1 -> "Guitar"; 2 -> "Warm Keys"; 3 -> "Bass"; else -> "" },
            kind = if (id in 0..3) ContinuousPadKind.SAMPLE else ContinuousPadKind.EMPTY,
            peaks = if (id in 0..3) padPeaks else emptyList(), sourceEndFrame = if (id in 0..3) frames(6.0) else 0,
            gain = if (id == 2) .7f else 1f) },
        selectedPadId = 2,
        tracks = listOf(ContinuousTrack("melody", "メロディ", 0xFF94B750), ContinuousTrack("drums", "ドラム", 0xFFE5A04B),
            ContinuousTrack("voice", "声", 0xFFD3C28A), ContinuousTrack("scratch", "スクラッチ", 0xFFD98B5F)),
        clips = listOf(clip("piano", "melody", "A01", 0.0, 4.0), clip("warm-1", "melody", "A03 Warm Keys", 6.0, 6.0),
            clip("warm-2", "melody", "A03 Warm Keys", 12.0, 6.0), clip("guitar", "melody", "A02 Guitar", 18.0, 6.0)) +
            (0..5).map { clip("drum-$it", "drums", "B01", it * 4.0, 4.0) } +
            listOf(clip("voice-1", "voice", "D01", 4.0, 3.0), clip("voice-2", "voice", "D01", 20.0, 3.0),
                clip("scratch-1", "scratch", "C01", 10.5, 2.0)),
        selectedClipId = "warm-1", selectedTrackId = "melody", pixelsPerSecond = 25f,
        bpm = 92,
        capabilities = ContinuousCapability.entries.toSet() - setOf(ContinuousCapability.ADD_DRUM, ContinuousCapability.RECORD_VOICE,
            ContinuousCapability.SCRATCH, ContinuousCapability.LIVE_CHOP),
    )
    fun readout() = ContinuousEditorReadout(originalFrame = 0, songFrame = frames(8.3))
}
