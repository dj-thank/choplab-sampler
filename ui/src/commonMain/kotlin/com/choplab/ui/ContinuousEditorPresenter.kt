package com.choplab.ui

import com.choplab.core.*
import com.choplab.core.ai.*
import com.choplab.core.edit.Intent
import com.choplab.core.kits.DrumKits
import com.choplab.core.model.*
import com.choplab.engine.PlayMode
import com.choplab.engine.Tempo
import com.choplab.ui.ai.*
import com.choplab.ui.pattern.*
import com.choplab.ui.separation.*
import com.choplab.core.separation.*
import com.choplab.core.pattern.PatternVoiceRender
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.time.TimeSource

/** Platform dialogs, monitoring and waveform decoding. No filesystem paths enter UI/document state. */
interface ContinuousEditorPorts {
    val recordingCue: RecordingCuePort? get() = null
    val lyricFiles: LyricFiles? get() = null
    val lyricProposal: LyricProposalPort? get() = null
    val vocalGuide: VocalGuidePort? get() = null
    val fourStems: FourStemFactory? get() = null
    /** This host stores complete performed PAD voices for step/pattern arrangement placement. */
    val stepPatternsAvailable: Boolean get() = false
    val systemAudioCapture: SystemAudioCapture? get() = null
    val separationAvailable: Boolean get() = false
    suspend fun separateSource(source: Asset): Location? = null
    val onlineAvailable: Boolean get() = false
    suspend fun chooseOnline(): Location? = null
    val libraryAvailable: Boolean get() = false
    suspend fun chooseLibrary(): Location? = null
    /** Opens a metadata-only provider view. It cannot return or import an audio location. */
    val spotifyMetadataAvailable: Boolean get() = false
    suspend fun openSpotifyMetadata() {}
    suspend fun chooseAudio(): Location?
    suspend fun chooseOpen(): Location?
    suspend fun chooseSave(): Location?
    suspend fun chooseExport(frames: Long): ExportRequest?
    suspend fun peaks(asset: Asset): List<Float>
    fun readout(): ContinuousEditorReadout
    fun originalPlaying(): Boolean? = null
    fun playingPads(): Set<Int>? = null
    suspend fun setSongMonitorGain(gain: Float): Boolean
    val originalAvailable: Boolean get() = false
    fun cancelOriginalPreparation() {}
    suspend fun playOriginal(asset: Asset): Boolean = false
    suspend fun stopOriginal(): Boolean = false
    suspend fun resetOriginal(): Boolean = stopOriginal()
    suspend fun seekOriginal(frame: Long): Boolean = false
    suspend fun setOriginalMonitorGain(gain: Float): Boolean = false
    /** Listening level for the independent original HAND voice, separate from SOURCE and CUT. */
    suspend fun setHandMonitorGain(gain: Float): Boolean = false
    /** Whether [drumKit] can render and store the built-in kits. */
    val drumKitsAvailable: Boolean get() = false
    /** A built-in kit's 16 sounds in slot order, stored and verified; null when this host has none. */
    suspend fun drumKit(kitId: String): List<Asset>? = null
    /** Output health for the diagnostics card; null when this host measures nothing. */
    fun diagnostics(): ContinuousDiagnostics? = null
    /** Puts text on the system clipboard. */
    suspend fun copyText(text: String): Boolean = false
    /** Whether this host can record the microphone. */
    val voiceAvailable: Boolean get() = false
    /** Opens the microphone for a take of at most [maxSeconds], asking for permission first where the platform needs it. */
    suspend fun startVoice(maxSeconds: Int): VoiceStart = VoiceStart.UNAVAILABLE
    /** The song started now: what the microphone captured before this is the take's lead-in. */
    fun cueVoice() {}
    /** The running take reached its length limit and records nothing more. */
    fun voiceFull(): Boolean = false
    fun voiceRecordedMillis(): Long = 0
    /** The running take stopped by itself: the microphone went away or the take could not be written. */
    fun voiceInterrupted(): Boolean = false
    /** Ends the take and stores it as [name]; null when nothing was recorded. */
    suspend fun stopVoice(name: String): VoiceTake? = null
    /** Ends the take and drops it. */
    suspend fun discardVoice() {}
    /**
     * Takes an independent HAND copy from [from] within [start, end) (native frames). SOURCE keeps playing;
     * HAND sounds only while moved by [scratchOriginalTo]. Ending HAND never seeks or restarts SOURCE.
     */
    suspend fun scratchOriginalStart(asset: Asset, from: Long, start: Long, end: Long): Boolean = false
    suspend fun scratchOriginalTo(position: Double, durationFrames: Int): Boolean = false
    suspend fun scratchOriginalCut(gain: Float): Boolean = false
    suspend fun scratchOriginalEnd(): Boolean = false
    /** Whether this host renders a transformed PAD (pitch, reverse, tone) so it can be placed on the song. */
    val padRenderAvailable: Boolean get() = false
    /** Renders [pad] from [source] as it sounds from its PAD into a stored sound for the song; null when it cannot. */
    suspend fun renderPad(pad: Pad, source: Asset): Asset? = null
    /** Complete performed voice, including gain/pan and release. */
    suspend fun renderPerformance(pad: Pad, source: Asset, releaseAt: Int?, limitFrames: Int, stopAt: Int? = null): Asset? = null
}

/** How opening the microphone went: running, not allowed, no usable input, or no room left to store a take. */
enum class VoiceStart { STARTED, DENIED, UNAVAILABLE, NO_ROOM }

/**
 * How far behind the engine the user hears the original, which live chop subtracts from a tap: the earlier app's
 * fixed 60 ms until the output's measured latency is used.
 */
private const val LIVE_CHOP_LATENCY_SECONDS = .06

/** BANK D holds voice takes, as in the earlier app. */
private const val VOICE_BANK = 3
/** The longest take; the engine's PCM budget, shared with every other sound, usually allows less. */
private const val MAX_VOICE_SECONDS = 300
/** Kept free of the engine's PCM budget when sizing a take, so adding it never lands exactly on the limit. */
private const val VOICE_BUDGET_MARGIN_SECONDS = 2

/**
 * A take being recorded: the song position it started from, where the song ended then, and how far the output
 * played behind the engine, all in 48 kHz frames.
 */
private data class VoiceRecording(val songFrame: Long, val songEnd: Long, val outputDelayFrames: Long,
                                  val emptySong: Boolean = false, val cueFrame: Long = -1)

/**
 * A pass recording what the PADs play: the song's frame when it began and where the song ends, how late the output
 * played the song, each PAD where it was heard, and whether presses went past what one pass holds.
 */
private data class HitRecording(val from: Long, val songEnd: Long, val outputDelayFrames: Long, val emptySong: Boolean = false,
                                val cueFrame: Long = -1,
                                val played: List<ContinuousHit> = emptyList(), val overflow: Boolean = false,
                                val pending: Map<ContinuousHitGesture, ContinuousHit> = emptyMap())

/** At most this many PAD presses in one pass: as many clips as the song holds. */
private const val MAX_HITS = 1024

/** How often a held platter's moves go on: about every 12 ms, or once the engine took the last one when it is slower. */
private const val SCRATCH_TICK_MILLIS = 12L
/** One move covers at most two ticks, so a stall never turns into a long glide toward an old aim. */
private const val SCRATCH_GAP_FRAMES = 2 * SCRATCH_TICK_MILLIS * CONTINUOUS_TIMELINE_RATE / 1_000
/** The engine moves a scratch at most eight times normal speed; moves aim well inside that, and a faster one arrives late. */
private const val SCRATCH_SPEED_LIMIT = 8.0 * .9
/** A screen reader's nudge moves in this many steps, one a tick: a short movement rather than a jump. */
private const val SCRATCH_NUDGE_STEPS = 12

/** As in the earlier app, 60 × divisor screen pixels a second is normal speed: fine 12, normal 7, wide 4. */
private fun ContinuousScratchSensitivity.framesPerPixel(rate: Int): Double = rate / (60.0 * when (this) {
    ContinuousScratchSensitivity.FINE -> 12.0
    ContinuousScratchSensitivity.NORMAL -> 7.0
    ContinuousScratchSensitivity.WIDE -> 4.0
})

/** The open scratch panel. */
private data class ScratchView(val target: ContinuousScratchTarget, val sensitivity: ContinuousScratchSensitivity = ContinuousScratchSensitivity.NORMAL,
                               val cut: Float = 1f, val holding: Boolean = false)

/**
 * A hand on the platter, over [start, end) in the target's frames: 48 kHz frames for a PAD, the original's own frames
 * for the original, each [engineFramesPerFrame] engine frames. Only the pump moves it: [aim] is where the drags put the
 * sound, [sent] where the engine was last told to take it, [cut] the cut fader the engine took last.
 */
private class ScratchGrip(val target: ContinuousScratchTarget, val padId: Int, val start: Double, val end: Double,
                          val framesPerPixel: Double, val engineFramesPerFrame: Double, from: Double, val sourceHash: String? = null) {
    @Volatile var aim = from
    @Volatile var sent = from
    var cut = 1f
    var gain = 1f
    val fraction: Float get() = ((sent - start) / (end - start)).toFloat().coerceIn(0f, 1f)
}

private data class OriginalHandCursor(val hash: String, val start: Double, val end: Double, val frame: Double)

/** A question is bound to the drum BANK it counted; any change there asks again. */
private data class KitQuestion(val kitId: String, val bank: List<Pad>, val replaced: Int)

private data class EditorView(
    val stage: ContinuousStage = ContinuousStage.CAPTURE,
    val clip: String? = null,
    val track: String? = null,
    val pixelsPerSecond: Float = 24f,
    val grid: ContinuousGrid = ContinuousGrid.BEAT,
    val paneFraction: Float = .41f,
    val pane: ContinuousPane = ContinuousPane.PADS,
    val originalPlaying: Boolean = false,
    val vocalPreview: Boolean = false,
    val originalGain: Float = 1f,
    val songGain: Float = 1f,
    val handGain: Float = 1f,
    val status: ContinuousStatus? = null,
    val playingPads: Set<Int> = emptySet(),
    val kitChooser: Boolean = false,
    val kitQuestion: KitQuestion? = null,
    /** PADs chopped in the running live chop pass, in tap order; null when no pass runs. */
    val liveChop: List<Int>? = null,
    /** Live chop passes begun so far: a reading of the original taken before a pass began cannot end that pass. */
    val livePasses: Int = 0,
    /** The take being recorded; null when the microphone is off. */
    val voice: VoiceRecording? = null,
    val startingVoice: Boolean = false,
    val countInBars: Int = 0,
    /** What the PADs play is being recorded; null when no pass runs. */
    val hits: HitRecording? = null,
    val recordingSource: Boolean = false,
    val systemSource: Boolean = false,
    val startingSource: Boolean = false,
    /** The scratch panel while it is open. */
    val scratch: ScratchView? = null,
    /** The selected PAD's play settings panel is open. */
    val padPlay: Boolean = false,
)
private data class EditorInputs(val document: DocumentState, val selection: SelectionState,
                                val work: WorkState, val playing: Boolean, val attached: Boolean,
                                val metronome: Boolean = false, val countInBeats: Int = 0)
private data class EditorTransport(val playing: Boolean, val attached: Boolean, val metronome: Boolean, val countInBeats: Int)

/** Studio is the only project/Undo owner. This adapter owns ephemeral selection and display state. */
class ContinuousEditorPresenter(val studio: Studio, scope: CoroutineScope, private val ports: ContinuousEditorPorts) {
    private val owner = SupervisorJob(scope.coroutineContext[Job])
    private val jobs = CoroutineScope(scope.coroutineContext + owner)
    private val view = MutableStateFlow(EditorView())
    private val envelopes = MutableStateFlow<Map<String, List<Float>>>(emptyMap())
    private val refresh = MutableStateFlow(0L)
    val refreshKey = refresh.asStateFlow()
    private val serialized = Mutex()
    private var serial = 0L
    private val held = mutableSetOf<Int>()
    private val taps = mutableMapOf<Int, Job>()
    /** UI events in the order they were made; a Release can never overtake its Hold. */
    private val queue = Channel<ContinuousEditorAction>(Channel.UNLIMITED)
    /** Mode each PAD had before the loop toggle made it LOOP, restored when that loop ends. */
    private val loopModes = mutableMapOf<Int, PlayMode>()
    /** Why the action being dispatched was refused, when there is more to say than that it failed. Held under [serialized]. */
    private var refusal: ContinuousStatus? = null
    /** A finished take is being added: a stop pressed meanwhile must not cancel that edit. */
    @Volatile private var finishingTake = false
    @Volatile private var openingSource = false
    @Volatile private var sourceOpeningCancelled = false
    @Volatile private var voiceOpeningCancelled = false
    /**
     * Notices already received as the answer to this presenter's own requests. The studio also posts each on its notice
     * flow, collected on another coroutine, where the copy can arrive after a newer message; those copies are skipped.
     */
    private val answeredNotices = MutableStateFlow<List<Notice>>(emptyList())
    /** The hand on the scratch platter, if any, and the loop that passes its moves on. */
    @Volatile private var grip: ScratchGrip? = null
    private var pump: Job? = null
    /** Drags the pump has not passed on yet, in screen pixels: kept from the moment a hand takes the platter. */
    private val dragPixels = MutableStateFlow(0.0)
    /** Where the platter stood when let go, so the next hold of the same silent PAD continues from there. */
    private var lastPadScratch: Pair<Int, Double>? = null
    private var lastOriginalScratch: OriginalHandCursor? = null
    @Volatile private var scratchOpening = false
    @Volatile private var scratchOpeningCancelled = false
    @Volatile private var lastScratchFraction = 0f
    private val lyricEditor = ContinuousLyricsController(studio, ports.lyricFiles) { intent, revision -> send(Action.Edit(intent, revision)) }
    private val proposal = MutableStateFlow<LyricProposalController?>(null)
    val lyricProposal: StateFlow<LyricProposalController?> = proposal.asStateFlow()
    private val separation = MutableStateFlow<FourStemController?>(null)
    val fourStems: StateFlow<FourStemController?> = separation.asStateFlow()
    private val separationAvailability = combine(view, studio.work) { editor, work ->
        when (bankPadBlock(editor, work)) {
            BankPadEditProblem.RECORDING -> FourStemAvailability.RECORDING
            null -> FourStemAvailability.EDITABLE
            else -> FourStemAvailability.BUSY
        }
    }.stateIn(jobs, SharingStarted.Eagerly, FourStemAvailability.EDITABLE)
    private val vocal = MutableStateFlow<VocalGuideController?>(null)
    val vocalGuide: StateFlow<VocalGuideController?> = vocal.asStateFlow()
    private val vocalAvailability = combine(view, studio.work) { editor, work ->
        when (bankPadBlock(editor, work)) {
            BankPadEditProblem.RECORDING -> VocalGuideAvailability.RECORDING
            null -> VocalGuideAvailability.EDITABLE
            else -> VocalGuideAvailability.BUSY
        }
    }.stateIn(jobs, SharingStarted.Eagerly, VocalGuideAvailability.EDITABLE)
    private val patterns = MutableStateFlow<StepPatternController?>(null)
    val stepPatterns: StateFlow<StepPatternController?> = patterns.asStateFlow()
    private val bankPadEditor = BankPadEditController(studio, { bankPadBlock(view.value, studio.work.value) }) { intent, revision ->
        send(Action.Edit(intent, revision))
    }
    private val inputs = combine(studio.document, studio.selection, studio.work,
        studio.transport.map { EditorTransport(it.playing, it.outputAttached, it.metronomeEnabled, it.countInBeatsRemaining) }.distinctUntilChanged()) { d, s, w, t ->
            EditorInputs(d, s, w, t.playing, t.attached, t.metronome, t.countInBeats) }
    val state: StateFlow<ContinuousEditorState> = combine(inputs, view, envelopes, lyricEditor.view, bankPadEditor.view) { input, editor, peaks, lyrics, bankPad ->
        project(input, editor, peaks).copy(lyrics = lyrics.copy(lines = input.document.project.lyrics), bankPadEditor = bankPad)
    }
        .stateIn(jobs, SharingStarted.Eagerly, project(EditorInputs(studio.document.value, studio.selection.value,
            studio.work.value, false, false), view.value, envelopes.value))
    private val patternAvailability = combine(view, studio.work) { editor, work -> patternAvailability(editor, work) }
        .stateIn(jobs, SharingStarted.Eagerly, patternAvailability(view.value, studio.work.value))

    /** Runs the queued UI events one at a time; [close] waits for it, so nothing queued starts afterwards. */
    private val consumer: Job

    init {
        ports.vocalGuide?.preview?.let { preview -> jobs.launch {
            preview.state.collect { current ->
                view.update { it.copy(vocalPreview = current.ownsSource, originalPlaying = if (current.ownsSource) false else it.originalPlaying) }
                refresh.update { it + 1 }
            }
        } }
        jobs.launch {
            studio.document.map { it.project.assets }.distinctUntilChanged().collectLatest { assets ->
                val next = mutableMapOf<String, List<Float>>()
                for (asset in assets) {
                    ensureActive()
                    val values = envelopes.value[asset.hash] ?: try { ports.peaks(asset).also {
                        require(it.size <= 2048 && it.all(Float::isFinite))
                    }.toList() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { emptyList() }
                    next[asset.hash] = values
                }
                envelopes.value = next.toMap()
            }
        }
        // Subscribe before a host can dispatch its first Open. SharedFlow has no replay for late subscribers.
        jobs.launch(start = CoroutineStart.UNDISPATCHED) { studio.notices.collect { notice ->
            var answered = false
            answeredNotices.update { list ->
                val at = list.indexOfFirst { it === notice }
                answered = at >= 0
                if (at >= 0) list.filterIndexed { index, _ -> index != at } else list
            }
            if (answered) return@collect
            view.update { it.copy(status = when (notice) {
                is Notice.Completed -> when (notice.operation) {
                    Operation.SAVE -> ContinuousStatus.SAVED
                    Operation.EXPORT -> ContinuousStatus.EXPORTED
                    else -> null
                }
                is Notice.Failed, is Notice.Rejected -> ContinuousStatus.FAILED
                is Notice.Cancelled -> ContinuousStatus.CANCELLED
                is Notice.Rescued -> when (notice.unplaced) {
                    0 -> if (notice.audio == 0) ContinuousStatus.RESCUED_NOTHING else ContinuousStatus.RESCUED
                    notice.audio -> ContinuousStatus.RESCUED_TOO_LONG
                    else -> ContinuousStatus.RESCUED_PARTLY
                }
                else -> it.status
            }) }
        } }
        jobs.launch { while (isActive) {
            studio.dispatch(Action.RefreshTransport)
            val passes = view.value.livePasses
            ports.originalPlaying()?.takeIf { ports.vocalGuide?.preview?.state?.value?.ownsSource != true }?.let { playing -> view.update { v ->
                // A pass ends with the original, also when it reaches the end by itself.
                if (v.livePasses != passes) v else v.copy(originalPlaying = playing, liveChop = v.liveChop.takeIf { playing })
            } }
            ports.playingPads()?.let { pads -> view.update { it.copy(playingPads = pads) } }
            endLostScratch()
            endLiveChopPastRange(passes)
            endVoiceWithTheSong()
            endHitsWithTheSong()
            endSourceAtLimit()
            delay(if (studio.transport.value.countInBeatsRemaining > 0) 25 else 200)
        } }
        jobs.launch {
            studio.transport.map { it.outputAttached }.distinctUntilChanged().drop(1).collect { attached ->
                if (!attached) cancelFourStemPreparation()
            }
        }
        consumer = jobs.launch { for (action in queue) dispatch(action) }
    }

    fun readout(): ContinuousEditorReadout {
        val preview = ports.vocalGuide?.preview?.state?.value
        val clock = ports.readout().let { if (preview?.ownsSource == true) it.copy(originalFrame = preview.originalFrame) else it }
        val held = grip
        val fraction = if (held?.target == ContinuousScratchTarget.ORIGINAL && clock.handSourceFrame >= 0)
            ((clock.handSourceFrame - held.start) / (held.end - held.start)).toFloat().coerceIn(0f, 1f)
            else held?.fraction ?: lastScratchFraction
        return clock.copy(scratchFraction = fraction,
            recordingMillis = if (view.value.systemSource) ports.systemAudioCapture?.recordedMillis ?: 0 else ports.voiceRecordedMillis())
    }
    fun diagnostics(): ContinuousDiagnostics? = ports.diagnostics()
    /** A host interruption cancels offline separation without waiting for a native worker or the action lock. */
    fun cancelFourStemPreparation() { separation.value?.cancel() }
    fun onAction(action: ContinuousEditorAction) {
        if (sourcePreviewBlocked(action)) return
        // A platter and its cut fader move at pointer rate: each move only updates where they should be.
        if (action is ContinuousEditorAction.ScratchDrag) { dragScratch(action.distancePx); return }
        if (action is ContinuousEditorAction.SetScratchCut) { setScratchCut(action.gain); return }
        if (action is ContinuousEditorAction.SetHandMonitorGain) { setHandGain(action.gain); return }
        if (endsScratch(action)) cancelScratchOpening()
        // Stop must not wait behind an import, decode or preparation that is ahead of it in the queue.
        if (interrupts(action)) jobs.launch { interrupt(action) }
        queue.trySend(action)
    }

    private fun interrupts(action: ContinuousEditorAction) = action == ContinuousEditorAction.StopOriginal ||
        action == ContinuousEditorAction.StopSourceRecording || action == ContinuousEditorAction.DiscardSourceRecording ||
        action == ContinuousEditorAction.StopVoice || action == ContinuousEditorAction.StopHits ||
        action == ContinuousEditorAction.StopAll || action == ContinuousEditorAction.StopSong || action == ContinuousEditorAction.PauseSong
    private suspend fun interrupt(action: ContinuousEditorAction) {
        if (action == ContinuousEditorAction.StopAll || action == ContinuousEditorAction.StopOriginal) {
            cancelFourStemPreparation()
            vocal.value?.cancel(); ports.vocalGuide?.preview?.requestStop()
        }
        if (action != ContinuousEditorAction.StopOriginal) voiceOpeningCancelled = true
        if (action == ContinuousEditorAction.StopAll || action == ContinuousEditorAction.StopSourceRecording ||
            action == ContinuousEditorAction.DiscardSourceRecording) cancelSourceOpening()
        if (action == ContinuousEditorAction.StopOriginal || action == ContinuousEditorAction.StopAll) ports.cancelOriginalPreparation()
        // Nothing else runs while a take is added, and cancelling that edit would lose the take.
        if (action != ContinuousEditorAction.StopOriginal && !finishingTake) studio.dispatch(Action.CancelWork)
    }

    suspend fun dispatch(action: ContinuousEditorAction): Boolean {
        if (action in listOf(ContinuousEditorAction.RecordVoice, ContinuousEditorAction.RecordHits,
            ContinuousEditorAction.RecordSource, ContinuousEditorAction.RecordSystemSource)) {
            vocal.value?.cancel(TtsProblem.RECORDING)
            separation.value?.cancel(SeparationProblem.RECORDING)
        }
        // The controller can be awaiting a selected-PAD action; cancel outside our serialized edit lock.
        if (action == ContinuousEditorAction.StopAll) patterns.value?.dispatch(PatternAction.Cancel)
        if (endsScratch(action)) cancelScratchOpening()
        // Cancel preparation before waiting for a UI edit; Stop cannot queue behind decoding.
        if (interrupts(action)) interrupt(action)
        return serialized.withLock {
        try {
            // A stop that finds no take or pass (the song already ended it) leaves its message in place, and so does
            // letting go of a PAD, which a pass ending under a held finger sends.
            if ((action != ContinuousEditorAction.StopVoice || view.value.voice != null) &&
                (action != ContinuousEditorAction.StopHits || view.value.hits != null) && action !is ContinuousEditorAction.ReleasePad && action !is ContinuousEditorAction.EndHit &&
                ((action !is ContinuousEditorAction.CaptureHit && action !is ContinuousEditorAction.DropHit) || view.value.hits != null) &&
                (action != ContinuousEditorAction.StopSourceRecording || view.value.recordingSource)) view.update { it.copy(status = null) }
            refusal = null
            val project = studio.document.value.project
            val preparingRecording = action in listOf(ContinuousEditorAction.RecordVoice, ContinuousEditorAction.RecordHits,
                ContinuousEditorAction.RecordSource, ContinuousEditorAction.RecordSystemSource)
            val previewRestored = !preparingRecording || ports.vocalGuide?.preview?.stop() !is TtsResult.Failure
            // Like the earlier app, a take in progress allows playing along and stopping, never document changes.
            val accepted = if (!previewRestored || sourcePreviewBlocked(action)) false else if (((view.value.voice != null || view.value.hits != null) && !allowedWhileRecording(action)) ||
                (view.value.recordingSource && !allowedWhileCollecting(action))) {
                refusal = ContinuousStatus.RECORDING_BUSY; false
            } else when (action) {
                is ContinuousEditorAction.RecordingGuide -> {
                    if (studio.work.value.jobId != null || studio.work.value.preparationId != null) false
                    else when (val guide = action.action) {
                        is RecordingGuideAction.Metronome -> send(Action.SetMetronome(guide.enabled))
                        is RecordingGuideAction.CountInBars -> { view.update { it.copy(countInBars = guide.bars) }; true }
                    }
                }
                is ContinuousEditorAction.BankPadEdit -> bankPadEditor.dispatch(action.action)
                is ContinuousEditorAction.Lyrics -> if (action.action != LyricAction.Close &&
                    (studio.work.value.jobId != null || studio.work.value.preparationId != null)) false else lyricEditor.dispatch(action.action)
                ContinuousEditorAction.OpenFourStems -> openFourStems()
                ContinuousEditorAction.CloseFourStems -> closeFourStems()
                ContinuousEditorAction.OpenVocalGuide -> openVocalGuide()
                ContinuousEditorAction.CloseVocalGuide -> { closeVocalGuide(); true }
                ContinuousEditorAction.OpenLyricProposal -> openLyricProposal()
                ContinuousEditorAction.CloseLyricProposal -> { closeLyricProposal(); true }
                ContinuousEditorAction.OpenStepPatterns -> openStepPatterns()
                ContinuousEditorAction.CloseStepPatterns -> { closeStepPatterns(); true }
                is ContinuousEditorAction.Navigate -> {
                    if (action.stage != view.value.stage && !closeFourStems()) return@withLock false
                    if (action.stage != view.value.stage) { bankPadEditor.dispatch(BankPadEditAction.Cancel); closeLyricProposal(); closeStepPatterns(); closeVocalGuide() }
                    releaseHeld()
                    if (action.stage != ContinuousStage.BEAT && view.value.scratch != null) { letGoScratch(); view.update { it.copy(scratch = null) } }
                    // The take belongs to the BEAT stage, where its stop button is: leaving it ends the take.
                    if (view.value.voice != null && action.stage != view.value.stage) { pauseSong(); finishVoice() }
                    if (view.value.hits != null && action.stage != view.value.stage) { pauseSong(); finishHits() }
                    if (view.value.recordingSource && action.stage != view.value.stage) finishSource()
                    view.update { it.copy(stage = action.stage, liveChop = it.liveChop.takeIf { action.stage == ContinuousStage.CHOP }) }; true
                }
                ContinuousEditorAction.SeparateSource -> project.source?.let { source ->
                    releaseHeld(); stopOriginal()
                    ports.separateSource(project.asset(source.assetHash))?.let { send(Action.Import(it)) } ?: cancelled()
                } ?: false
                ContinuousEditorAction.ImportOnline -> ports.chooseOnline()?.let { releaseHeld(); stopOriginal(); send(Action.Import(it)) } ?: cancelled()
                ContinuousEditorAction.ImportLibrary -> ports.chooseLibrary()?.let { releaseHeld(); stopOriginal(); send(Action.Import(it)) } ?: cancelled()
                ContinuousEditorAction.OpenSpotifyMetadata -> {
                    if (!ports.spotifyMetadataAvailable || studio.work.value.jobId != null || studio.work.value.preparationId != null) false
                    else { ports.openSpotifyMetadata(); true }
                }
                ContinuousEditorAction.ImportAudio -> ports.chooseAudio()?.let { releaseHeld(); stopOriginal(); send(Action.Import(it)) } ?: cancelled()
                ContinuousEditorAction.RecordSource -> startSource(project)
                ContinuousEditorAction.RecordSystemSource -> startSource(project, system = true)
                ContinuousEditorAction.StopSourceRecording -> finishSource()
                ContinuousEditorAction.DiscardSourceRecording -> {
                    if (view.value.recordingSource) {
                        try { discardSource() } finally { view.update { it.copy(recordingSource = false, systemSource = false) } }
                    }
                    view.update { it.copy(status = ContinuousStatus.CANCELLED) }
                    true
                }
                ContinuousEditorAction.OpenProject -> ports.chooseOpen()?.let { releaseHeld(); stopOriginal(); loopModes.clear(); send(Action.Open(it)) } ?: cancelled()
                ContinuousEditorAction.SaveProject -> ports.chooseSave()?.let { send(Action.Save(it)) } ?: cancelled()
                ContinuousEditorAction.ExportWav -> ports.chooseExport(songFrames(project))?.let {
                    send(Action.Export(it, PlaybackTarget.Arrangement()))
                } ?: cancelled()
                // Undo and Redo change what this pass has cut: as in the earlier app, they stop a running pass.
                ContinuousEditorAction.Undo -> { endLiveChop(); send(Action.Undo) }
                ContinuousEditorAction.Redo -> { endLiveChop(); send(Action.Redo) }
                ContinuousEditorAction.StopAll -> {
                    val restored = ports.vocalGuide?.preview?.stop() !is TtsResult.Failure
                    // Everything stops first, a scratch with it, so letting go afterwards plays nothing on.
                    val hitEnd = snapshotHitEnd()
                    releaseHeld(); val stopped = send(Action.Stop)
                    if (ports.originalAvailable) ports.stopOriginal()
                    letGoScratch()
                    view.update { it.copy(originalPlaying = false, playingPads = emptySet(), liveChop = null) }
                    val kept = finishVoice() && finishHits(hitEnd, stopVoices = true) && finishSource()
                    restored && stopped && kept
                }
                ContinuousEditorAction.ReloadAudio -> {
                    if (studio.work.value.jobId != null || studio.work.value.preparationId != null || finishingTake) false
                    else {
                        val sourceFrame = ports.readout().originalFrame
                        releaseHeld()
                        val stopped = send(Action.Stop)
                        letGoScratch()
                        val originalStopped = !ports.originalAvailable || ports.resetOriginal()
                        view.update { it.copy(originalPlaying = false, playingPads = emptySet(), liveChop = null) }
                        // Preparing the same target replaces failed PCM without an edit, revision or Undo entry.
                        stopped && originalStopped && send(Action.SelectPlaybackTarget(studio.selection.value.playbackTarget)) &&
                            (project.source == null || !ports.originalAvailable ||
                                ports.seekOriginal(sourceFrame.coerceIn(0, project.asset(project.source!!.assetHash).frames)))
                    }
                }
                ContinuousEditorAction.PlayOriginal -> project.source?.let { source ->
                    ports.playOriginal(project.asset(source.assetHash)).also { ok -> if (ok) view.update { it.copy(originalPlaying = true) } }
                } ?: false
                ContinuousEditorAction.StopOriginal -> (ports.vocalGuide?.preview?.stop() !is TtsResult.Failure && ports.stopOriginal()).also { ok ->
                    if (ok && grip?.target == ContinuousScratchTarget.ORIGINAL) letGoScratch()
                    view.update { it.copy(originalPlaying = if (ok) false else it.originalPlaying, liveChop = null) }
                }
                ContinuousEditorAction.BeginLiveChop -> project.source?.let { source ->
                    releaseHeld()
                    // Like the earlier app, a pass plays the original from the top of the range.
                    val started = ports.seekOriginal(source.range.start) && ports.playOriginal(project.asset(source.assetHash))
                    if (started) view.update { it.copy(originalPlaying = true, liveChop = emptyList(), livePasses = it.livePasses + 1) }
                    started
                } ?: false
                ContinuousEditorAction.EndLiveChop -> endLiveChop()
                is ContinuousEditorAction.CapturePad -> {
                    val pass = view.value.liveChop
                    val source = project.source
                    // A tap still queued when its pass ended (the original reached the end meanwhile) cuts nothing.
                    if (pass == null || source == null) true else {
                        // The PAD was pressed on what was heard, which the output plays this far behind the engine
                        // (the earlier app's 60 ms), in source frames at the original's key.
                        val rate = 2.0.pow(source.pitchSemitones / 12.0)
                        val heard = action.originalFrame - (LIVE_CHOP_LATENCY_SECONDS * project.asset(source.assetHash).sampleRate * rate).roundToLong()
                        // Heard after the range ends (the pass ends there): there is nothing left to cut.
                        if (heard >= source.range.end) true else {
                            val chopped = edit(Intent.LiveChop(action.padId, heard, pass.frozen()))
                            if (chopped) {
                                view.update { it.copy(liveChop = it.liveChop?.let { cut -> cut - action.padId + action.padId }) }
                                send(Action.SelectPad(action.padId))
                            }
                            chopped
                        }
                    }
                }
                is ContinuousEditorAction.SeekOriginal -> ports.seekOriginal(action.sourceFrame)
                is ContinuousEditorAction.SetOriginalMonitorGain -> {
                    requireGain(action.gain); ports.setOriginalMonitorGain(action.gain).also { ok -> if (ok) view.update { it.copy(originalGain = action.gain) } }
                }
                is ContinuousEditorAction.SetSongMonitorGain -> {
                    requireGain(action.gain); ports.setSongMonitorGain(action.gain).also { ok -> if (ok) view.update { it.copy(songGain = action.gain) } }
                }
                is ContinuousEditorAction.SetSourceRange -> edit(Intent.SetSourceRange(FrameRange(action.startFrame, action.endFrame)))
                // The song key is part of the document (saved, one Undo per step); the backend plays the original at it.
                is ContinuousEditorAction.SetOriginalPitch -> edit(Intent.SetSourcePitch(action.semitones.toDouble()))
                ContinuousEditorAction.AutoChop -> edit(Intent.EqualChop(16))
                is ContinuousEditorAction.AssignSourceRange -> project.source?.let {
                    edit(Intent.AssignRange(it.assetHash, it.range, action.padId))
                } ?: false
                is ContinuousEditorAction.SelectBank -> { require(action.bankId in 0..7); releaseHeld(); send(Action.SelectPad(action.bankId * 16)) }
                is ContinuousEditorAction.SelectPad -> { releaseHeld(); send(Action.SelectPad(action.padId)) }
                is ContinuousEditorAction.TapPad -> {
                    taps.remove(action.padId)?.cancel()
                    if (!send(Action.Trigger(action.padId))) false else {
                        if (project.pads[action.padId].mode == PlayMode.GATE) taps[action.padId] = jobs.launch {
                            delay(120); serialized.withLock { releaseRecordedPad(action.padId); send(Action.Release(action.padId)); taps.remove(action.padId) }
                        }
                        true
                    }
                }
                is ContinuousEditorAction.HoldPad -> send(Action.Trigger(action.padId)).also { if (it) held += action.padId }
                is ContinuousEditorAction.ReleasePad -> {
                    held -= action.padId
                    releaseRecordedPad(action.padId)
                    send(Action.Release(action.padId))
                }
                is ContinuousEditorAction.TogglePadLoop -> {
                    val pad = project.pads[action.padId]
                    val playing = ports.playingPads() ?: view.value.playingPads
                    val turnOn = pad.mode != PlayMode.LOOP || pad.id !in playing
                    // One loop sounds at a time. A mode this toggle changed returns exactly (GATE stays
                    // GATE); a LOOP the document already had keeps its mode and is only released.
                    val recorded = loopModes.filterKeys { project.pads[it].mode == PlayMode.LOOP }
                    val remaining = recorded.toMutableMap()
                    val replacements = mutableListOf<Pad>()
                    recorded.forEach { (id, mode) -> if (id != pad.id) { replacements += project.pads[id].copy(mode = mode); remaining -= id } }
                    if (turnOn) {
                        if (pad.mode != PlayMode.LOOP) { remaining[pad.id] = pad.mode; replacements += pad.copy(mode = PlayMode.LOOP) }
                    } else remaining.remove(pad.id)?.let { replacements += pad.copy(mode = it) }
                    val documentLoops = playing.filter { it != pad.id && it !in recorded && project.pads[it].mode == PlayMode.LOOP }
                    if (replacements.isNotEmpty() && !edit(Intent.ApplyKit(frozenListOf(), replacements.frozen()))) false
                    else {
                        loopModes.clear(); loopModes.putAll(remaining)
                        documentLoops.forEach { send(Action.Release(it)) }
                        (if (turnOn) send(Action.Trigger(pad.id)) else send(Action.Release(pad.id))).also { ok ->
                            if (ok) view.update { it.copy(playingPads = if (turnOn) setOf(pad.id) else emptySet()) }
                        }
                    }
                }
                is ContinuousEditorAction.SetPadPitch -> edit(Intent.SetPad(project.pads[action.padId].copy(pitchSemitones = action.semitones.toDouble())))
                is ContinuousEditorAction.SetPadGain -> edit(Intent.SetPad(project.pads[action.padId].copy(gain = action.gain)))
                is ContinuousEditorAction.SetPadTone -> edit(Intent.SetPad(project.pads[action.padId].copy(tone = action.tone)))
                ContinuousEditorAction.OpenPadPlay -> (project.pads[studio.selection.value.padId].assetHash != null).also { ok ->
                    if (ok) view.update { it.copy(padPlay = true) }
                }
                ContinuousEditorAction.ClosePadPlay -> { view.update { it.copy(padPlay = false) }; true }
                // Within its sound and never past the other boundary. Nudges of one boundary in a row are one Undo
                // step, as the earlier app's trim dials were; at a limit a nudge changes nothing and is not a failure.
                is ContinuousEditorAction.NudgePadBoundary -> {
                    require(action.milliseconds != 0 && action.milliseconds in -1000..1000)
                    project.pads[action.padId].takeIf { it.assetHash != null }?.let { pad ->
                        val frames = project.asset(requireNotNull(pad.assetHash)).let { it.frames to it.sampleRate }
                        val range = requireNotNull(pad.range)
                        val delta = action.milliseconds.toLong() * frames.second / 1000
                        val moved = if (action.end) FrameRange(range.start, (range.end + delta).coerceIn(range.start + 1, frames.first))
                            else FrameRange((range.start + delta).coerceIn(0, range.end - 1), range.end)
                        moved == range || edit(Intent.SetPad(pad.copy(range = moved), if (action.end) "trim-end" else "trim-start"))
                    } ?: false
                }
                // A changed PAD stops sounding (Studio stops it), as with its other settings; only a PAD with a sound changes.
                is ContinuousEditorAction.SetPadReverse -> project.pads[action.padId].takeIf { it.assetHash != null }
                    ?.let { edit(Intent.SetPad(it.copy(reverse = action.reverse))) } ?: false
                // Choosing for a PAD the loop button made loop ends that loop. The record of its earlier mode stays: it
                // applies only while the PAD is LOOP, so again after an Undo of the choice, as the loop button left it.
                is ContinuousEditorAction.SetPadMode -> {
                    require(action.mode != ContinuousPadMode.LOOP) { "Looping is the loop button's" }
                    project.pads[action.padId].takeIf { it.assetHash != null }
                        ?.let { edit(Intent.SetPad(it.copy(mode = PlayMode.valueOf(action.mode.name)))) } ?: false
                }
                is ContinuousEditorAction.SetPadChoke -> {
                    require(action.group in 0..4)
                    project.pads[action.padId].takeIf { it.assetHash != null }
                        ?.let { edit(Intent.SetPad(it.copy(chokeGroup = action.group))) } ?: false
                }
                // Studio stops the PAD. A loop the loop button started stays on record, so an Undo brings it back as it was.
                is ContinuousEditorAction.ClearPad -> project.pads[action.padId].takeIf { it.assetHash != null }?.let { pad ->
                    edit(Intent.ClearPad(pad.id)).also { cleared ->
                        if (cleared) view.update { it.copy(padPlay = false, playingPads = it.playingPads - pad.id) }
                    }
                } ?: false
                is ContinuousEditorAction.PlacePad -> placePad(project, action.padId, action)
                is ContinuousEditorAction.FillPad -> placePad(project, action.padId, action)
                is ContinuousEditorAction.MoveClip, is ContinuousEditorAction.NudgeClip, is ContinuousEditorAction.TrimClip,
                is ContinuousEditorAction.SplitClip, is ContinuousEditorAction.DuplicateClip, is ContinuousEditorAction.RepeatBars,
                is ContinuousEditorAction.DeleteClip,
                is ContinuousEditorAction.SetClipGain, is ContinuousEditorAction.SetTrackMuted -> {
                    val intent = ContinuousClipEdits.intent(project, action, ::freshId, grid = view.value.grid)
                    edit(intent)
                }
                is ContinuousEditorAction.SelectClip -> {
                    require(action.clipId == null || project.clips.any { it.id == action.clipId })
                    view.update { it.copy(clip = action.clipId, track = project.clips.firstOrNull { c -> c.id == action.clipId }?.trackId) }; true
                }
                is ContinuousEditorAction.SetGrid -> { view.update { it.copy(grid = action.grid) }; true }
                is ContinuousEditorAction.SetPixelsPerSecond -> { require(action.value.isFinite()); view.update { it.copy(pixelsPerSecond = action.value.coerceIn(4f, 240f)) }; true }
                ContinuousEditorAction.FitTimeline -> { view.update { it.copy(pixelsPerSecond = (720f / (songFrames(project) / 48_000f)).coerceIn(4f, 240f)) }; true }
                is ContinuousEditorAction.ResizePanes -> { require(action.fraction.isFinite()); view.update { it.copy(paneFraction = action.fraction.coerceIn(.2f, .8f)) }; true }
                ContinuousEditorAction.ResetPanes -> { view.update { it.copy(paneFraction = .41f) }; true }
                is ContinuousEditorAction.SelectCompactPane -> { releaseHeld(); view.update { it.copy(pane = action.pane) }; true }
                is ContinuousEditorAction.SeekSong -> selectArrangement() && send(Action.Seek(action.timelineFrame))
                ContinuousEditorAction.PlaySong -> selectArrangement() && run {
                    send(Action.RefreshTransport)
                    // The engine parks at the song end, where Resume is accepted but plays nothing.
                    (!songEnded(project) || send(Action.Seek(0))) && send(Action.Resume)
                }
                ContinuousEditorAction.PauseSong -> send(Action.Pause).also { finishVoice(); finishHits() }
                ContinuousEditorAction.StopSong -> {
                    val hitEnd = snapshotHitEnd()
                    send(Action.Stop).also { ok -> if (ok) view.update { it.copy(playingPads = emptySet()) }; finishVoice(); finishHits(hitEnd, stopVoices = true) }
                }
                is ContinuousEditorAction.SetTempo -> edit(Intent.SetTempo(Tempo(action.bpm * 1000, action.swingPermille ?: project.tempo.swingPermille)))
                ContinuousEditorAction.AddDrum -> ports.drumKitsAvailable.also { if (it) view.update { v -> v.copy(kitChooser = true, kitQuestion = null) } }
                is ContinuousEditorAction.ChooseDrumKit -> ports.drumKitsAvailable && chooseKit(DrumKits.kit(action.kitId).id, project)
                ContinuousEditorAction.ConfirmDrumKit -> view.value.kitQuestion?.let { question ->
                    if (drumBank(project) == question.bank) installKit(question.kitId) else chooseKit(question.kitId, project)
                } ?: false
                ContinuousEditorAction.DismissDrumKit -> { view.update { it.copy(kitChooser = false, kitQuestion = null) }; true }
                is ContinuousEditorAction.CopyDiagnostics ->
                    ports.copyText(action.text).also { copied -> if (copied) view.update { it.copy(status = ContinuousStatus.COPIED) } }
                ContinuousEditorAction.RecordVoice -> startVoice(project)
                ContinuousEditorAction.RecordHits -> startHits(project)
                ContinuousEditorAction.StopHits -> { if (view.value.hits != null) pauseSong(); finishHits() }
                is ContinuousEditorAction.BeginHit -> view.value.hits?.let { recording ->
                    val press = action.gesture
                    when {
                        recording.cueFrame >= 0 && !recordingCueHasStarted(recording.cueFrame) -> true
                        project.pads.getOrNull(press.padId)?.assetHash == null -> false
                        press.songFrame !in recording.from until recording.songEnd -> true
                        recording.played.size + recording.pending.size >= MAX_HITS -> {
                            view.update { it.copy(hits = it.hits?.copy(overflow = true)) }; true
                        }
                        else -> {
                            val heard = ContinuousHit(press.padId, (press.songFrame - recording.outputDelayFrames).coerceAtLeast(0),
                                performed = true, limitFrames = (recording.songEnd - press.songFrame).coerceAtMost(com.choplab.engine.PadRender.MAX_FRAMES.toLong()).toInt())
                            val group = project.pads[press.padId].chokeGroup
                            fun choked(hit: ContinuousHit): ContinuousHit =
                                if (group != 0 && hit.performed && project.pads[hit.padId].chokeGroup == group)
                                    releaseHit(hit, (heard.timelineFrame - hit.timelineFrame).coerceAtLeast(0).coerceAtMost(hit.limitFrames.toLong()).toInt())
                                else hit
                            view.update { it.copy(hits = recording.copy(
                                played = recording.played.map(::choked),
                                pending = recording.pending.mapValues { choked(it.value) } + (press to heard))) }; true
                        }
                    }
                } ?: true
                is ContinuousEditorAction.EndHit -> {
                    val recording = view.value.hits
                    val heard = recording?.pending?.get(action.gesture)
                    if (recording != null && heard != null && (action.cancelled || project.pads[heard.padId].mode != PlayMode.LOOP)) {
                        val end = action.songFrame ?: ports.readout().songFrame
                        val heldFrames = (end - action.gesture.songFrame).coerceIn(0L, heard.limitFrames.toLong()).toInt()
                        val released = if (project.pads[heard.padId].mode == PlayMode.ONE_SHOT) heard
                            else releaseHit(heard, heldFrames)
                        view.update { it.copy(hits = recording.copy(pending = recording.pending - action.gesture,
                            played = if (action.cancelled) recording.played else recording.played + released)) }
                        // A rejected scroll gesture must not leave a looping voice outside the pass's ownership.
                        if (action.cancelled && project.pads[heard.padId].mode == PlayMode.LOOP) send(Action.Release(heard.padId))
                    }
                    true
                }
                is ContinuousEditorAction.CaptureHit -> view.value.hits?.let { recording ->
                    when {
                        recording.cueFrame >= 0 && !recordingCueHasStarted(recording.cueFrame) -> true
                        project.pads.getOrNull(action.padId)?.assetHash == null -> false
                        // Before the pass began, or once the song has ended (the engine stands at its end until the pass
                        // ends): not played to the song.
                        action.songFrame !in recording.from until recording.songEnd -> true
                        recording.played.size >= MAX_HITS -> { view.update { v -> v.copy(hits = v.hits?.copy(overflow = true)) }; true }
                        else -> {
                            // Pressed on what was heard, which the output played this far behind the engine.
                            val heard = ContinuousHit(action.padId, (action.songFrame - recording.outputDelayFrames).coerceAtLeast(0))
                            view.update { v -> v.copy(hits = v.hits?.let { it.copy(played = it.played + heard) }) }
                            true
                        }
                    }
                } ?: true // A press still queued when its pass ended records nothing.
                is ContinuousEditorAction.DropHit -> {
                    view.update { v -> v.copy(hits = v.hits?.let { recording ->
                        // The same hit the press recorded, heard where CaptureHit put it; the latest such, if any.
                        val heard = ContinuousHit(action.padId, (action.songFrame - recording.outputDelayFrames).coerceAtLeast(0))
                        val at = recording.played.lastIndexOf(heard)
                        if (at < 0) recording else recording.copy(played = recording.played.filterIndexed { index, _ -> index != at })
                    }) }
                    true
                }
                // Only a pass puts what was played onto the song, from what it recorded.
                is ContinuousEditorAction.PlaceHits -> false
                ContinuousEditorAction.StopVoice -> { if (view.value.voice != null) pauseSong(); finishVoice() }
                ContinuousEditorAction.OpenScratch -> openScratch(project)
                ContinuousEditorAction.CloseScratch -> { letGoScratch(); view.update { it.copy(scratch = null) }; true }
                is ContinuousEditorAction.SetScratchTarget -> view.value.scratch?.let { sheet ->
                    val available = if (action.target == ContinuousScratchTarget.PAD) project.pads[studio.selection.value.padId].assetHash != null
                        else project.source != null && ports.originalAvailable
                    (grip == null && available).also { ok -> if (ok) {
                        lastScratchFraction = restingScratchFraction(project, action.target)
                        view.update { it.copy(scratch = sheet.copy(target = action.target)) }
                    } }
                } ?: false
                is ContinuousEditorAction.SetScratchSensitivity -> view.value.scratch?.let { sheet ->
                    view.update { it.copy(scratch = sheet.copy(sensitivity = action.sensitivity)) }; true
                } ?: false
                is ContinuousEditorAction.SetScratchCut -> setScratchCut(action.gain)
                is ContinuousEditorAction.SetHandMonitorGain -> setHandGain(action.gain)
                ContinuousEditorAction.ScratchHold -> holdScratch(project)
                is ContinuousEditorAction.ScratchDrag -> { dragScratch(action.distancePx); true }
                ContinuousEditorAction.ScratchLetGo -> letGoScratch()
                is ContinuousEditorAction.ScratchNudge -> nudgeScratch(project, action.forward)
            }
            if (!accepted) {
                val reason = refusal
                view.update { it.copy(status = reason ?: if (it.status == ContinuousStatus.CANCELLED) ContinuousStatus.CANCELLED else ContinuousStatus.FAILED) }
            }
            refresh.update { it + 1 }
            accepted
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: ContinuousClipEdits.SongFull) { view.update { it.copy(status = ContinuousStatus.SONG_FULL) }; false }
        catch (_: Exception) { view.update { it.copy(status = ContinuousStatus.FAILED) }; false }
        }
    }

    private fun drumBank(project: Project): List<Pad> = project.pads.subList(DrumKits.BANK * 16, DrumKits.BANK * 16 + 16).toList()

    private fun sourcePreviewBlocked(action: ContinuousEditorAction): Boolean {
        if (ports.vocalGuide?.preview?.state?.value?.ownsSource != true) return false
        return when (action) {
            ContinuousEditorAction.PlayOriginal, is ContinuousEditorAction.SeekOriginal, is ContinuousEditorAction.SetOriginalMonitorGain,
            is ContinuousEditorAction.SetOriginalPitch, is ContinuousEditorAction.SetSourceRange, ContinuousEditorAction.BeginLiveChop,
            ContinuousEditorAction.OpenScratch, ContinuousEditorAction.ScratchHold, is ContinuousEditorAction.ScratchDrag,
            is ContinuousEditorAction.ScratchNudge, ContinuousEditorAction.ReloadAudio -> true
            else -> false
        }
    }
    private suspend fun vocalGuard(revision: Long): TtsResult<Unit> {
        if (studio.document.value.revision != revision) return ttsFailure(TtsProblem.STALE_DOCUMENT)
        return when (bankPadBlock(view.value, studio.work.value)) {
            null -> TtsResult.Success(Unit)
            BankPadEditProblem.RECORDING -> ttsFailure(TtsProblem.RECORDING)
            else -> ttsFailure(TtsProblem.BUSY)
        }
    }
    private fun separationGuard(revision: Long): SeparationResult<Unit> = when {
        studio.document.value.revision != revision -> separationFailure(SeparationProblem.STALE_DOCUMENT)
        bankPadBlock(view.value, studio.work.value) == BankPadEditProblem.RECORDING -> separationFailure(SeparationProblem.RECORDING)
        bankPadBlock(view.value, studio.work.value) != null -> separationFailure(SeparationProblem.BUSY)
        else -> SeparationResult.Success(Unit)
    }
    /** A dialog owns a worker, not the presenter action lock or the backend's shared PCM. */
    private suspend fun openFourStems(): Boolean {
        val factory = ports.fourStems ?: return false
        if (studio.document.value.project.source == null || bankPadBlock(view.value, studio.work.value) != null) return false
        if (separation.value != null) return true
        closeLyricProposal(); closeStepPatterns(); closeVocalGuide()
        lyricEditor.dispatch(LyricAction.Close)
        bankPadEditor.dispatch(BankPadEditAction.Cancel)
        val controller = FourStemController(studio.document, separationAvailability, factory.create(), object : FourStemActions {
            override suspend fun prepareAllowed(expectedRevision: Long): SeparationResult<Unit> {
                val early = separationGuard(expectedRevision)
                return if (early is SeparationResult.Failure) early else serialized.withLock {
                    currentCoroutineContext().ensureActive()
                    separationGuard(expectedRevision)
                }
            }
            override suspend fun apply(intent: Intent.SetArrangement, expectedRevision: Long) = applyPreparedEdit(intent, expectedRevision)
        }, jobs)
        separation.value = controller
        jobs.launch { controller.state.first { it.phase == FourStemPhase.CLOSED }; separation.compareAndSet(controller, null) }
        return true
    }
    private fun closeFourStems(): Boolean {
        if (separation.value?.state?.value?.phase == FourStemPhase.APPLYING) return false
        separation.getAndUpdate { null }?.close()
        return true
    }

    private suspend fun openVocalGuide(): Boolean {
        if (!closeFourStems()) return false
        val port = ports.vocalGuide ?: return false
        if (bankPadBlock(view.value, studio.work.value) != null) return false
        if (vocal.value != null) return true
        closeLyricProposal(); closeStepPatterns()
        lyricEditor.dispatch(LyricAction.Close)
        bankPadEditor.dispatch(BankPadEditAction.Cancel)
        val controller = VocalGuideController(studio.document, vocalAvailability, port.createSynthesis(), port.preview, object : VocalGuideActions {
            override suspend fun prepareAllowed(expectedRevision: Long): TtsResult<Unit> {
                val early = vocalGuard(expectedRevision)
                return if (early is TtsResult.Failure) early else serialized.withLock { vocalGuard(expectedRevision) }
            }
            override suspend fun preview(asset: Asset, expectedRevision: Long): TtsResult<Unit> = serialized.withLock {
                when (val guard = vocalGuard(expectedRevision)) {
                    is TtsResult.Failure -> guard
                    is TtsResult.Success -> {
                        releaseHeld(); letGoScratch(); endLiveChop()
                        port.preview.start(asset, expectedRevision)
                    }
                }
            }
            override suspend fun apply(intent: Intent.ApplyVocalGuide, expectedRevision: Long) = applyPreparedEdit(intent, expectedRevision)
        }, jobs)
        vocal.value = controller
        jobs.launch { controller.state.first { it.phase == VocalGuidePhase.CLOSED }; vocal.compareAndSet(controller, null) }
        return true
    }
    private suspend fun closeVocalGuide() { vocal.getAndUpdate { null }?.close(); ports.vocalGuide?.preview?.stop() }

    private suspend fun openLyricProposal(): Boolean {
        if (!closeFourStems()) return false
        val port = ports.lyricProposal ?: return false
        if (bankPadBlock(view.value, studio.work.value) != null) return false
        if (proposal.value != null) return true
        closeStepPatterns(); closeVocalGuide()
        val controller = LyricProposalController(studio.document, port.createProvider(), LyricProposalApply { placement, revision ->
            applyPreparedEdit(Intent.SetStructuredLyrics(placement.lines, placement.structure), revision)
        }, jobs, port.availability)
        proposal.value = controller
        jobs.launch {
            controller.state.first { it.phase == LyricProposalPhase.CLOSED }
            proposal.compareAndSet(controller, null)
        }
        lyricEditor.dispatch(LyricAction.Close)
        return true
    }

    private fun closeLyricProposal() { proposal.getAndUpdate { null }?.close() }

    /** Opening only publishes a controller; its rendering/apply callbacks run after the action lock is released. */
    private suspend fun openStepPatterns(): Boolean {
        if (!closeFourStems()) return false
        if (!ports.stepPatternsAvailable || bankPadBlock(view.value, studio.work.value) != null) return false
        if (patterns.value != null) return true
        closeLyricProposal(); closeVocalGuide()
        lyricEditor.dispatch(LyricAction.Close)
        bankPadEditor.dispatch(BankPadEditAction.Cancel)
        val controller = StepPatternController(studio.document, studio.selection, patternAvailability, object : StepPatternPorts {
            override suspend fun apply(intent: Intent, expectedRevision: Long) = applyPreparedEdit(intent, expectedRevision)
            override suspend fun render(pad: Pad, source: Asset, request: PatternVoiceRender) =
                ports.renderPerformance(pad, source, request.releaseAt, request.limitFrames, request.stopAt)
            override suspend fun selectPad(padId: Int) = dispatch(ContinuousEditorAction.SelectPad(padId))
        }, jobs)
        patterns.value = controller
        jobs.launch {
            controller.state.first { it.phase == PatternPhase.CLOSED }
            patterns.compareAndSet(controller, null)
        }
        return true
    }

    private fun closeStepPatterns() { patterns.getAndUpdate { null }?.close() }
    private fun patternAvailability(editor: EditorView, work: WorkState): PatternAvailability = when (bankPadBlock(editor, work)) {
        BankPadEditProblem.RECORDING -> PatternAvailability.RECORDING
        BankPadEditProblem.BUSY -> PatternAvailability.BUSY
        else -> PatternAvailability.EDITABLE
    }

    /** Proposal callbacks await this directly, outside an open action; recording flags and revision are checked at apply. */
    suspend fun applyPreparedEdit(intent: Intent, expectedRevision: Long): Boolean = serialized.withLock {
        when (bankPadBlock(view.value, studio.work.value)) {
            BankPadEditProblem.RECORDING -> { view.update { it.copy(status = ContinuousStatus.RECORDING_BUSY) }; false }
            BankPadEditProblem.BUSY -> false
            null -> send(Action.Edit(intent, expectedRevision = expectedRevision))
            else -> false
        }
    }

    private fun bankPadBlock(editor: EditorView, work: WorkState): BankPadEditProblem? = when {
        editor.voice != null || editor.startingVoice || editor.hits != null || editor.recordingSource || editor.startingSource || finishingTake -> BankPadEditProblem.RECORDING
        work.jobId != null || work.preparationId != null -> BankPadEditProblem.BUSY
        else -> null
    }

    private fun allowedWhileCollecting(action: ContinuousEditorAction) = when (action) {
        // The controller keeps drafts/cancel available and itself refuses opening or applying while recording.
        is ContinuousEditorAction.BankPadEdit -> true
        is ContinuousEditorAction.Lyrics -> action.action == LyricAction.Close
        ContinuousEditorAction.CloseLyricProposal, ContinuousEditorAction.CloseStepPatterns, ContinuousEditorAction.CloseVocalGuide, ContinuousEditorAction.CloseFourStems -> true
        ContinuousEditorAction.RecordSource, ContinuousEditorAction.RecordSystemSource, ContinuousEditorAction.StopSourceRecording,
        ContinuousEditorAction.DiscardSourceRecording, ContinuousEditorAction.StopAll,
        is ContinuousEditorAction.Navigate, is ContinuousEditorAction.CopyDiagnostics -> true
        else -> false
    }

    /** Collects an original without playing a song. The same owned microphone is shared with voice takes. */
    private suspend fun startSource(project: Project, system: Boolean = false): Boolean {
        if (view.value.recordingSource) {
            if (view.value.systemSource == system) return true
            refusal = ContinuousStatus.RECORDING_BUSY; return false
        }
        if (studio.work.value.jobId != null || studio.work.value.preparationId != null) return false
        if (if (system) ports.systemAudioCapture == null else !ports.voiceAvailable) {
            refusal = if (system) ContinuousStatus.SYSTEM_UNAVAILABLE else ContinuousStatus.MIC_UNAVAILABLE; return false
        }
        val seconds = voiceSecondsLeft(project, if (system) 2 else 1)
        if (seconds < 1) { refusal = ContinuousStatus.VOICE_NO_ROOM; return false }
        releaseHeld(); stopOriginal()
        if (!send(Action.Stop)) return false
        letGoScratch()
        sourceOpeningCancelled = false
        openingSource = true
        view.update { it.copy(recordingSource = true, systemSource = system, startingSource = true, scratch = null, liveChop = null, playingPads = emptySet()) }
        try {
            val error = if (system) when (requireNotNull(ports.systemAudioCapture).start(seconds)) {
                SystemAudioCapture.Start.STARTED -> null
                SystemAudioCapture.Start.NO_ROOM -> ContinuousStatus.VOICE_NO_ROOM
                SystemAudioCapture.Start.DENIED -> ContinuousStatus.SYSTEM_DENIED
                SystemAudioCapture.Start.NO_DISPLAY -> ContinuousStatus.SYSTEM_NO_DISPLAY
                SystemAudioCapture.Start.TIMEOUT -> ContinuousStatus.SYSTEM_TIMEOUT
                SystemAudioCapture.Start.CANCELLED -> ContinuousStatus.CANCELLED
                SystemAudioCapture.Start.UNAVAILABLE -> ContinuousStatus.SYSTEM_UNAVAILABLE
            } else when (ports.startVoice(seconds)) {
                VoiceStart.STARTED -> null
                VoiceStart.DENIED -> ContinuousStatus.MIC_DENIED
                VoiceStart.UNAVAILABLE -> ContinuousStatus.MIC_UNAVAILABLE
                VoiceStart.NO_ROOM -> ContinuousStatus.VOICE_NO_ROOM
            }
            if (error != null || sourceOpeningCancelled) {
                discardSource()
                refusal = if (sourceOpeningCancelled) ContinuousStatus.CANCELLED else error
                view.update { it.copy(recordingSource = false, systemSource = false) }
                return false
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) { discardSource() }
            view.update { it.copy(recordingSource = false, systemSource = false) }
            throw failure
        } finally { openingSource = false; view.update { it.copy(startingSource = false) } }
        return true
    }

    private fun cancelSourceOpening() {
        if (openingSource) { sourceOpeningCancelled = true; ports.systemAudioCapture?.cancelOpening() }
    }
    private suspend fun discardSource() {
        if (view.value.systemSource) ports.systemAudioCapture?.discard() else ports.discardVoice()
    }
    private fun sourceFull() = if (view.value.systemSource) ports.systemAudioCapture?.full == true else ports.voiceFull()
    private fun sourceInterrupted() = if (view.value.systemSource) ports.systemAudioCapture?.interrupted == true else ports.voiceInterrupted()

    /** Keeps the whole capture as the original, one Undo; neither a PAD nor a song is replaced. */
    private suspend fun finishSource(): Boolean {
        if (!view.value.recordingSource) return true
        val system = view.value.systemSource
        val full = sourceFull()
        val interrupted = sourceInterrupted()
        view.update { it.copy(recordingSource = false, systemSource = false, status = ContinuousStatus.SAVING) }
        fun report(status: ContinuousStatus, ok: Boolean): Boolean {
            view.update { it.copy(status = status) }
            if (!ok) refusal = status
            return ok
        }
        val names = studio.document.value.project.assets.map { it.name }.toSet()
        val prefix = if (system) "SYSTEM" else "MIC"
        var number = 1
        while ("$prefix $number" in names) number++
        val asset = try {
            if (system) ports.systemAudioCapture?.stop("$prefix $number") else ports.stopVoice("$prefix $number")?.asset
        }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { return report(ContinuousStatus.VOICE_NOT_SAVED, false) }
            ?: return report(if (system) ContinuousStatus.SYSTEM_EMPTY else ContinuousStatus.VOICE_EMPTY, true)
        if (!addTake(Intent.ImportAsset(asset))) return report(ContinuousStatus.VOICE_NOT_SAVED, false)
        ports.resetOriginal()
        return report(when {
            interrupted -> ContinuousStatus.SOURCE_RECORDING_INTERRUPTED
            full -> ContinuousStatus.SOURCE_RECORDING_LIMIT
            else -> ContinuousStatus.SOURCE_RECORDED
        }, true)
    }

    private suspend fun endSourceAtLimit() {
        if (!view.value.recordingSource || !serialized.tryLock()) return
        try {
            if (view.value.recordingSource && (sourceFull() || sourceInterrupted())) {
                finishSource()
                refresh.update { it + 1 }
            }
        } finally { serialized.unlock() }
    }

    /** Hosts finish a recording before their final autosave, or when moving to the background. */
    suspend fun finishRecording(): Boolean {
        cancelSourceOpening()
        voiceOpeningCancelled = true
        return serialized.withLock {
            if (view.value.voice != null || view.value.hits != null) pauseSong()
            finishVoice() && finishHits() && finishSource()
        }
    }

    private fun allowedWhileRecording(action: ContinuousEditorAction) = when (action) {
        is ContinuousEditorAction.BankPadEdit -> true
        is ContinuousEditorAction.Lyrics -> action.action == LyricAction.Close
        ContinuousEditorAction.CloseLyricProposal, ContinuousEditorAction.CloseStepPatterns, ContinuousEditorAction.CloseVocalGuide, ContinuousEditorAction.CloseFourStems -> true
        ContinuousEditorAction.RecordVoice, ContinuousEditorAction.StopVoice, ContinuousEditorAction.StopAll,
        ContinuousEditorAction.RecordHits, ContinuousEditorAction.StopHits, is ContinuousEditorAction.CaptureHit,
        is ContinuousEditorAction.BeginHit, is ContinuousEditorAction.EndHit, is ContinuousEditorAction.DropHit,
        ContinuousEditorAction.StopSong, ContinuousEditorAction.PauseSong, is ContinuousEditorAction.Navigate,
        is ContinuousEditorAction.SelectBank, is ContinuousEditorAction.SelectPad, is ContinuousEditorAction.SelectClip,
        is ContinuousEditorAction.TapPad, is ContinuousEditorAction.HoldPad, is ContinuousEditorAction.ReleasePad,
        is ContinuousEditorAction.SetSongMonitorGain, is ContinuousEditorAction.SetOriginalMonitorGain,
        is ContinuousEditorAction.SetPixelsPerSecond, is ContinuousEditorAction.SetGrid, ContinuousEditorAction.FitTimeline, is ContinuousEditorAction.ResizePanes,
        ContinuousEditorAction.ResetPanes, is ContinuousEditorAction.SelectCompactPane, is ContinuousEditorAction.CopyDiagnostics,
        ContinuousEditorAction.DismissDrumKit, ContinuousEditorAction.ClosePadPlay -> true
        else -> false
    }

    /**
     * Whole seconds of voice the engine can still hold with everything the document already keeps resident (less a
     * small margin), within the document's own size and asset count limits; at most [MAX_VOICE_SECONDS]. The host
     * lowers this further to what its storage takes.
     */
    private fun voiceSecondsLeft(project: Project, channels: Int = 1): Int {
        if (project.assets.size >= ProjectLimits.MAX_ASSETS) return 0
        val resident = (ProgramCompiler.RESIDENT_FRAME_LIMIT - (ProgramCompiler.residentBudgetBytes(project) / 8)) / CONTINUOUS_TIMELINE_RATE -
            VOICE_BUDGET_MARGIN_SECONDS
        // A take is a 32-bit float WAV: 4 bytes per frame after a 44-byte header, mono at 48 kHz at most.
        val stored = (ProjectLimits.MAX_TOTAL_BYTES - project.assets.sumOf { it.byteCount } - 44) / (4L * CONTINUOUS_TIMELINE_RATE * channels)
        return minOf(resident, stored, MAX_VOICE_SECONDS.toLong()).coerceAtLeast(0).toInt()
    }

    /**
     * Records a take while the song plays. Unlike the earlier app, which recorded over a looping beat PAD, the song
     * plays on from where it stands (from the top once it had ended) and the take lands on the song where it was sung.
     * The microphone opens first (the platform may ask for permission), with the song paused and ready.
     */
    private suspend fun startVoice(project: Project): Boolean {
        if (view.value.voice != null) return true
        if (view.value.hits != null || !ports.voiceAvailable) return false
        val seconds = voiceSecondsLeft(project)
        if (seconds < 1) { refusal = ContinuousStatus.VOICE_NO_ROOM; return false }
        releaseHeld()
        stopOriginal()
        // Nothing plays while the platform may be asking for the microphone; the song is ready before it opens.
        val emptySong = project.clips.isEmpty()
        val recordingEnd = if (emptySong) seconds.toLong() * CONTINUOUS_TIMELINE_RATE else songFrames(project)
        var microphoneOpened = false
        var started = false
        voiceOpeningCancelled = false
        view.update { it.copy(startingVoice = true) }
        try {
            if (!recordingOutputReady() || (studio.transport.value.playing && !send(Action.Pause)) ||
                !selectArrangement(if (emptySong) recordingEnd else 0)) return false
            val cue = ports.recordingCue
            if (cue == null && view.value.countInBars > 0) { refusal = ContinuousStatus.MIC_UNAVAILABLE; return false }
            when (cue?.startArmedVoice(seconds) ?: ports.startVoice(seconds)) {
                VoiceStart.STARTED -> microphoneOpened = true
                VoiceStart.DENIED -> { refusal = ContinuousStatus.MIC_DENIED; return false }
                VoiceStart.UNAVAILABLE -> { refusal = ContinuousStatus.MIC_UNAVAILABLE; return false }
                VoiceStart.NO_ROOM -> { refusal = ContinuousStatus.VOICE_NO_ROOM; return false }
            }
            if (voiceOpeningCancelled) { refusal = ContinuousStatus.CANCELLED; return false }
            // Permission may have kept the microphone dialog open while the output route disappeared.
            if (!recordingOutputReady()) return false
            val ended = emptySong || songEnded(project)
            if (ended && !send(Action.Seek(0))) return false
            val from = if (ended) 0L else studio.transport.value.sequenceFrame
            // What the output had queued when the song started is how late the singer heard it.
            val outputDelay = if (cue == null) ports.diagnostics()?.pendingFrames?.coerceIn(0L, CONTINUOUS_TIMELINE_RATE.toLong()) ?: 0L else 0L
            val cueFrame = if (cue != null) {
                if (!send(Action.CountInAndResume(view.value.countInBars))) return false
                val frame = studio.transport.value.recordingStartFrame
                if (frame < 0 || !cue.cueVoiceAt(frame)) { refusal = ContinuousStatus.RECORDING_CUE_CANCELLED; return false }
                frame
            } else {
                if (!send(Action.Resume)) return false
                ports.cueVoice()
                -1L
            }
            if (voiceOpeningCancelled) { refusal = ContinuousStatus.CANCELLED; return false }
            view.update { it.copy(voice = VoiceRecording(from, recordingEnd, outputDelay, emptySong, cueFrame)) }
            started = true
            return true
        } finally {
            view.update { it.copy(startingVoice = false) }
            if (!started) withContext(NonCancellable) {
                try { if (microphoneOpened) { pauseSong(); ports.discardVoice() } }
                finally { clearRecordingClock() }
            }
        }
    }

    /**
     * Ends the take, if one is running, and adds it as one Undo: to the first empty PAD of BANK D and onto the song
     * where the singer heard it, on the song's voice track, never past where the song ended. What was captured before
     * the song started and the output's delay at that moment are cut from the clip's start (a take whose first frame
     * came late is placed later instead); the microphone's own delay is not measured yet. The caller stops or pauses
     * the song. Reports the outcome in the status line itself, also when a poll ends the take.
     */
    private suspend fun finishVoice(): Boolean {
        if (view.value.voice == null) return true
        var cleared: Boolean
        val saved = try { finishVoiceTake() } finally { cleared = clearRecordingClock() }
        return saved && cleared
    }

    private suspend fun finishVoiceTake(): Boolean {
        val recording = view.value.voice ?: return true
        view.update { it.copy(voice = null, status = ContinuousStatus.SAVING) }
        fun report(status: ContinuousStatus, ok: Boolean): Boolean {
            if (!ok) refusal = status
            view.update { it.copy(status = status) }
            return ok
        }
        val timedOut = ports.recordingCue?.armingTimedOut() == true
        if (timedOut || (recording.cueFrame >= 0 && !recordingCueHasStarted(recording.cueFrame))) {
            ports.discardVoice()
            return report(if (timedOut) ContinuousStatus.RECORDING_ARM_TIMEOUT else ContinuousStatus.RECORDING_CUE_CANCELLED, true)
        }
        val full = ports.voiceFull()
        val interrupted = ports.voiceInterrupted()
        val project = studio.document.value.project
        val pad = (VOICE_BANK * 16 until VOICE_BANK * 16 + 16).firstOrNull { project.pads[it].assetHash == null }
        val vocal = project.tracks.firstOrNull { it.kind == TrackKind.VOCAL }
        val number = pad?.let { it - VOICE_BANK * 16 + 1 } ?: (project.clips.count { it.trackId == vocal?.id } + 1)
        val take = try { ports.stopVoice("VOICE $number") }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { return report(ContinuousStatus.VOICE_NOT_SAVED, false) }
            ?: return report(ContinuousStatus.VOICE_EMPTY, true)
        val asset = take.asset
        val rate = asset.sampleRate.toLong()
        // Take frames ahead of the song: cut from the clip's start, or (negative) how late the clip starts.
        val offset = take.leadFrames + recording.outputDelayFrames * rate / CONTINUOUS_TIMELINE_RATE
        val start = offset.coerceAtLeast(0)
        val at = recording.songFrame + (-offset).coerceAtLeast(0) * CONTINUOUS_TIMELINE_RATE / rate
        // What was captured after the song ended (the poll notices a little later) is not part of the take.
        val end = minOf(asset.frames, start + (recording.songEnd - at).coerceAtLeast(0) * rate / CONTINUOUS_TIMELINE_RATE)
        // Stopped before anything was sung to the song: there is nothing to place.
        if (end <= start) return report(ContinuousStatus.VOICE_TOO_SHORT, true)
        val track = if (vocal == null) Track(freshId("track"), "VOICE", TrackKind.VOCAL) else null
        val clip = Clip(freshId("clip"), (vocal ?: track!!).id, asset.hash, FrameRange(start, end), timelineStartFrame = at)
        val voicePad = pad?.let { Pad(it, asset.hash, FrameRange(0, asset.frames), asset.name, gain = .9f) }
        val placed = when {
            addTake(Intent.AddVoiceTake(asset, voicePad, clip, track)) -> if (voicePad == null) ContinuousStatus.VOICE_SAVED_SONG_ONLY else ContinuousStatus.VOICE_SAVED
            // The song refused the clip (too many at once, say): the PAD still keeps the take.
            voicePad != null && addTake(Intent.AddVoiceTake(asset, voicePad, null, null)) -> ContinuousStatus.VOICE_SAVED_PAD_ONLY
            else -> return report(ContinuousStatus.FAILED, false)
        }
        if (voicePad != null) send(Action.SelectPad(voicePad.id))
        return report(when {
            interrupted -> ContinuousStatus.VOICE_INTERRUPTED
            full -> ContinuousStatus.VOICE_LIMIT
            else -> placed
        }, true)
    }

    /** Adds a take; a stop pressed meanwhile cancels the edit in progress, so a cancelled one is tried again. */
    private suspend fun addTake(intent: Intent): Boolean {
        finishingTake = true
        try {
            repeat(3) {
                val result = answered(studio.dispatch(Action.Edit(intent)))
                if (result.accepted) return true
                if (result.notice !is Notice.Cancelled) return false
            }
            return false
        } finally { finishingTake = false }
    }

    /**
     * A take ends with the song: when it reaches the end, the host stops it, or the output is lost so the song can no
     * longer be heard; and when the take reached its length limit or its microphone went away. Never waits behind
     * other work; the next poll looks again.
     */
    private suspend fun endVoiceWithTheSong() {
        if (view.value.voice == null || !serialized.tryLock()) return
        try {
            if (view.value.voice == null || !send(Action.RefreshTransport)) return
            val transport = studio.transport.value
            if (!ports.voiceFull() && !ports.voiceInterrupted() && ports.recordingCue?.armingTimedOut() != true &&
                (transport.playing || transport.countInBeatsRemaining > 0) && transport.outputAttached) return
            if (transport.playing || transport.countInBeatsRemaining > 0) send(Action.Pause)
            finishVoice()
            refresh.update { it + 1 }
        } finally { serialized.unlock() }
    }

    /** Places PAD [padId] on the song once or, filling, many times: a transformed PAD is rendered first, once. */
    private suspend fun placePad(project: Project, padId: Int, action: ContinuousEditorAction): Boolean {
        val pad = project.pads.getOrNull(padId) ?: return false
        if (pad.assetHash == null) return false
        val rendered = renderTransformed(project, listOf(padId))
        if (ContinuousClipEdits.transformed(pad) && padId !in rendered) return false
        return edit(ContinuousClipEdits.intent(project, action, ::freshId, rendered, view.value.grid))
    }

    /**
     * The sound of each transformed PAD of [padIds], rendered once, by PAD, while the engine has room for them. One
     * that has no room or fails to render is left out, with the reason as the refusal.
     */
    private suspend fun renderTransformed(project: Project, padIds: Collection<Int>): Map<Int, Asset> {
        val rendered = mutableMapOf<Int, Asset>()
        var resident = (ProgramCompiler.residentBudgetBytes(project) / 8)
        for (pad in padIds.distinct().map { project.pads[it] }.filter(ContinuousClipEdits::transformed)) {
            val source = project.asset(requireNotNull(pad.assetHash))
            val range = requireNotNull(pad.range)
            val length = ProgramCompiler.sourceFrameTo48k(range.end, source.sampleRate) - range.start * 48_000 / source.sampleRate
            val frames = kotlin.math.ceil(length / 2.0.pow(pad.pitchSemitones / 12)).toLong()
            if (resident + frames > ProgramCompiler.RESIDENT_FRAME_LIMIT) { refusal = ContinuousStatus.PLACE_NO_ROOM; continue }
            rendered[pad.id] = try { ports.renderPad(pad, source) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                ?: run { refusal = ContinuousStatus.PLACE_FAILED; continue }
            resident += frames
        }
        return rendered
    }

    /**
     * Records what the PADs play while the song plays on from where it stands (from the top once it has ended). What
     * the output had queued as the pass began is how late the player hears the song, as for a voice take.
     */
    private suspend fun startHits(project: Project): Boolean {
        if (view.value.hits != null) return true
        if (view.value.voice != null || project.pads.none { it.assetHash != null }) return false
        releaseHeld()
        stopOriginal()
        val emptySong = project.clips.isEmpty()
        val recordingEnd = if (emptySong) MAX_VOICE_SECONDS.toLong() * CONTINUOUS_TIMELINE_RATE else songFrames(project)
        var started = false
        try {
            if (!recordingOutputReady() || ((emptySong || view.value.countInBars > 0) && studio.transport.value.playing && !send(Action.Pause)) ||
                !selectArrangement(if (emptySong) recordingEnd else 0) || !recordingOutputReady()) return false
            val ended = emptySong || songEnded(project)
            if (ended && !send(Action.Seek(0))) return false
            val from = if (ended) 0L else studio.transport.value.sequenceFrame
            val outputDelay = ports.diagnostics()?.pendingFrames?.coerceIn(0L, CONTINUOUS_TIMELINE_RATE.toLong()) ?: 0L
            val cueFrame = if (view.value.countInBars > 0) {
                if (!send(Action.CountInAndResume(view.value.countInBars))) return false
                studio.transport.value.recordingStartFrame
            } else {
                if (!studio.transport.value.playing && !send(Action.Resume)) return false
                -1L
            }
            view.update { it.copy(hits = HitRecording(from, recordingEnd, outputDelay, emptySong = emptySong, cueFrame = cueFrame)) }
            started = true
            return true
        } finally { if (!started) { pauseSong(); clearRecordingClock() } }
    }

    /**
     * Ends the pass, if one runs, and puts what the PADs played onto the song as one Undo: each PAD where it was heard,
     * on the grid, as placing it would. The caller stops or pauses the song. Reports the outcome in the status line
     * itself, also when the song's end ends the pass.
     */
    private suspend fun releaseRecordedPad(padId: Int) {
                    if (view.value.hits?.pending?.keys?.any { it.padId == padId } == true) {
                        send(Action.RefreshTransport)
                        val end = studio.transport.value.sequenceFrame
                        view.update { v -> v.copy(hits = v.hits?.let { r -> r.copy(pending = r.pending.mapValues { (gesture, hit) ->
                            if (gesture.padId == padId) releaseHit(hit, (end - gesture.songFrame).coerceIn(0L, hit.limitFrames.toLong()).toInt()) else hit
                        }) }) }
                    }
    }

    /** A later finger-up cannot undo an earlier choke/note-off. */
    private fun releaseHit(hit: ContinuousHit, afterFrames: Int) =
        hit.copy(releaseAfterFrames = minOf(hit.releaseAfterFrames ?: afterFrames, afterFrames))

    private suspend fun snapshotHitEnd(): Long? {
        if (view.value.hits == null) return null
        send(Action.RefreshTransport)
        return studio.transport.value.sequenceFrame
    }

    private suspend fun finishHits(capturedEnd: Long? = null, stopVoices: Boolean = false): Boolean {
        if (view.value.hits == null) return true
        var cleared: Boolean
        val placed = try { finishHitTake(capturedEnd, stopVoices) } finally { cleared = clearRecordingClock() }
        return placed && cleared
    }

    private suspend fun finishHitTake(capturedEnd: Long?, stopVoices: Boolean): Boolean {
        val active = view.value.hits ?: return true
        // The pass owns presses already heard: a later pointer release cannot erase them at the song boundary.
        val endFrame = (capturedEnd ?: studio.transport.value.sequenceFrame).coerceAtMost(active.songEnd)
        val pending = active.pending.map { (gesture, hit) ->
            releaseHit(hit, (endFrame - gesture.songFrame).coerceIn(0L, hit.limitFrames.toLong()).toInt())
        }
        // A loop started by a tap remains audible after pointer-up, until the pass stops it.
        active.pending.keys.map { it.padId }.distinct().forEach { send(Action.Release(it)); held -= it }
        val played = (active.played + pending).map { hit ->
            if (stopVoices && hit.performed) hit.copy(stopAfterFrames =
                (endFrame - active.outputDelayFrames - hit.timelineFrame).coerceIn(0L, hit.limitFrames.toLong()).toInt()) else hit
        }
        val recording = active.copy(played = played, pending = emptyMap())
        view.update { it.copy(hits = null) }
        fun report(status: ContinuousStatus, ok: Boolean): Boolean {
            if (!ok) refusal = status
            view.update { it.copy(status = status) }
            return ok
        }
        if (recording.played.isEmpty()) return report(ContinuousStatus.HITS_EMPTY, true)
        // The song's end or a pause can end a pass outside any action: whatever goes wrong is reported, never thrown.
        return try {
            val project = studio.document.value.project
            // A transformed PAD that cannot be rendered leaves out its own hits, not the whole pass.
            val rendered = renderTransformed(project, recording.played.filterNot { it.performed }.map { it.padId })
            val performances = mutableMapOf<ContinuousHit, Asset>()
            var resident = (ProgramCompiler.residentBudgetBytes(project) / 8) + rendered.values.sumOf { it.frames }
            for (hit in recording.played.filter { it.performed }.distinct()) {
                val pad = project.pads[hit.padId]
                val source = project.asset(requireNotNull(pad.assetHash))
                val natural = kotlin.math.ceil(requireNotNull(pad.range).length * 48_000.0 / source.sampleRate / 2.0.pow(pad.pitchSemitones / 12)).toLong()
                val upper = minOf(hit.limitFrames.toLong(),
                    if (pad.mode == PlayMode.LOOP) Long.MAX_VALUE else natural,
                    hit.releaseAfterFrames?.let { it.toLong() + pad.releaseFrames } ?: Long.MAX_VALUE,
                    hit.stopAfterFrames?.let { it.toLong() + com.choplab.engine.EngineCore.STEAL_FADE_FRAMES } ?: Long.MAX_VALUE)
                if (resident + upper > ProgramCompiler.RESIDENT_FRAME_LIMIT) { refusal = ContinuousStatus.PLACE_NO_ROOM; continue }
                val asset = try { ports.renderPerformance(pad, source, hit.releaseAfterFrames, hit.limitFrames, hit.stopAfterFrames) }
                    catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
                if (asset == null) { refusal = ContinuousStatus.PLACE_FAILED; continue }
                performances[hit] = asset
                if (project.assets.none { it.hash == asset.hash } && performances.values.count { it.hash == asset.hash } == 1) resident += asset.frames
            }
            val refused = refusal
            val playable = recording.played.filter {
                if (it.performed) it in performances else !ContinuousClipEdits.transformed(project.pads[it.padId]) || it.padId in rendered
            }.sortedBy { it.timelineFrame }
            fun plan(count: Int) = try {
                ContinuousClipEdits.intent(project, ContinuousEditorAction.PlaceHits(playable.take(count)), ::freshId, rendered, view.value.grid, performances)
            } catch (_: ContinuousClipEdits.SongFull) { null }
            // What the song cannot take is left out from the end: the longest start of the pass that fits goes on.
            var fits = 0
            var intent: Intent.SetArrangement? = null
            if (playable.isNotEmpty()) {
                intent = plan(playable.size)
                if (intent != null) fits = playable.size else {
                    var tooMany = playable.size
                    while (tooMany - fits > 1) {
                        val middle = (fits + tooMany) / 2
                        plan(middle)?.let { intent = it; fits = middle } ?: run { tooMany = middle }
                    }
                }
            }
            val placed = intent
            when {
                placed == null -> report(if (playable.isEmpty()) refused ?: ContinuousStatus.PLACE_FAILED else ContinuousStatus.SONG_FULL, false)
                // Played where the same sounds already start: nothing to add, and no Undo to promise.
                placed.clips == project.clips -> report(ContinuousStatus.HITS_UNCHANGED, true)
                // Added as a take is, so a stop pressed meanwhile cannot cancel it.
                !addTake(placed) -> report(ContinuousStatus.FAILED, false)
                recording.overflow || fits < recording.played.size -> report(ContinuousStatus.HITS_PARTLY, true)
                else -> report(ContinuousStatus.HITS_PLACED, true)
            }
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { report(ContinuousStatus.FAILED, false) }
    }

    /** A pass ends with the song: at its end, or when the output goes away. */
    private suspend fun endHitsWithTheSong() {
        if (view.value.hits == null || !serialized.tryLock()) return
        try {
            if (view.value.hits == null || !send(Action.RefreshTransport)) return
            val transport = studio.transport.value
            if ((transport.playing || transport.countInBeatsRemaining > 0) && transport.outputAttached) return
            if (transport.playing || transport.countInBeatsRemaining > 0) send(Action.Pause)
            finishHits()
            refresh.update { it + 1 }
        } finally { serialized.unlock() }
    }

    /** Opens the scratch panel on the selected PAD when it holds a sound, otherwise on the original. */
    private fun openScratch(project: Project): Boolean {
        val pad = project.pads[studio.selection.value.padId].assetHash != null
        if (!pad && (project.source == null || !ports.originalAvailable)) return false
        val target = if (pad) ContinuousScratchTarget.PAD else ContinuousScratchTarget.ORIGINAL
        lastScratchFraction = restingScratchFraction(project, target)
        view.update { it.copy(scratch = ScratchView(target)) }
        return true
    }

    private fun restingScratchFraction(project: Project, target: ContinuousScratchTarget): Float {
        val frame: Double
        val start: Double
        val end: Double
        if (target == ContinuousScratchTarget.ORIGINAL) {
            val source = project.source ?: return 0f
            val cursor = lastOriginalScratch?.takeIf { it.hash == source.assetHash && it.start == source.range.start.toDouble() && it.end == source.range.end.toDouble() }
                ?: return 0f
            frame = cursor.frame; start = cursor.start; end = cursor.end
        } else {
            val pad = project.pads[studio.selection.value.padId]
            val cursor = lastPadScratch?.takeIf { it.first == pad.id } ?: return 0f
            val asset = pad.assetHash?.let(project::asset) ?: return 0f
            val range = pad.range ?: return 0f
            frame = cursor.second; start = range.start * 48_000.0 / asset.sampleRate; end = range.end * 48_000.0 / asset.sampleRate
        }
        return ((frame - start) / (end - start)).toFloat().coerceIn(0f, 1f)
    }

    /**
     * A hand takes the platter. A PAD is scratched in the engine over its whole range, taken where it plays while it
     * sounds, its own voices waiting meanwhile. Original HAND starts from its own saved position and leaves SOURCE
     * and the song playing independently.
     */
    private suspend fun holdScratch(project: Project): Boolean {
        val sheet = view.value.scratch ?: return false
        if (!studio.transport.value.outputAttached) return false
        if (grip != null) return true
        releaseHeld()
        scratchOpeningCancelled = false
        scratchOpening = true
        val held = try { when (sheet.target) {
            ContinuousScratchTarget.PAD -> {
                val padId = studio.selection.value.padId
                val pad = project.pads[padId]
                val asset = project.asset(pad.assetHash ?: return false)
                val range = requireNotNull(pad.range)
                // The engine holds the PAD at 48 kHz, as the compiler maps its range.
                val start = (range.start * 48_000 / asset.sampleRate).toDouble()
                val end = ProgramCompiler.sourceFrameTo48k(range.end, asset.sampleRate).toDouble()
                val from = lastPadScratch?.takeIf { it.first == padId }?.second?.coerceIn(start, end - 1) ?: start
                if (!send(Action.ScratchStart(padId, from))) return false
                // A sounding PAD is taken where it plays; the engine says where that is.
                val at = studio.transport.value.scratchFrame.takeIf { it >= 0 } ?: from
                ScratchGrip(ContinuousScratchTarget.PAD, padId, start, end, sheet.sensitivity.framesPerPixel(CONTINUOUS_TIMELINE_RATE), 1.0,
                    at.coerceIn(start, end - 1))
            }
            ContinuousScratchTarget.ORIGINAL -> {
                val source = project.source ?: return false
                val asset = project.asset(source.assetHash)
                val from = lastOriginalScratch?.takeIf { it.hash == asset.hash && it.start == source.range.start.toDouble() && it.end == source.range.end.toDouble() }
                    ?.frame?.coerceIn(source.range.start.toDouble(), source.range.end - 1.0) ?: source.range.start.toDouble()
                if (!ports.setHandMonitorGain(view.value.handGain) || scratchOpeningCancelled ||
                    !ports.scratchOriginalStart(asset, from.toLong(), source.range.start, source.range.end)) {
                    if (scratchOpeningCancelled) refusal = ContinuousStatus.CANCELLED
                    return false
                }
                ScratchGrip(ContinuousScratchTarget.ORIGINAL, -1, source.range.start.toDouble(), source.range.end.toDouble(),
                    sheet.sensitivity.framesPerPixel(asset.sampleRate), CONTINUOUS_TIMELINE_RATE.toDouble() / asset.sampleRate, from, asset.hash)
            }
        } } catch (cancel: CancellationException) {
            if (scratchOpeningCancelled && currentCoroutineContext().isActive) { refusal = ContinuousStatus.CANCELLED; return false }
            throw cancel
        } finally { scratchOpening = false }
        if (scratchOpeningCancelled) {
            if (held.target == ContinuousScratchTarget.ORIGINAL) ports.scratchOriginalEnd() else send(Action.ScratchEnd)
            dragPixels.value = 0.0
            refusal = ContinuousStatus.CANCELLED
            return false
        }
        // A closed cut fader closes before the first move can sound.
        val cut = view.value.scratch?.cut ?: 1f
        val gain = view.value.handGain
        if ((cut != 1f || gain != 1f) && !cutScratch(held, cut, gain)) {
            if (held.target == ContinuousScratchTarget.ORIGINAL) ports.scratchOriginalEnd() else send(Action.ScratchEnd)
            return false
        }
        held.cut = cut; held.gain = gain
        grip = held
        view.update { it.copy(scratch = it.scratch?.copy(holding = true)) }
        pump = jobs.launch { pumpScratch(held) }
        return true
    }

    /** The held platter moved [distancePx] (positive forward); the pump passes it on. Any thread, at pointer rate. */
    private fun dragScratch(distancePx: Float) {
        if (distancePx.isFinite() && view.value.scratch != null) dragPixels.update { it + distancePx }
    }

    /** Sets the cut fader; the pump passes it on while a hand holds the platter. Any thread, at pointer rate. */
    private fun setScratchCut(gain: Float): Boolean {
        requireGain(gain)
        var open = false
        view.update { v -> v.scratch?.let { sheet -> open = true; v.copy(scratch = sheet.copy(cut = gain)) } ?: v.also { open = false } }
        return open
    }

    private fun setHandGain(gain: Float): Boolean {
        requireGain(gain)
        if (view.value.scratch == null) return false
        view.update { it.copy(handGain = gain) }
        return true
    }

    private fun endsScratch(action: ContinuousEditorAction): Boolean = action == ContinuousEditorAction.CloseScratch ||
        action == ContinuousEditorAction.ScratchLetGo || action == ContinuousEditorAction.StopAll || action == ContinuousEditorAction.ReloadAudio ||
        action == ContinuousEditorAction.StopOriginal || (action is ContinuousEditorAction.Navigate && action.stage != ContinuousStage.BEAT)

    private fun cancelScratchOpening() {
        if (scratchOpening) { scratchOpeningCancelled = true; ports.cancelOriginalPreparation() }
    }

    /** An engine program replacement, source stop or lost output can end the voice without a pointer release. */
    private suspend fun endLostScratch() {
        if (grip == null || !serialized.tryLock()) return
        try {
            val held = grip ?: return
            if (!studio.transport.value.outputAttached ||
                (held.target == ContinuousScratchTarget.ORIGINAL && ports.readout().handSourceFrame < 0)) letGoScratch()
        } finally { serialized.unlock() }
    }

    /**
     * Passes the hand's moves and the cut fader on about every 12 ms. Each move lasts half as long again as the time
     * since the last, at most two ticks' worth, so the sound keeps moving until the next one arrives, and stays inside
     * the engine's speed limit. A move refused means the scratch ended under it (the output was rebuilt, say): the pump
     * stops quietly and the hand's letting go tidies up.
     */
    private suspend fun pumpScratch(held: ScratchGrip) {
        var last = TimeSource.Monotonic.markNow()
        while (currentCoroutineContext().isActive && grip === held) {
            delay((SCRATCH_TICK_MILLIS - last.elapsedNow().inWholeMilliseconds).coerceAtLeast(1))
            val now = TimeSource.Monotonic.markNow()
            val elapsed = ((now - last).inWholeMicroseconds * CONTINUOUS_TIMELINE_RATE / 1_000_000).coerceIn(48, SCRATCH_GAP_FRAMES)
            last = now
            val dragged = dragPixels.getAndUpdate { 0.0 }
            if (dragged != 0.0) held.aim = (held.aim + dragged * held.framesPerPixel).coerceIn(held.start, held.end - 1)
            val cut = view.value.scratch?.cut ?: held.cut
            val gain = view.value.handGain
            if (cut != held.cut || gain != held.gain) {
                if (!cutScratch(held, cut, gain)) { releaseRefusedScratch(held); return }
                held.cut = cut; held.gain = gain
            }
            val reach = SCRATCH_SPEED_LIMIT * elapsed / held.engineFramesPerFrame
            val target = held.aim.coerceIn(held.sent - reach, held.sent + reach)
            if (target == held.sent) continue
            val duration = (elapsed * 3 / 2).toInt()
            val moved = if (held.target == ContinuousScratchTarget.PAD) send(Action.ScratchMove(target, duration))
                else ports.scratchOriginalTo(target, duration)
            if (!moved) { releaseRefusedScratch(held); return }
            held.sent = target
        }
    }

    private suspend fun cutScratch(held: ScratchGrip, cut: Float, gain: Float): Boolean =
        if (held.target == ContinuousScratchTarget.PAD) send(Action.ScratchCut(cut * gain))
        else (gain == held.gain || ports.setHandMonitorGain(gain)) && ports.scratchOriginalCut(cut)

    private fun releaseRefusedScratch(held: ScratchGrip) {
        jobs.launch { serialized.withLock { if (grip === held) letGoScratch() } }
    }

    /**
     * The hand lets go once. A PAD resumes its own prior voices; original HAND ends without touching SOURCE.
     */
    private suspend fun letGoScratch(): Boolean {
        val held = grip
        if (held == null) { dragPixels.value = 0.0; return true }
        lastScratchFraction = held.fraction
        if (held.target == ContinuousScratchTarget.ORIGINAL) {
            val at = ports.readout().handSourceFrame.takeIf { it >= 0 } ?: held.sent
            lastOriginalScratch = OriginalHandCursor(requireNotNull(held.sourceHash), held.start, held.end, at)
            lastScratchFraction = ((at - held.start) / (held.end - held.start)).toFloat().coerceIn(0f, 1f)
        }
        grip = null
        pump?.cancelAndJoin()
        pump = null
        dragPixels.value = 0.0
        val ended = if (held.target == ContinuousScratchTarget.PAD) send(Action.ScratchEnd).also { lastPadScratch = held.padId to held.sent }
            else ports.scratchOriginalEnd()
        view.update { it.copy(scratch = it.scratch?.copy(holding = false)) }
        return ended
    }

    /**
     * For screen readers: one short movement back or forward, then let go. It covers an eighth of the range, at most half
     * a second of sound, over about 0.15 s.
     */
    private suspend fun nudgeScratch(project: Project, forward: Boolean): Boolean {
        if (grip == null && !holdScratch(project)) return false
        val held = grip ?: return false
        val step = minOf((held.end - held.start) / 8, CONTINUOUS_TIMELINE_RATE / held.engineFramesPerFrame / 2)
        val pixels = (if (forward) step else -step) / held.framesPerPixel / SCRATCH_NUDGE_STEPS
        repeat(SCRATCH_NUDGE_STEPS) { dragPixels.update { it + pixels }; delay(SCRATCH_TICK_MILLIS) }
        withTimeoutOrNull(1_000) { while ((dragPixels.value != 0.0 || held.sent != held.aim) && grip === held) delay(SCRATCH_TICK_MILLIS) }
        delay(100)
        return letGoScratch()
    }

    /** The user's own sounds on the drum BANK are replaced only after they agree; kit sounds just change kit. */
    private suspend fun chooseKit(kitId: String, project: Project): Boolean {
        val bank = drumBank(project)
        val replaced = bank.count { pad -> pad.assetHash?.let { DrumKits.identify(project.asset(it)) == null } == true }
        if (replaced == 0) return installKit(kitId)
        view.update { it.copy(kitChooser = false, kitQuestion = KitQuestion(kitId, bank, replaced)) }
        return true
    }

    private suspend fun installKit(kitId: String): Boolean {
        view.update { it.copy(kitChooser = false, kitQuestion = null) }
        val sounds = ports.drumKit(kitId) ?: return false
        val first = DrumKits.BANK * 16
        val project = studio.document.value.project
        val pads = sounds.mapIndexed { slot, asset ->
            // Changing kits swaps only the sound: a PAD that holds this drum from any kit keeps the user's settings.
            val current = project.pads[first + slot]
            val sameDrum = current.assetHash?.let { DrumKits.identify(project.asset(it))?.slot } == slot
            if (sameDrum && requireNotNull(current.range).end <= asset.frames) current.copy(assetHash = asset.hash)
            else DrumKits.pad(first + slot, slot, asset)
        }
        releaseHeld()
        return edit(Intent.InstallKit(sounds.frozen(), pads.frozen())) && send(Action.SelectPad(first))
    }

    private suspend fun selectArrangement(minimumFrames: Long =
        view.value.voice?.takeIf { it.emptySong }?.songEnd ?: view.value.hits?.takeIf { it.emptySong }?.songEnd ?: 0): Boolean {
        val current = studio.selection.value.playbackTarget as? PlaybackTarget.Arrangement
        val target = PlaybackTarget.Arrangement(current?.takeIds ?: frozenListOf(), minimumFrames)
        return current == target || send(Action.SelectPlaybackTarget(target))
    }
    private suspend fun recordingOutputReady(): Boolean {
        if (!send(Action.RefreshTransport)) return false
        return studio.transport.value.outputAttached.also { if (!it) refusal = ContinuousStatus.NO_OUTPUT }
    }
    /** A denied, cancelled or finished take must never leave a silent five-minute song behind. */
    private suspend fun clearRecordingClock(): Boolean = withContext(NonCancellable) {
        val current = studio.selection.value.playbackTarget as? PlaybackTarget.Arrangement ?: return@withContext true
        if (current.minimumFrames == 0L) return@withContext true
        // A concurrent Stop may cancel a preparation, so use the same bounded retry as adding the take.
        finishingTake = true
        try {
            repeat(3) {
                val result = answered(studio.dispatch(Action.SelectPlaybackTarget(current.copy(minimumFrames = 0))))
                if (result.accepted) return@withContext true
                if (result.notice !is Notice.Cancelled) return@withContext false
            }
            false
        } finally { finishingTake = false }
    }
    /** The song stands at its end (played there, or paused there), where Resume plays nothing. Reads the last refresh. */
    private fun songEnded(project: Project): Boolean = studio.transport.value.sequenceFrame >= songFrames(project)
    /** Pauses the song only while it plays: pausing at its end would leave the next start there. */
    private suspend fun pauseSong() {
        send(Action.RefreshTransport)
        if (studio.transport.value.playing || studio.transport.value.countInBeatsRemaining > 0) send(Action.Pause)
    }
    private suspend fun recordingCueHasStarted(frame: Long): Boolean = send(Action.RefreshTransport) &&
        studio.transport.value.recordingStartedFrame == frame
    private suspend fun send(action: Action): Boolean = answered(studio.dispatch(action)).let { result ->
        if (!result.accepted && result.notice is Notice.Cancelled) refusal = ContinuousStatus.CANCELLED
        result.accepted
    }
    /** Remembers the notice answered to one of this presenter's own requests, so its later flow copy is skipped. */
    private fun answered(result: ActionResult): ActionResult {
        result.notice?.let { notice -> answeredNotices.update { (it + notice).takeLast(32) } }
        return result
    }
    private fun cancelled(): Boolean { view.update { it.copy(status = ContinuousStatus.CANCELLED) }; return false }
    private suspend fun edit(intent: Intent): Boolean = send(Action.Edit(intent))
    private suspend fun releaseHeld() {
        val release = held.toSet() + taps.keys
        taps.values.forEach(Job::cancel); taps.clear()
        release.forEach { send(Action.Release(it)) }; held.clear()
    }
    /**
     * A pass covers the range only: once the original has played past its end, [pass] ends with the original. Never
     * waits behind other work such as an import; the next poll looks again.
     */
    private suspend fun endLiveChopPastRange(pass: Int) {
        fun pastRange() = studio.document.value.project.source?.let { ports.readout().originalFrame >= it.range.end } == true
        if (view.value.liveChop == null || !pastRange() || !serialized.tryLock()) return
        try { if (view.value.livePasses == pass && pastRange()) endLiveChop() } finally { serialized.unlock() }
    }
    /** Ends a running live chop pass together with the original it plays. */
    private suspend fun endLiveChop(): Boolean {
        if (view.value.liveChop == null) return true
        val stopped = ports.stopOriginal()
        view.update { it.copy(originalPlaying = if (stopped) false else it.originalPlaying, liveChop = null) }
        return stopped
    }
    private suspend fun stopOriginal() {
        ports.cancelOriginalPreparation()
        if (ports.originalAvailable) ports.resetOriginal()
        view.update { it.copy(originalPlaying = false, liveChop = null) }
    }
    private fun freshId(prefix: String): String {
        val p = studio.document.value.project
        var value: String
        do { value = "$prefix-${++serial}" } while (p.clips.any { it.id == value } || p.tracks.any { it.id == value })
        return value
    }
    suspend fun close() {
        closeFourStems()
        closeVocalGuide()
        ports.vocalGuide?.preview?.stop()
        closeLyricProposal()
        closeStepPatterns()
        cancelScratchOpening()
        cancelSourceOpening()
        voiceOpeningCancelled = true
        queue.close()
        // Nothing queued starts after this (a take, for one, could otherwise open the microphone after the host let go).
        consumer.cancelAndJoin()
        // A take still running keeps what was sung.
        serialized.withLock {
            separation.getAndUpdate { null }?.close()
            closeLyricProposal()
            closeStepPatterns()
            // The original stops before a hand on it lets go, so nothing plays on as the editor closes.
            releaseHeld(); stopOriginal(); letGoScratch()
            if (view.value.voice != null || view.value.hits != null) { pauseSong(); finishVoice(); finishHits() }
            finishSource()
        }
        ports.vocalGuide?.preview?.close()
        owner.cancel()
    }
    private fun requireGain(gain: Float) { require(gain.isFinite() && gain in 0f..1f) }

    private fun songFrames(p: Project): Long = p.clips.maxOfOrNull { ContinuousClipEdits.startFrame(p, it) + ContinuousClipEdits.durationFrames(p, it) }
        ?.coerceAtLeast(1) ?: 48_000L

    private fun slicePeaks(p: Project, hash: String?, range: FrameRange?, peaks: Map<String, List<Float>>): List<Float> {
        if (hash == null || range == null) return emptyList()
        val full = peaks[hash].orEmpty()
        if (full.isEmpty()) return full
        val frames = p.asset(hash).frames
        val start = (range.start * full.size / frames).toInt().coerceIn(0, full.lastIndex)
        val end = ((range.end * full.size + frames - 1) / frames).toInt().coerceIn(start + 1, full.size)
        return full.subList(start, end).toList()
    }

    private fun project(input: EditorInputs, v: EditorView, peaks: Map<String, List<Float>>): ContinuousEditorState {
        val p = input.document.project
        val source = p.source?.let { s -> p.asset(s.assetHash).let { a -> ContinuousSource(a.hash, a.name, a.frames, a.sampleRate,
            peaks[a.hash].orEmpty(), s.range.start, s.range.end, s.pitchSemitones.toFloat()) } }
        val busy = input.work.jobId != null || input.work.preparationId != null
        val recording = v.voice != null || v.startingVoice || v.recordingSource || v.hits != null
        val selected = p.pads[input.selection.padId]
        val capabilities = if (recording) mutableSetOf(ContinuousCapability.STOP_ALL)
            else mutableSetOf(ContinuousCapability.OPEN_PROJECT, ContinuousCapability.IMPORT_AUDIO, ContinuousCapability.STOP_ALL)
        if (ports.separationAvailable && source != null && !busy && !recording) capabilities += ContinuousCapability.SEPARATE_SOURCE
        if (ports.fourStems != null && source != null && bankPadBlock(v, input.work) == null) capabilities += ContinuousCapability.FOUR_STEMS
        if (ports.onlineAvailable && !busy && !recording) capabilities += ContinuousCapability.IMPORT_ONLINE
        if (ports.libraryAvailable && !busy && !recording) capabilities += ContinuousCapability.IMPORT_LIBRARY
        if (ports.spotifyMetadataAvailable && !busy && !recording) capabilities += ContinuousCapability.SPOTIFY_METADATA
        if (v.voice != null || v.hits != null) {
            // Playing along, pausing or stopping, and listening levels; the take or pass ends with the song.
            if (input.attached) capabilities += setOf(ContinuousCapability.PAD_AUDITION, ContinuousCapability.SONG_PLAYBACK,
                ContinuousCapability.SONG_MONITOR_GAIN)
            if (source != null && ports.originalAvailable) capabilities += ContinuousCapability.ORIGINAL_MONITOR_GAIN
        } else if (!busy && !recording) {
            capabilities += ContinuousCapability.RELOAD_AUDIO
            capabilities += ContinuousCapability.LYRICS_EDIT
            if (ports.lyricFiles != null) capabilities += ContinuousCapability.LYRICS_FILES
            if (ports.lyricProposal != null && !v.startingSource && !finishingTake) capabilities += ContinuousCapability.LYRIC_PROPOSAL
            if (ports.vocalGuide != null && !v.startingSource && !finishingTake) capabilities += ContinuousCapability.VOCAL_GUIDE
            if (ports.stepPatternsAvailable && !v.startingSource && !finishingTake) capabilities += ContinuousCapability.STEP_PATTERNS
            capabilities += setOf(ContinuousCapability.SAVE_PROJECT, ContinuousCapability.HISTORY, ContinuousCapability.TEMPO,
                ContinuousCapability.MOVE_CLIP, ContinuousCapability.TRIM_CLIP, ContinuousCapability.SPLIT_CLIP,
                ContinuousCapability.DUPLICATE_CLIP, ContinuousCapability.DELETE_CLIP, ContinuousCapability.TRACK_MUTE, ContinuousCapability.CLIP_GAIN)
            if (source != null) capabilities += setOf(ContinuousCapability.SOURCE_RANGE, ContinuousCapability.ASSIGN_SOURCE_RANGE,
                ContinuousCapability.AUTO_CHOP, ContinuousCapability.ORIGINAL_PITCH)
            if (selected.assetHash != null) {
                capabilities += setOf(ContinuousCapability.PAD_PITCH, ContinuousCapability.PAD_TONE, ContinuousCapability.PAD_GAIN,
                    ContinuousCapability.PAD_PLAY)
                // A placed clip plays its sound as it is: a transformed PAD needs a host that renders it first.
                if (!ContinuousClipEdits.transformed(selected) || ports.padRenderAvailable) capabilities += ContinuousCapability.PLACE_PAD
            }
            if (p.clips.isNotEmpty()) capabilities += ContinuousCapability.EXPORT_WAV
            if (input.attached) {
                capabilities += setOf(ContinuousCapability.PAD_AUDITION, ContinuousCapability.PAD_LOOP,
                    ContinuousCapability.SONG_PLAYBACK, ContinuousCapability.SONG_SEEK, ContinuousCapability.SONG_MONITOR_GAIN)
            }
            if (source != null && ports.originalAvailable) capabilities += setOf(ContinuousCapability.ORIGINAL_PLAYBACK,
                ContinuousCapability.ORIGINAL_SEEK, ContinuousCapability.ORIGINAL_MONITOR_GAIN, ContinuousCapability.LIVE_CHOP)
            if (ports.drumKitsAvailable) capabilities += ContinuousCapability.ADD_DRUM
            if (ports.voiceAvailable && input.attached) capabilities += ContinuousCapability.RECORD_VOICE
            if (input.attached && p.pads.any { it.assetHash != null }) capabilities += ContinuousCapability.RECORD_HITS
            if (ports.voiceAvailable) capabilities += ContinuousCapability.RECORD_SOURCE
            if (ports.systemAudioCapture != null) capabilities += ContinuousCapability.RECORD_SYSTEM_SOURCE
            if (input.attached && (selected.assetHash != null || (source != null && ports.originalAvailable))) capabilities += ContinuousCapability.SCRATCH
        }
        if (v.vocalPreview) capabilities.removeAll(setOf(ContinuousCapability.ORIGINAL_PLAYBACK, ContinuousCapability.ORIGINAL_SEEK,
            ContinuousCapability.ORIGINAL_MONITOR_GAIN, ContinuousCapability.ORIGINAL_PITCH, ContinuousCapability.SOURCE_RANGE,
            ContinuousCapability.LIVE_CHOP, ContinuousCapability.SCRATCH, ContinuousCapability.RELOAD_AUDIO))
        val kitSounds = p.pads.map { pad -> pad.assetHash?.let { DrumKits.identify(p.asset(it)) } }
        return ContinuousEditorState(stage = v.stage, projectTitle = p.title, original = source,
            originalPlaying = v.originalPlaying && !v.vocalPreview, vocalPreview = v.vocalPreview, liveChopping = v.liveChop != null, recordingVoice = v.voice != null,
            startingVoiceRecording = v.startingVoice, recordingHits = v.hits != null,
            recordingGuide = RecordingGuideState(input.metronome, v.countInBars, input.countInBeats, !busy && !recording && input.attached),
            recordingSource = v.recordingSource, recordingSystemAudio = v.systemSource,
            startingSourceRecording = v.startingSource, originalMonitorGain = v.originalGain,
            banks = p.banks.map { ContinuousBank(it.id, it.name, it.color, it.role) }, selectedBank = input.selection.padId / 16,
            bankPadBlocked = bankPadBlock(v, input.work),
            pads = p.pads.map { pad -> ContinuousPad(pad.id, pad.name,
                when { pad.assetHash == null -> ContinuousPadKind.EMPTY; kitSounds[pad.id] != null -> ContinuousPadKind.DRUM; else -> ContinuousPadKind.SAMPLE },
                ContinuousPadMode.valueOf(pad.mode.name), slicePeaks(p, pad.assetHash, pad.range, peaks),
                pad.range?.start ?: 0, pad.range?.end ?: 0, pad.assetHash?.let { p.asset(it).sampleRate } ?: 48_000,
                pad.pitchSemitones.toFloat(), tone = pad.tone, gain = pad.gain, looping = pad.mode == PlayMode.LOOP && pad.id in v.playingPads,
                reverse = pad.reverse, chokeGroup = pad.chokeGroup) },
            selectedPadId = input.selection.padId,
            tracks = p.tracks.mapIndexed { i, track -> ContinuousTrack(track.id, track.name,
                listOf(0xFF89AD50, 0xFFC1843D, 0xFFB6A66D, 0xFFBF7A53)[i % 4], track.mute) },
            // A zero-length clip saved by an earlier build is silent, but stays visible, selectable and deletable.
            clips = p.clips.map { c -> p.asset(c.assetHash).let { a -> ContinuousClip(c.id, c.trackId, a.name,
                ContinuousClipEdits.startFrame(p, c), ContinuousClipEdits.durationFrames(p, c).coerceAtLeast(1), c.range.start, c.range.end,
                a.frames, a.sampleRate, slicePeaks(p, a.hash, c.range, peaks), c.gain) } },
            selectedClipId = v.clip?.takeIf { id -> p.clips.any { it.id == id } }, selectedTrackId = v.track,
            timelineDurationFrames = songFrames(p), pixelsPerSecond = v.pixelsPerSecond, paneFraction = v.paneFraction,
            compactPane = v.pane, songPlaying = input.playing, songMonitorGain = v.songGain, bpm = p.tempo.milliBpm / 1000,
            milliBpm = p.tempo.milliBpm, swingPermille = p.tempo.swingPermille, grid = v.grid,
            canUndo = input.document.canUndo, canRedo = input.document.canRedo, capabilities = capabilities,
            unavailable = ContinuousCapability.entries.filterNot { it in capabilities }.associateWith { capability ->
                // Recording needs an attached output clock and (for the PADs) a PAD with a sound.
                val toSong = (capability == ContinuousCapability.RECORD_VOICE && ports.voiceAvailable) || capability == ContinuousCapability.RECORD_HITS
                when {
                    busy -> ContinuousUnavailable.BUSY
                    recording || (capability == ContinuousCapability.FOUR_STEMS && (v.startingSource || finishingTake)) -> ContinuousUnavailable.RECORDING
                    capability == ContinuousCapability.FOUR_STEMS && source == null -> ContinuousUnavailable.NO_SOURCE
                    toSong && !input.attached -> ContinuousUnavailable.NO_OUTPUT
                    capability == ContinuousCapability.RECORD_HITS && p.pads.none { it.assetHash != null } -> ContinuousUnavailable.EMPTY_PAD
                    else -> ContinuousUnavailable.NOT_CONNECTED
                }
            },
            status = if (busy) ContinuousStatus.LOADING else v.status,
            drumKits = if (ports.drumKitsAvailable) DrumKits.catalog.map { ContinuousDrumKit(it.id, it.name) } else emptyList(),
            // In use only while every sound on the drum BANK comes from that one kit.
            installedDrumKit = (DrumKits.BANK * 16 until DrumKits.BANK * 16 + 16).filter { p.pads[it].assetHash != null }
                .map { kitSounds[it]?.kit?.id }.distinct().singleOrNull(),
            drumKitChooserOpen = v.kitChooser, drumKitQuestion = v.kitQuestion?.let { ContinuousKitQuestion(it.kitId, it.replaced) },
            scratch = v.scratch?.let { sheet -> ContinuousScratch(sheet.target, padAvailable = input.attached && selected.assetHash != null,
                originalAvailable = input.attached && source != null && ports.originalAvailable, sheet.sensitivity, sheet.cut, sheet.holding, v.handGain) },
            // Open while each change is prepared (busy), as the scratch panel and kit chooser are; a PAD left empty closes it.
            padPlayOpen = v.padPlay && selected.assetHash != null)
    }
}
