package com.choplab.ui

import androidx.compose.runtime.Immutable

/** Presentation contract for the user-selected 2026-09-24 linked workspace, not a document model.
 * Timeline/song frames are 48 kHz. Original/PAD/clip source frames retain their explicit source rate.
 * Lists/sets are immutable snapshots supplied by the host. No paths, PCM or reducers are owned here.
 */
const val CONTINUOUS_TIMELINE_RATE = 48_000

enum class ContinuousStage { CAPTURE, CHOP, BEAT, SAVE }
enum class ContinuousPane { PADS, TIMELINE }
enum class ContinuousPadMode { ONE_SHOT, GATE, LOOP }
enum class ContinuousPadKind { EMPTY, SAMPLE, DRUM, VOICE }
enum class ContinuousCapability {
    IMPORT_AUDIO, OPEN_PROJECT, SAVE_PROJECT, EXPORT_WAV, HISTORY,
    ORIGINAL_PLAYBACK, ORIGINAL_SEEK, ORIGINAL_MONITOR_GAIN, ORIGINAL_PITCH,
    SOURCE_RANGE, ASSIGN_SOURCE_RANGE, AUTO_CHOP, LIVE_CHOP,
    PAD_AUDITION, PAD_LOOP, PAD_PITCH, PAD_TONE, PAD_GAIN,
    PLACE_PAD, MOVE_CLIP, TRIM_CLIP, SPLIT_CLIP, DUPLICATE_CLIP, DELETE_CLIP,
    TRACK_MUTE, CLIP_GAIN, SONG_PLAYBACK, SONG_SEEK, SONG_MONITOR_GAIN, TEMPO,
    ADD_DRUM, RECORD_VOICE, SCRATCH, STOP_ALL,
}
enum class ContinuousUnavailable { NOT_CONNECTED, BUSY, NO_SOURCE, EMPTY_PAD, NO_CLIP, NO_OUTPUT }
enum class ContinuousStatus { LOADING, SAVING, SAVED, EXPORTING, EXPORTED, CANCELLED, FAILED, NO_OUTPUT }

@Immutable data class ContinuousSource(
    val id: String,
    val title: String,
    val frames: Long,
    val sampleRate: Int = 48_000,
    val peaks: List<Float> = emptyList(),
    val rangeStartFrame: Long = 0,
    val rangeEndFrame: Long = frames,
    val pitchSemitones: Float = 0f,
) {
    init { require(frames > 0 && sampleRate > 0 && rangeStartFrame >= 0 && rangeEndFrame > rangeStartFrame && rangeEndFrame <= frames) }
}

@Immutable data class ContinuousBank(val id: Int, val name: String = "") {
    init { require(id in 0..7) }
}

@Immutable data class ContinuousPad(
    val id: Int,
    val name: String = "",
    val kind: ContinuousPadKind = ContinuousPadKind.EMPTY,
    val mode: ContinuousPadMode = ContinuousPadMode.ONE_SHOT,
    val peaks: List<Float> = emptyList(),
    val sourceStartFrame: Long = 0,
    val sourceEndFrame: Long = 0,
    val sourceRate: Int = 48_000,
    val pitchSemitones: Float = 0f,
    val tone: Float = 1f,
    val gain: Float = 1f,
    val looping: Boolean = false,
) { init { require(id in 0..127 && sourceRate > 0) } }

@Immutable data class ContinuousTrack(
    val id: String,
    val name: String,
    val color: Long = 0xFF89AD50,
    val muted: Boolean = false,
)

@Immutable data class ContinuousClip(
    val id: String,
    val trackId: String,
    val title: String,
    /** Absolute 48 kHz frame position on the continuous arrangement. */
    val timelineStartFrame: Long,
    val timelineDurationFrames: Long,
    /** Source bounds use sourceRate and stay independent of timeline placement. */
    val sourceStartFrame: Long,
    val sourceEndFrame: Long,
    val sourceTotalFrames: Long,
    val sourceRate: Int = 48_000,
    val peaks: List<Float> = emptyList(),
    val gain: Float = 1f,
) {
    init {
        require(timelineStartFrame >= 0 && timelineDurationFrames > 0)
        require(sourceRate > 0 && sourceStartFrame >= 0 && sourceEndFrame > sourceStartFrame && sourceEndFrame <= sourceTotalFrames)
    }
    val timelineEndFrame: Long get() = timelineStartFrame + timelineDurationFrames
}

@Immutable data class ContinuousEditorState(
    val stage: ContinuousStage = ContinuousStage.CAPTURE,
    val projectTitle: String = "",
    /** Same original source object/identity in stages 1, 2 and 3; PAD selection cannot replace it. */
    val original: ContinuousSource? = null,
    val originalPlaying: Boolean = false,
    val originalMonitorGain: Float = 1f,
    val banks: List<ContinuousBank> = (0..7).map(::ContinuousBank),
    val selectedBank: Int = 0,
    val pads: List<ContinuousPad> = emptyList(),
    val selectedPadId: Int = 0,
    val tracks: List<ContinuousTrack> = emptyList(),
    val clips: List<ContinuousClip> = emptyList(),
    val selectedClipId: String? = null,
    val selectedTrackId: String? = null,
    val timelineDurationFrames: Long = 24L * CONTINUOUS_TIMELINE_RATE,
    /** View state only. It does not change clips or song duration. */
    val pixelsPerSecond: Float = 24f,
    val paneFraction: Float = .41f,
    val compactPane: ContinuousPane = ContinuousPane.PADS,
    val songPlaying: Boolean = false,
    val songMonitorGain: Float = 1f,
    val bpm: Int = 120,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val capabilities: Set<ContinuousCapability> = emptySet(),
    val unavailable: Map<ContinuousCapability, ContinuousUnavailable> = emptyMap(),
    val status: ContinuousStatus? = null,
) {
    init {
        require(selectedBank in 0..7 && selectedPadId in 0..127)
        require(timelineDurationFrames > 0 && pixelsPerSecond.isFinite() && pixelsPerSecond in 4f..240f)
        require(paneFraction.isFinite() && paneFraction in 0.2f..0.8f)
    }
    fun permits(capability: ContinuousCapability) = capability in capabilities
    val selectedPad: ContinuousPad? get() = pads.firstOrNull { it.id == selectedPadId }
    val selectedClip: ContinuousClip? get() = clips.firstOrNull { it.id == selectedClipId }
}

/** Read only in source waveform/time or song timeline/transport subtrees, never whole-app ticks. */
@Immutable data class ContinuousEditorReadout(
    val originalFrame: Long = 0,
    val songFrame: Long = 0,
)

/** Typed requests. Hosts/Studio confirm every edit; UI drag previews are never document commits. */
sealed interface ContinuousEditorAction {
    data class Navigate(val stage: ContinuousStage) : ContinuousEditorAction
    data object ImportAudio : ContinuousEditorAction
    data object OpenProject : ContinuousEditorAction
    data object SaveProject : ContinuousEditorAction
    data object ExportWav : ContinuousEditorAction
    data object Undo : ContinuousEditorAction
    data object Redo : ContinuousEditorAction
    data object StopAll : ContinuousEditorAction
    data object PlayOriginal : ContinuousEditorAction
    data object StopOriginal : ContinuousEditorAction
    data class SeekOriginal(val sourceFrame: Long) : ContinuousEditorAction
    /** Monitoring only; never alters PADs, arrangement, or exported audio. */
    data class SetOriginalMonitorGain(val gain: Float) : ContinuousEditorAction
    data class SetOriginalPitch(val semitones: Float) : ContinuousEditorAction
    data class SetSourceRange(val startFrame: Long, val endFrame: Long) : ContinuousEditorAction
    data object BeginLiveChop : ContinuousEditorAction
    data object AutoChop : ContinuousEditorAction
    data class AssignSourceRange(val padId: Int) : ContinuousEditorAction
    data class SelectBank(val bankId: Int) : ContinuousEditorAction
    data class SelectPad(val padId: Int) : ContinuousEditorAction
    data class TapPad(val padId: Int) : ContinuousEditorAction
    data class HoldPad(val padId: Int) : ContinuousEditorAction
    data class ReleasePad(val padId: Int) : ContinuousEditorAction
    data class TogglePadLoop(val padId: Int) : ContinuousEditorAction
    data class SetPadPitch(val padId: Int, val semitones: Float) : ContinuousEditorAction
    data class SetPadTone(val padId: Int, val tone: Float) : ContinuousEditorAction
    data class SetPadGain(val padId: Int, val gain: Float) : ContinuousEditorAction
    /** Explicit user placement only: original source is never placed by merely changing stages. */
    data class PlacePad(val padId: Int, val trackId: String?, val timelineFrame: Long) : ContinuousEditorAction
    data class SelectClip(val clipId: String?) : ContinuousEditorAction
    data class MoveClip(val clipId: String, val trackId: String, val timelineStartFrame: Long) : ContinuousEditorAction
    data class TrimClip(val clipId: String, val sourceStartFrame: Long, val sourceEndFrame: Long,
                        val timelineStartFrame: Long) : ContinuousEditorAction
    data class SplitClip(val clipId: String, val timelineFrame: Long) : ContinuousEditorAction
    data class DuplicateClip(val clipId: String) : ContinuousEditorAction
    data class DeleteClip(val clipId: String) : ContinuousEditorAction
    data class SetClipGain(val clipId: String, val gain: Float) : ContinuousEditorAction
    data class SetTrackMuted(val trackId: String, val muted: Boolean) : ContinuousEditorAction
    data class SetPixelsPerSecond(val value: Float) : ContinuousEditorAction
    data object FitTimeline : ContinuousEditorAction
    data class ResizePanes(val fraction: Float) : ContinuousEditorAction
    data object ResetPanes : ContinuousEditorAction
    data class SelectCompactPane(val pane: ContinuousPane) : ContinuousEditorAction
    data class SeekSong(val timelineFrame: Long) : ContinuousEditorAction
    data object PlaySong : ContinuousEditorAction
    data object PauseSong : ContinuousEditorAction
    data object StopSong : ContinuousEditorAction
    /** Playback monitoring only, separate from track/clip/export gains. */
    data class SetSongMonitorGain(val gain: Float) : ContinuousEditorAction
    data class SetTempo(val bpm: Int) : ContinuousEditorAction
    data object AddDrum : ContinuousEditorAction
    data object RecordVoice : ContinuousEditorAction
    data object OpenScratch : ContinuousEditorAction
}
