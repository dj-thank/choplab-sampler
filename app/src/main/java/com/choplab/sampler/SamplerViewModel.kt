package com.choplab.sampler

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.choplab.sampler.audio.AudioDecoder
import com.choplab.sampler.audio.AndroidPlaybackFocusAdapter
import com.choplab.sampler.audio.BuiltInDrumKits
import com.choplab.sampler.audio.CaptureEventBus
import com.choplab.sampler.audio.CaptureTempFileStore
import com.choplab.sampler.audio.MicrophoneRecorder
import com.choplab.sampler.audio.PlaybackCaptureService
import com.choplab.sampler.audio.PlaybackCaptureState
import com.choplab.sampler.audio.PatternRenderer
import com.choplab.sampler.audio.PlaybackInterruption
import com.choplab.sampler.audio.PlaybackInterruptionCoordinator
import com.choplab.sampler.audio.PlaybackSilencer
import com.choplab.sampler.audio.PlaybackStartDecision
import com.choplab.sampler.audio.SamplerEngine
import com.choplab.sampler.audio.SamplerPlaybackEngine
import com.choplab.sampler.audio.SCRATCH_GESTURE_IDLE_TIMEOUT_MS
import com.choplab.sampler.audio.TransientDetector
import com.choplab.sampler.audio.normalizeScratchSpeed
import com.choplab.sampler.model.DrumKitApplyDecision
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPressAction
import com.choplab.sampler.model.PerformancePadPressAction
import com.choplab.sampler.model.PadSurfaceMode
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PadTrimBoundary
import com.choplab.sampler.model.PadTrimSnapshot
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.PendingSourceCommand
import com.choplab.sampler.model.ProjectLaunchTarget
import com.choplab.sampler.model.ProjectOperationEpoch
import com.choplab.sampler.model.ProductionCommand
import com.choplab.sampler.model.ProductionEffect
import com.choplab.sampler.model.ProductionMutation
import com.choplab.sampler.model.ProductionSession
import com.choplab.sampler.model.RepeatGrid
import com.choplab.sampler.model.RecordingKind
import com.choplab.sampler.model.RecordingPhase
import com.choplab.sampler.model.RecordingSession
import com.choplab.sampler.model.RecordingStartPolicy
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.ScratchReturnTarget
import com.choplab.sampler.model.SliceRange
import com.choplab.sampler.model.SourceCaptureOperation
import com.choplab.sampler.model.SourcePlaybackRequest
import com.choplab.sampler.model.SourcePlaybackToggleAction
import com.choplab.sampler.model.SourceUiPhase
import com.choplab.sampler.model.activeSliceRange
import com.choplab.sampler.model.assignLiveChopToPad
import com.choplab.sampler.model.assignRangesToPads
import com.choplab.sampler.model.audibleStepKeys
import com.choplab.sampler.model.beginAutosaveRecovery
import com.choplab.sampler.model.beginRecordingSession
import com.choplab.sampler.model.beginSourceReplacement
import com.choplab.sampler.model.canUsePatternSteps
import com.choplab.sampler.model.canRequestStop
import com.choplab.sampler.model.clearPadSteps
import com.choplab.sampler.model.completeAutosaveRecoveryWithoutProject
import com.choplab.sampler.model.drumKitApplyDecision
import com.choplab.sampler.model.ensurePlayablePadSelected as ensurePlayablePadSelectedState
import com.choplab.sampler.model.endRecordingSession
import com.choplab.sampler.model.editingRequestAllowedDuringRecording
import com.choplab.sampler.model.failAutosaveRecovery
import com.choplab.sampler.model.failRecordingSession
import com.choplab.sampler.model.hasAudiblePatternContent
import com.choplab.sampler.model.inferProjectLaunchTarget
import com.choplab.sampler.model.isActive
import com.choplab.sampler.model.nextVocalPadIndex
import com.choplab.sampler.model.observeRecordingSession
import com.choplab.sampler.model.pendingSourceCommandAfterPlaybackRetarget
import com.choplab.sampler.model.pendingSourceCommandAfterStartRequest
import com.choplab.sampler.model.pendingSourceCommandAfterStopRequest
import com.choplab.sampler.model.playbackRequestBlockedByProjectOperation
import com.choplab.sampler.model.preserveAppliedSourceTruthWhileStopping
import com.choplab.sampler.model.prepareDefaultMelodyChopDestination
import com.choplab.sampler.model.playbackRequestAllowedDuringRecording
import com.choplab.sampler.model.recordPadStep
import com.choplab.sampler.model.reconcilePendingSourceCommand
import com.choplab.sampler.model.recordingStartPolicy
import com.choplab.sampler.model.replacePadSteps
import com.choplab.sampler.model.replaceSourceAudio
import com.choplab.sampler.model.resetProjectState
import com.choplab.sampler.model.restorePadTrimSnapshot
import com.choplab.sampler.model.resolvePadPressAction
import com.choplab.sampler.model.liveRecordingStep
import com.choplab.sampler.model.minimumChopFrames
import com.choplab.sampler.model.resolvePerformancePadPressAction
import com.choplab.sampler.model.selectPlayableBank as selectPlayableBankState
import com.choplab.sampler.model.selectPlayablePad as selectPlayablePadState
import com.choplab.sampler.model.selectPlayablePadPage as selectPlayablePadPageState
import com.choplab.sampler.model.selectSourceRangeForScratch
import com.choplab.sampler.model.selectScratchReturnTarget
import com.choplab.sampler.model.selectedPadModel
import com.choplab.sampler.model.sliceRanges
import com.choplab.sampler.model.sourcePlaybackAppliedStatusMessage
import com.choplab.sampler.model.sourcePlaybackRequestFeedback
import com.choplab.sampler.model.sourcePlaybackStartFrame
import com.choplab.sampler.model.sourcePlaybackToggleAction
import com.choplab.sampler.model.sourceScratchRange
import com.choplab.sampler.model.sourceUiPhase
import com.choplab.sampler.model.stopAllPlaybackState
import com.choplab.sampler.model.vocalCompanionPadIndicesForLoopStart
import com.choplab.sampler.model.scratchReturnTargetIsValid
import com.choplab.sampler.model.stopRecordingSession
import com.choplab.sampler.model.shouldContinueSourcePlayback
import com.choplab.sampler.model.snapFrameToZeroCrossing
import com.choplab.sampler.model.setPadTrimBoundary
import com.choplab.sampler.model.togglePadStep
import com.choplab.sampler.model.transientAnalysisStillCurrent
import com.choplab.sampler.persistence.AtomicProjectStore
import com.choplab.sampler.persistence.ProjectArchiveCodec
import com.choplab.sampler.ui.SamplerDeckController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.pow

class SamplerViewModel(application: Application) : AndroidViewModel(application), SamplerDeckController {
    private val mutableUiState = MutableStateFlow(beginAutosaveRecovery())
    val uiState: StateFlow<SamplerUiState> = mutableUiState.asStateFlow()
    private val decoder = AudioDecoder(application)
    private val microphoneRecorder = MicrophoneRecorder()
    private val engine: SamplerPlaybackEngine = SamplerEngine(application) { message ->
        viewModelScope.launch { setStatus(message) }
    }
    private val playbackFocusAdapter = AndroidPlaybackFocusAdapter(application) { interruption ->
        handlePlaybackInterruption(interruption)
    }
    private val playbackInterruptionCoordinator = PlaybackInterruptionCoordinator(
        focusAdapter = playbackFocusAdapter,
        playbackSilencer = PlaybackSilencer(engine::stopAllPlayback),
    )
    private val productionSession = ProductionSession(maxHistoryEntries = MAX_HISTORY_ENTRIES)
    private val autosaveStore = AtomicProjectStore(File(application.filesDir, "projects"))
    private val captureTempFiles = CaptureTempFileStore(File(application.cacheDir, "captures"))
    private var autosaveJob: Job? = null
    private var scratchIdleJob: Job? = null
    private var projectLaunchRevision = 0L
    private var scratchReturnTarget: ScratchReturnTarget = ScratchReturnTarget.None
    private val projectOperations = ProjectOperationEpoch()
    private val microphoneSourceCapture = SourceCaptureOperation(projectOperations)
    private val systemSourceCapture = SourceCaptureOperation(projectOperations)
    private val vocalCapture = SourceCaptureOperation(projectOperations)
    private var discardNextSystemCaptureResult = false

    init {
        viewModelScope.launch(Dispatchers.IO) {
            captureTempFiles.cleanupStale(
                nowMillis = System.currentTimeMillis(),
                maxAgeMillis = STALE_CAPTURE_MAX_AGE_MS,
            )
        }
        engine.start()
        observePlaybackCapture()
        pollTransportStep()
        recoverAutosave()
    }

    private fun prepareRecordingSession(
        kind: RecordingKind,
        message: String,
    ): RecordingStartPolicy? {
        val current = mutableUiState.value
        if (current.isLoading) {
            setStatus("素材の読込完了後に録音してください")
            return null
        }
        val policy = recordingStartPolicy(current.recordingSession, kind)
        if (!policy.allowed) {
            setStatus("${recordingKindLabel(current.recordingSession)}を停止してから開始してください")
            return null
        }
        val safeState = if (policy.stopAllPlaybackBeforeStart) {
            stopCompetingPlayback()
        } else {
            current
        }
        if (policy.stopAllPlaybackBeforeStart) {
            playbackInterruptionCoordinator.endPlaybackSession()
        }
        mutableUiState.value = beginRecordingSession(safeState, kind).copy(statusMessage = message)
        return policy
    }

    private fun recordingKindLabel(session: RecordingSession): String = when (session) {
        RecordingSession.Idle -> "録音"
        is RecordingSession.Active -> when (session.kind) {
            RecordingKind.SOURCE_MICROPHONE -> "マイク素材録音"
            RecordingKind.SOURCE_SYSTEM_AUDIO -> "端末音声録音"
            RecordingKind.VOCAL_OVERDUB -> "ボーカル録音"
        }
    }

    private fun applyRecorderFailure(
        kind: RecordingKind,
        message: String,
    ) {
        mutableUiState.update { state ->
            val failed = failRecordingSession(state, kind)
            if (failed.recordingSession == state.recordingSession) {
                state
            } else {
                failed.copy(statusMessage = message)
            }
        }
    }

    private enum class VocalFailureCleanupOwner {
        RECORDER_CALLBACK,
        STOP_OPERATION,
    }

    private fun finishVocalRecordingFailure(
        message: String,
        cleanupOwner: VocalFailureCleanupOwner,
    ) {
        var loopToStop: Int? = null
        mutableUiState.update { state ->
            val finished = if (cleanupOwner == VocalFailureCleanupOwner.STOP_OPERATION) {
                endRecordingSession(state, RecordingKind.VOCAL_OVERDUB)
            } else {
                failRecordingSession(state, RecordingKind.VOCAL_OVERDUB)
            }
            if (finished.recordingSession == state.recordingSession) {
                state
            } else {
                loopToStop = state.loopingPadIndex
                finished.copy(
                    loopingPadIndex = null,
                    loopPlayheadFrame = -1,
                    statusMessage = message,
                )
            }
        }
        loopToStop?.let { loopPad ->
            engine.stopPad(loopPad)
            playbackInterruptionCoordinator.endPlaybackSession()
        }
    }

    private fun observeSystemAudioRecording(state: SamplerUiState): SamplerUiState {
        val observed = observeRecordingSession(state, RecordingKind.SOURCE_SYSTEM_AUDIO)
        val session = observed.recordingSession as? RecordingSession.Active
        return if (
            session?.kind == RecordingKind.SOURCE_SYSTEM_AUDIO &&
            session.phase == RecordingPhase.STOPPING
        ) {
            observed
        } else {
            observed.copy(statusMessage = "端末音声を録音中です")
        }
    }

    private fun finishDiscardedSystemAudioRecording(state: SamplerUiState): SamplerUiState {
        val active = state.recordingSession as? RecordingSession.Active
        val finished = endRecordingSession(state, RecordingKind.SOURCE_SYSTEM_AUDIO)
        return if (active?.kind == RecordingKind.SOURCE_SYSTEM_AUDIO) {
            finished.copy(statusMessage = "前回の端末音声録音を破棄しました。新しい素材を入れてください")
        } else {
            finished
        }
    }

    private fun preparePlaybackStart(allowDuringRecording: Boolean = false): Boolean {
        val state = mutableUiState.value
        if (playbackRequestBlockedByProjectOperation(state)) {
            setStatus("読み込みが終わるまで音の操作を待ってください")
            return false
        }
        val session = state.recordingSession
        if (
            !allowDuringRecording &&
            !playbackRequestAllowedDuringRecording(session, startsPlayback = true)
        ) {
            setStatus("${recordingKindLabel(session)}中です。STOPしてから音を鳴らしてください")
            return false
        }
        return when (playbackInterruptionCoordinator.beginPlayback()) {
            PlaybackStartDecision.READY -> true
            PlaybackStartDecision.FOCUS_DENIED -> {
                setStatus("他のアプリが音声を使用中です。停止してからもう一度お試しください")
                false
            }
        }
    }

    private fun canRetargetActiveSource(state: SamplerUiState): Boolean =
        playbackInterruptionCoordinator.canRetargetPlayback() &&
            !state.recordingSession.isActive &&
            shouldContinueSourcePlayback(
                appliedPlaying = state.sourcePlaying,
                pendingCommand = state.pendingSourceCommand,
            )

    fun handlePlaybackInterruption(interruption: PlaybackInterruption) {
        val outcome = playbackInterruptionCoordinator.interrupt(
            event = interruption,
            recordingSession = mutableUiState.value.recordingSession,
        ) ?: return

        if (outcome.playbackStopped) {
            clearScratchRuntime()
            mutableUiState.update(::stopAllPlaybackState)
        }
        if (outcome.requestRecordingStop) {
            stopActiveRecording()
        }
        mutableUiState.update { it.copy(statusMessage = outcome.statusMessage) }
    }

    private fun stopCompetingPlayback(preserveScratchReturn: Boolean = false): SamplerUiState {
        if (!preserveScratchReturn) clearScratchRuntime()
        engine.stopAllPlayback()
        return stopAllPlaybackState(mutableUiState.value).also { stopped ->
            mutableUiState.value = stopped
        }
    }

    private fun clearScratchRuntime() {
        scratchReturnTarget = ScratchReturnTarget.None
        scratchIdleJob?.cancel()
        scratchIdleJob = null
    }

    fun loadAudio(uri: Uri) {
        if (mutableUiState.value.recordingSession.isActive) {
            setStatus("録音をSTOPしてから別の素材を読み込んでください")
            return
        }
        loadAudio(uri, projectOperations.begin())
    }

    private fun loadAudio(
        uri: Uri,
        operation: Long,
        completedRecordingKind: RecordingKind? = null,
        ownedCaptureFile: File? = null,
    ) {
        viewModelScope.launch {
            try {
                if (!projectOperations.isCurrent(operation)) return@launch
                clearScratchRuntime()
                engine.stopAllPlayback()
                playbackInterruptionCoordinator.endPlaybackSession()
                mutableUiState.update(::beginSourceReplacement)
                runCatching { decoder.decode(uri) }
                    .onSuccess { audio ->
                        projectOperations.completeIfCurrent(operation) {
                            val previous = mutableUiState.value
                            val newProduction = BuiltInDrumKits.installStarterKit(
                                replaceSourceAudio(previous, audio),
                            ).copy(
                                projectLaunchTarget = ProjectLaunchTarget.CHOP,
                                projectLaunchRevision = nextProjectLaunchRevision(),
                            )
                            val replaced = preserveAppliedSourceTruthWhileStopping(
                                previousState = previous,
                                replacementState = newProduction,
                            )
                            val transition = productionSession.replaceProject(replaced)
                            mutableUiState.value = transition.state
                            engine.updateAllPads(transition.state.pads)
                            syncPattern()
                            scheduleAutosave()
                        }
                    }
                    .onFailure { throwable ->
                        projectOperations.completeIfCurrent(operation) {
                            mutableUiState.update {
                                val completed = completedRecordingKind
                                    ?.let { kind -> endRecordingSession(it, kind) }
                                    ?: it
                                completed.copy(
                                    isLoading = false,
                                    statusMessage = throwable.message ?: "音声を読み込めませんでした",
                                )
                            }
                        }
                    }
            } finally {
                ownedCaptureFile?.let(captureTempFiles::deleteOwned)
            }
        }
    }

    fun startMicrophoneRecording() {
        if (microphoneRecorder.isRecording) {
            setStatus("マイク録音はすでに動作中です。STOPしてください")
            return
        }
        prepareRecordingSession(
            RecordingKind.SOURCE_MICROPHONE,
            "マイク素材録音を準備中です…",
        ) ?: return
        val operation = microphoneSourceCapture.begin()
        val file = captureTempFiles.create("microphone")
        val result = microphoneRecorder.start(file) { message ->
            captureTempFiles.deleteOwned(file)
            viewModelScope.launch {
                projectOperations.completeIfCurrent(operation) {
                    applyRecorderFailure(RecordingKind.SOURCE_MICROPHONE, message)
                }
            }
        }
        result.onSuccess {
            projectOperations.completeIfCurrent(operation) {
                mutableUiState.update {
                    observeRecordingSession(it, RecordingKind.SOURCE_MICROPHONE).copy(
                        statusMessage = "マイク素材を録音中です。STOPすると新しい波形へ切り替わります",
                    )
                }
            }
        }.onFailure { throwable ->
            captureTempFiles.deleteOwned(file)
            microphoneSourceCapture.discard(operation)
            projectOperations.completeIfCurrent(operation) {
                applyRecorderFailure(
                    RecordingKind.SOURCE_MICROPHONE,
                    throwable.message ?: "マイク録音を開始できません",
                )
            }
        }
    }

    fun stopMicrophoneRecording() {
        if (!mutableUiState.value.recordingSession.canRequestStop(RecordingKind.SOURCE_MICROPHONE)) {
            setStatus("マイク素材録音は停止処理中です")
            return
        }
        val operation = microphoneSourceCapture.consumeOrBegin()
        mutableUiState.update {
            stopRecordingSession(it, RecordingKind.SOURCE_MICROPHONE).copy(
                statusMessage = "マイク素材録音を停止しています…",
            )
        }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { microphoneRecorder.stop() }
            result.onSuccess { file ->
                loadAudio(
                    uri = Uri.fromFile(file),
                    operation = operation,
                    completedRecordingKind = RecordingKind.SOURCE_MICROPHONE,
                    ownedCaptureFile = file,
                )
            }.onFailure { throwable ->
                projectOperations.completeIfCurrent(operation) {
                    mutableUiState.update {
                        endRecordingSession(it, RecordingKind.SOURCE_MICROPHONE).copy(
                            statusMessage = throwable.message ?: "マイク録音を停止できません",
                        )
                    }
                }
            }
        }
    }

    fun startVocalOverdubRecording() {
        if (microphoneRecorder.isRecording) {
            setStatus("別の録音を停止してから声を重ねてください")
            return
        }
        val state = mutableUiState.value
        val loopPadIndex = state.loopingPadIndex
            ?: state.pads.firstOrNull { it.isAssigned && it.playMode == PadPlayMode.LOOP }?.globalIndex
            ?: run {
                setStatus("先にビートPADを選び「ビートをループ」を開始してください")
                return
            }
        val loopPad = state.pads[loopPadIndex]
        val recordingPolicy = prepareRecordingSession(
            RecordingKind.VOCAL_OVERDUB,
            "ボーカル録音を準備中です…",
        ) ?: return
        val operation = vocalCapture.begin()
        val file = captureTempFiles.create("vocal")
        microphoneRecorder.start(file) { message ->
            captureTempFiles.deleteOwned(file)
            viewModelScope.launch {
                projectOperations.completeIfCurrent(operation) {
                    finishVocalRecordingFailure(
                        message = message,
                        cleanupOwner = VocalFailureCleanupOwner.RECORDER_CALLBACK,
                    )
                }
            }
        }.onSuccess {
            projectOperations.completeIfCurrent(operation) {
                val beatLoopReady = !recordingPolicy.allowBeatLoopDuringRecording ||
                    preparePlaybackStart(allowDuringRecording = true)
                if (!beatLoopReady) {
                    stopVocalOverdubRecording()
                    setStatus("他のアプリが音声を使用中のため、ボーカル録音を停止します")
                } else {
                    if (recordingPolicy.allowBeatLoopDuringRecording) {
                        engine.startPadLoop(loopPadIndex)
                    }
                    mutableUiState.update {
                        observeRecordingSession(it, RecordingKind.VOCAL_OVERDUB).copy(
                            loopingPadIndex = loopPadIndex,
                            loopPlayheadFrame = loopPad.startFrame,
                            statusMessage = "声を録音中 — ヘッドホン推奨 / もう一度押すとテイクを保存",
                        )
                    }
                }
            }
        }.onFailure { throwable ->
            captureTempFiles.deleteOwned(file)
            vocalCapture.discard(operation)
            projectOperations.completeIfCurrent(operation) {
                applyRecorderFailure(
                    RecordingKind.VOCAL_OVERDUB,
                    throwable.message ?: "声の録音を開始できません",
                )
            }
        }
    }

    fun stopVocalOverdubRecording() {
        if (!mutableUiState.value.recordingSession.canRequestStop(RecordingKind.VOCAL_OVERDUB)) {
            setStatus("ボーカル録音は停止処理中です")
            return
        }
        val operation = vocalCapture.consumeOrBegin()
        mutableUiState.update {
            stopRecordingSession(it, RecordingKind.VOCAL_OVERDUB).copy(
                statusMessage = "ボーカル録音を停止してテイクを保存しています…",
            )
        }
        viewModelScope.launch {
            val stopped = withContext(Dispatchers.IO) { microphoneRecorder.stop() }
            stopped.onSuccess { file ->
                runCatching {
                    captureTempFiles.consume(file) { decoder.decode(Uri.fromFile(file)) }
                }
                    .onSuccess { audio ->
                        projectOperations.completeIfCurrent(operation) {
                            assignVocalTake(audio)
                            mutableUiState.update {
                                endRecordingSession(it, RecordingKind.VOCAL_OVERDUB)
                            }
                        }
                    }
                    .onFailure { throwable ->
                        projectOperations.completeIfCurrent(operation) {
                            finishVocalRecordingFailure(
                                message = throwable.message ?: "録音した声を読み込めませんでした",
                                cleanupOwner = VocalFailureCleanupOwner.STOP_OPERATION,
                            )
                        }
                    }
            }.onFailure { throwable ->
                projectOperations.completeIfCurrent(operation) {
                    finishVocalRecordingFailure(
                        message = throwable.message ?: "声の録音を停止できません",
                        cleanupOwner = VocalFailureCleanupOwner.STOP_OPERATION,
                    )
                }
            }
        }
    }

    private fun assignVocalTake(audio: PcmAudio) {
        val bankStart = SamplerConfig.VOCAL_BANK_INDEX * SamplerConfig.PADS_PER_BANK
        val target = mutableUiState.value.pads.nextVocalPadIndex() ?: run {
            setStatus("BANK D は${SamplerConfig.PADS_PER_BANK}テイクで満杯です。不要なVOICE PADを消してから録音してください")
            return
        }
        val vocalPad = PadModel(
            globalIndex = target,
            audio = audio,
            startFrame = 0,
            endFrame = audio.frameCount,
            gain = 0.9f,
            contentKind = PadContentKind.VOCAL,
        )
        commitEdit { state ->
            val pads = state.pads.toMutableList().also { it[target] = vocalPad }
            state.copy(
                pads = pads,
                selectedBank = SamplerConfig.VOCAL_BANK_INDEX,
                selectedPad = target,
                activeSteps = state.activeSteps.clearPadSteps(target),
                statusMessage = "VOICE TAKE を BANK D-%02d に保存しました".format(target - bankStart + 1),
            )
        }
        engine.updatePad(vocalPad)
        syncPattern()
    }

    override fun applyBuiltInDrumKit(kitId: String, replaceExisting: Boolean) {
        val bankIndex = SamplerConfig.DRUM_BANK_INDEX
        if (
            drumKitApplyDecision(mutableUiState.value.pads) == DrumKitApplyDecision.CONFIRM_REPLACE &&
            !replaceExisting
        ) {
            setStatus("BANK B ドラムには音があります。確認操作なしでは上書きしません")
            return
        }
        val replacement = runCatching { BuiltInDrumKits.createBankPads(kitId, bankIndex) }
            .getOrElse {
                setStatus("ドラムキットを読み込めませんでした")
                return
            }
        val bankStart = bankIndex * SamplerConfig.PADS_PER_BANK
        val bankEnd = bankStart + SamplerConfig.DRUM_KIT_PAD_COUNT
        mutableUiState.value.loopingPadIndex
            ?.takeIf { it in bankStart until bankEnd }
            ?.let(engine::stopPad)
        commitEdit { state ->
            val pads = state.pads.toMutableList()
            replacement.forEach { pads[it.globalIndex] = it }
            state.copy(
                pads = pads,
                selectedBank = bankIndex,
                selectedPad = bankStart,
                selectedDrumKitId = kitId,
                activeSteps = state.activeSteps
                    .filterNotTo(linkedSetOf()) { key -> key / SamplerConfig.STEP_COUNT in bankStart until bankEnd } +
                    BuiltInDrumKits.starterPattern(kitId, bankIndex),
                loopingPadIndex = state.loopingPadIndex?.takeUnless { it in bankStart until bankEnd },
                loopPlayheadFrame = if (state.loopingPadIndex in bankStart until bankEnd) -1 else state.loopPlayheadFrame,
                statusMessage = "${BuiltInDrumKits.catalog.first { it.id == kitId }.name} を BANK B ドラムにセット",
            )
        }
        replacement.forEach(engine::updatePad)
        syncPattern()
    }

    override fun beginScratch() {
        val state = mutableUiState.value
        val padIndex = state.selectedPad.takeIf { state.pads.getOrNull(it)?.isAssigned == true }
            ?: state.loopingPadIndex
            ?: state.pads.firstOrNull { it.isAssigned }?.globalIndex
            ?: state.selectedPad
        val pad = state.pads.getOrNull(padIndex)
        if (pad?.isAssigned != true) {
            setStatus("スクラッチするビートPADを選んでください")
            return
        }
        if (!preparePlaybackStart()) return
        scratchReturnTarget = selectScratchReturnTarget(state)
        stopCompetingPlayback(preserveScratchReturn = true)
        val startFrame = state.loopPlayheadFrame.takeIf { it in pad.startFrame until pad.endFrame }
            ?: pad.startFrame
        engine.beginScratch(padIndex, startFrame)
        mutableUiState.update {
            it.copy(
                loopingPadIndex = null,
                loopPlayheadFrame = -1,
                scratchingPadIndex = padIndex,
                scratchPlayheadFrame = startFrame,
                sourceScratchActive = false,
                scratchSpeed = 0f,
                scratchReturnAvailable = scratchReturnTarget != ScratchReturnTarget.None,
                statusMessage = "指を左右へ動かしてスクラッチ",
            )
        }
    }

    override fun beginSourceScratch() {
        val state = mutableUiState.value
        val audio = state.currentAudio
        val range = state.sourceScratchRange()
        if (audio == null || range == null) {
            setStatus("先に元曲の波形でスクラッチ範囲を選んでください")
            return
        }
        if (!preparePlaybackStart()) return
        scratchReturnTarget = selectScratchReturnTarget(state)
        stopCompetingPlayback(preserveScratchReturn = true)
        engine.beginSourceScratch(audio, range.startFrame, range.endFrame)
        mutableUiState.update { current ->
            current.copy(
                pendingSourceCommand = pendingSourceCommandAfterStopRequest(current.sourcePlaying),
                loopingPadIndex = null,
                loopPlayheadFrame = -1,
                scratchingPadIndex = null,
                scratchPlayheadFrame = range.startFrame,
                sourceScratchActive = true,
                scratchSpeed = 0f,
                scratchReturnAvailable = scratchReturnTarget != ScratchReturnTarget.None,
                statusMessage = "選んだ元曲の範囲をスクラッチ中",
            )
        }
    }

    fun useSourceRangeForScratch() {
        mutableUiState.update { state ->
            state.selectSourceRangeForScratch().copy(
                statusMessage = "元曲のS/E範囲をスクラッチ対象にしました",
            )
        }
    }

    override fun updateScratchSpeed(speed: Float) {
        val normalized = normalizeScratchSpeed(speed)
        engine.updateScratchSpeed(normalized)
        mutableUiState.update { state ->
            if (state.scratchingPadIndex != null || state.sourceScratchActive) {
                state.copy(scratchSpeed = normalized)
            } else {
                state
            }
        }
        scratchIdleJob?.cancel()
        scratchIdleJob = viewModelScope.launch {
            delay(SCRATCH_GESTURE_IDLE_TIMEOUT_MS)
            engine.updateScratchSpeed(0f)
            mutableUiState.update { state ->
                if (state.scratchingPadIndex != null || state.sourceScratchActive) {
                    state.copy(scratchSpeed = 0f)
                } else {
                    state
                }
            }
        }
    }

    override fun endScratch() {
        val active = mutableUiState.value.let { it.scratchingPadIndex != null || it.sourceScratchActive }
        if (!active) {
            clearScratchRuntime()
            return
        }
        scratchIdleJob?.cancel()
        scratchIdleJob = null
        engine.endScratch()
        mutableUiState.update {
            it.copy(
                scratchingPadIndex = null,
                scratchPlayheadFrame = -1,
                sourceScratchActive = false,
                scratchSpeed = 0f,
                scratchReturnAvailable = false,
                statusMessage = "スクラッチを停止しました",
            )
        }
        val target = scratchReturnTarget
        clearScratchRuntime()
        if (!resumeAfterScratch(target)) {
            playbackInterruptionCoordinator.endPlaybackSession()
        }
    }

    fun startSystemAudioCapture(resultCode: Int, resultData: Intent) {
        if (discardNextSystemCaptureResult) {
            setStatus("前回の端末音声録音を終了しています。完了後にもう一度開始してください")
            return
        }
        prepareRecordingSession(
            RecordingKind.SOURCE_SYSTEM_AUDIO,
            "端末音声録音を準備中です…",
        ) ?: return
        val operation = systemSourceCapture.begin()
        val application = getApplication<Application>()
        val intent = PlaybackCaptureService.startIntent(application, resultCode, resultData)
        runCatching {
            ContextCompat.startForegroundService(application, intent)
        }.onSuccess {
            projectOperations.completeIfCurrent(operation) {
                mutableUiState.update {
                    it.copy(
                        statusMessage = "端末音声録音を開始しています。STOPはいつでも押せます",
                    )
                }
            }
        }.onFailure { throwable ->
            systemSourceCapture.discard(operation)
            projectOperations.completeIfCurrent(operation) {
                applyRecorderFailure(
                    RecordingKind.SOURCE_SYSTEM_AUDIO,
                    throwable.message ?: "端末音声録音サービスを開始できませんでした",
                )
            }
        }
    }

    fun stopSystemAudioCapture() {
        if (!mutableUiState.value.recordingSession.canRequestStop(RecordingKind.SOURCE_SYSTEM_AUDIO)) {
            setStatus("端末音声録音は停止処理中です")
            return
        }
        val application = getApplication<Application>()
        mutableUiState.update {
            stopRecordingSession(it, RecordingKind.SOURCE_SYSTEM_AUDIO).copy(
                statusMessage = "端末音声録音を終了しています…",
            )
        }
        application.startService(PlaybackCaptureService.stopIntent(application))
    }

    override fun stopActiveRecording() {
        when ((mutableUiState.value.recordingSession as? RecordingSession.Active)?.kind) {
            RecordingKind.SOURCE_MICROPHONE -> stopMicrophoneRecording()
            RecordingKind.SOURCE_SYSTEM_AUDIO -> stopSystemAudioCapture()
            RecordingKind.VOCAL_OVERDUB -> stopVocalOverdubRecording()
            null -> setStatus("現在、録音は動作していません")
        }
    }

    override fun dispatch(command: ProductionCommand) {
        val before = mutableUiState.value
        val plan = productionSession.planCommand(before, command)
        if (plan.mutation == ProductionMutation.NONE && plan.effects.isEmpty()) {
            productionSession.cancel(plan)
            return
        }

        val stopFailure = plan.effects
            .filterIsInstance<ProductionEffect.StopPad>()
            .firstNotNullOfOrNull { effect ->
                runCatching { engine.stopPad(effect.index) }.exceptionOrNull()
            }
        if (stopFailure != null) {
            productionSession.cancel(plan)
            setStatus("PADを停止できないため編集を適用しませんでした: ${stopFailure.message ?: "不明なエラー"}")
            return
        }

        val transition = productionSession.commit(plan)
        mutableUiState.value = transition.state
        val refreshFailure = transition.effects.firstNotNullOfOrNull { effect ->
            when (effect) {
                is ProductionEffect.StopPad -> null
                is ProductionEffect.RefreshPad -> runCatching { engine.updatePad(effect.pad) }.exceptionOrNull()
                ProductionEffect.RefreshPattern -> runCatching { syncPattern() }.exceptionOrNull()
            }
        }
        if (transition.persistenceRequired) {
            scheduleAutosave()
        }
        if (refreshFailure != null) {
            setStatus("編集は保存しましたが再生への反映に失敗しました: ${refreshFailure.message ?: "不明なエラー"}")
        }
    }

    fun resetRange() {
        commitEdit { state ->
            val audio = state.currentAudio ?: return@commitEdit state
            state.copy(
                rangeStartFrame = 0,
                rangeEndFrame = audio.frameCount,
                sliceMarkers = emptyList(),
                activeSliceIndex = null,
            )
        }
    }

    override fun resetProject() {
        projectOperations.invalidate()
        val previous = mutableUiState.value
        val stoppingSystemAudio = previous.systemAudioRecording
        if (stoppingSystemAudio) discardNextSystemCaptureResult = true
        clearScratchRuntime()
        val bootstrapped = BuiltInDrumKits.installStarterKit(resetProjectState(previous)).copy(
            projectLaunchTarget = ProjectLaunchTarget.CAPTURE,
            projectLaunchRevision = nextProjectLaunchRevision(),
            statusMessage = "新しい制作を準備しました — BANK BにDUSTY JAZZをセット済み",
        )
        val reset = bootstrapped.let { blank ->
            if (stoppingSystemAudio) {
                blank.copy(
                    recordingSession = RecordingSession.Active(
                        RecordingKind.SOURCE_SYSTEM_AUDIO,
                        RecordingPhase.STOPPING,
                    ),
                    statusMessage = "前回の端末音声録音を停止して破棄しています…",
                )
            } else {
                blank
            }
        }
        autosaveJob?.cancel()
        engine.stopAllPlayback()
        playbackInterruptionCoordinator.endPlaybackSession()
        runCatching { microphoneRecorder.stop().onSuccess(captureTempFiles::deleteOwned) }
        runCatching {
            val application = getApplication<Application>()
            application.startService(PlaybackCaptureService.stopIntent(application))
        }
        CaptureEventBus.reset()
        val stoppingReset = preserveAppliedSourceTruthWhileStopping(previous, reset)
        val transition = productionSession.replaceProject(stoppingReset)
        val revision = transition.revision
        mutableUiState.value = transition.state
        engine.updateAllPads(transition.state.pads)
        syncPattern()
        autosaveJob = viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { autosaveStore.save(reset, revision) }.exceptionOrNull()
            }
            if (failure != null && productionSession.revision == revision) {
                mutableUiState.update {
                    it.copy(statusMessage = failure.message ?: "リセット状態を保存できませんでした")
                }
            }
        }
    }

    fun toggleManualChop() {
        commitEdit { it.copy(manualChopEnabled = !it.manualChopEnabled) }
    }

    fun clearSliceMarkers() {
        commitEdit {
            it.copy(sliceMarkers = emptyList(), activeSliceIndex = null, statusMessage = "チョップ位置を消去しました")
        }
    }

    fun removeBoundaryForActiveSlice() {
        commitEdit { state ->
            if (state.sliceMarkers.isEmpty()) {
                return@commitEdit state.copy(statusMessage = "削除できるチョップ境界がありません")
            }
            val sliceIndex = state.activeSliceIndex
                ?: return@commitEdit state.copy(statusMessage = "先に波形上のスライスを選択してください")
            val markerIndex = if (sliceIndex < state.sliceMarkers.size) sliceIndex else sliceIndex - 1
            if (markerIndex !in state.sliceMarkers.indices) return@commitEdit state

            val markers = state.sliceMarkers.toMutableList().apply { removeAt(markerIndex) }
            val remainingSlices = markers.size + 1
            state.copy(
                sliceMarkers = markers,
                activeSliceIndex = sliceIndex.coerceAtMost(remainingSlices - 1),
                statusMessage = "選択スライスに接する境界を削除しました",
            )
        }
    }

    fun autoChopEqual(sliceCount: Int) {
        commitEdit { state ->
            val audio = state.currentAudio ?: return@commitEdit state
            if (state.rangeEndFrame <= state.rangeStartFrame) return@commitEdit state
            val length = state.rangeEndFrame - state.rangeStartFrame
            val maximumByLength = length / minimumSliceFrames(audio.sampleRate)
            if (maximumByLength < 2) {
                return@commitEdit state.copy(statusMessage = "選択範囲が短く、等分チョップできません")
            }
            val count = sliceCount.coerceIn(
                2,
                minOf(SamplerConfig.PADS_PER_BANK, maximumByLength),
            )
            val markers = (1 until count).map { index ->
                state.rangeStartFrame + (length.toLong() * index / count).toInt()
            }
            state.copy(
                sliceMarkers = markers.distinct().sorted(),
                activeSliceIndex = 0,
                manualChopEnabled = false,
                statusMessage = "$count 分割しました",
            )
        }
    }

    fun autoChopTransient() {
        val snapshot = mutableUiState.value
        val revisionAtStart = productionSession.revision
        val audio = snapshot.currentAudio ?: return
        viewModelScope.launch(Dispatchers.Default) {
            withContext(Dispatchers.Main) {
                mutableUiState.update { it.copy(isLoading = true, statusMessage = "トランジェントを検出しています…") }
            }
            val detectedMarkers = TransientDetector.detect(
                samples = audio.samples,
                startFrame = snapshot.rangeStartFrame,
                endFrame = snapshot.rangeEndFrame,
                sampleRate = audio.sampleRate,
                maxSlices = SamplerConfig.PADS_PER_BANK,
            )
            val markers = detectedMarkers
                .map { marker ->
                    snapToZeroCrossing(
                        audio = audio,
                        targetFrame = marker,
                        lowerBound = snapshot.rangeStartFrame + minimumSliceFrames(audio.sampleRate),
                        upperBound = snapshot.rangeEndFrame - minimumSliceFrames(audio.sampleRate),
                    )
                }
                .distinct()
                .sorted()
            withContext(Dispatchers.Main) {
                val current = mutableUiState.value
                if (!transientAnalysisStillCurrent(
                        snapshot = snapshot,
                        snapshotRevision = revisionAtStart,
                        current = current,
                        currentRevision = productionSession.revision,
                    )
                ) {
                    mutableUiState.update { state ->
                        state.copy(
                            isLoading = false,
                            statusMessage = "範囲が変更されたため、自動チョップ結果を破棄しました",
                        )
                    }
                } else {
                    commitEdit { state ->
                        state.copy(
                            isLoading = false,
                            sliceMarkers = markers,
                            activeSliceIndex = if (markers.isEmpty()) null else 0,
                            manualChopEnabled = false,
                            statusMessage = if (markers.isEmpty()) {
                                "明確なトランジェントを検出できませんでした"
                            } else {
                                "${markers.size + 1} 個のスライス候補を作成しました"
                            },
                        )
                    }
                }
            }
        }
    }

    fun previewCurrentSelection() {
        val state = mutableUiState.value
        val audio = state.currentAudio ?: return
        val range = state.activeSliceRange()
            ?: SliceRange(state.rangeStartFrame, state.rangeEndFrame)
        if (!preparePlaybackStart()) return
        stopCompetingPlayback()
        engine.preview(audio, range.startFrame, range.endFrame)
        setStatus("選択した範囲を試聴中です")
    }

    fun assignCurrentSelectionToPad() {
        val state = mutableUiState.value
        val audio = state.currentAudio ?: run {
            setStatus("先に音声を読み込んでください")
            return
        }
        val range = state.activeSliceRange()
            ?: SliceRange(state.rangeStartFrame, state.rangeEndFrame)
        if (range.length < minimumSliceFrames(audio.sampleRate)) {
            setStatus("選択範囲が短すぎます")
            return
        }

        assignRanges(listOf(range), "選択範囲をPADへ割り当てました")
    }

    fun assignAllSlicesToPads() {
        val state = mutableUiState.value
        val audio = state.currentAudio
        val ranges = state.sliceRanges().filter { range ->
            audio != null && range.length >= minimumSliceFrames(audio.sampleRate)
        }
        if (ranges.isEmpty()) {
            setStatus("割り当てるスライスがありません")
            return
        }
        assignRanges(
            ranges.take(SamplerConfig.PADS_PER_BANK),
            "${ranges.size.coerceAtMost(SamplerConfig.PADS_PER_BANK)}スライスを連続割り当てしました",
        )
    }

    private fun assignRanges(ranges: List<SliceRange>, message: String) {
        var padsToSync: List<PadModel> = emptyList()
        commitEdit { state ->
            val result = assignRangesToPads(state, ranges, message)
            padsToSync = result.changedPads
            result.state
        }
        padsToSync.forEach(engine::updatePad)
    }

    override fun selectBank(bankIndex: Int) {
        mutableUiState.update { state ->
            val bank = bankIndex.coerceIn(0, SamplerConfig.BANK_COUNT - 1)
            val indexInBank = state.selectedPad % SamplerConfig.PADS_PER_BANK
            state.copy(
                selectedBank = bank,
                selectedPad = bank * SamplerConfig.PADS_PER_BANK + indexInBank,
            )
        }
    }

    fun selectLayerBank(bankIndex: Int) {
        mutableUiState.update { state ->
            val bank = bankIndex.coerceIn(0, SamplerConfig.BANK_COUNT - 1)
            val indexInBank = state.selectedPad % SamplerConfig.PADS_PER_BANK
            val selectedPad = bank * SamplerConfig.PADS_PER_BANK + indexInBank
            val target = state.pads[selectedPad]
            state.copy(
                selectedBank = bank,
                selectedPad = selectedPad,
                statusMessage = if (target.isAssigned) {
                    "BANK ${('A'.code + bank).toChar()}-%02dを選択。配置プリセットで重ねられます"
                        .format(indexInBank + 1)
                } else {
                    "BANK ${('A'.code + bank).toChar()}-%02dは空です。PADを叩いて音を入れてください"
                        .format(indexInBank + 1)
                },
            )
        }
    }

    override fun selectPad(globalIndex: Int) {
        if (globalIndex !in 0 until SamplerConfig.PAD_COUNT) return
        mutableUiState.update {
            it.copy(
                selectedPad = globalIndex,
                selectedBank = globalIndex / SamplerConfig.PADS_PER_BANK,
            )
        }
    }

    override fun selectPadPage(pageIndex: Int) {
        if (pageIndex !in 0 until SamplerConfig.PAD_PAGES_PER_BANK) return
        mutableUiState.update { state ->
            val bankStart = state.selectedBank * SamplerConfig.PADS_PER_BANK
            val indexOnPage = state.selectedPad % SamplerConfig.PAD_PAGE_SIZE
            val target = bankStart + pageIndex * SamplerConfig.PAD_PAGE_SIZE + indexOnPage
            state.copy(selectedPad = target)
        }
    }

    override fun selectPlayablePad(globalIndex: Int) {
        mutableUiState.update { state -> selectPlayablePadState(state, globalIndex) }
    }

    override fun selectPlayablePadPage(pageIndex: Int) {
        mutableUiState.update { state -> selectPlayablePadPageState(state, pageIndex) }
    }

    override fun selectPlayableBank(bankIndex: Int) {
        mutableUiState.update { state -> selectPlayableBankState(state, bankIndex) }
    }

    override fun ensurePlayablePadSelected() {
        mutableUiState.update(::ensurePlayablePadSelectedState)
    }

    override fun prepareDefaultChopDestination() {
        mutableUiState.update(::prepareDefaultMelodyChopDestination)
    }

    override fun capturePad(globalIndex: Int) {
        handlePadPress(globalIndex, PadSurfaceMode.CAPTURE)
    }

    override fun triggerPad(globalIndex: Int) {
        handlePadPress(globalIndex, PadSurfaceMode.PERFORMANCE)
    }

    /** Preview is a single-voice audition: retriggering never stacks two previews. */
    override fun previewPad(globalIndex: Int) {
        val pad = mutableUiState.value.pads.getOrNull(globalIndex) ?: return
        if (!pad.isAssigned) return
        stopCompetingPlayback()
        if (!preparePlaybackStart()) return
        engine.triggerPad(globalIndex)
    }

    private fun handlePadPress(globalIndex: Int, surfaceMode: PadSurfaceMode) {
        val state = mutableUiState.value
        val pad = state.pads.getOrNull(globalIndex) ?: return
        when (
            resolvePadPressAction(
                sourcePlaying = state.sourcePlaying,
                padAssigned = pad.isAssigned,
                surfaceMode = surfaceMode,
                pendingSourceCommand = state.pendingSourceCommand,
                recordingSession = state.recordingSession,
            )
        ) {
            PadPressAction.CAPTURE_CHOP -> {
                assignLiveChop(globalIndex)
                return
            }
            PadPressAction.BLOCKED_DURING_RECORDING -> {
                setStatus("${recordingKindLabel(state.recordingSession)}中はPADを鳴らしません。STOPしてから操作してください")
                return
            }
            PadPressAction.BLOCKED_DURING_SOURCE_TRANSITION -> {
                setStatus(
                    if (state.sourceUiPhase() == SourceUiPhase.STARTING) {
                        "元曲の再生準備中です。音が鳴ってからPADを叩いてください"
                    } else {
                        "元曲の停止処理中です。停止してからPADを叩いてください"
                    },
                )
                return
            }
            PadPressAction.SELECT_ONLY -> return
            PadPressAction.PLAY_ASSIGNED -> Unit
        }
        when (
            resolvePerformancePadPressAction(
                pad = pad,
                recordArmed = state.recordArmed,
                transportPlaying = state.transportPlaying,
            )
        ) {
            PerformancePadPressAction.TOGGLE_LOOP -> toggleBeatLoop(globalIndex)
            PerformancePadPressAction.TRIGGER_ONLY -> {
                if (!preparePlaybackStart()) return
                engine.triggerPad(globalIndex)
            }
            PerformancePadPressAction.TRIGGER_AND_RECORD_STEP -> {
                if (!preparePlaybackStart()) return
                engine.triggerPad(globalIndex)
                val step = liveRecordingStep(engine.currentStep)
                commitEdit { current ->
                    val currentPad = current.pads.getOrNull(globalIndex) ?: return@commitEdit current
                    val updated = current.activeSteps.recordPadStep(currentPad, step)
                    current.copy(activeSteps = updated)
                }
                syncPattern()
            }
        }
    }

    override fun releasePad(globalIndex: Int) {
        engine.releasePad(globalIndex)
    }

    override fun stopSourceForWorkspaceChange() {
        val state = mutableUiState.value
        val shouldStop = state.sourcePlaying ||
            state.pendingSourceCommand == PendingSourceCommand.START
        if (!shouldStop) return

        engine.stopSource()
        playbackInterruptionCoordinator.endPlaybackSession()
        mutableUiState.update { current ->
            val feedback = sourcePlaybackRequestFeedback(
                appliedPlaying = current.sourcePlaying,
                request = SourcePlaybackRequest.STOP,
            )
            current.copy(
                sourcePlaying = feedback.sourcePlaying,
                pendingSourceCommand = pendingSourceCommandAfterStopRequest(current.sourcePlaying),
                statusMessage = feedback.statusMessage,
            )
        }
    }

    override fun toggleSourcePlayback() {
        val state = mutableUiState.value
        val audio = state.currentAudio ?: run {
            setStatus("先に曲を読み込んでください")
            return
        }
        when (
            sourcePlaybackToggleAction(
                state.sourcePlaying,
                state.pendingSourceCommand == PendingSourceCommand.START,
            )
        ) {
            SourcePlaybackToggleAction.STOP -> {
                engine.stopSource()
                playbackInterruptionCoordinator.endPlaybackSession()
                mutableUiState.update { current ->
                    val feedback = sourcePlaybackRequestFeedback(
                        appliedPlaying = current.sourcePlaying,
                        request = SourcePlaybackRequest.STOP,
                    )
                    current.copy(
                        sourcePlaying = feedback.sourcePlaying,
                        pendingSourceCommand = pendingSourceCommandAfterStopRequest(current.sourcePlaying),
                        statusMessage = feedback.statusMessage,
                    )
                }
                return
            }
            SourcePlaybackToggleAction.START -> if (!preparePlaybackStart()) return
        }

        stopCompetingPlayback()
        val start = sourcePlaybackStartFrame(state.sourcePlayheadFrame, audio.frameCount)
        engine.playSource(audio, start, state.masterPitchSemitones)
        mutableUiState.update { current ->
            val feedback = sourcePlaybackRequestFeedback(
                appliedPlaying = current.sourcePlaying,
                request = SourcePlaybackRequest.START,
            )
            current.copy(
                sourcePlaying = feedback.sourcePlaying,
                pendingSourceCommand = PendingSourceCommand.START,
                transportPlaying = false,
                currentStep = -1,
                sourcePlayheadFrame = start,
                statusMessage = feedback.statusMessage,
            )
        }
    }

    override fun toggleChopPlayback() {
        val state = mutableUiState.value
        when (
            sourcePlaybackToggleAction(
                state.sourcePlaying,
                state.pendingSourceCommand == PendingSourceCommand.START,
            )
        ) {
            SourcePlaybackToggleAction.START -> restartSourcePlayback()
            SourcePlaybackToggleAction.STOP -> stopSourceForWorkspaceChange()
        }
    }

    override fun restartSourcePlayback() {
        val state = mutableUiState.value
        if (state.pendingSourceCommand == PendingSourceCommand.STOP) {
            setStatus("停止処理中です。音が止まってから再生してください")
            return
        }
        val audio = state.currentAudio ?: run {
            setStatus("先に曲を読み込んでください")
            return
        }
        if (!preparePlaybackStart()) return
        stopCompetingPlayback()
        engine.playSource(audio, 0, state.masterPitchSemitones)
        mutableUiState.update { current ->
            val feedback = sourcePlaybackRequestFeedback(
                appliedPlaying = current.sourcePlaying,
                request = SourcePlaybackRequest.RESTART,
            )
            current.copy(
                sourcePlaying = feedback.sourcePlaying,
                pendingSourceCommand = pendingSourceCommandAfterStartRequest(current.sourcePlaying),
                transportPlaying = false,
                currentStep = -1,
                sourcePlayheadFrame = 0,
                statusMessage = feedback.statusMessage,
            )
        }
    }

    override fun playSourceFrom(frame: Int) {
        val state = mutableUiState.value
        if (state.pendingSourceCommand == PendingSourceCommand.STOP) {
            setStatus("停止処理中です。音が止まってから位置を選んでください")
            return
        }
        val audio = state.currentAudio ?: run {
            setStatus("先に曲を読み込んでください")
            return
        }
        val safe = frame.coerceIn(0, audio.frameCount - 1)
        if (!preparePlaybackStart()) return
        stopCompetingPlayback()
        engine.playSource(audio, safe, state.masterPitchSemitones)
        mutableUiState.update { current ->
            val feedback = sourcePlaybackRequestFeedback(
                appliedPlaying = current.sourcePlaying,
                request = SourcePlaybackRequest.SEEK,
            )
            current.copy(
                sourcePlaying = feedback.sourcePlaying,
                pendingSourceCommand = pendingSourceCommandAfterStartRequest(current.sourcePlaying),
                transportPlaying = false,
                currentStep = -1,
                sourcePlayheadFrame = safe,
                statusMessage = feedback.statusMessage,
            )
        }
    }

    override fun seekSourcePlayback(frame: Int) {
        val state = mutableUiState.value
        val audio = state.currentAudio ?: return
        val safe = frame.coerceIn(0, audio.frameCount - 1)
        val shouldContinuePlayback = canRetargetActiveSource(state)
        if (shouldContinuePlayback) {
            engine.playSource(audio, safe, state.masterPitchSemitones)
        }
        mutableUiState.update { current ->
            current.copy(
                sourcePlayheadFrame = safe,
                pendingSourceCommand = pendingSourceCommandAfterPlaybackRetarget(
                    shouldContinuePlayback = shouldContinuePlayback,
                    appliedPlaying = current.sourcePlaying,
                    currentPendingCommand = current.pendingSourceCommand,
                ),
            )
        }
    }

    override fun setMasterPitch(value: Float) {
        val pitch = value.coerceIn(-12f, 12f)
        val state = mutableUiState.value
        val audio = state.currentAudio
        val frame = engine.currentSourceFrame.takeIf { it >= 0 } ?: state.sourcePlayheadFrame
        val shouldContinuePlayback = canRetargetActiveSource(state)
        if (shouldContinuePlayback && audio != null) {
            engine.playSource(audio, frame.coerceIn(0, audio.frameCount - 1), pitch)
        }
        commitEdit(mergeKey = "master-pitch") {
            it.copy(
                masterPitchSemitones = pitch,
                pendingSourceCommand = pendingSourceCommandAfterPlaybackRetarget(
                    shouldContinuePlayback = shouldContinuePlayback,
                    appliedPlaying = it.sourcePlaying,
                    currentPendingCommand = it.pendingSourceCommand,
                ),
            )
        }
    }

    fun clearVisibleChops() {
        val state = mutableUiState.value
        val bankStart = state.selectedBank * SamplerConfig.PADS_PER_BANK
        val bankEnd = bankStart + SamplerConfig.PADS_PER_BANK
        val clearedPads = (bankStart until bankEnd).map(::PadModel)
        commitEdit { current ->
            val pads = current.pads.toMutableList()
            clearedPads.forEach { pads[it.globalIndex] = it }
            current.copy(
                pads = pads,
                sliceMarkers = emptyList(),
                activeSliceIndex = null,
                activeSteps = current.activeSteps.filterNot { key ->
                    key / SamplerConfig.STEP_COUNT in bankStart until bankEnd
                }.toSet(),
                selectedPad = bankStart,
                loopingPadIndex = state.loopingPadIndex?.takeUnless { it in bankStart until bankEnd },
                loopPlayheadFrame = if (
                    state.loopingPadIndex?.let { it in bankStart until bankEnd } == true
                ) {
                    -1
                } else {
                    state.loopPlayheadFrame
                },
                statusMessage = "BANK ${state.selectedBank + 1} のチョップを消去しました",
            )
        }
        clearedPads.forEach(engine::updatePad)
        syncPattern()
    }

    private fun assignLiveChop(globalIndex: Int) {
        val state = mutableUiState.value
        val audio = state.currentAudio ?: return
        val overwriting = state.pads.getOrNull(globalIndex)?.isAssigned == true
        if (overwriting) {
            engine.stopPad(globalIndex)
            if (state.scratchingPadIndex == globalIndex) engine.endScratch()
        }
        val observedFrame = engine.currentSourceFrame
            .takeIf { it >= 0 }
            ?: state.sourcePlayheadFrame
        val playbackRate = 2.0.pow(state.masterPitchSemitones.toDouble() / 12.0)
        val latencyFrames = (audio.sampleRate * LIVE_CHOP_LATENCY_SECONDS * playbackRate).toInt()
        val startFrame = (observedFrame - latencyFrames).coerceAtLeast(state.rangeStartFrame)
        var changedPads: List<PadModel> = emptyList()
        commitEdit { current ->
            val result = assignLiveChopToPad(current, globalIndex, startFrame)
            changedPads = result.changedPads
            result.state
        }
        changedPads.forEach(engine::updatePad)
    }

    fun toggleAutoNext() {
        commitEdit { it.copy(autoNextPad = !it.autoNextPad) }
    }

    override fun setSelectedPadPitch(value: Float) = updateSelectedPad("pitch") {
        it.copy(pitchSemitones = value.coerceIn(-24f, 24f))
    }

    override fun setSelectedPadTone(value: Float) = updateSelectedPad("tone") {
        it.copy(tone = value.coerceIn(0f, 1f))
    }

    override fun setSelectedPadGain(value: Float) = updateSelectedPad("gain") {
        it.copy(gain = value.coerceIn(0f, 1.5f))
    }

    override fun setSelectedPadStartFrame(frame: Int) = updateSelectedPad("trim-start") {
        setPadTrimBoundary(it, PadTrimBoundary.START, frame)
    }

    override fun setSelectedPadEndFrame(frame: Int) = updateSelectedPad("trim-end") {
        setPadTrimBoundary(it, PadTrimBoundary.END, frame)
    }

    override fun restoreSelectedPadTrim(snapshot: PadTrimSnapshot) = updateSelectedPad {
        restorePadTrimSnapshot(it, snapshot)
    }

    override fun toggleSelectedPadReverse() = updateSelectedPad {
        it.copy(reverse = !it.reverse)
    }

    fun toggleSelectedBeatLoop() {
        toggleBeatLoop(mutableUiState.value.selectedPad)
    }

    override fun toggleBeatLoopControl() {
        val state = mutableUiState.value
        toggleBeatLoop(state.loopingPadIndex ?: state.selectedPad)
    }

    private fun toggleBeatLoop(globalIndex: Int) {
        val state = mutableUiState.value
        val pad = state.pads.getOrNull(globalIndex) ?: return
        if (!pad.isAssigned) {
            setStatus("先に音の入ったPADを選んでください")
            return
        }
        if (state.loopingPadIndex == globalIndex) {
            engine.stopPad(globalIndex)
            state.pads
                .vocalCompanionPadIndicesForLoopStart(loopPadIndex = globalIndex)
                .forEach(engine::stopPad)
            playbackInterruptionCoordinator.endPlaybackSession()
            mutableUiState.update {
                it.copy(
                    loopingPadIndex = null,
                    loopPlayheadFrame = -1,
                    statusMessage = "ビートループを停止しました",
                )
            }
            return
        }
        if (!preparePlaybackStart()) return

        stopCompetingPlayback()
        var loopPad = pad
        val changedPads = mutableListOf<PadModel>()
        commitEdit { current ->
            val pads = current.pads.map { candidate ->
                val updated = when {
                    candidate.globalIndex == globalIndex -> candidate.copy(playMode = PadPlayMode.LOOP)
                    candidate.playMode == PadPlayMode.LOOP -> candidate.copy(playMode = PadPlayMode.ONE_SHOT)
                    else -> candidate
                }
                if (updated != candidate) changedPads += updated
                updated
            }
            loopPad = pads[globalIndex]
            current.copy(pads = pads)
        }
        changedPads.forEach(engine::updatePad)
        syncPattern()
        engine.startPadLoop(globalIndex)
        mutableUiState.value.pads
            .vocalCompanionPadIndicesForLoopStart(loopPadIndex = globalIndex)
            .forEach(engine::triggerPad)
        mutableUiState.update {
            it.copy(
                pendingSourceCommand = pendingSourceCommandAfterStopRequest(it.sourcePlaying),
                loopingPadIndex = globalIndex,
                loopPlayheadFrame = if (loopPad.reverse) loopPad.endFrame - 1 else loopPad.startFrame,
                statusMessage = "${('A'.code + loopPad.bankIndex).toChar()}-%02d の音声全体をループ中"
                    .format(loopPad.indexInBank + 1),
            )
        }
    }

    override fun setSelectedPadChokeGroup(group: Int) = updateSelectedPad {
        it.copy(chokeGroup = group.coerceIn(0, 4))
    }

    override fun clearSelectedPad() {
        val selectedPad = mutableUiState.value.selectedPad
        if (mutableUiState.value.loopingPadIndex == selectedPad) {
            engine.stopPad(selectedPad)
        }
        var clearedPad: PadModel? = null
        commitEdit { state ->
            val mutablePads = state.pads.toMutableList()
            val empty = PadModel(state.selectedPad)
            mutablePads[state.selectedPad] = empty
            clearedPad = empty
            val filteredSteps = state.activeSteps.filterNot { key ->
                key / SamplerConfig.STEP_COUNT == state.selectedPad
            }.toSet()
            state.copy(
                pads = mutablePads,
                activeSteps = filteredSteps,
                loopingPadIndex = state.loopingPadIndex?.takeUnless { it == state.selectedPad },
                loopPlayheadFrame = if (state.loopingPadIndex == state.selectedPad) -1 else state.loopPlayheadFrame,
                statusMessage = "選択PADを消去しました",
            )
        }
        clearedPad?.let(engine::updatePad)
        syncPattern()
    }

    private fun updateSelectedPad(
        parameter: String? = null,
        transform: (PadModel) -> PadModel,
    ) {
        var changed: PadModel? = null
        val selectedPad = mutableUiState.value.selectedPad
        val mergeKey = parameter?.let { "pad-$selectedPad-$it" }
        commitEdit(mergeKey = mergeKey) { state ->
            val mutablePads = state.pads.toMutableList()
            val current = state.selectedPadModel()
            val updated = transform(current)
            mutablePads[state.selectedPad] = updated
            changed = updated
            state.copy(pads = mutablePads)
        }
        changed?.let(engine::updatePad)
    }

    override fun toggleStep(stepIndex: Int) {
        if (stepIndex !in 0 until SamplerConfig.STEP_COUNT) return
        val selectedPad = mutableUiState.value.selectedPadModel()
        patternStepBlockedMessage(selectedPad)?.let { message ->
            setStatus(message)
            return
        }
        commitEdit { state ->
            val pad = state.selectedPadModel()
            state.copy(activeSteps = state.activeSteps.togglePadStep(pad, stepIndex))
        }
        syncPattern()
    }

    override fun setBpm(value: Float) {
        commitEdit(mergeKey = "bpm") { it.copy(bpm = value.coerceIn(40f, 240f)) }
        syncPattern()
    }

    override fun setSwing(value: Float) {
        commitEdit(mergeKey = "swing") { it.copy(swing = value.coerceIn(50f, 75f)) }
        syncPattern()
    }

    override fun toggleTransport() {
        val playing = mutableUiState.value.transportPlaying
        if (playing) {
            engine.stopTransport()
            playbackInterruptionCoordinator.endPlaybackSession()
            mutableUiState.update { it.copy(transportPlaying = false, currentStep = -1) }
        } else {
            if (!preparePlaybackStart()) return
            val recordArmed = mutableUiState.value.recordArmed
            stopCompetingPlayback()
            syncPattern()
            engine.startTransport()
            mutableUiState.update {
                it.copy(
                    transportPlaying = true,
                    recordArmed = recordArmed,
                    pendingSourceCommand = pendingSourceCommandAfterStopRequest(it.sourcePlaying),
                )
            }
        }
    }

    override fun toggleRecordArm() {
        val state = mutableUiState.value
        if (state.recordArmed) {
            mutableUiState.update {
                it.copy(recordArmed = false, statusMessage = "RECを解除しました")
            }
            return
        }
        if (!preparePlaybackStart()) return
        if (!state.transportPlaying) {
            stopCompetingPlayback()
            syncPattern()
            engine.startTransport()
            mutableUiState.update {
                it.copy(
                    transportPlaying = true,
                    recordArmed = true,
                    pendingSourceCommand = pendingSourceCommandAfterStopRequest(it.sourcePlaying),
                    statusMessage = "REC待機中 — PADを叩くと現在のステップへ記録します",
                )
            }
        } else {
            mutableUiState.update {
                it.copy(
                    recordArmed = true,
                    statusMessage = "REC待機中 — PADを叩くと現在のステップへ記録します",
                )
            }
        }
    }

    override fun clearSelectedPadPattern() {
        commitEdit { state ->
            val filtered = state.activeSteps.clearPadSteps(state.selectedPad)
            state.copy(activeSteps = filtered, statusMessage = "選択PADのシーケンスを消去しました")
        }
        syncPattern()
    }

    override fun fillSelectedPadPattern(repeatGrid: RepeatGrid) {
        val selectedPad = mutableUiState.value.selectedPadModel()
        patternStepBlockedMessage(selectedPad)?.let { message ->
            setStatus(message)
            return
        }
        commitEdit { state ->
            state.copy(
                activeSteps = state.activeSteps.replacePadSteps(state.selectedPad, repeatGrid),
                statusMessage = "${repeatGrid.statusLabel}を BANK ${('A'.code + state.selectedBank).toChar()}-%02d に配置しました"
                    .format(state.selectedPadModel().indexInBank + 1),
            )
        }
        syncPattern()
    }

    private fun patternStepBlockedMessage(pad: PadModel): String? = when {
        pad.canUsePatternSteps() -> null
        !pad.isAssigned -> "先に音が入ったPADを選んでください"
        pad.playMode == PadPlayMode.LOOP ->
            "ビートループは音声全体を繰り返します。配置は別PADへ追加してください"
        pad.contentKind == PadContentKind.VOCAL -> "VOICE TAKE はビート開始時に1回だけ再生します"
        else -> "このPADはステップへ配置できません"
    }

    override fun clearAllPattern() {
        commitEdit { it.copy(activeSteps = emptySet(), statusMessage = "パターンを全消去しました") }
        syncPattern()
    }

    override fun stopAllSounds() {
        clearScratchRuntime()
        engine.stopAllPlayback()
        playbackInterruptionCoordinator.endPlaybackSession()
        mutableUiState.update(::stopAllPlaybackState)
    }

    fun exportPattern(destination: Uri) {
        val snapshot = mutableUiState.value
        if (!snapshot.activeSteps.hasAudiblePatternContent(snapshot.pads)) {
            setStatus("先にビートをループするか、配置プリセットで音を置いてください")
            return
        }

        viewModelScope.launch {
            mutableUiState.update {
                it.copy(isLoading = true, statusMessage = "4小節のWAVを書き出しています…")
            }
            val application = getApplication<Application>()
            val temporary = File(application.cacheDir, "choplab_export_${System.currentTimeMillis()}.wav")
            runCatching {
                val summary = withContext(Dispatchers.Default) {
                    PatternRenderer.renderToWav(
                        outputFile = temporary,
                        pads = snapshot.pads,
                        activeSteps = snapshot.activeSteps,
                        bpm = snapshot.bpm,
                        swing = snapshot.swing,
                        bars = EXPORT_BARS,
                    )
                }
                withContext(Dispatchers.IO) {
                    application.contentResolver.openOutputStream(destination, "w")?.use { output ->
                        temporary.inputStream().use { input -> input.copyTo(output) }
                    } ?: error("保存先を開けません")
                }
                summary
            }.onSuccess { summary ->
                mutableUiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = "WAV保存完了: ${summary.bars}小節 / ${"%.2f".format(summary.durationSeconds)}秒",
                    )
                }
            }.onFailure { throwable ->
                mutableUiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = throwable.message ?: "WAVを書き出せませんでした",
                    )
                }
            }
            temporary.delete()
        }
    }

    fun saveProject(destination: Uri) {
        val snapshot = mutableUiState.value
        val revision = productionSession.revision
        viewModelScope.launch {
            mutableUiState.update { it.copy(isLoading = true, statusMessage = "プロジェクトを保存しています…") }
            runCatching {
                withContext(Dispatchers.IO) {
                    val application = getApplication<Application>()
                    val verified = File(
                        application.cacheDir,
                        "project-save-${System.currentTimeMillis()}.choplab",
                    )
                    try {
                        verified.outputStream().buffered().use { output ->
                            ProjectArchiveCodec.write(snapshot, output)
                        }
                        verified.inputStream().buffered().use(ProjectArchiveCodec::read)
                        autosaveStore.save(snapshot, revision)
                        application.contentResolver.openOutputStream(destination, "w")?.use { output ->
                            verified.inputStream().buffered().use { input -> input.copyTo(output) }
                        } ?: error("保存先を開けません")
                    } finally {
                        verified.delete()
                    }
                }
            }.onSuccess {
                mutableUiState.update {
                    it.copy(isLoading = false, statusMessage = "検証済みプロジェクトを保存し、安全コピーも保持しました")
                }
            }.onFailure { throwable ->
                mutableUiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = throwable.message ?: "プロジェクトを保存できませんでした",
                    )
                }
            }
        }
    }

    fun loadProject(source: Uri) {
        if (mutableUiState.value.recordingSession.isActive) {
            setStatus("録音をSTOPして保存が終わってからプロジェクトを開いてください")
            return
        }
        val operation = projectOperations.begin()
        val revisionAtStart = productionSession.revision
        stopCompetingPlayback()
        playbackInterruptionCoordinator.endPlaybackSession()
        mutableUiState.update { it.copy(isLoading = true, statusMessage = "プロジェクトを開いています…") }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val application = getApplication<Application>()
                    application.contentResolver.openInputStream(source)?.use(ProjectArchiveCodec::read)
                        ?: error("プロジェクトを開けません")
                }
            }.onSuccess { restored ->
                if (productionSession.revision != revisionAtStart) {
                    projectOperations.completeIfCurrent(operation) {
                        mutableUiState.update {
                            it.copy(isLoading = false, statusMessage = "編集中のため読み込み結果を破棄しました")
                        }
                    }
                    return@onSuccess
                }
                projectOperations.completeIfCurrent(operation) {
                    val transition = productionSession.replaceProject(restored)
                    applyProjectState(
                        restored = transition.state,
                        message = "プロジェクトを開きました",
                        launchTarget = inferProjectLaunchTarget(
                            restored,
                            starterOnly = BuiltInDrumKits.hasUntouchedStarterDrums(restored),
                        ),
                    )
                    scheduleAutosave()
                }
            }.onFailure { throwable ->
                projectOperations.completeIfCurrent(operation) {
                    mutableUiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = throwable.message ?: "プロジェクトを開けませんでした",
                        )
                    }
                }
            }
        }
    }

    override fun undoEdit() {
        if (rejectEditWhileRecording()) return
        val transition = productionSession.undo(mutableUiState.value) ?: run {
            setStatus("戻せる操作はありません")
            return
        }
        applyProjectState(transition.state, "1つ前の操作へ戻しました")
        scheduleAutosave()
    }

    override fun redoEdit() {
        if (rejectEditWhileRecording()) return
        val transition = productionSession.redo(mutableUiState.value) ?: run {
            setStatus("やり直せる操作はありません")
            return
        }
        applyProjectState(transition.state, "操作をやり直しました")
        scheduleAutosave()
    }

    fun setStatus(message: String) {
        mutableUiState.update { it.copy(statusMessage = message) }
    }

    private fun commitEdit(
        mergeKey: String? = null,
        transform: (SamplerUiState) -> SamplerUiState,
    ) {
        if (rejectEditWhileRecording()) return
        val before = mutableUiState.value
        val after = transform(before)
        val transition = productionSession.applyEdit(before, after, mergeKey)
        if (transition.mutation == ProductionMutation.NONE) return
        mutableUiState.value = transition.state
        if (transition.persistenceRequired) scheduleAutosave()
    }

    private fun rejectEditWhileRecording(): Boolean {
        val session = mutableUiState.value.recordingSession
        if (editingRequestAllowedDuringRecording(session)) return false
        setStatus("${recordingKindLabel(session)}をSTOPしてから編集してください")
        return true
    }

    private fun applyProjectState(
        restored: SamplerUiState,
        message: String,
        launchTarget: ProjectLaunchTarget? = null,
    ) {
        val previous = mutableUiState.value
        clearScratchRuntime()
        engine.stopAllPlayback()
        playbackInterruptionCoordinator.endPlaybackSession()
        val restoredRuntimeState = restored.copy(
            isLoading = false,
            statusMessage = message,
            transportPlaying = false,
            recordArmed = false,
            currentStep = -1,
            recordingSession = previous.recordingSession,
            loopingPadIndex = null,
            loopPlayheadFrame = -1,
            scratchingPadIndex = null,
            scratchPlayheadFrame = -1,
            sourceScratchActive = false,
            projectLaunchTarget = launchTarget ?: previous.projectLaunchTarget,
            projectLaunchRevision = if (launchTarget != null) {
                nextProjectLaunchRevision()
            } else {
                previous.projectLaunchRevision
            },
            scratchSpeed = 0f,
            scratchReturnAvailable = false,
            canUndo = productionSession.canUndo,
            canRedo = productionSession.canRedo,
        )
        val stoppedRestoredState = preserveAppliedSourceTruthWhileStopping(
            previousState = previous,
            replacementState = restoredRuntimeState,
        )
        mutableUiState.value = stoppedRestoredState
        stoppedRestoredState.pads.forEach(engine::updatePad)
        syncPattern()
    }

    private fun scheduleAutosave() {
        val snapshot = mutableUiState.value
        val revision = productionSession.revision
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            val failure = withContext(Dispatchers.IO) {
                runCatching { autosaveStore.save(snapshot, revision) }.exceptionOrNull()
            }
            if (failure != null && productionSession.revision == revision) {
                mutableUiState.update {
                    it.copy(statusMessage = failure.message ?: "自動保存できませんでした")
                }
            }
        }
    }

    private fun recoverAutosave() {
        val operation = projectOperations.begin()
        val revisionAtStart = productionSession.revision
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { autosaveStore.loadWithRevision() } }
            if (productionSession.revision != revisionAtStart) return@launch
            projectOperations.completeIfCurrent(operation) {
                result.onSuccess { recovered ->
                    if (recovered != null) {
                        val restored = recovered.state
                        val transition = productionSession.replaceProject(
                            restored,
                            persistenceRequired = false,
                            recoveredRevision = recovered.revision,
                        )
                        applyProjectState(
                            restored = transition.state,
                            message = "前回の自動保存を復元しました",
                            launchTarget = inferProjectLaunchTarget(
                                restored,
                                starterOnly = BuiltInDrumKits.hasUntouchedStarterDrums(restored),
                            ),
                        )
                    } else {
                        val blank = completeAutosaveRecoveryWithoutProject(mutableUiState.value)
                        val starter = BuiltInDrumKits.installStarterKit(blank).copy(
                            projectLaunchTarget = ProjectLaunchTarget.CAPTURE,
                            projectLaunchRevision = nextProjectLaunchRevision(),
                            statusMessage = "新しい制作を準備しました — BANK BにDUSTY JAZZをセット済み",
                        )
                        val transition = productionSession.replaceProject(starter)
                        mutableUiState.value = transition.state
                        engine.updateAllPads(transition.state.pads)
                        syncPattern()
                        scheduleAutosave()
                    }
                }.onFailure { throwable ->
                    mutableUiState.update { failAutosaveRecovery(it, throwable.message) }
                }
            }
        }
    }

    private fun nextProjectLaunchRevision(): Long {
        projectLaunchRevision++
        return projectLaunchRevision
    }

    private fun resumeAfterScratch(target: ScratchReturnTarget): Boolean {
        val state = mutableUiState.value
        if (!scratchReturnTargetIsValid(target, state)) return false
        return when (target) {
            ScratchReturnTarget.None -> false
            ScratchReturnTarget.Transport -> {
                if (!preparePlaybackStart()) return false
                syncPattern()
                engine.startTransport()
                mutableUiState.update {
                    it.copy(
                        transportPlaying = true,
                        recordArmed = false,
                        currentStep = 0,
                        statusMessage = "スクラッチからビート再生へ戻りました",
                    )
                }
                true
            }
            is ScratchReturnTarget.PadLoop -> {
                val pad = state.pads[target.padIndex]
                if (!preparePlaybackStart()) return false
                engine.startPadLoop(target.padIndex)
                state.pads
                    .vocalCompanionPadIndicesForLoopStart(loopPadIndex = target.padIndex)
                    .forEach(engine::triggerPad)
                mutableUiState.update {
                    it.copy(
                        loopingPadIndex = target.padIndex,
                        loopPlayheadFrame = if (pad.reverse) pad.endFrame - 1 else pad.startFrame,
                        statusMessage = "スクラッチから${('A'.code + pad.bankIndex).toChar()}-%02dループへ戻りました"
                            .format(pad.indexInBank + 1),
                    )
                }
                true
            }
        }
    }

    private fun syncPattern() {
        val state = mutableUiState.value
        engine.setPattern(state.activeSteps.audibleStepKeys(state.pads), state.bpm, state.swing)
    }

    private fun observePlaybackCapture() {
        viewModelScope.launch {
            CaptureEventBus.state.collect { captureState ->
                when (captureState) {
                    PlaybackCaptureState.Idle -> Unit
                    PlaybackCaptureState.Recording -> {
                        val snapshot = mutableUiState.value
                        val active = snapshot.recordingSession as? RecordingSession.Active
                        if (discardNextSystemCaptureResult) {
                            val application = getApplication<Application>()
                            application.startService(PlaybackCaptureService.stopIntent(application))
                        } else if (active != null && active.kind != RecordingKind.SOURCE_SYSTEM_AUDIO) {
                            discardNextSystemCaptureResult = true
                            val application = getApplication<Application>()
                            application.startService(PlaybackCaptureService.stopIntent(application))
                            setStatus("別の録音セッションが動作中のため端末音声録音を停止しました")
                        } else if (snapshot.isLoading && snapshot.recordingSession == RecordingSession.Idle) {
                            engine.stopAllPlayback()
                            playbackInterruptionCoordinator.endPlaybackSession()
                            mutableUiState.update {
                                observeSystemAudioRecording(
                                    stopAllPlaybackState(it),
                                )
                            }
                        } else {
                            val operation = systemSourceCapture.ensureStarted()
                            projectOperations.completeIfCurrent(operation) {
                                mutableUiState.update(::observeSystemAudioRecording)
                            }
                        }
                    }
                    is PlaybackCaptureState.Completed -> {
                        if (discardNextSystemCaptureResult) {
                            discardNextSystemCaptureResult = false
                            runCatching { captureState.file.delete() }
                            mutableUiState.update(::finishDiscardedSystemAudioRecording)
                            CaptureEventBus.reset()
                            return@collect
                        }
                        val operation = systemSourceCapture.consumeOrBegin()
                        projectOperations.completeIfCurrent(operation) {
                            mutableUiState.update {
                                stopRecordingSession(it, RecordingKind.SOURCE_SYSTEM_AUDIO).copy(
                                    statusMessage = "端末音声録音を読み込んでいます…",
                                )
                            }
                        }
                        CaptureEventBus.reset()
                        loadAudio(
                            uri = Uri.fromFile(captureState.file),
                            operation = operation,
                            completedRecordingKind = RecordingKind.SOURCE_SYSTEM_AUDIO,
                            ownedCaptureFile = captureState.file,
                        )
                    }
                    is PlaybackCaptureState.Error -> {
                        if (discardNextSystemCaptureResult) {
                            discardNextSystemCaptureResult = false
                            mutableUiState.update(::finishDiscardedSystemAudioRecording)
                            CaptureEventBus.reset()
                            return@collect
                        }
                        val operation = systemSourceCapture.consumeOrBegin()
                        projectOperations.completeIfCurrent(operation) {
                            mutableUiState.update {
                                endRecordingSession(it, RecordingKind.SOURCE_SYSTEM_AUDIO).copy(
                                    statusMessage = captureState.message,
                                )
                            }
                        }
                        CaptureEventBus.reset()
                    }
                }
            }
        }
    }

    private fun pollTransportStep() {
        viewModelScope.launch {
            while (isActive) {
                val step = engine.currentStep
                val sourceFrame = engine.currentSourceFrame
                val sourceIsPlaying = engine.sourcePlaying
                val loopPad = engine.currentLoopPad.takeIf { it >= 0 }
                val loopFrame = engine.currentLoopFrame
                val scratchPad = engine.currentScratchPad.takeIf { it >= 0 }
                val scratchFrame = engine.currentScratchFrame
                val snapshot = mutableUiState.value
                val nextPendingSourceCommand = reconcilePendingSourceCommand(
                    pendingCommand = snapshot.pendingSourceCommand,
                    appliedPlaying = sourceIsPlaying,
                )
                val visibleStep = if (snapshot.transportPlaying) step else -1
                if (
                    snapshot.currentStep != visibleStep ||
                    snapshot.sourcePlaying != sourceIsPlaying ||
                    snapshot.pendingSourceCommand != nextPendingSourceCommand ||
                    (sourceFrame >= 0 && snapshot.sourcePlayheadFrame != sourceFrame) ||
                    snapshot.loopingPadIndex != loopPad ||
                    snapshot.loopPlayheadFrame != loopFrame ||
                    snapshot.scratchingPadIndex != scratchPad ||
                    snapshot.scratchPlayheadFrame != scratchFrame
                ) {
                    mutableUiState.update { state ->
                        state.copy(
                            currentStep = if (state.transportPlaying) step else -1,
                            sourcePlaying = sourceIsPlaying,
                            pendingSourceCommand = reconcilePendingSourceCommand(
                                pendingCommand = state.pendingSourceCommand,
                                appliedPlaying = sourceIsPlaying,
                            ),
                            sourcePlayheadFrame = sourceFrame.takeIf { it >= 0 }
                                ?: state.sourcePlayheadFrame,
                            loopingPadIndex = loopPad,
                            loopPlayheadFrame = loopFrame,
                            scratchingPadIndex = scratchPad,
                            scratchPlayheadFrame = scratchFrame,
                            statusMessage = sourcePlaybackAppliedStatusMessage(
                                previouslyApplied = state.sourcePlaying,
                                nowApplied = sourceIsPlaying,
                                currentMessage = state.statusMessage,
                            ),
                        )
                    }
                }
                delay(24L)
            }
        }
    }

    private fun minimumSliceFrames(sampleRate: Int): Int = minimumChopFrames(sampleRate)

    private fun snapToZeroCrossing(
        audio: PcmAudio,
        targetFrame: Int,
        lowerBound: Int,
        upperBound: Int,
    ): Int = snapFrameToZeroCrossing(audio, targetFrame, lowerBound, upperBound)

    private companion object {
        const val EXPORT_BARS = 4
        const val MAX_HISTORY_ENTRIES = 40
        const val AUTOSAVE_DELAY_MS = 900L
        const val LIVE_CHOP_LATENCY_SECONDS = 0.06
        const val STALE_CAPTURE_MAX_AGE_MS = 24L * 60L * 60L * 1_000L
    }

    override fun onCleared() {
        runCatching { microphoneRecorder.stop().onSuccess(captureTempFiles::deleteOwned) }
        runCatching {
            val application = getApplication<Application>()
            application.startService(PlaybackCaptureService.stopIntent(application))
        }
        playbackInterruptionCoordinator.close()
        engine.shutdown()
        super.onCleared()
    }
}
