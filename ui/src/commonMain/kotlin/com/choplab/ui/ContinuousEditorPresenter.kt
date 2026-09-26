package com.choplab.ui

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.engine.PlayMode
import com.choplab.engine.Tempo
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
}

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
    private val inputs = combine(studio.document, studio.selection, studio.work,
        studio.transport.map { it.playing to it.outputAttached }.distinctUntilChanged()) { d, s, w, t -> EditorInputs(d, s, w, t.first, t.second) }
    val state: StateFlow<ContinuousEditorState> = combine(inputs, view, envelopes, ::project)
        .stateIn(jobs, SharingStarted.Eagerly, project(EditorInputs(studio.document.value, studio.selection.value,
            studio.work.value, false, false), view.value, envelopes.value))

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
        jobs.launch { studio.notices.collect { notice ->
            view.update { it.copy(status = when (notice) {
                is Notice.Completed -> when (notice.operation) {
                    Operation.SAVE -> ContinuousStatus.SAVED
                    Operation.EXPORT -> ContinuousStatus.EXPORTED
                    else -> null
                }
                is Notice.Failed, is Notice.Rejected -> ContinuousStatus.FAILED
                is Notice.Cancelled -> ContinuousStatus.CANCELLED
                else -> it.status
            }) }
        } }
        jobs.launch { while (isActive) {
            studio.dispatch(Action.RefreshTransport)
            ports.originalPlaying()?.let { playing -> view.update { it.copy(originalPlaying = playing) } }
            ports.playingPads()?.let { pads -> view.update { it.copy(playingPads = pads) } }
            delay(200)
        } }
        jobs.launch { for (action in queue) dispatch(action) }
    }

    fun readout(): ContinuousEditorReadout = ports.readout()
    fun onAction(action: ContinuousEditorAction) {
        // Stop must not wait behind an import, decode or preparation that is ahead of it in the queue.
        if (interrupts(action)) jobs.launch { interrupt(action) }
        queue.trySend(action)
    }

    private fun interrupts(action: ContinuousEditorAction) = action == ContinuousEditorAction.StopOriginal ||
        action == ContinuousEditorAction.StopAll || action == ContinuousEditorAction.StopSong || action == ContinuousEditorAction.PauseSong
    private suspend fun interrupt(action: ContinuousEditorAction) {
        if (action == ContinuousEditorAction.StopOriginal || action == ContinuousEditorAction.StopAll) ports.cancelOriginalPreparation()
        if (action != ContinuousEditorAction.StopOriginal) studio.dispatch(Action.CancelWork)
    }

    suspend fun dispatch(action: ContinuousEditorAction): Boolean {
        // Cancel preparation before waiting for a UI edit; Stop cannot queue behind decoding.
        if (interrupts(action)) interrupt(action)
        return serialized.withLock {
        try {
            view.update { it.copy(status = null) }
            val project = studio.document.value.project
            val accepted = when (action) {
                is ContinuousEditorAction.Navigate -> { releaseHeld(); view.update { it.copy(stage = action.stage) }; true }
                ContinuousEditorAction.ImportAudio -> ports.chooseAudio()?.let { releaseHeld(); stopOriginal(); send(Action.Import(it)) } ?: cancelled()
                ContinuousEditorAction.OpenProject -> ports.chooseOpen()?.let { releaseHeld(); stopOriginal(); loopModes.clear(); send(Action.Open(it)) } ?: cancelled()
                ContinuousEditorAction.SaveProject -> ports.chooseSave()?.let { send(Action.Save(it)) } ?: cancelled()
                ContinuousEditorAction.ExportWav -> ports.chooseExport(songFrames(project))?.let {
                    send(Action.Export(it, PlaybackTarget.Arrangement()))
                } ?: cancelled()
                ContinuousEditorAction.Undo -> send(Action.Undo)
                ContinuousEditorAction.Redo -> send(Action.Redo)
                ContinuousEditorAction.StopAll -> {
                    releaseHeld(); val stopped = send(Action.Stop)
                    if (ports.originalAvailable) ports.stopOriginal()
                    view.update { it.copy(originalPlaying = false, playingPads = emptySet()) }; stopped
                }
                ContinuousEditorAction.PlayOriginal -> project.source?.let { source ->
                    ports.playOriginal(project.asset(source.assetHash)).also { ok -> if (ok) view.update { it.copy(originalPlaying = true) } }
                } ?: false
                ContinuousEditorAction.StopOriginal -> ports.stopOriginal().also { ok -> if (ok) view.update { it.copy(originalPlaying = false) } }
                is ContinuousEditorAction.SeekOriginal -> ports.seekOriginal(action.sourceFrame)
                is ContinuousEditorAction.SetOriginalMonitorGain -> {
                    requireGain(action.gain); ports.setOriginalMonitorGain(action.gain).also { ok -> if (ok) view.update { it.copy(originalGain = action.gain) } }
                }
                is ContinuousEditorAction.SetSongMonitorGain -> {
                    requireGain(action.gain); ports.setSongMonitorGain(action.gain).also { ok -> if (ok) view.update { it.copy(songGain = action.gain) } }
                }
                is ContinuousEditorAction.SetSourceRange -> edit(Intent.SetSourceRange(FrameRange(action.startFrame, action.endFrame)))
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
                is ContinuousEditorAction.PlacePad, is ContinuousEditorAction.MoveClip, is ContinuousEditorAction.TrimClip,
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
                    val transport = studio.transport.value
                    // The engine parks at the song end, where Resume is accepted but plays nothing.
                    val finished = !transport.sequencePaused && transport.sequenceFrame >= songFrames(project)
                    (!finished || send(Action.Seek(0))) && send(Action.Resume)
                }
                ContinuousEditorAction.PauseSong -> send(Action.Pause)
                ContinuousEditorAction.StopSong -> send(Action.Stop).also { ok -> if (ok) view.update { it.copy(playingPads = emptySet()) } }
                is ContinuousEditorAction.SetTempo -> edit(Intent.SetTempo(Tempo(action.bpm * 1000, project.tempo.swingPermille)))
                // Keep these controls visible but unavailable until their real adapters are integrated.
                is ContinuousEditorAction.SetOriginalPitch, is ContinuousEditorAction.SetPadTone,
                ContinuousEditorAction.BeginLiveChop, ContinuousEditorAction.AddDrum,
                ContinuousEditorAction.RecordVoice, ContinuousEditorAction.OpenScratch -> false
            }
            if (!accepted && view.value.status != ContinuousStatus.CANCELLED) view.update { it.copy(status = ContinuousStatus.FAILED) }
            refresh.update { it + 1 }
            accepted
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { view.update { it.copy(status = ContinuousStatus.FAILED) }; false }
        }
    }

    private suspend fun selectArrangement(): Boolean = if (studio.selection.value.playbackTarget is PlaybackTarget.Arrangement) true
        else send(Action.SelectPlaybackTarget(PlaybackTarget.Arrangement()))
    private suspend fun send(action: Action): Boolean = studio.dispatch(action).accepted
    private fun cancelled(): Boolean { view.update { it.copy(status = ContinuousStatus.CANCELLED) }; return false }
    private suspend fun edit(intent: Intent): Boolean = send(Action.Edit(intent))
    private suspend fun releaseHeld() {
        val release = held.toSet() + taps.keys
        taps.values.forEach(Job::cancel); taps.clear()
        release.forEach { send(Action.Release(it)) }; held.clear()
    }
    private suspend fun stopOriginal() {
        ports.cancelOriginalPreparation()
        if (ports.originalAvailable) ports.resetOriginal()
        view.update { it.copy(originalPlaying = false) }
    }
    private fun freshId(prefix: String): String {
        val p = studio.document.value.project
        var value: String
        do { value = "$prefix-${++serial}" } while (p.clips.any { it.id == value } || p.tracks.any { it.id == value })
        return value
    }
    suspend fun close() { queue.close(); serialized.withLock { releaseHeld(); stopOriginal() }; owner.cancel() }
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
            peaks[a.hash].orEmpty(), s.range.start, s.range.end) } }
        val busy = input.work.jobId != null || input.work.preparationId != null
        val selected = p.pads[input.selection.padId]
        val capabilities = mutableSetOf(ContinuousCapability.OPEN_PROJECT, ContinuousCapability.IMPORT_AUDIO, ContinuousCapability.STOP_ALL)
        if (!busy) {
            capabilities += setOf(ContinuousCapability.SAVE_PROJECT, ContinuousCapability.HISTORY, ContinuousCapability.TEMPO,
                ContinuousCapability.MOVE_CLIP, ContinuousCapability.TRIM_CLIP, ContinuousCapability.SPLIT_CLIP,
                ContinuousCapability.DUPLICATE_CLIP, ContinuousCapability.DELETE_CLIP, ContinuousCapability.TRACK_MUTE, ContinuousCapability.CLIP_GAIN)
            if (source != null) capabilities += setOf(ContinuousCapability.SOURCE_RANGE, ContinuousCapability.ASSIGN_SOURCE_RANGE, ContinuousCapability.AUTO_CHOP)
            if (selected.assetHash != null) {
                capabilities += setOf(ContinuousCapability.PAD_PITCH, ContinuousCapability.PAD_GAIN)
                if (selected.pitchSemitones == 0.0 && !selected.reverse) capabilities += ContinuousCapability.PLACE_PAD
            }
            if (p.clips.isNotEmpty()) capabilities += ContinuousCapability.EXPORT_WAV
            if (input.attached) {
                capabilities += setOf(ContinuousCapability.PAD_AUDITION, ContinuousCapability.PAD_LOOP,
                    ContinuousCapability.SONG_PLAYBACK, ContinuousCapability.SONG_SEEK, ContinuousCapability.SONG_MONITOR_GAIN)
            }
            if (source != null && ports.originalAvailable) capabilities += setOf(ContinuousCapability.ORIGINAL_PLAYBACK,
                ContinuousCapability.ORIGINAL_SEEK, ContinuousCapability.ORIGINAL_MONITOR_GAIN)
        }
        return ContinuousEditorState(stage = v.stage, projectTitle = p.title, original = source,
            originalPlaying = v.originalPlaying, originalMonitorGain = v.originalGain,
            banks = p.banks.map { ContinuousBank(it.id, it.name) }, selectedBank = input.selection.padId / 16,
            pads = p.pads.map { pad -> ContinuousPad(pad.id, pad.name,
                if (pad.assetHash == null) ContinuousPadKind.EMPTY else ContinuousPadKind.SAMPLE,
                ContinuousPadMode.valueOf(pad.mode.name), slicePeaks(p, pad.assetHash, pad.range, peaks),
                pad.range?.start ?: 0, pad.range?.end ?: 0, pad.assetHash?.let { p.asset(it).sampleRate } ?: 48_000,
                pad.pitchSemitones.toFloat(), gain = pad.gain, looping = pad.mode == PlayMode.LOOP && pad.id in v.playingPads) },
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
            unavailable = ContinuousCapability.entries.filterNot { it in capabilities }.associateWith {
                if (busy) ContinuousUnavailable.BUSY else ContinuousUnavailable.NOT_CONNECTED },
            status = if (busy) ContinuousStatus.LOADING else v.status)
    }
}
