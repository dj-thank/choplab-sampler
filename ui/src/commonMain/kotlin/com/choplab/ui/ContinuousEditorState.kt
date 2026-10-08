package com.choplab.ui

import androidx.compose.runtime.Immutable
import com.choplab.core.chop.*
import com.choplab.ui.chop.LiveChopTimingState

/** Presentation contract for the user-selected 2026-09-24 linked workspace, not a document model.
 * Timeline/song frames are 48 kHz. Original/PAD/clip source frames retain their explicit source rate.
 * Lists/sets are immutable snapshots supplied by the host. No paths, PCM or reducers are owned here.
 */
const val CONTINUOUS_TIMELINE_RATE = 48_000

enum class WaveformLoadState { LOADING, READY, FAILED }

enum class ContinuousStage { CAPTURE, CHOP, BEAT, SAVE }
enum class ContinuousPane { PADS, TIMELINE }
enum class ContinuousPadMode { ONE_SHOT, GATE, LOOP }
enum class ContinuousPadKind { EMPTY, SAMPLE, DRUM, VOICE }
enum class ContinuousNoteRepeat(val ticks: Int) {
    OFF(0), QUARTER(960), EIGHTH(480), SIXTEENTH(240), THIRTY_SECOND(120), EIGHTH_TRIPLET(320), SIXTEENTH_TRIPLET(160)
}
/**
 * Where the song timeline puts what is placed or moved: on the nearest straight or triplet grid at the song's
 * tempo ([ticks] at 960 a beat), where the clip then keeps its beat when the tempo changes; or just where it is let go.
 */
enum class ContinuousGrid(val ticks: Int) { BEAT(960), HALF(480), QUARTER(240), EIGHTH_TRIPLET(320), SIXTEENTH_TRIPLET(160), FREE(0) }
enum class ContinuousCapability {
    BEAT_STRETCH,
    LYRICS_EDIT, LYRICS_FILES, LYRIC_PROPOSAL, STEP_PATTERNS, NOTE_REPEAT, LOOP_OVERDUB, VOCAL_GUIDE, VOCAL_TAKES, VOCAL_PUNCH, VOCAL_PRACTICE, VOCAL_PITCH, VOCAL_COACH, FOUR_STEMS, SOURCE_ANALYSIS,
    NEW_PROJECT, RELOAD_AUDIO, IMPORT_AUDIO, IMPORT_LIBRARY, IMPORT_ONLINE, SPOTIFY_METADATA, SEPARATE_SOURCE, OPEN_PROJECT, SAVE_PROJECT, EXPORT_WAV, EXPORT_STEMS, MIXER, HISTORY,
    ORIGINAL_PLAYBACK, ORIGINAL_SEEK, ORIGINAL_MONITOR_GAIN, ORIGINAL_PITCH,
    SOURCE_RANGE, ASSIGN_SOURCE_RANGE, AUTO_CHOP, LIVE_CHOP,
    PAD_AUDITION, PAD_LOOP, PAD_PITCH, PAD_TONE, PAD_GAIN,
    /** The selected PAD's settings: where it starts and ends, how it plays (reverse, once or while held, choke), clearing it. */
    PAD_PLAY,
    PLACE_PAD, MOVE_CLIP, TRIM_CLIP, SPLIT_CLIP, DUPLICATE_CLIP, DELETE_CLIP,
    TRACK_MUTE, CLIP_GAIN, SONG_PLAYBACK, SONG_SEEK, SONG_MONITOR_GAIN, TEMPO,
    ADD_DRUM, RECORD_VOICE, RECORD_SOURCE, RECORD_SYSTEM_SOURCE, SCRATCH, STOP_ALL,
    /** Recording what the PADs play into the song while it plays. */
    RECORD_HITS,
}
enum class ContinuousUnavailable { NOT_CONNECTED, BUSY, NO_SOURCE, EMPTY_PAD, NO_CLIP, NO_OUTPUT, NO_SONG, RECORDING }
enum class ContinuousStatus {
    LOADING, SAVING, SAVED, EXPORTING, EXPORTED, CANCELLED, FAILED, NO_OUTPUT, COPIED,
    IMPORT_FAILED, OPEN_FAILED, SAVE_FAILED, EXPORT_FAILED, OUTPUT_UNAVAILABLE, BUSY, SOURCE_STOP_FAILED, SPLIT_POSITION,
    /**
     * A voice take went to a BANK D PAD and onto the song; with BANK D full, onto the song only; to the PAD only when
     * the song refused it; it stopped at its length limit, or because the microphone went away.
     */
    VOICE_SAVED, VOICE_SAVED_SONG_ONLY, VOICE_SAVED_PAD_ONLY, VOICE_SAVED_TAKE_ONLY, VOICE_LIMIT, VOICE_INTERRUPTED,
    /** Nothing but silence was recorded; it ended before the song was heard; it could not be stored; no room is left. */
    VOICE_EMPTY, VOICE_TOO_SHORT, VOICE_NOT_SAVED, VOICE_NO_ROOM, PLACE_NO_ROOM, PLACE_FAILED,
    /** A song edit refused, as the song could no longer play: too many clips at once, too many or too long. */
    SONG_FULL,
    MIC_DENIED, MIC_UNAVAILABLE, INPUT_TIMEOUT,
    SOURCE_RECORDED, SOURCE_RECORDING_LIMIT, SOURCE_RECORDING_INTERRUPTED,
    SYSTEM_DENIED, SYSTEM_NO_DISPLAY, SYSTEM_UNAVAILABLE, SYSTEM_TIMEOUT, SYSTEM_EMPTY,
    /** Refused because a take is being recorded. */
    RECORDING_BUSY,
    RECORDING_ARM_TIMEOUT, RECORDING_CUE_CANCELLED,
    /**
     * A project file of the earlier app opened as a new document with its audio only: the first sound as the original
     * and the others on PADs; some beyond the sound limit, kept in the document only; all of them beyond it; or no
     * audio in it at all.
     */
    RESCUED, RESCUED_PARTLY, RESCUED_TOO_LONG, RESCUED_NOTHING,
    /**
     * What the PADs played went onto the song as one Undo; only part of it, as the song could not take the rest or a
     * PAD's sound could not be prepared; nothing was played; or all of it was already there, so the song is as it was.
     */
    HITS_PLACED, HITS_PARTLY, HITS_EMPTY, HITS_UNCHANGED, LOOP_NOT_SAVED, LOOP_INTERRUPTED,
}

@Immutable data class ContinuousSource(
    val id: String,
    val title: String,
    val frames: Long,
    val sampleRate: Int = 48_000,
    val peaks: List<Float> = emptyList(),
    val rangeStartFrame: Long = 0,
    val rangeEndFrame: Long = frames,
    val pitchSemitones: Float = 0f,
    val markers: List<Long> = emptyList(),
) {
    init { require(frames > 0 && sampleRate > 0 && rangeStartFrame >= 0 && rangeEndFrame > rangeStartFrame && rangeEndFrame <= frames) }
}

@Immutable data class ContinuousBank(val id: Int, val name: String = "", val color: Int = 0x4477aa, val role: String = "samples") {
    init { require(id in 0..7 && color in 0..0xffffff) }
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
    val reverse: Boolean = false,
    /** PADs sharing a nonzero group cut each other off; 0 is none. */
    val chokeGroup: Int = 0,
) { init { require(id in 0..127 && sourceRate > 0 && chokeGroup in 0..128) } }

/** What a scratch moves: the selected PAD's sound, or the original within its range. */
enum class ContinuousScratchTarget { PAD, ORIGINAL }
/** How far a drag moves the sound, in screen pixels as in the earlier app: fine, normal or wide. */
enum class ContinuousScratchSensitivity { FINE, NORMAL, WIDE }

/**
 * The open scratch panel: its target, whether each target can be scratched now, the sensitivity, the cut fader and
 * whether a hand holds the platter. Where the platter stands is read live from [ContinuousEditorReadout.scratchFraction].
 */
@Immutable data class ContinuousScratch(
    val target: ContinuousScratchTarget,
    val padAvailable: Boolean = false,
    val originalAvailable: Boolean = false,
    val sensitivity: ContinuousScratchSensitivity = ContinuousScratchSensitivity.NORMAL,
    val cut: Float = 1f,
    val holding: Boolean = false,
    val handMonitorGain: Float = 1f,
) { init { require(cut.isFinite() && cut in 0f..1f && handMonitorGain.isFinite() && handMonitorGain in 0f..1f) } }

/** A built-in drum kit the host can install; the name is the kit's own name in every language. */
@Immutable data class ContinuousDrumKit(val id: String, val name: String)

/** Asked before a kit replaces the user's own sounds on the drum BANK. */
@Immutable data class ContinuousKitQuestion(val kitId: String, val replacedSounds: Int) {
    init { require(replacedSounds in 1..16) }
}

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
    val lyrics: ContinuousLyricsState = ContinuousLyricsState(),
    val bankPadEditor: BankPadEditorState = BankPadEditorState(),
    val mixer: com.choplab.ui.mixer.MixerEditorState = com.choplab.ui.mixer.MixerEditorState(),
    val exportBits: Int = 24,
    val exportTail: Boolean = true,
    val stemProgress: com.choplab.core.StemExportProgress? = null,
    val recordingGuide: RecordingGuideState = RecordingGuideState(),
    val bankPadBlocked: BankPadEditProblem? = null,
    val stage: ContinuousStage = ContinuousStage.CAPTURE,
    val projectTitle: String = "",
    val documentRevision: Long = 0,
    val newProjectRevision: Long? = null,
    val autosaveFailed: Boolean = false,
    val pendingRecording: Boolean = false,
    val pendingRecordingApplied: Boolean = false,
    val pendingRecordingCanRecoverSource: Boolean = false,
    val assetWaveforms: Map<String, WaveformLoadState> = emptyMap(),
    val assetWaveformNames: Map<String, String> = emptyMap(),
    val recordingPunch: Boolean = false,
    val recordingInterruption: RecordingInterruption? = null,
    /** Before-start storage/document estimates; actual capture limits come from the input readout. */
    val voiceRecordingEstimateMillis: Long? = null,
    val systemRecordingEstimateMillis: Long? = null,
    /** Same original source object/identity in stages 1, 2 and 3; PAD selection cannot replace it. */
    val original: ContinuousSource? = null,
    val originalPlaying: Boolean = false,
    val vocalPreview: Boolean = false,
    /** A live chop pass is running: tapping a PAD of the CHOP stage cuts the original at that moment. */
    val liveChopping: Boolean = false,
    val liveChopTiming: LiveChopTimingState = LiveChopTimingState(),
    /** A voice take is being recorded while the song plays. */
    val recordingVoice: Boolean = false,
    val startingVoiceRecording: Boolean = false,
    /** What the PADs play is being recorded into the song while it plays. */
    val recordingHits: Boolean = false,
    /** The microphone is collecting a new original, independent of a song or output device. */
    val recordingSource: Boolean = false,
    val recordingSystemAudio: Boolean = false,
    val startingSourceRecording: Boolean = false,
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
    /** The song's exact tempo, which the grid follows; [bpm] is it rounded down for display. */
    val milliBpm: Int = bpm * 1000,
    /**
     * The song's swing: how far through its eighth an off sixteenth placed on the beat sounds, in permille (500 is
     * straight, 750 the most). It moves those clips and their grid lines alike.
     */
    val swingPermille: Int = 500,
    /** View state only: what placing and moving clips snap to. */
    val grid: ContinuousGrid = ContinuousGrid.BEAT,
    /** Session performance preference; captured phrases remain ordinary reversible audio placements. */
    val noteRepeat: ContinuousNoteRepeat = ContinuousNoteRepeat.OFF,
    val loopOverdubBars: Int = 0,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val capabilities: Set<ContinuousCapability> = emptySet(),
    val unavailable: Map<ContinuousCapability, ContinuousUnavailable> = emptyMap(),
    val status: ContinuousStatus? = null,
    /** Last host-confirmed output; no path or handle enters the saved document. */
    val completedOutputName: String? = null,
    val drumKits: List<ContinuousDrumKit> = emptyList(),
    /** The kit on the drum BANK when its PADs hold one kit's sounds. */
    val installedDrumKit: String? = null,
    val drumKitChooserOpen: Boolean = false,
    val drumKitQuestion: ContinuousKitQuestion? = null,
    /** The scratch panel while it is open. */
    val scratch: ContinuousScratch? = null,
    /** The selected PAD's settings panel is open. */
    val padPlayOpen: Boolean = false,
) {
    init {
        require(selectedBank in 0..7 && selectedPadId in 0..127)
        require(timelineDurationFrames > 0 && pixelsPerSecond.isFinite() && pixelsPerSecond in .01f..240f)
        require(paneFraction.isFinite() && paneFraction in 0.2f..0.8f)
        require(milliBpm in 40_000..240_000 && swingPermille in 500..750)
    }
    fun permits(capability: ContinuousCapability) = capability in capabilities
    val selectedPad: ContinuousPad? get() = pads.firstOrNull { it.id == selectedPadId }
    val selectedClip: ContinuousClip? get() = clips.firstOrNull { it.id == selectedClipId }
}

/**
 * Output health for the diagnostics card: formats, times and counts only, never a device name or identifier. Null
 * where the platform or the current state does not tell.
 */
@Immutable data class ContinuousDiagnostics(
    val outputAttached: Boolean,
    /** Samples go out as 32-bit float (true) or 16-bit (false) while attached. */
    val floatOutput: Boolean? = null,
    val sampleRate: Int = CONTINUOUS_TIMELINE_RATE,
    val blockFrames: Int = 256,
    val bufferFrames: Int? = null,
    /** Frames written but not yet played: the output's delay as far as the platform tells. */
    val pendingFrames: Long? = null,
    val underruns: Int? = null,
    /** Output lost or failing to open since the editor opened. */
    val outputLosses: Long = 0,
    val measuredBlocks: Int = 0,
    /** Time to produce one block as a share of its duration: 99th percentile and maximum over [measuredBlocks]. */
    val renderP99: Double? = null,
    val renderMax: Double? = null,
    /** Frames drawn since the editor opened, and those that took 1/30 s or longer. */
    val drawnFrames: Long? = null,
    val slowFrames: Long? = null,
    val pcm: ContinuousPcmReadout = ContinuousPcmReadout(),
)

/** Read only in source waveform/time or song timeline/transport subtrees, never whole-app ticks. */
/** A PAD played where song frame [timelineFrame] (48 kHz) was heard. */
/** Identity belongs to one physical press, even when a new pass starts at the same frame. */
class ContinuousHitGesture(val padId: Int, val songFrame: Long)

@Immutable data class ContinuousHit(val padId: Int, val timelineFrame: Long,
    val performed: Boolean = false, val releaseAfterFrames: Int? = null, val limitFrames: Int = 0, val stopAfterFrames: Int? = null,
    val repeatTicks: Int = 0)

@Immutable data class ContinuousEditorReadout(
    val originalFrame: Long = 0,
    val songFrame: Long = 0,
    /** Where the scratch platter stands within what it scratches, 0 to 1. */
    val scratchFraction: Float = 0f,
    /** Samples actually captured, not wall-clock time spent waiting for microphone permission. */
    val recordingMillis: Long = 0,
    val input: RecordingInputReadout = RecordingInputReadout(),
    val punchPhase: com.choplab.core.vocal.PunchPhase? = null,
    /** Independent original HAND position in native source frames; -1 while inactive. */
    val handSourceFrame: Double = -1.0,
    val countInBeatsRemaining: Int = 0,
    val liveChopGesture: ContinuousChopGesture? = null,
    val pcm: ContinuousPcmReadout = ContinuousPcmReadout(),
)

/** One physical press, captured before awaiting release; it cannot cross a pass or document revision. */
data class ContinuousChopGesture(val pass: Any, val revision: Long, val output: LiveChopOutput)

/** Typed requests. Hosts/Studio confirm every edit; UI drag previews are never document commits. */
sealed interface ContinuousEditorAction {
    data object RevealCompletedOutput : ContinuousEditorAction
    data class RecordingGuide(val action: RecordingGuideAction) : ContinuousEditorAction
    data class Lyrics(val action: LyricAction) : ContinuousEditorAction
    data object OpenVocalPunch : ContinuousEditorAction
    data object CloseVocalPunch : ContinuousEditorAction
    data object OpenVocalTakes : ContinuousEditorAction
    data object CloseVocalTakes : ContinuousEditorAction
    data object OpenFourStems : ContinuousEditorAction
    data object CloseFourStems : ContinuousEditorAction
    data object OpenVocalGuide : ContinuousEditorAction
    data object CloseVocalGuide : ContinuousEditorAction
    data object OpenLyricProposal : ContinuousEditorAction
    data object CloseLyricProposal : ContinuousEditorAction
    data object OpenVocalPractice : ContinuousEditorAction
    data object CloseVocalPractice : ContinuousEditorAction
    data object OpenStepPatterns : ContinuousEditorAction
    data object CloseStepPatterns : ContinuousEditorAction
    data object OpenVocalPitch : ContinuousEditorAction
    data class OpenBeatStretch(val target: com.choplab.core.model.StretchTarget) : ContinuousEditorAction
    data object CloseBeatStretch : ContinuousEditorAction
    data object CloseVocalPitch : ContinuousEditorAction
    data object OpenVocalCoach : ContinuousEditorAction
    data object CloseVocalCoach : ContinuousEditorAction
    data object OpenSourceAnalysis : ContinuousEditorAction
    data object CloseSourceAnalysis : ContinuousEditorAction
    data class BankPadEdit(val action: BankPadEditAction) : ContinuousEditorAction
    data class Navigate(val stage: ContinuousStage) : ContinuousEditorAction
    data object NewProject : ContinuousEditorAction
    data class ConfirmNewProject(val saveCurrent: Boolean, val revision: Long) : ContinuousEditorAction
    data object CancelNewProject : ContinuousEditorAction
    data object ImportAudio : ContinuousEditorAction
    /** A file explicitly selected by a platform drop/open event; uses the same import transaction. */
    data class ImportAudioFile(val location: com.choplab.core.Location) : ContinuousEditorAction
    data object ImportLibrary : ContinuousEditorAction
    data object OpenSpotifyMetadata : ContinuousEditorAction
    data object SeparateSource : ContinuousEditorAction
    data object ImportOnline : ContinuousEditorAction
    data object CloseOnline : ContinuousEditorAction
    data object RecordSource : ContinuousEditorAction
    data object RecordSystemSource : ContinuousEditorAction
    data object StopSourceRecording : ContinuousEditorAction
    data object DiscardSourceRecording : ContinuousEditorAction
    data object OpenProject : ContinuousEditorAction
    data class OpenProjectFile(val location: com.choplab.core.Location) : ContinuousEditorAction
    data object SaveProject : ContinuousEditorAction
    data class Mixer(val action: com.choplab.ui.mixer.MixerAction) : ContinuousEditorAction
    data class ExportBits(val bits: Int) : ContinuousEditorAction { init { require(bits == 16 || bits == 24) } }
    data class ExportTail(val include: Boolean) : ContinuousEditorAction
    data object ExportStems : ContinuousEditorAction
    data object ExportWav : ContinuousEditorAction
    data object Undo : ContinuousEditorAction
    data object Redo : ContinuousEditorAction
    data object StopAll : ContinuousEditorAction
    data object ReloadAudio : ContinuousEditorAction
    data object PlayOriginal : ContinuousEditorAction
    data object PlayOriginalFromStart : ContinuousEditorAction
    data object StopOriginal : ContinuousEditorAction
    data class SeekOriginal(val sourceFrame: Long) : ContinuousEditorAction
    /** Monitoring only; never alters PADs, arrangement, or exported audio. */
    data class SetOriginalMonitorGain(val gain: Float) : ContinuousEditorAction
    data class SetOriginalPitch(val semitones: Float) : ContinuousEditorAction
    data class SetSourceRange(val startFrame: Long, val endFrame: Long) : ContinuousEditorAction
    data object OpenLiveChopTiming : ContinuousEditorAction
    data object CloseLiveChopTiming : ContinuousEditorAction
    data class SetLiveChopCorrection(val route: LiveChopRoute, val correction: LiveChopCorrection) : ContinuousEditorAction
    data object BeginLiveChop : ContinuousEditorAction
    data object EndLiveChop : ContinuousEditorAction
    /** [originalFrame] is the original's position sampled when the PAD was pressed, in the source's own frames. */
    data class CapturePad(val padId: Int, val gesture: ContinuousChopGesture) : ContinuousEditorAction
    data object AutoChop : ContinuousEditorAction
    data object CloseAutoChop : ContinuousEditorAction
    data class AssignSourceSlice(val slice: Int, val padId: Int, val assetHash: String, val startFrame: Long, val endFrame: Long) : ContinuousEditorAction
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
    data object OpenPadPlay : ContinuousEditorAction
    data object ClosePadPlay : ContinuousEditorAction
    /** Moves where the PAD starts (or, with [end], ends) by [milliseconds], earlier when negative. */
    data class NudgePadBoundary(val padId: Int, val end: Boolean, val milliseconds: Int) : ContinuousEditorAction
    data class SetPadReverse(val padId: Int, val reverse: Boolean) : ContinuousEditorAction
    /** Once or while held; looping stays the loop button's, so LOOP is not one of these. */
    data class SetPadMode(val padId: Int, val mode: ContinuousPadMode) : ContinuousEditorAction
    /** 0 (none) to 4, as in the earlier app. */
    data class SetPadChoke(val padId: Int, val group: Int) : ContinuousEditorAction
    data class ClearPad(val padId: Int) : ContinuousEditorAction
    /** Explicit user placement only: original source is never placed by merely changing stages. */
    data class PlacePad(val padId: Int, val trackId: String?, val timelineFrame: Long) : ContinuousEditorAction
    /**
     * Places the PAD every [spacing] (a beat, half or quarter beat) through [bars] bars from the bar holding
     * [timelineFrame], on the beat, in place of the same sound on that track there. One Undo.
     */
    data class FillPad(val padId: Int, val trackId: String?, val timelineFrame: Long, val spacing: ContinuousGrid, val bars: Int) : ContinuousEditorAction
    data class SelectClip(val clipId: String?) : ContinuousEditorAction
    data class MoveClip(val clipId: String, val trackId: String, val timelineStartFrame: Long) : ContinuousEditorAction
    /** Moves the clip to the next grid line later (or earlier); on a free grid, by one second. */
    data class NudgeClip(val clipId: String, val forward: Boolean) : ContinuousEditorAction
    data class TrimClip(val clipId: String, val sourceStartFrame: Long, val sourceEndFrame: Long,
                        val timelineStartFrame: Long) : ContinuousEditorAction
    data class SplitClip(val clipId: String, val timelineFrame: Long) : ContinuousEditorAction
    data class DuplicateClip(val clipId: String) : ContinuousEditorAction
    /**
     * Repeats every clip starting in the [bars] bars from the bar holding [timelineFrame] [times] more times right after
     * them, when those bars are empty. One Undo.
     */
    data class RepeatBars(val timelineFrame: Long, val bars: Int, val times: Int) : ContinuousEditorAction
    data class DeleteClip(val clipId: String) : ContinuousEditorAction
    data class SetClipGain(val clipId: String, val gain: Float) : ContinuousEditorAction
    data class SetTrackMuted(val trackId: String, val muted: Boolean) : ContinuousEditorAction
    /** What placing and moving clips snap to; the document keeps no grid. */
    data class SetGrid(val grid: ContinuousGrid) : ContinuousEditorAction
    data class SetNoteRepeat(val rate: ContinuousNoteRepeat) : ContinuousEditorAction
    data class SetPixelsPerSecond(val value: Float) : ContinuousEditorAction
    data object FitTimeline : ContinuousEditorAction
    data class FitTimelineWidth(val widthDp: Float) : ContinuousEditorAction
    data class ResizePanes(val fraction: Float) : ContinuousEditorAction
    data object ResetPanes : ContinuousEditorAction
    data class SelectCompactPane(val pane: ContinuousPane) : ContinuousEditorAction
    data class SeekSong(val timelineFrame: Long) : ContinuousEditorAction
    data object PlaySong : ContinuousEditorAction
    data object PauseSong : ContinuousEditorAction
    data object StopSong : ContinuousEditorAction
    /** Playback monitoring only, separate from track/clip/export gains. */
    data class SetSongMonitorGain(val gain: Float) : ContinuousEditorAction
    /** The song's tempo, with its swing in permille (500..750); null keeps the swing. */
    data class SetTempoExact(val milliBpm: Int, val swingPermille: Int) : ContinuousEditorAction
    data class SetTempo(val bpm: Int, val swingPermille: Int? = null) : ContinuousEditorAction
    /** Opens the kit chooser; a kit fills the drum BANK only after [ChooseDrumKit]. */
    data object AddDrum : ContinuousEditorAction
    data class ChooseDrumKit(val kitId: String) : ContinuousEditorAction
    /** Answers the question about replacing the user's own sounds; it applies only to the sounds it counted. */
    data object ConfirmDrumKit : ContinuousEditorAction
    data object DismissDrumKit : ContinuousEditorAction
    /** Opens the microphone and plays the song; the take ends with [StopVoice] or when the song stops. */
    data object RecordVoice : ContinuousEditorAction
    data object StopVoice : ContinuousEditorAction
    data object DiscardVoice : ContinuousEditorAction
    data object DiscardHits : ContinuousEditorAction
    data class RetryWaveforms(val assetHash: String) : ContinuousEditorAction
    data object RecoverRecordingAsSource : ContinuousEditorAction
    data object RetryRecordingSave : ContinuousEditorAction
    data object DiscardPendingRecording : ContinuousEditorAction
    /**
     * Plays the song on from where it stands (from the top once it has ended) and records what the PADs play into it;
     * [StopHits], pausing or stopping the song, or its end puts what was played onto the song as one Undo.
     */
    data class RecordLoopOverdub(val bars: Int) : ContinuousEditorAction
    data object CancelLoopOverdub : ContinuousEditorAction
    data object RecordHits : ContinuousEditorAction
    data object StopHits : ContinuousEditorAction
    /**
     * PAD [padId] was pressed as the engine played song frame [songFrame], while recording what the PADs play. Sent as
     * the press begins, so a PAD still held when the pass ends is in it.
     */
    data class CaptureHit(val padId: Int, val songFrame: Long) : ContinuousEditorAction
    data class BeginHit(val gesture: ContinuousHitGesture) : ContinuousEditorAction
    data class EndHit(val gesture: ContinuousHitGesture, val cancelled: Boolean, val songFrame: Long? = null) : ContinuousEditorAction
    /** The press [CaptureHit] recorded turned out not to be one (a scroll, or a finger slid off the PAD): taken back. */
    data class DropHit(val padId: Int, val songFrame: Long) : ContinuousEditorAction
    /** The presenter's one edit for a recorded pass: each PAD where it was heard, on the grid. Not sent by the UI. */
    data class PlaceHits(val hits: List<ContinuousHit>) : ContinuousEditorAction
    /** Puts the diagnostics card's text, already in the user's language, on the clipboard. */
    data class CopyDiagnostics(val text: String) : ContinuousEditorAction
    /** Opens the scratch panel; the beat plays on underneath. */
    data object OpenScratch : ContinuousEditorAction
    data object CloseScratch : ContinuousEditorAction
    data class SetScratchTarget(val target: ContinuousScratchTarget) : ContinuousEditorAction
    data class SetScratchSensitivity(val sensitivity: ContinuousScratchSensitivity) : ContinuousEditorAction
    /** The independent cut fader: 1 lets the scratch through, 0 silences it. Handled at once, like a drag. */
    data class SetScratchCut(val gain: Float) : ContinuousEditorAction
    data class SetHandMonitorGain(val gain: Float) : ContinuousEditorAction
    /** A hand takes the platter: the sound stops under it and sounds again as it moves. */
    data object ScratchHold : ContinuousEditorAction
    /**
     * The held platter moved by [distancePx] screen pixels (positive forward), as the earlier app counted them; handled
     * at once, never queued behind other work.
     */
    data class ScratchDrag(val distancePx: Float) : ContinuousEditorAction
    /** The hand lets go: what the scratch paused plays on once, and nothing else starts. */
    data object ScratchLetGo : ContinuousEditorAction
    /** One short scratch back or forward, for screen readers. */
    data class ScratchNudge(val forward: Boolean) : ContinuousEditorAction
}
