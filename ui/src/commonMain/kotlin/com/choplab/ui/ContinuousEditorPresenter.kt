package com.choplab.ui

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.kits.DrumKits
import com.choplab.core.model.*
import com.choplab.engine.PlayMode
import com.choplab.engine.Tempo
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
     * Takes the original by hand from [from] within [start, end) (its own frames): its playback pauses and it sounds
     * only while moved by [scratchOriginalTo].
     */
    suspend fun scratchOriginalStart(asset: Asset, from: Long, start: Long, end: Long): Boolean = false
    suspend fun scratchOriginalTo(position: Double, durationFrames: Int): Boolean = false
    suspend fun scratchOriginalCut(gain: Float): Boolean = false
    suspend fun scratchOriginalEnd(): Boolean = false
    /** Whether this host renders a transformed PAD (pitch, reverse, tone) so it can be placed on the song. */
    val padRenderAvailable: Boolean get() = false
    /** Renders [pad] from [source] as it sounds from its PAD into a stored sound for the song; null when it cannot. */
    suspend fun renderPad(pad: Pad, source: Asset): Asset? = null
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
private data class VoiceRecording(val songFrame: Long, val songEnd: Long, val outputDelayFrames: Long)

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
                          val framesPerPixel: Double, val engineFramesPerFrame: Double, from: Double) {
    @Volatile var aim = from
    @Volatile var sent = from
    var cut = 1f
    val fraction: Float get() = ((sent - start) / (end - start)).toFloat().coerceIn(0f, 1f)
}

/** A question is bound to the drum BANK it counted; any change there asks again. */
private data class KitQuestion(val kitId: String, val bank: List<Pad>, val replaced: Int)

private data class EditorView(
    val stage: ContinuousStage = ContinuousStage.CAPTURE,
    val clip: String? = null,
    val track: String? = null,
    val pixelsPerSecond: Float = 24f,
    val paneFraction: Float = .41f,
    val pane: ContinuousPane = ContinuousPane.PADS,
    val originalPlaying: Boolean = false,
    val originalGain: Float = 1f,
    val songGain: Float = 1f,
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
    val recordingSource: Boolean = false,
    /** The scratch panel while it is open. */
    val scratch: ScratchView? = null,
    /** The selected PAD's play settings panel is open. */
    val padPlay: Boolean = false,
)
private data class EditorInputs(val document: DocumentState, val selection: SelectionState,
                                val work: WorkState, val playing: Boolean, val attached: Boolean)

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
    @Volatile private var lastScratchFraction = 0f
    private val inputs = combine(studio.document, studio.selection, studio.work,
        studio.transport.map { it.playing to it.outputAttached }.distinctUntilChanged()) { d, s, w, t -> EditorInputs(d, s, w, t.first, t.second) }
    val state: StateFlow<ContinuousEditorState> = combine(inputs, view, envelopes, ::project)
        .stateIn(jobs, SharingStarted.Eagerly, project(EditorInputs(studio.document.value, studio.selection.value,
            studio.work.value, false, false), view.value, envelopes.value))

    /** Runs the queued UI events one at a time; [close] waits for it, so nothing queued starts afterwards. */
    private val consumer: Job

    init {
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
            ports.originalPlaying()?.let { playing -> view.update { v ->
                // A pass ends with the original, also when it reaches the end by itself.
                if (v.livePasses != passes) v else v.copy(originalPlaying = playing, liveChop = v.liveChop.takeIf { playing })
            } }
            ports.playingPads()?.let { pads -> view.update { it.copy(playingPads = pads) } }
            endLiveChopPastRange(passes)
            endVoiceWithTheSong()
            endSourceAtLimit()
            delay(200)
        } }
        consumer = jobs.launch { for (action in queue) dispatch(action) }
    }

    fun readout(): ContinuousEditorReadout = ports.readout().copy(scratchFraction = grip?.fraction ?: lastScratchFraction,
        recordingMillis = ports.voiceRecordedMillis())
    fun diagnostics(): ContinuousDiagnostics? = ports.diagnostics()
    fun onAction(action: ContinuousEditorAction) {
        // A platter and its cut fader move at pointer rate: each move only updates where they should be.
        if (action is ContinuousEditorAction.ScratchDrag) { dragScratch(action.distancePx); return }
        if (action is ContinuousEditorAction.SetScratchCut) { setScratchCut(action.gain); return }
        // Stop must not wait behind an import, decode or preparation that is ahead of it in the queue.
        if (interrupts(action)) jobs.launch { interrupt(action) }
        queue.trySend(action)
    }

    private fun interrupts(action: ContinuousEditorAction) = action == ContinuousEditorAction.StopOriginal ||
        action == ContinuousEditorAction.StopAll || action == ContinuousEditorAction.StopSong || action == ContinuousEditorAction.PauseSong
    private suspend fun interrupt(action: ContinuousEditorAction) {
        if (action == ContinuousEditorAction.StopOriginal || action == ContinuousEditorAction.StopAll) ports.cancelOriginalPreparation()
        // Nothing else runs while a take is added, and cancelling that edit would lose the take.
        if (action != ContinuousEditorAction.StopOriginal && !finishingTake) studio.dispatch(Action.CancelWork)
    }

    suspend fun dispatch(action: ContinuousEditorAction): Boolean {
        // Cancel preparation before waiting for a UI edit; Stop cannot queue behind decoding.
        if (interrupts(action)) interrupt(action)
        return serialized.withLock {
        try {
            // A stop that finds no take (the song already ended it) leaves that take's message in place.
            if ((action != ContinuousEditorAction.StopVoice || view.value.voice != null) &&
                (action != ContinuousEditorAction.StopSourceRecording || view.value.recordingSource)) view.update { it.copy(status = null) }
            refusal = null
            val project = studio.document.value.project
            // Like the earlier app, a take in progress allows playing along and stopping, never document changes.
            val accepted = if ((view.value.voice != null && !allowedWhileRecording(action)) ||
                (view.value.recordingSource && !allowedWhileCollecting(action))) {
                refusal = ContinuousStatus.RECORDING_BUSY; false
            } else when (action) {
                is ContinuousEditorAction.Navigate -> {
                    releaseHeld()
                    if (action.stage != ContinuousStage.BEAT && view.value.scratch != null) { letGoScratch(); view.update { it.copy(scratch = null) } }
                    // The take belongs to the BEAT stage, where its stop button is: leaving it ends the take.
                    if (view.value.voice != null && action.stage != view.value.stage) { pauseSong(); finishVoice() }
                    if (view.value.recordingSource && action.stage != view.value.stage) finishSource()
                    view.update { it.copy(stage = action.stage, liveChop = it.liveChop.takeIf { action.stage == ContinuousStage.CHOP }) }; true
                }
                ContinuousEditorAction.ImportAudio -> ports.chooseAudio()?.let { releaseHeld(); stopOriginal(); send(Action.Import(it)) } ?: cancelled()
                ContinuousEditorAction.RecordSource -> startSource(project)
                ContinuousEditorAction.StopSourceRecording -> finishSource()
                ContinuousEditorAction.DiscardSourceRecording -> {
                    if (view.value.recordingSource) {
                        try { ports.discardVoice() } finally { view.update { it.copy(recordingSource = false) } }
                        view.update { it.copy(status = ContinuousStatus.CANCELLED) }
                    }
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
                    // Everything stops first, a scratch with it, so letting go afterwards plays nothing on.
                    releaseHeld(); val stopped = send(Action.Stop)
                    if (ports.originalAvailable) ports.stopOriginal()
                    letGoScratch()
                    view.update { it.copy(originalPlaying = false, playingPads = emptySet(), liveChop = null) }
                    val kept = finishVoice() && finishSource()
                    stopped && kept
                }
                ContinuousEditorAction.PlayOriginal -> project.source?.let { source ->
                    ports.playOriginal(project.asset(source.assetHash)).also { ok -> if (ok) view.update { it.copy(originalPlaying = true) } }
                } ?: false
                ContinuousEditorAction.StopOriginal -> ports.stopOriginal().also { ok ->
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
                            delay(120); serialized.withLock { send(Action.Release(action.padId)); taps.remove(action.padId) }
                        }
                        true
                    }
                }
                is ContinuousEditorAction.HoldPad -> send(Action.Trigger(action.padId)).also { if (it) held += action.padId }
                is ContinuousEditorAction.ReleasePad -> { held -= action.padId; send(Action.Release(action.padId)) }
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
                is ContinuousEditorAction.PlacePad -> placePad(project, action)
                is ContinuousEditorAction.MoveClip, is ContinuousEditorAction.TrimClip,
                is ContinuousEditorAction.SplitClip, is ContinuousEditorAction.DuplicateClip, is ContinuousEditorAction.DeleteClip,
                is ContinuousEditorAction.SetClipGain, is ContinuousEditorAction.SetTrackMuted -> {
                    val intent = ContinuousClipEdits.intent(project, action, ::freshId)
                    edit(intent)
                }
                is ContinuousEditorAction.SelectClip -> {
                    require(action.clipId == null || project.clips.any { it.id == action.clipId })
                    view.update { it.copy(clip = action.clipId, track = project.clips.firstOrNull { c -> c.id == action.clipId }?.trackId) }; true
                }
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
                ContinuousEditorAction.PauseSong -> send(Action.Pause).also { finishVoice() }
                ContinuousEditorAction.StopSong -> send(Action.Stop).also { ok -> if (ok) view.update { it.copy(playingPads = emptySet()) }; finishVoice() }
                is ContinuousEditorAction.SetTempo -> edit(Intent.SetTempo(Tempo(action.bpm * 1000, project.tempo.swingPermille)))
                ContinuousEditorAction.AddDrum -> ports.drumKitsAvailable.also { if (it) view.update { v -> v.copy(kitChooser = true, kitQuestion = null) } }
                is ContinuousEditorAction.ChooseDrumKit -> ports.drumKitsAvailable && chooseKit(DrumKits.kit(action.kitId).id, project)
                ContinuousEditorAction.ConfirmDrumKit -> view.value.kitQuestion?.let { question ->
                    if (drumBank(project) == question.bank) installKit(question.kitId) else chooseKit(question.kitId, project)
                } ?: false
                ContinuousEditorAction.DismissDrumKit -> { view.update { it.copy(kitChooser = false, kitQuestion = null) }; true }
                is ContinuousEditorAction.CopyDiagnostics ->
                    ports.copyText(action.text).also { copied -> if (copied) view.update { it.copy(status = ContinuousStatus.COPIED) } }
                ContinuousEditorAction.RecordVoice -> startVoice(project)
                ContinuousEditorAction.StopVoice -> { if (view.value.voice != null) pauseSong(); finishVoice() }
                ContinuousEditorAction.OpenScratch -> openScratch(project)
                ContinuousEditorAction.CloseScratch -> { letGoScratch(); view.update { it.copy(scratch = null) }; true }
                is ContinuousEditorAction.SetScratchTarget -> view.value.scratch?.let { sheet ->
                    val available = if (action.target == ContinuousScratchTarget.PAD) project.pads[studio.selection.value.padId].assetHash != null
                        else project.source != null && ports.originalAvailable
                    (grip == null && available).also { ok -> if (ok) view.update { it.copy(scratch = sheet.copy(target = action.target)) } }
                } ?: false
                is ContinuousEditorAction.SetScratchSensitivity -> view.value.scratch?.let { sheet ->
                    view.update { it.copy(scratch = sheet.copy(sensitivity = action.sensitivity)) }; true
                } ?: false
                is ContinuousEditorAction.SetScratchCut -> setScratchCut(action.gain)
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
        catch (_: Exception) { view.update { it.copy(status = ContinuousStatus.FAILED) }; false }
        }
    }

    private fun drumBank(project: Project): List<Pad> = project.pads.subList(DrumKits.BANK * 16, DrumKits.BANK * 16 + 16).toList()

    private fun allowedWhileCollecting(action: ContinuousEditorAction) = when (action) {
        ContinuousEditorAction.RecordSource, ContinuousEditorAction.StopSourceRecording,
        ContinuousEditorAction.DiscardSourceRecording, ContinuousEditorAction.StopAll,
        is ContinuousEditorAction.Navigate, is ContinuousEditorAction.CopyDiagnostics -> true
        else -> false
    }

    /** Collects an original without playing a song. The same owned microphone is shared with voice takes. */
    private suspend fun startSource(project: Project): Boolean {
        if (view.value.recordingSource) return true
        if (studio.work.value.jobId != null || studio.work.value.preparationId != null) return false
        if (!ports.voiceAvailable) { refusal = ContinuousStatus.MIC_UNAVAILABLE; return false }
        val seconds = voiceSecondsLeft(project)
        if (seconds < 1) { refusal = ContinuousStatus.VOICE_NO_ROOM; return false }
        releaseHeld(); stopOriginal()
        if (!send(Action.Stop)) return false
        letGoScratch()
        try {
            when (ports.startVoice(seconds)) {
                VoiceStart.STARTED -> view.update { it.copy(recordingSource = true, scratch = null, liveChop = null, playingPads = emptySet()) }
                VoiceStart.DENIED -> { refusal = ContinuousStatus.MIC_DENIED; return false }
                VoiceStart.UNAVAILABLE -> { refusal = ContinuousStatus.MIC_UNAVAILABLE; return false }
                VoiceStart.NO_ROOM -> { refusal = ContinuousStatus.VOICE_NO_ROOM; return false }
            }
        } catch (failure: Exception) {
            withContext(NonCancellable) { ports.discardVoice() }
            throw failure
        }
        return true
    }

    /** Keeps the whole capture as the original, one Undo; neither a PAD nor a song is replaced. */
    private suspend fun finishSource(): Boolean {
        if (!view.value.recordingSource) return true
        val full = ports.voiceFull()
        val interrupted = ports.voiceInterrupted()
        view.update { it.copy(recordingSource = false, status = ContinuousStatus.SAVING) }
        fun report(status: ContinuousStatus, ok: Boolean): Boolean {
            view.update { it.copy(status = status) }
            if (!ok) refusal = status
            return ok
        }
        val names = studio.document.value.project.assets.map { it.name }.toSet()
        var number = 1
        while ("MIC $number" in names) number++
        val take = try { ports.stopVoice("MIC $number") }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { return report(ContinuousStatus.VOICE_NOT_SAVED, false) }
            ?: return report(ContinuousStatus.VOICE_EMPTY, true)
        if (!addTake(Intent.ImportAsset(take.asset))) return report(ContinuousStatus.VOICE_NOT_SAVED, false)
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
            if (view.value.recordingSource && (ports.voiceFull() || ports.voiceInterrupted())) {
                finishSource()
                refresh.update { it + 1 }
            }
        } finally { serialized.unlock() }
    }

    /** Hosts finish a recording before their final autosave, or when moving to the background. */
    suspend fun finishRecording(): Boolean = serialized.withLock {
        if (view.value.voice != null) pauseSong()
        finishVoice() && finishSource()
    }

    private fun allowedWhileRecording(action: ContinuousEditorAction) = when (action) {
        ContinuousEditorAction.RecordVoice, ContinuousEditorAction.StopVoice, ContinuousEditorAction.StopAll,
        ContinuousEditorAction.StopSong, ContinuousEditorAction.PauseSong, is ContinuousEditorAction.Navigate,
        is ContinuousEditorAction.SelectBank, is ContinuousEditorAction.SelectPad, is ContinuousEditorAction.SelectClip,
        is ContinuousEditorAction.TapPad, is ContinuousEditorAction.HoldPad, is ContinuousEditorAction.ReleasePad,
        is ContinuousEditorAction.SetSongMonitorGain, is ContinuousEditorAction.SetOriginalMonitorGain,
        is ContinuousEditorAction.SetPixelsPerSecond, ContinuousEditorAction.FitTimeline, is ContinuousEditorAction.ResizePanes,
        ContinuousEditorAction.ResetPanes, is ContinuousEditorAction.SelectCompactPane, is ContinuousEditorAction.CopyDiagnostics,
        ContinuousEditorAction.DismissDrumKit, ContinuousEditorAction.ClosePadPlay -> true
        else -> false
    }

    /**
     * Whole seconds of voice the engine can still hold with everything the document already keeps resident (less a
     * small margin), within the document's own size and asset count limits; at most [MAX_VOICE_SECONDS]. The host
     * lowers this further to what its storage takes.
     */
    private fun voiceSecondsLeft(project: Project): Int {
        if (project.assets.size >= ProjectLimits.MAX_ASSETS) return 0
        val resident = (ProgramCompiler.RESIDENT_FRAME_LIMIT - ProgramCompiler.residentFrames(project)) / CONTINUOUS_TIMELINE_RATE -
            VOICE_BUDGET_MARGIN_SECONDS
        // A take is a 32-bit float WAV: 4 bytes per frame after a 44-byte header, mono at 48 kHz at most.
        val stored = (ProjectLimits.MAX_TOTAL_BYTES - project.assets.sumOf { it.byteCount } - 44) / (4L * CONTINUOUS_TIMELINE_RATE)
        return minOf(resident, stored, MAX_VOICE_SECONDS.toLong()).coerceAtLeast(0).toInt()
    }

    /**
     * Records a take while the song plays. Unlike the earlier app, which recorded over a looping beat PAD, the song
     * plays on from where it stands (from the top once it had ended) and the take lands on the song where it was sung.
     * The microphone opens first (the platform may ask for permission), with the song paused and ready.
     */
    private suspend fun startVoice(project: Project): Boolean {
        if (view.value.voice != null) return true
        if (!ports.voiceAvailable || project.clips.isEmpty()) return false
        val seconds = voiceSecondsLeft(project)
        if (seconds < 1) { refusal = ContinuousStatus.VOICE_NO_ROOM; return false }
        releaseHeld()
        stopOriginal()
        // Nothing plays while the platform may be asking for the microphone; the song is ready before it opens.
        if (!send(Action.RefreshTransport) || (studio.transport.value.playing && !send(Action.Pause)) || !selectArrangement()) return false
        when (ports.startVoice(seconds)) {
            VoiceStart.STARTED -> Unit
            VoiceStart.DENIED -> { refusal = ContinuousStatus.MIC_DENIED; return false }
            VoiceStart.UNAVAILABLE -> { refusal = ContinuousStatus.MIC_UNAVAILABLE; return false }
            VoiceStart.NO_ROOM -> { refusal = ContinuousStatus.VOICE_NO_ROOM; return false }
        }
        try {
            if (!send(Action.RefreshTransport)) { ports.discardVoice(); return false }
            val ended = songEnded(project)
            if (ended && !send(Action.Seek(0))) { ports.discardVoice(); return false }
            val from = if (ended) 0L else studio.transport.value.sequenceFrame
            // What the output had queued when the song started is how late the singer heard it.
            val outputDelay = ports.diagnostics()?.pendingFrames?.coerceIn(0L, CONTINUOUS_TIMELINE_RATE.toLong()) ?: 0L
            if (!send(Action.Resume)) { ports.discardVoice(); return false }
            ports.cueVoice()
            view.update { it.copy(voice = VoiceRecording(from, songFrames(project), outputDelay)) }
            return true
        } catch (failure: Exception) {
            withContext(NonCancellable) { ports.discardVoice() }
            throw failure
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
        val recording = view.value.voice ?: return true
        view.update { it.copy(voice = null, status = ContinuousStatus.SAVING) }
        fun report(status: ContinuousStatus, ok: Boolean): Boolean {
            if (!ok) refusal = status
            view.update { it.copy(status = status) }
            return ok
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
            if (!ports.voiceFull() && !ports.voiceInterrupted() && transport.playing && transport.outputAttached) return
            if (transport.playing) send(Action.Pause)
            finishVoice()
            refresh.update { it + 1 }
        } finally { serialized.unlock() }
    }

    /** A transformed PAD is rendered first and placed as that sound, if the engine has room for it. */
    private suspend fun placePad(project: Project, action: ContinuousEditorAction.PlacePad): Boolean {
        val pad = project.pads.getOrNull(action.padId) ?: return false
        val source = project.asset(pad.assetHash ?: return false)
        val rendered = if (!ContinuousClipEdits.transformed(pad)) null else {
            val range = requireNotNull(pad.range)
            val length = ProgramCompiler.sourceFrameTo48k(range.end, source.sampleRate) - range.start * 48_000 / source.sampleRate
            val frames = kotlin.math.ceil(length / 2.0.pow(pad.pitchSemitones / 12)).toLong()
            if (ProgramCompiler.residentFrames(project) + frames > ProgramCompiler.RESIDENT_FRAME_LIMIT) {
                refusal = ContinuousStatus.PLACE_NO_ROOM; return false
            }
            val asset = try { ports.renderPad(pad, source) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { null }
            asset ?: run { refusal = ContinuousStatus.PLACE_FAILED; return false }
        }
        return edit(ContinuousClipEdits.intent(project, action, ::freshId, rendered))
    }

    /** Opens the scratch panel on the selected PAD when it holds a sound, otherwise on the original. */
    private fun openScratch(project: Project): Boolean {
        val pad = project.pads[studio.selection.value.padId].assetHash != null
        if (!pad && (project.source == null || !ports.originalAvailable)) return false
        view.update { it.copy(scratch = ScratchView(if (pad) ContinuousScratchTarget.PAD else ContinuousScratchTarget.ORIGINAL)) }
        return true
    }

    /**
     * A hand takes the platter. A PAD is scratched in the engine over its whole range, taken where it plays while it
     * sounds, its own voices waiting meanwhile; the original within its range, its playback paused. The song plays on
     * underneath either way.
     */
    private suspend fun holdScratch(project: Project): Boolean {
        val sheet = view.value.scratch ?: return false
        if (grip != null) return true
        releaseHeld()
        val held = when (sheet.target) {
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
                val now = ports.readout().originalFrame
                val from = if (now in source.range.start until source.range.end) now else source.range.start
                if (!ports.scratchOriginalStart(asset, from, source.range.start, source.range.end)) return false
                view.update { it.copy(originalPlaying = false) }
                ScratchGrip(ContinuousScratchTarget.ORIGINAL, -1, source.range.start.toDouble(), source.range.end.toDouble(),
                    sheet.sensitivity.framesPerPixel(asset.sampleRate), CONTINUOUS_TIMELINE_RATE.toDouble() / asset.sampleRate, from.toDouble())
            }
        }
        // A closed cut fader closes before the first move can sound.
        val cut = view.value.scratch?.cut ?: 1f
        if (cut < 1f && cutScratch(held, cut)) held.cut = cut
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
            if (cut != held.cut) { if (!cutScratch(held, cut)) return; held.cut = cut }
            val reach = SCRATCH_SPEED_LIMIT * elapsed / held.engineFramesPerFrame
            val target = held.aim.coerceIn(held.sent - reach, held.sent + reach)
            if (target == held.sent) continue
            val duration = (elapsed * 3 / 2).toInt()
            val moved = if (held.target == ContinuousScratchTarget.PAD) send(Action.ScratchMove(target, duration))
                else ports.scratchOriginalTo(target, duration)
            if (!moved) return
            held.sent = target
        }
    }

    private suspend fun cutScratch(held: ScratchGrip, gain: Float): Boolean =
        if (held.target == ContinuousScratchTarget.PAD) send(Action.ScratchCut(gain)) else ports.scratchOriginalCut(gain)

    /**
     * The hand lets go. The engine plays on once what the scratch paused: a PAD's own voices, or the original when it
     * was playing as the hand took it; nothing that was not playing starts.
     */
    private suspend fun letGoScratch(): Boolean {
        val held = grip
        if (held == null) { dragPixels.value = 0.0; return true }
        lastScratchFraction = held.fraction
        grip = null
        pump?.cancelAndJoin()
        pump = null
        dragPixels.value = 0.0
        val ended = if (held.target == ContinuousScratchTarget.PAD) send(Action.ScratchEnd).also { lastPadScratch = held.padId to held.sent }
            else ports.scratchOriginalEnd().also { ports.originalPlaying()?.let { playing -> view.update { it.copy(originalPlaying = playing) } } }
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

    private suspend fun selectArrangement(): Boolean = if (studio.selection.value.playbackTarget is PlaybackTarget.Arrangement) true
        else send(Action.SelectPlaybackTarget(PlaybackTarget.Arrangement()))
    /** The song stands at its end (played there, or paused there), where Resume plays nothing. Reads the last refresh. */
    private fun songEnded(project: Project): Boolean = studio.transport.value.sequenceFrame >= songFrames(project)
    /** Pauses the song only while it plays: pausing at its end would leave the next start there. */
    private suspend fun pauseSong() {
        send(Action.RefreshTransport)
        if (studio.transport.value.playing) send(Action.Pause)
    }
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
        queue.close()
        // Nothing queued starts after this (a take, for one, could otherwise open the microphone after the host let go).
        consumer.cancelAndJoin()
        // A take still running keeps what was sung.
        serialized.withLock {
            // The original stops before a hand on it lets go, so nothing plays on as the editor closes.
            releaseHeld(); stopOriginal(); letGoScratch()
            if (view.value.voice != null) { pauseSong(); finishVoice() }
            finishSource()
        }
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
        val recording = v.voice != null || v.recordingSource
        val selected = p.pads[input.selection.padId]
        val capabilities = if (recording) mutableSetOf(ContinuousCapability.STOP_ALL)
            else mutableSetOf(ContinuousCapability.OPEN_PROJECT, ContinuousCapability.IMPORT_AUDIO, ContinuousCapability.STOP_ALL)
        if (v.voice != null) {
            // Playing along, pausing or stopping, and listening levels; the take ends with the song.
            if (input.attached) capabilities += setOf(ContinuousCapability.PAD_AUDITION, ContinuousCapability.SONG_PLAYBACK,
                ContinuousCapability.SONG_MONITOR_GAIN)
            if (source != null && ports.originalAvailable) capabilities += ContinuousCapability.ORIGINAL_MONITOR_GAIN
        } else if (!busy && !recording) {
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
            if (ports.voiceAvailable && input.attached && p.clips.isNotEmpty()) capabilities += ContinuousCapability.RECORD_VOICE
            if (ports.voiceAvailable) capabilities += ContinuousCapability.RECORD_SOURCE
            if (input.attached && (selected.assetHash != null || (source != null && ports.originalAvailable))) capabilities += ContinuousCapability.SCRATCH
        }
        val kitSounds = p.pads.map { pad -> pad.assetHash?.let { DrumKits.identify(p.asset(it)) } }
        return ContinuousEditorState(stage = v.stage, projectTitle = p.title, original = source,
            originalPlaying = v.originalPlaying, liveChopping = v.liveChop != null, recordingVoice = v.voice != null,
            recordingSource = v.recordingSource, originalMonitorGain = v.originalGain,
            banks = p.banks.map { ContinuousBank(it.id, it.name) }, selectedBank = input.selection.padId / 16,
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
            canUndo = input.document.canUndo, canRedo = input.document.canRedo, capabilities = capabilities,
            unavailable = ContinuousCapability.entries.filterNot { it in capabilities }.associateWith { capability ->
                val voice = capability == ContinuousCapability.RECORD_VOICE && ports.voiceAvailable
                when {
                    busy -> ContinuousUnavailable.BUSY
                    recording -> ContinuousUnavailable.RECORDING
                    voice && !input.attached -> ContinuousUnavailable.NO_OUTPUT
                    voice && p.clips.isEmpty() -> ContinuousUnavailable.NO_SONG
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
                originalAvailable = input.attached && source != null && ports.originalAvailable, sheet.sensitivity, sheet.cut, sheet.holding) },
            // Open while each change is prepared (busy), as the scratch panel and kit chooser are; a PAD left empty closes it.
            padPlayOpen = v.padPlay && selected.assetHash != null)
    }
}
