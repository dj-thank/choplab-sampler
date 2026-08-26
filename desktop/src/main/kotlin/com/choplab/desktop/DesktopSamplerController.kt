package com.choplab.desktop

import com.choplab.desktop.audio.DesktopWavDecoder
import com.choplab.desktop.audio.DesktopMicrophoneRecorder
import com.choplab.desktop.audio.DesktopSystemAudioRecorder
import com.choplab.desktop.audio.DesktopAudioRecorder
import com.choplab.desktop.audio.DesktopSamplerAudioEngine
import com.choplab.desktop.audio.DesktopTransport
import com.choplab.desktop.audio.DesktopScratchPlayer
import com.choplab.desktop.persistence.DesktopProjectFiles
import com.choplab.sampler.persistence.AtomicProjectStore
import com.choplab.sampler.audio.AudioResourceLimits
import com.choplab.sampler.audio.BuiltInDrumKits
import com.choplab.sampler.audio.PatternRenderer
import com.choplab.sampler.audio.SCRATCH_GESTURE_IDLE_TIMEOUT_MS
import com.choplab.sampler.audio.normalizeScratchSpeed
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadTrimBoundary
import com.choplab.sampler.model.PadTrimSnapshot
import com.choplab.sampler.model.DrumKitApplyDecision
import com.choplab.sampler.model.RecordingKind
import com.choplab.sampler.model.RecordingPhase
import com.choplab.sampler.model.RecordingSession
import com.choplab.sampler.model.RepeatGrid
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.assignLiveChopToPad
import com.choplab.sampler.model.chokeLoopSessionTransition
import com.choplab.sampler.model.clearEveryPattern
import com.choplab.sampler.model.clearPadSteps
import com.choplab.sampler.model.replacePadSteps
import com.choplab.sampler.model.restorePadTrimSnapshot
import com.choplab.sampler.model.selectPlayableBank
import com.choplab.sampler.model.selectPlayablePad
import com.choplab.sampler.model.selectPlayablePadPage
import com.choplab.sampler.model.setPadTrimBoundary
import com.choplab.sampler.model.sliceRanges
import com.choplab.sampler.model.stopAllPlaybackState
import com.choplab.sampler.model.beginRecordingSession
import com.choplab.sampler.model.endRecordingSession
import com.choplab.sampler.model.failRecordingSession
import com.choplab.sampler.model.nextVocalPadIndex
import com.choplab.sampler.model.observeRecordingSession
import com.choplab.sampler.model.isActive
import com.choplab.sampler.model.editingRequestAllowedDuringRecording
import com.choplab.sampler.model.drumKitApplyDecision
import com.choplab.sampler.model.prepareDefaultMelodyChopDestination
import com.choplab.sampler.model.patternSequenceForExport
import com.choplab.sampler.model.patternSequenceForPlayback
import com.choplab.sampler.model.removePadFromEveryPattern
import com.choplab.sampler.model.replaceBankStepsAcrossPatterns
import com.choplab.sampler.model.togglePadStep
import com.choplab.sampler.model.audibleStepKeys
import com.choplab.sampler.model.stepKey
import com.choplab.sampler.model.vocalCompanionPadIndicesForLoopStart
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.sourceScratchRange
import com.choplab.sampler.model.PendingSourceCommand
import com.choplab.sampler.model.ProjectOperationEpoch
import com.choplab.sampler.model.ProjectLaunchTarget
import com.choplab.sampler.model.ProductionCommand
import com.choplab.sampler.model.ProductionEffect
import com.choplab.sampler.model.ProductionMutation
import com.choplab.sampler.model.ProductionSession
import com.choplab.sampler.model.ScratchReturnTarget
import com.choplab.sampler.model.inferProjectLaunchTarget
import com.choplab.sampler.model.ensurePlayablePadSelected as ensurePlayablePadSelectedState
import com.choplab.sampler.model.scratchReturnTargetIsValid
import com.choplab.sampler.model.selectScratchReturnTarget
import com.choplab.sampler.ui.SamplerDeckController
import com.choplab.sampler.ui.DocumentAction
import com.choplab.sampler.ui.documentCompletionMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * JVM shell for the shared deck. Audio/filesystem/permission work stays here;
 * all state shapes and editing operations come from the shared module.
 */
class DesktopSamplerController(
    private val player: DesktopSamplerAudioEngine,
    private val microphone: DesktopAudioRecorder = DesktopMicrophoneRecorder(),
    private val systemAudio: DesktopAudioRecorder = DesktopSystemAudioRecorder(),
    private val autosaveStore: AtomicProjectStore? = defaultAutosaveStore(),
    private val autosaveDelayMillis: Long = 900L,
    private val recoverAutosaveOnStart: Boolean = true,
    private val preserveAutosaveUntilInitialProjectReplacement: Boolean = false,
) : SamplerDeckController, AutoCloseable {
    private val mutableState = MutableStateFlow(
        SamplerUiState(
            isLoading = autosaveStore != null && recoverAutosaveOnStart,
            statusMessage = if (autosaveStore != null && recoverAutosaveOnStart) {
                "前回の制作を確認しています"
            } else {
                "音声を読み込むか録音してください"
            },
        ),
    )
    private val productionSession = ProductionSession(maxHistoryEntries = 40)
    private val projectOperations = ProjectOperationEpoch()
    private val recoveryOperations = ProjectOperationEpoch()
    private val statusOperations = ProjectOperationEpoch()
    private val sourceLoadOperations = ProjectOperationEpoch()
    internal var transportWorkerStarter: (Thread) -> Unit = Thread::start
    internal var recoveredHydrationAdmission: () -> Unit = {}
    private val transport = DesktopTransport(
        startWorker = { worker -> transportWorkerStarter(worker) },
        onStep = ::onTransportStep,
    )
    private val scratch = DesktopScratchPlayer()
    private val playbackMonitor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "ChopLab-Windows-Playback-Monitor").apply { isDaemon = true }
    }.also { executor ->
        executor.scheduleAtFixedRate(::observePlaybackPosition, 33L, 33L, TimeUnit.MILLISECONDS)
    }
    private val persistenceExecutor = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "ChopLab-Windows-Autosave").apply { isDaemon = true }
    }
    private val ioExecutor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ChopLab-Windows-Project-IO").apply { isDaemon = true }
    }
    private val autosaveLifecycleLock = Any()
    private var autosaveWork: AutosaveWork? = null
    private var startupRecoveryFuture: Future<*>? = null
    private var startupRecoveryOutcome = if (autosaveStore != null && recoverAutosaveOnStart) {
        StartupRecoveryOutcome.PENDING
    } else {
        StartupRecoveryOutcome.NOT_REQUESTED
    }
    private var startupRecoveryFailureRevision: Long? = null
    private var startupRecoveryDurableRevision: Long? = null
    private var closed = false
    private val sourcePlaybackLoadLock = ReentrantLock()
    @Volatile private var closePlayerAfterSourceLoad = false
    private var sourcePlayerClosed = false
    private val sourcePlaybackStateLock = Any()
    private var sourcePlaybackAvailability: SourcePlaybackAvailability = SourcePlaybackAvailability.Unavailable
    @Volatile private var scratchIdleFuture: ScheduledFuture<*>? = null
    @Volatile private var scratchReturnTarget: ScratchReturnTarget = ScratchReturnTarget.None
    private val projectLaunchRevision = AtomicLong(0L)
    val state: StateFlow<SamplerUiState> = mutableState.asStateFlow()

    init {
        if (autosaveStore != null && recoverAutosaveOnStart) {
            startupRecoveryFuture = recoverAutosave()
        } else {
            mutableState.value = freshProductionState()
        }
    }

    fun loadWav(file: File) {
        statusOperations.invalidate()
        val operation = projectOperations.begin()
        stopCompetingPlayback()
        mutableState.update { it.copy(isLoading = true, statusMessage = "${file.name}を解析しています") }
        ioExecutor.execute {
            runCatching { DesktopWavDecoder.decode(file) }
                .onSuccess { audio ->
                    projectOperations.completeIfCurrent(operation) {
                        recoveryOperations.invalidate()
                        val playbackFailure = loadSourcePcm(audio)
                        val next = BuiltInDrumKits.installStarterKit(
                            SamplerUiState(
                                statusMessage = playbackFailure?.let(::sourcePlaybackFailureMessage)
                                    ?: "${file.name}を読み込みました。チョップで音を切ってください",
                                currentAudio = audio,
                                rangeEndFrame = audio.frameCount,
                            ),
                        ).copy(
                            projectLaunchTarget = ProjectLaunchTarget.CHOP,
                            projectLaunchRevision = nextProjectLaunchRevision(),
                        )
                        mutableState.value = productionSession.replaceProject(next).state
                        scheduleAutosave()
                    }
                }
                .onFailure { error ->
                    projectOperations.completeIfCurrent(operation) {
                        mutableState.update { it.copy(isLoading = false, statusMessage = "WAV読込失敗: ${error.message ?: error.javaClass.simpleName}") }
                    }
                }
        }
    }

    fun toggleMicrophoneRecording() = toggleRecording(RecordingKind.SOURCE_MICROPHONE)
    fun toggleSystemAudioRecording() = toggleRecording(RecordingKind.SOURCE_SYSTEM_AUDIO)
    fun toggleVocalRecording() = toggleRecording(RecordingKind.VOCAL_OVERDUB)
    fun exportBeat() = setStatus("保存先を選択してください")
    fun exportBeat(outputFile: File) {
        val operation = statusOperations.begin()
        val snapshot = mutableState.value
        val exportSequence = snapshot.patternSequenceForExport().map { steps ->
            steps.audibleStepKeys(snapshot.pads)
        }
        mutableState.update { it.copy(isLoading = true, statusMessage = "4小節WAVを書き出しています") }
        ioExecutor.execute {
            runCatching {
                PatternRenderer.renderSequenceToWav(
                    outputFile = outputFile,
                    pads = snapshot.pads,
                    patternSequence = exportSequence,
                    bpm = snapshot.bpm,
                    swing = snapshot.swing,
                )
            }.onSuccess {
                statusOperations.completeIfCurrent(operation) {
                    mutableState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = documentCompletionMessage(
                                action = DocumentAction.EXPORT_WAV,
                                destinationName = outputFile.name,
                                detail = "4小節",
                            ),
                        )
                    }
                }
            }.onFailure { error ->
                statusOperations.completeIfCurrent(operation) {
                    mutableState.update { it.copy(isLoading = false, statusMessage = "WAV書き出し失敗: ${error.message ?: error.javaClass.simpleName}") }
                }
            }
        }
    }
    fun openProject(file: File) {
        statusOperations.invalidate()
        val operation = projectOperations.begin()
        stopCompetingPlayback()
        mutableState.update { it.copy(isLoading = true, statusMessage = "${file.name}を開いています") }
        ioExecutor.execute {
            runCatching { DesktopProjectFiles.load(file) }
                .onSuccess { restored ->
                    projectOperations.completeIfCurrent(operation) {
                        recoveryOperations.invalidate()
                        val transition = productionSession.replaceProject(restored)
                        applyHistoryState(
                            restored = transition.state,
                            message = "${file.name}を開きました",
                            launchTarget = inferProjectLaunchTarget(
                                restored,
                                starterOnly = BuiltInDrumKits.hasUntouchedStarterDrums(restored),
                            ),
                        )
                        scheduleAutosave()
                    }
                }
                .onFailure { error ->
                    projectOperations.completeIfCurrent(operation) {
                        mutableState.update { it.copy(isLoading = false, statusMessage = "制作読込失敗: ${error.message ?: error.javaClass.simpleName}") }
                    }
                }
        }
    }

    fun saveProject(file: File) {
        val operation = statusOperations.begin()
        val snapshot = mutableState.value
        mutableState.update { it.copy(isLoading = true, statusMessage = "制作を保存しています") }
        ioExecutor.execute {
            runCatching { DesktopProjectFiles.save(file, snapshot) }
                .onSuccess { written ->
                    statusOperations.completeIfCurrent(operation) {
                        mutableState.update {
                            it.copy(
                                isLoading = false,
                                statusMessage = documentCompletionMessage(
                                    action = DocumentAction.SAVE_PROJECT,
                                    destinationName = written.name,
                                ),
                            )
                        }
                    }
                }
                .onFailure { error ->
                    statusOperations.completeIfCurrent(operation) {
                        mutableState.update { it.copy(isLoading = false, statusMessage = "制作保存失敗: ${error.message ?: error.javaClass.simpleName}") }
                    }
                }
        }
    }

    private fun toggleRecording(kind: RecordingKind) {
        if (mutableState.value.isLoading) return setStatus("現在の処理が終わってから録音してください")
        val active = mutableState.value.recordingSession as? RecordingSession.Active
        if (active != null) {
            if (active.kind == kind) stopRecording(kind) else setStatus("別の録音を停止してから操作してください")
            return
        }
        val before = mutableState.value
        val prepared = beginRecordingSession(before, kind)
        if (!prepared.recordingSession.isActive) {
            setStatus("録音を開始できません")
            return
        }
        val vocalLoopPadIndex = if (kind == RecordingKind.VOCAL_OVERDUB) {
            before.loopingPadIndex
                ?.takeIf { index -> before.pads.getOrNull(index)?.isAssigned == true }
                ?: before.pads.firstOrNull { pad -> pad.isAssigned && pad.playMode == PadPlayMode.LOOP }?.globalIndex
                ?: return setStatus("先にビートPADを選び「ビートをループ」を開始してください")
        } else {
            null
        }
        if (vocalLoopPadIndex == null) stopAllSounds()
        val output = File(
            File(System.getProperty("java.io.tmpdir"), "ChopLab/recordings"),
            "${kind.name.lowercase()}-${Instant.now().toEpochMilli()}.wav",
        )
        mutableState.value = prepared
        recorderFor(kind).start(output).onSuccess {
            val loopPad = vocalLoopPadIndex?.let { index -> mutableState.value.pads[index] }
            val loopPlaybackFailure = if (loopPad != null) {
                stopAllSounds()
                runCatching { triggerPlayerPad(loopPad, forceLoop = true) }.exceptionOrNull()
            } else {
                null
            }
            if (loopPlaybackFailure != null) {
                stopRecordingAfterPlaybackFailure(kind, output, loopPlaybackFailure)
                return@onSuccess
            }
            mutableState.update {
                observeRecordingSession(it, kind).copy(
                    loopingPadIndex = loopPad?.globalIndex ?: it.loopingPadIndex,
                    loopPlayheadFrame = loopPad?.startFrame ?: it.loopPlayheadFrame,
                    statusMessage = if (loopPad != null) {
                        "声を録音中 — ヘッドホン推奨 / もう一度押すとテイクを保存"
                    } else {
                        "録音中です。停止ボタンで素材にします"
                    },
                )
            }
        }.onFailure { error ->
            mutableState.update { failRecordingSession(it, kind).copy(statusMessage = "録音開始失敗: ${error.message ?: error.javaClass.simpleName}") }
        }
    }

    private fun stopRecordingAfterPlaybackFailure(kind: RecordingKind, output: File, error: Throwable) {
        val message = "ビートを開始できないため声の録音を停止しました。Windowsの出力デバイスを確認してください: " +
            (error.message ?: error.javaClass.simpleName)
        mutableState.update { state ->
            state.copy(
                recordingSession = (state.recordingSession as? RecordingSession.Active)
                    ?.copy(phase = RecordingPhase.STOPPING)
                    ?: state.recordingSession,
                loopingPadIndex = null,
                loopPlayheadFrame = -1,
                statusMessage = message,
            )
        }
        ioExecutor.execute {
            val ownedFile = runCatching { recorderFor(kind).stop().getOrThrow() }.getOrNull()
            runCatching { ownedFile?.delete() }
            runCatching { output.delete() }
            mutableState.update {
                endRecordingSession(it, kind).copy(
                    loopingPadIndex = null,
                    loopPlayheadFrame = -1,
                    statusMessage = message,
                )
            }
        }
    }

    private fun stopRecording(kind: RecordingKind) {
        statusOperations.invalidate()
        val operation = projectOperations.begin()
        mutableState.update { it.copy(recordingSession = (it.recordingSession as? RecordingSession.Active)?.copy(phase = RecordingPhase.STOPPING) ?: it.recordingSession) }
        ioExecutor.execute {
            val stopped = recorderFor(kind).stop()
            val ownedFile = stopped.getOrNull()
            stopped
                .mapCatching { file -> file to DesktopWavDecoder.decode(file) }
                .onSuccess { (_, audio) ->
                    projectOperations.completeIfCurrent(operation) {
                        recoveryOperations.invalidate()
                        val current = mutableState.value
                        val next = if (kind == RecordingKind.VOCAL_OVERDUB) {
                            val target = current.pads.nextVocalPadIndex()
                            if (target == null) {
                                current.copy(statusMessage = "VOICE BANKが満杯です", recordingSession = RecordingSession.Idle)
                            } else {
                                current.copy(
                                    pads = current.pads.toMutableList().also { pads ->
                                        pads[target] = pads[target].copy(audio = audio, startFrame = 0, endFrame = audio.frameCount, contentKind = PadContentKind.VOCAL)
                                    },
                                    selectedBank = 3,
                                    selectedPad = target,
                                    statusMessage = "VOICE PAD ${target + 1}に録音しました",
                                    recordingSession = RecordingSession.Idle,
                                )
                            }
                        } else {
                            current.copy(
                                currentAudio = audio,
                                rangeStartFrame = 0,
                                rangeEndFrame = audio.frameCount,
                                sourcePlayheadFrame = 0,
                                projectLaunchTarget = ProjectLaunchTarget.CHOP,
                                projectLaunchRevision = nextProjectLaunchRevision(),
                                statusMessage = if (kind == RecordingKind.SOURCE_SYSTEM_AUDIO) {
                                    "端末音声録音を読み込みました。チョップで音を切ってください"
                                } else {
                                    "マイク録音を読み込みました。チョップで音を切ってください"
                                },
                                recordingSession = RecordingSession.Idle,
                            )
                        }
                        val transition = productionSession.applyEdit(current, next)
                        mutableState.value = transition.state
                        val playbackFailure = if (kind == RecordingKind.VOCAL_OVERDUB) {
                            runCatching { player.loadPcm(audio) }.exceptionOrNull()
                        } else {
                            loadSourcePcm(audio)
                        }
                        playbackFailure?.let { error ->
                            setStatus("録音は読込済みですが再生機器を開けません: ${error.message ?: error.javaClass.simpleName}")
                        }
                        scheduleAutosave()
                    }
                }
                .onFailure { error ->
                    projectOperations.completeIfCurrent(operation) {
                        mutableState.update {
                            endRecordingSession(it, kind).copy(
                                statusMessage = "録音停止または読込失敗: ${error.message ?: error.javaClass.simpleName}",
                            )
                        }
                    }
                }
            runCatching { ownedFile?.delete() }
        }
    }

    fun setStatus(message: String) = mutableState.update { it.copy(statusMessage = message) }

    override fun resetProject() {
        statusOperations.invalidate()
        projectOperations.invalidate()
        recoveryOperations.invalidate()
        sourceLoadOperations.invalidate()
        stopCompetingPlayback()
        synchronized(sourcePlaybackStateLock) {
            sourcePlaybackAvailability = SourcePlaybackAvailability.Unavailable
        }
        mutableState.value = productionSession.replaceProject(freshProductionState()).state
        scheduleAutosave()
    }

    override fun stopAllSounds() {
        scratchReturnTarget = ScratchReturnTarget.None
        scratchIdleFuture?.cancel(false)
        scratchIdleFuture = null
        transport.stop()
        scratch.stop()
        player.stopAll()
        mutableState.update {
            stopAllPlaybackState(it).copy(
                sourcePlaying = false,
                pendingSourceCommand = PendingSourceCommand.NONE,
            )
        }
    }

    override fun stopActiveRecording() {
        val active = mutableState.value.recordingSession as? RecordingSession.Active
        if (active == null) setStatus("録音は停止しています") else {
            stopRecording(active.kind)
        }
    }
    override fun dispatch(command: ProductionCommand) {
        val before = mutableState.value
        val plan = productionSession.planCommand(before, command)
        if (plan.mutation == ProductionMutation.NONE && plan.effects.isEmpty()) {
            productionSession.cancel(plan)
            return
        }

        val stopFailure = plan.effects
            .filterIsInstance<ProductionEffect.StopPad>()
            .firstNotNullOfOrNull { effect ->
                runCatching { player.stopPad(effect.index) }.exceptionOrNull()
            }
        if (stopFailure != null) {
            productionSession.cancel(plan)
            setStatus("PADを停止できないため編集を適用しませんでした: ${stopFailure.message ?: "不明なエラー"}")
            return
        }

        val transition = productionSession.commit(plan)
        mutableState.value = transition.state
        transition.effects.forEach { effect ->
            when (effect) {
                is ProductionEffect.StopPad -> Unit
                is ProductionEffect.RefreshPad -> Unit // Java Sound reads the PAD on its next trigger.
                ProductionEffect.RefreshPattern -> Unit // The desktop step callback reads current state.
            }
        }
        if (transition.persistenceRequired) {
            scheduleAutosave()
        }
    }
    override fun stopSourceForWorkspaceChange() = stopAllSounds()
    override fun ensurePlayablePadSelected() = mutableState.update(::ensurePlayablePadSelectedState)
    override fun prepareDefaultChopDestination() = commitEdit { prepareDefaultMelodyChopDestination(it) }
    override fun restartSourcePlayback() = playSourceFrom(0)

    override fun selectBank(index: Int) = mutableState.update { it.copy(selectedBank = index.coerceIn(0, 3), selectedPad = index.coerceIn(0, 3) * 32) }
    override fun selectPadPage(index: Int) = mutableState.update { it.copy(selectedPad = it.selectedBank * 32 + index.coerceIn(0, 1) * 16) }
    override fun selectPad(index: Int) = mutableState.update { state ->
        val safe = index.coerceIn(0, state.pads.lastIndex)
        state.copy(selectedPad = safe, selectedBank = safe / 32)
    }
    override fun selectPlayableBank(index: Int) = mutableState.update { selectPlayableBank(it, index) }
    override fun selectPlayablePadPage(index: Int) = mutableState.update { selectPlayablePadPage(it, index) }
    override fun selectPlayablePad(index: Int) = mutableState.update { selectPlayablePad(it, index) }

    override fun capturePad(index: Int) {
        val state = mutableState.value
        val observedFrame = if (state.sourcePlaying) player.sourceFramePosition() else state.sourcePlayheadFrame
        val start = observedFrame.coerceIn(0, (state.rangeEndFrame - 1).coerceAtLeast(0))
        commitEdit { assignLiveChopToPad(it, index, start).state }
    }

    override fun triggerPad(index: Int) {
        triggerPadAndReturnOwnership(index)
    }

    override fun triggerPadWithOwnership(index: Int): Long =
        triggerPadAndReturnOwnership(index) ?: 0L

    private fun triggerPadAndReturnOwnership(index: Int): Long? {
        val pad = mutableState.value.pads.getOrNull(index)
        if (pad?.isAssigned == true) {
            if (!stopChokedLoopSessionBeforeTrigger(index)) return null
            val ownership = triggerPlayerPad(pad)
            val beforeRecord = mutableState.value
            if (beforeRecord.recordArmed && beforeRecord.transportPlaying && beforeRecord.currentStep >= 0) {
                commitEdit { it.copy(activeSteps = it.activeSteps + stepKey(index, it.currentStep)) }
            }
            mutableState.update { it.copy(selectedPad = index, statusMessage = "PAD ${index + 1}を再生中です") }
            return ownership
        } else {
            selectPad(index)
            return null
        }
    }

    override fun releasePad(index: Int) {
        player.releasePad(index)
    }

    override fun releasePadIfOwned(index: Int, ownership: Long) {
        if (ownership != 0L) player.releasePadIfOwned(index, ownership)
    }

    private fun triggerPlayerPad(pad: PadModel, forceLoop: Boolean = false): Long =
        player.triggerPad(pad, forceLoop)

    private fun stopChokedLoopSessionBeforeTrigger(index: Int): Boolean {
        val transition = mutableState.value.chokeLoopSessionTransition(index)
        if (!transition.stopsLoopSession) return true
        val stopFailure = runCatching {
            transition.padIndicesToStop.forEach(player::stopPad)
        }.exceptionOrNull()
        if (stopFailure != null) {
            setStatus(
                "CHOKEでビートループを停止できないためPADを再生しませんでした: " +
                    (stopFailure.message ?: stopFailure.javaClass.simpleName),
            )
            return false
        }
        mutableState.value = transition.state
        return true
    }

    override fun previewPad(index: Int) {
        stopCompetingPlayback()
        triggerPad(index)
    }
    override fun playSourceFrom(frame: Int) {
        val state = mutableState.value
        if (state.currentAudio == null) return
        if (!sourcePlaybackIsReady()) return
        val safe = frame.coerceIn(0, state.rangeEndFrame)
        stopCompetingPlayback()
        val playbackFailure = runCatching { player.playFrom(safe) }.exceptionOrNull()
        if (playbackFailure != null) {
            publishSourcePlaybackFailure(playbackFailure)
            return
        }
        mutableState.update { it.copy(sourcePlayheadFrame = safe, sourcePlaying = true, statusMessage = "元曲を再生中です") }
    }
    override fun seekSourcePlayback(frame: Int) {
        if (mutableState.value.currentAudio != null && !sourcePlaybackIsReady()) return
        val safe = frame.coerceIn(0, mutableState.value.rangeEndFrame)
        val playbackFailure = runCatching { player.seekSource(safe) }.exceptionOrNull()
        if (playbackFailure != null) {
            publishSourcePlaybackFailure(playbackFailure)
            return
        }
        mutableState.update { it.copy(sourcePlayheadFrame = safe) }
    }
    override fun toggleSourcePlayback() = toggleSource(!mutableState.value.sourcePlaying)
    override fun toggleChopPlayback() = toggleSource(!mutableState.value.sourcePlaying)

    override fun beginScratch() {
        val state = mutableState.value
        val index = state.selectedPad.takeIf { state.pads.getOrNull(it)?.isAssigned == true }
            ?: state.loopingPadIndex
            ?: state.pads.firstOrNull(PadModel::isAssigned)?.globalIndex
        val pad = index?.let(state.pads::get)
        if (pad?.isAssigned != true) return setStatus("スクラッチするビートPADを選んでください")
        scratchReturnTarget = selectScratchReturnTarget(state)
        stopCompetingPlayback(preserveScratchReturn = true)
        runCatching {
            scratch.start(
                audio = requireNotNull(pad.audio),
                startFrame = pad.startFrame,
                endFrame = pad.endFrame,
                initialFrame = state.loopPlayheadFrame.takeIf { it in pad.startFrame until pad.endFrame } ?: pad.startFrame,
                pitchSemitones = pad.pitchSemitones,
                tone = pad.tone,
                gain = pad.gain,
                reverse = pad.reverse,
            )
        }.onSuccess {
            mutableState.update {
                it.copy(
                    transportPlaying = false,
                    currentStep = -1,
                    loopingPadIndex = null,
                    loopPlayheadFrame = -1,
                    scratchingPadIndex = index,
                    scratchPlayheadFrame = pad.startFrame,
                    sourceScratchActive = false,
                    scratchSpeed = 0f,
                    scratchReturnAvailable = scratchReturnTarget != ScratchReturnTarget.None,
                    statusMessage = "指を左右へ動かしてスクラッチ",
                )
            }
        }.onFailure { error ->
            scratchReturnTarget = ScratchReturnTarget.None
            setStatus("スクラッチ開始失敗: ${error.message ?: error.javaClass.simpleName}")
        }
    }

    override fun beginSourceScratch() {
        val state = mutableState.value
        val audio = state.currentAudio
        val range = state.sourceScratchRange()
        if (audio == null || range == null) return setStatus("先に元曲の波形でスクラッチ範囲を選んでください")
        scratchReturnTarget = selectScratchReturnTarget(state)
        stopCompetingPlayback(preserveScratchReturn = true)
        runCatching {
            scratch.start(audio, range.startFrame, range.endFrame, range.startFrame, state.masterPitchSemitones)
        }.onSuccess {
            mutableState.update {
                it.copy(
                    transportPlaying = false,
                    currentStep = -1,
                    sourcePlaying = false,
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
        }.onFailure { error ->
            scratchReturnTarget = ScratchReturnTarget.None
            setStatus("元曲スクラッチ開始失敗: ${error.message ?: error.javaClass.simpleName}")
        }
    }
    override fun updateScratchSpeed(speed: Float) {
        val normalized = normalizeScratchSpeed(speed)
        scratch.updateSpeed(normalized)
        mutableState.update { current ->
            if (current.scratchingPadIndex != null || current.sourceScratchActive) {
                current.copy(scratchSpeed = normalized)
            } else {
                current
            }
        }
        scratchIdleFuture?.cancel(false)
        scratchIdleFuture = playbackMonitor.schedule(
            {
                scratch.updateSpeed(0f)
                mutableState.update { current ->
                    if (current.scratchingPadIndex != null || current.sourceScratchActive) {
                        current.copy(scratchSpeed = 0f)
                    } else {
                        current
                    }
                }
            },
            SCRATCH_GESTURE_IDLE_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
    }
    override fun endScratch() {
        val active = mutableState.value.let { it.scratchingPadIndex != null || it.sourceScratchActive }
        if (!active) {
            scratchReturnTarget = ScratchReturnTarget.None
            scratchIdleFuture?.cancel(false)
            scratchIdleFuture = null
            return
        }
        scratchIdleFuture?.cancel(false)
        scratchIdleFuture = null
        scratch.stop()
        mutableState.update {
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
        scratchReturnTarget = ScratchReturnTarget.None
        resumeAfterScratch(target)
    }

    private fun toggleSource(shouldPlay: Boolean) {
        val state = mutableState.value
        if (state.currentAudio == null) return
        if (shouldPlay) {
            if (!sourcePlaybackIsReady()) return
            stopCompetingPlayback()
            val playbackFailure = runCatching { player.playFrom(state.sourcePlayheadFrame) }.exceptionOrNull()
            if (playbackFailure != null) {
                publishSourcePlaybackFailure(playbackFailure)
                return
            }
            mutableState.update { it.copy(sourcePlaying = true, statusMessage = "元曲を再生中です") }
        } else {
            val frame = player.sourceFramePosition().coerceIn(0, state.rangeEndFrame)
            player.stop()
            mutableState.update { it.copy(sourcePlaying = false, sourcePlayheadFrame = frame, statusMessage = "元曲を停止しました") }
        }
    }

    override fun setMasterPitch(value: Float) {
        val pitch = value.coerceIn(-24f, 24f)
        val wasPlaying = mutableState.value.sourcePlaying
        val frame = mutableState.value.sourcePlayheadFrame
        commitEdit("master-pitch") { it.copy(masterPitchSemitones = pitch) }
        mutableState.value.currentAudio?.let { audio ->
            val loadFailure = loadSourcePcm(audio, pitch)
            if (loadFailure != null) {
                runCatching { player.stop() }
                publishSourcePlaybackFailure(loadFailure)
            } else if (wasPlaying) {
                val playbackFailure = runCatching { player.playFrom(frame) }.exceptionOrNull()
                if (playbackFailure != null) publishSourcePlaybackFailure(playbackFailure)
            }
        }
    }
    override fun setSelectedPadPitch(value: Float) = updateSelected("pad-pitch") { it.copy(pitchSemitones = value.coerceIn(-24f, 24f)) }
    override fun setSelectedPadTone(value: Float) = updateSelected("pad-tone") { it.copy(tone = value.coerceIn(0f, 1f)) }
    override fun setSelectedPadGain(value: Float) = updateSelected("pad-gain") { it.copy(gain = value.coerceIn(0f, 1.5f)) }
    override fun setSelectedPadStartFrame(frame: Int) = updateSelected("pad-trim-start") { pad ->
        setPadTrimBoundary(pad, PadTrimBoundary.START, frame)
    }
    override fun setSelectedPadEndFrame(frame: Int) = updateSelected("pad-trim-end") { pad ->
        setPadTrimBoundary(pad, PadTrimBoundary.END, frame)
    }
    override fun restoreSelectedPadTrim(snapshot: PadTrimSnapshot) = updateSelected { pad ->
        restorePadTrimSnapshot(pad, snapshot)
    }
    override fun toggleSelectedPadReverse() = updateSelected { it.copy(reverse = !it.reverse) }
    override fun setSelectedPadChokeGroup(group: Int) = updateSelected { it.copy(chokeGroup = group.coerceIn(0, 4)) }
    override fun clearSelectedPad() {
        val selected = mutableState.value.selectedPad
        player.stopPad(selected)
        commitEdit { state ->
            state.removePadFromEveryPattern(selected).copy(
                pads = state.pads.toMutableList().also { it[selected] = PadModel(selected) },
                loopingPadIndex = state.loopingPadIndex?.takeUnless { it == selected },
                loopPlayheadFrame = if (state.loopingPadIndex == selected) -1 else state.loopPlayheadFrame,
                statusMessage = "選択PADを消去しました",
            )
        }
    }

    override fun fillSelectedPadPattern(grid: RepeatGrid) = commitEdit { state ->
        state.copy(activeSteps = state.activeSteps.replacePadSteps(state.selectedPad, grid))
    }
    override fun clearSelectedPadPattern() = commitEdit { it.copy(activeSteps = it.activeSteps.clearPadSteps(it.selectedPad)) }
    override fun toggleStep(step: Int) = commitEdit { state ->
        val pad = state.pads[state.selectedPad]
        state.copy(activeSteps = state.activeSteps.togglePadStep(pad, step))
    }
    override fun clearAllPattern() = commitEdit { it.clearEveryPattern() }
    override fun toggleBeatLoopControl() {
        val state = mutableState.value
        val index = state.loopingPadIndex ?: state.selectedPad
        val pad = state.pads.getOrNull(index)
        if (pad?.isAssigned != true) return setStatus("先に音の入ったPADを選んでください")
        if (state.loopingPadIndex == index) {
            player.stopPad(index)
            state.pads
                .vocalCompanionPadIndicesForLoopStart(loopPadIndex = index)
                .forEach(player::stopPad)
            mutableState.update { it.copy(loopingPadIndex = null, loopPlayheadFrame = -1, statusMessage = "ビートループを停止しました") }
            return
        }
        stopCompetingPlayback()
        var loopPad = pad
        commitEdit { current ->
            val pads = current.pads.map { candidate ->
                when {
                    candidate.globalIndex == index -> candidate.copy(playMode = PadPlayMode.LOOP)
                    candidate.playMode == PadPlayMode.LOOP -> candidate.copy(playMode = PadPlayMode.ONE_SHOT)
                    else -> candidate
                }
            }
            loopPad = pads[index]
            current.copy(pads = pads)
        }
        triggerPlayerPad(loopPad, forceLoop = true)
        mutableState.value.pads
            .vocalCompanionPadIndicesForLoopStart(loopPadIndex = index)
            .map(mutableState.value.pads::get)
            .forEach { triggerPlayerPad(it) }
        mutableState.update {
            it.copy(
                loopingPadIndex = index,
                loopPlayheadFrame = if (loopPad.reverse) loopPad.endFrame - 1 else loopPad.startFrame,
                statusMessage = "PAD ${index + 1}の音声全体をループ中です",
            )
        }
    }
    override fun toggleTransport() {
        val state = mutableState.value
        if (state.transportPlaying) {
            transport.stop()
            mutableState.update { it.copy(transportPlaying = false, currentStep = -1, statusMessage = "ビートを停止しました") }
        } else {
            stopCompetingPlayback()
            startTransport(
                statusMessage = "ビートを再生中です",
                failurePrefix = "ビート再生開始失敗",
            )
        }
    }
    override fun toggleRecordArm() = mutableState.update { it.copy(recordArmed = !it.recordArmed) }
    override fun undoEdit() {
        if (rejectEditWhileRecording()) return
        val transition = productionSession.undo(mutableState.value)
            ?: return setStatus("戻せる操作はありません")
        applyHistoryState(transition.state, "1つ前の操作へ戻しました")
        scheduleAutosave()
    }
    override fun redoEdit() {
        if (rejectEditWhileRecording()) return
        val transition = productionSession.redo(mutableState.value)
            ?: return setStatus("やり直せる操作はありません")
        applyHistoryState(transition.state, "操作をやり直しました")
        scheduleAutosave()
    }

    override fun applyBuiltInDrumKit(kitId: String, replaceExisting: Boolean) {
        val bankIndex = SamplerConfig.DRUM_BANK_INDEX
        if (
            drumKitApplyDecision(mutableState.value.pads) == DrumKitApplyDecision.CONFIRM_REPLACE &&
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
        mutableState.value.loopingPadIndex
            ?.takeIf { it in bankStart until bankEnd }
            ?.let(player::stopPad)
        commitEdit { state ->
            val pads = state.pads.toMutableList()
            replacement.forEach { pads[it.globalIndex] = it }
            state.replaceBankStepsAcrossPatterns(
                bankStart = bankStart,
                bankEndExclusive = bankEnd,
                selectedPatternReplacement = BuiltInDrumKits.starterPattern(kitId, bankIndex),
            ).copy(
                pads = pads,
                selectedBank = bankIndex,
                selectedPad = bankStart,
                selectedDrumKitId = kitId,
                loopingPadIndex = state.loopingPadIndex?.takeUnless { it in bankStart until bankEnd },
                loopPlayheadFrame = if (state.loopingPadIndex in bankStart until bankEnd) -1 else state.loopPlayheadFrame,
                statusMessage = "${BuiltInDrumKits.catalog.first { it.id == kitId }.name} を BANK B ドラムにセット",
            )
        }
    }

    override fun setBpm(value: Float) {
        commitEdit("bpm") { it.copy(bpm = value.coerceIn(40f, 240f)) }
        transport.updateTempo(mutableState.value.bpm, mutableState.value.swing)
    }
    override fun setSwing(value: Float) {
        commitEdit("swing") { it.copy(swing = value.coerceIn(50f, 75f)) }
        transport.updateTempo(mutableState.value.bpm, mutableState.value.swing)
    }

    private fun updateSelected(mergeKey: String? = null, transform: (PadModel) -> PadModel) {
        val selected = mutableState.value.selectedPad
        commitEdit(mergeKey) { state -> state.copy(pads = state.pads.toMutableList().also { it[state.selectedPad] = transform(it[state.selectedPad]) }) }
        val current = mutableState.value
        if (current.loopingPadIndex == selected && current.pads[selected].isAssigned) {
            player.stopPad(selected)
            triggerPlayerPad(current.pads[selected], forceLoop = true)
        }
    }

    private fun commitEdit(mergeKey: String? = null, transform: (SamplerUiState) -> SamplerUiState) {
        if (mutableState.value.isLoading) return setStatus("現在の処理が終わってから編集してください")
        if (rejectEditWhileRecording()) return
        val before = mutableState.value
        val after = transform(before)
        val transition = productionSession.applyEdit(before, after, mergeKey)
        if (transition.mutation == ProductionMutation.NONE) return
        mutableState.value = transition.state
        if (transition.persistenceRequired) scheduleAutosave()
    }

    private fun rejectEditWhileRecording(): Boolean {
        if (editingRequestAllowedDuringRecording(mutableState.value.recordingSession)) return false
        setStatus("録音をSTOPしてから編集してください")
        return true
    }

    private fun applyHistoryState(
        restored: SamplerUiState,
        message: String,
        launchTarget: ProjectLaunchTarget? = null,
        hydrateAudio: Boolean = true,
    ) {
        transport.stop()
        scratch.stop()
        scratchReturnTarget = ScratchReturnTarget.None
        scratchIdleFuture?.cancel(false)
        scratchIdleFuture = null
        player.stopAll()
        val next = restored.copy(
            statusMessage = message,
            transportPlaying = false,
            recordArmed = false,
            currentStep = -1,
            sourcePlaying = false,
            loopingPadIndex = null,
            loopPlayheadFrame = -1,
            scratchingPadIndex = null,
            scratchPlayheadFrame = -1,
            sourceScratchActive = false,
            projectLaunchTarget = launchTarget ?: mutableState.value.projectLaunchTarget,
            projectLaunchRevision = if (launchTarget != null) {
                nextProjectLaunchRevision()
            } else {
                mutableState.value.projectLaunchRevision
            },
            scratchSpeed = 0f,
            scratchReturnAvailable = false,
            canUndo = productionSession.canUndo,
            canRedo = productionSession.canRedo,
        )
        if (!hydrateAudio) {
            // Clear the old device state before publishing the recovered project. A state
            // observer may synchronously apply pitch and load the source while publication
            // is in progress; clearing after publication would erase that newer readiness.
            synchronized(sourcePlaybackStateLock) {
                sourcePlaybackAvailability = next.currentAudio?.let {
                    SourcePlaybackAvailability.Pending(next.statusMessage)
                } ?: SourcePlaybackAvailability.Unavailable
            }
        }
        mutableState.value = next
        if (hydrateAudio) {
            val playbackFailure = loadSourcePcm(next.currentAudio)
            if (playbackFailure != null) setStatus(sourcePlaybackFailureMessage(playbackFailure))
        }
    }

    private fun scheduleAutosave() {
        val store = autosaveStore ?: return
        val snapshot = AutosaveSnapshot(
            state = mutableState.value,
            revision = productionSession.revision,
        )
        synchronized(autosaveLifecycleLock) {
            if (closed) return
            autosaveWork
                ?.takeIf { it.phase == AutosavePhase.SCHEDULED }
                ?.let { pending ->
                    if (pending.future?.cancel(false) == true) {
                        pending.phase = AutosavePhase.CANCELLED
                    }
                }
            autosaveWork = enqueueAutosaveLocked(
                store = store,
                snapshot = snapshot,
                delayMillis = autosaveDelayMillis.coerceAtLeast(0L),
            )
        }
    }

    private fun enqueueAutosaveLocked(
        store: AtomicProjectStore,
        snapshot: AutosaveSnapshot,
        delayMillis: Long,
    ): AutosaveWork {
        val work = AutosaveWork(snapshot)
        work.future = persistenceExecutor.schedule(
            { executeAutosave(store, work) },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
        return work
    }

    private fun executeAutosave(store: AtomicProjectStore, work: AutosaveWork) {
        // Future.cancel(false) can succeed after its callable starts. Publish RUNNING
        // under the lifecycle lock before touching the store so close waits this body.
        val admitted = synchronized(autosaveLifecycleLock) {
            if (work.phase != AutosavePhase.SCHEDULED) {
                false
            } else {
                work.phase = AutosavePhase.RUNNING
                true
            }
        }
        if (!admitted) return
        try {
            work.savedSuccessfully = persistAutosave(store, work.snapshot.state)
        } finally {
            synchronized(autosaveLifecycleLock) {
                work.phase = AutosavePhase.COMPLETED
            }
        }
    }

    private fun persistAutosave(store: AtomicProjectStore, snapshot: SamplerUiState): Boolean =
        runCatching {
            store.save(snapshot)
            true
        }.getOrElse { error ->
            mutableState.update { current ->
                current.copy(statusMessage = "自動保存失敗: ${error.message ?: error.javaClass.simpleName}")
            }
            false
        }

    private fun flushAutosaveOnClose(request: AutosaveCloseRequest) {
        val store = autosaveStore ?: return
        if (
            preserveAutosaveUntilInitialProjectReplacement &&
            request.pending == null &&
            request.snapshot.revision == 0L
        ) {
            // An explicit startup path owns the first project replacement. Until that
            // replacement succeeds (or a real edit advances the revision), the fresh UI
            // is only a placeholder and must not replace an existing autosave on close.
            return
        }
        if (
            request.startupRecoveryOutcome == StartupRecoveryOutcome.FAILED &&
            request.pending == null &&
            request.snapshot.revision == request.startupRecoveryFailureRevision
        ) {
            // A recovery error publishes an empty UI placeholder, not a new project.
            // Keep the user's existing generations untouched unless a later edit owns work.
            return
        }
        if (
            request.startupRecoveryOutcome == StartupRecoveryOutcome.SUCCEEDED &&
            request.pending == null &&
            request.snapshot.revision == request.startupRecoveryDurableRevision
        ) {
            // Recovery was published without an editable mutation. The archive already owns
            // this content, so a no-op launch/close must not rotate distinct older generations.
            return
        }
        val workToAwait = synchronized(autosaveLifecycleLock) {
            when (request.pending?.phase) {
                AutosavePhase.SCHEDULED -> {
                    if (request.pending.future?.cancel(false) == true) {
                        request.pending.phase = AutosavePhase.CANCELLED
                        enqueueAutosaveLocked(store, request.snapshot, delayMillis = 0L).also {
                            autosaveWork = it
                        }
                    } else {
                        request.pending
                    }
                }
                AutosavePhase.RUNNING,
                AutosavePhase.COMPLETED,
                -> request.pending
                AutosavePhase.CANCELLED -> enqueueAutosaveLocked(
                    store = store,
                    snapshot = request.snapshot,
                    delayMillis = 0L,
                ).also { autosaveWork = it }
                null -> enqueueAutosaveLocked(
                    store = store,
                    snapshot = request.snapshot,
                    delayMillis = 0L,
                ).also { autosaveWork = it }
            }
        }
        awaitAutosave(workToAwait)

        // A project operation can publish a newer state while an older save is running,
        // then lose scheduling admission because close has already claimed the lifecycle.
        // Wait for the older body first, then persist the newer owned revision exactly once.
        val followUpRequired = !workToAwait.savedSuccessfully ||
            request.snapshot.revision > workToAwait.snapshot.revision
        if (!followUpRequired) return
        val followUp = synchronized(autosaveLifecycleLock) {
            enqueueAutosaveLocked(
                store = store,
                snapshot = request.snapshot,
                delayMillis = 0L,
            ).also { autosaveWork = it }
        }
        awaitAutosave(followUp)
    }

    private fun awaitAutosave(work: AutosaveWork?) {
        awaitPersistenceTask(work?.future)
    }

    private fun awaitPersistenceTask(completion: Future<*>?) {
        if (completion == null) return
        var interrupted = false
        while (true) {
            try {
                completion.get()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            } catch (_: Exception) {
                // Persistence tasks report their own failures through state.
                break
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun recoverAutosave(): Future<*> {
        val store = requireNotNull(autosaveStore)
        // Recovery owns a separate epoch before the controller escapes its constructor.
        // Project replacement invalidates it only after that replacement succeeds; failed
        // user loads therefore fall back to the still-recoverable startup snapshot.
        val recoveryOperation = recoveryOperations.begin()
        return persistenceExecutor.submit {
            var scheduleFreshAutosave = false
            var recoveryFailed = false
            val recoveredAudio = try {
                val recovered = store.loadWithRevision()
                if (recovered == null) {
                    recoveryOperations.completeIfCurrent(recoveryOperation) {
                        mutableState.value = productionSession.replaceProject(freshProductionState()).state
                        scheduleFreshAutosave = true
                    }
                    RecoveredAudioHydration()
                } else {
                    val restored = recovered.state
                    var audioToHydrate: PcmAudio? = null
                    var sourceLoadOperation: Long? = null
                    recoveryOperations.completeIfCurrent(recoveryOperation) {
                        val transition = productionSession.replaceProject(
                            restored,
                            persistenceRequired = false,
                            recoveredRevision = recovered.revision,
                        )
                        sourceLoadOperation = transition.state.currentAudio
                            ?.let { sourceLoadOperations.begin() }
                        applyHistoryState(
                            restored = transition.state,
                            message = "前回の自動保存を復元しました",
                            launchTarget = inferProjectLaunchTarget(
                                restored,
                                starterOnly = BuiltInDrumKits.hasUntouchedStarterDrums(restored),
                            ),
                            hydrateAudio = false,
                        )
                        audioToHydrate = transition.state.currentAudio
                        synchronized(autosaveLifecycleLock) {
                            startupRecoveryDurableRevision = transition.revision
                        }
                    }
                    RecoveredAudioHydration(
                        audio = audioToHydrate,
                        pitchSemitones = restored.masterPitchSemitones,
                        sourceLoadOperation = sourceLoadOperation,
                    )
                }
            } catch (error: Throwable) {
                recoveryOperations.completeIfCurrent(recoveryOperation) {
                    synchronized(autosaveLifecycleLock) {
                        startupRecoveryOutcome = StartupRecoveryOutcome.FAILED
                        startupRecoveryFailureRevision = productionSession.revision
                    }
                    mutableState.value = SamplerUiState(
                        statusMessage = "自動保存を復元できません: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
                recoveryFailed = true
                RecoveredAudioHydration()
            }
            if (!recoveryFailed) {
                synchronized(autosaveLifecycleLock) {
                    startupRecoveryOutcome = StartupRecoveryOutcome.SUCCEEDED
                }
                if (scheduleFreshAutosave) scheduleAutosave()
                scheduleRecoveredAudioHydration(recoveredAudio, recoveryOperation)
            }
        }
    }

    private fun scheduleRecoveredAudioHydration(
        hydration: RecoveredAudioHydration,
        recoveryOperation: Long,
    ) {
        val audio = hydration.audio ?: return
        val sourceLoadOperation = hydration.sourceLoadOperation ?: return
        synchronized(autosaveLifecycleLock) {
            if (closed) return
        }
        ioExecutor.execute {
            // Device opening must not run under the recovery epoch monitor: close and a
            // newer project action can revoke ownership without waiting for Windows audio.
            if (!recoveryOperations.isCurrent(recoveryOperation)) return@execute
            recoveredHydrationAdmission()
            val loadResult = loadSourcePcmIfCurrent(
                audio = audio,
                pitchSemitones = hydration.pitchSemitones,
                operation = sourceLoadOperation,
            )
            if (
                loadResult.applied &&
                loadResult.failure != null &&
                recoveryOperations.isCurrent(recoveryOperation)
            ) {
                setStatus(sourcePlaybackFailureMessage(requireNotNull(loadResult.failure)))
            }
        }
    }

    private fun loadSourcePcm(audio: PcmAudio?, pitchSemitones: Float = 0f): Throwable? {
        val operation = sourceLoadOperations.begin()
        return loadSourcePcmIfCurrent(audio, pitchSemitones, operation).failure
    }

    private fun loadSourcePcmIfCurrent(
        audio: PcmAudio?,
        pitchSemitones: Float,
        operation: Long,
    ): SourcePcmLoadResult {
        try {
            sourcePlaybackLoadLock.lockInterruptibly()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return SourcePcmLoadResult(applied = false)
        }
        try {
            if (!sourceLoadOperations.isCurrent(operation) || controllerIsClosed()) {
                return SourcePcmLoadResult(applied = false)
            }
            synchronized(sourcePlaybackStateLock) {
                val restoreStatus = (sourcePlaybackAvailability as? SourcePlaybackAvailability.Pending)
                    ?.restoreStatus
                    ?: mutableState.value.statusMessage
                sourcePlaybackAvailability = audio?.let {
                    SourcePlaybackAvailability.Pending(restoreStatus)
                } ?: SourcePlaybackAvailability.Unavailable
            }
            if (audio == null) {
                return SourcePcmLoadResult(applied = true)
            }
            val failure = runCatching { player.loadPcm(audio, pitchSemitones) }.exceptionOrNull()
            if (!sourceLoadOperations.isCurrent(operation) || controllerIsClosed()) {
                return SourcePcmLoadResult(applied = false, failure = failure)
            }
            synchronized(sourcePlaybackStateLock) {
                if (failure == null) {
                    val restoreStatus = (sourcePlaybackAvailability as? SourcePlaybackAvailability.Pending)
                        ?.restoreStatus
                    sourcePlaybackAvailability = SourcePlaybackAvailability.Ready
                    mutableState.update { current ->
                        if (
                            restoreStatus != null &&
                            current.statusMessage == SOURCE_PLAYBACK_PENDING_MESSAGE
                        ) {
                            current.copy(statusMessage = restoreStatus)
                        } else {
                            current
                        }
                    }
                } else {
                    sourcePlaybackAvailability = SourcePlaybackAvailability.Failed(
                        sourcePlaybackFailureMessage(failure),
                    )
                }
            }
            return SourcePcmLoadResult(applied = true, failure = failure)
        } finally {
            if (closePlayerAfterSourceLoad) closeSourcePlayerLocked()
            sourcePlaybackLoadLock.unlock()
        }
    }

    private fun controllerIsClosed(): Boolean = synchronized(autosaveLifecycleLock) { closed }

    private fun sourcePlaybackIsReady(): Boolean = synchronized(sourcePlaybackStateLock) {
        when (val availability = sourcePlaybackAvailability) {
            SourcePlaybackAvailability.Ready -> true
            is SourcePlaybackAvailability.Pending -> {
                mutableState.update {
                    it.copy(sourcePlaying = false, statusMessage = SOURCE_PLAYBACK_PENDING_MESSAGE)
                }
                false
            }
            is SourcePlaybackAvailability.Failed -> {
                mutableState.update {
                    it.copy(sourcePlaying = false, statusMessage = availability.message)
                }
                false
            }
            SourcePlaybackAvailability.Unavailable -> {
                mutableState.update {
                    it.copy(
                        sourcePlaying = false,
                        statusMessage = "音声を再生できません。Windowsの出力デバイスを確認してください",
                    )
                }
                false
            }
        }
    }

    private fun publishSourcePlaybackFailure(error: Throwable) {
        val message = sourcePlaybackFailureMessage(error)
        synchronized(sourcePlaybackStateLock) {
            sourcePlaybackAvailability = SourcePlaybackAvailability.Failed(message)
            mutableState.update {
                it.copy(sourcePlaying = false, statusMessage = message)
            }
        }
    }

    private fun sourcePlaybackFailureMessage(error: Throwable): String =
        "音声は読込済みですが再生機器を開けません: ${error.message ?: error.javaClass.simpleName}"

    private fun onTransportStep(barIndex: Int, step: Int) {
        val snapshot = mutableState.value
        if (!snapshot.transportPlaying) return
        mutableState.update { current -> if (current.transportPlaying) current.copy(currentStep = step) else current }
        val sequence = snapshot.patternSequenceForPlayback()
        val audible = sequence[barIndex % sequence.size].audibleStepKeys(snapshot.pads)
        snapshot.pads.forEach { pad ->
            if (stepKey(pad.globalIndex, step) in audible) triggerPlayerPad(pad)
        }
    }

    private fun stopCompetingPlayback(
        preserveScratchReturn: Boolean = false,
        stopAudioEngine: Boolean = true,
    ) {
        if (!preserveScratchReturn) scratchReturnTarget = ScratchReturnTarget.None
        scratchIdleFuture?.cancel(false)
        scratchIdleFuture = null
        transport.stop()
        scratch.stop()
        if (stopAudioEngine) player.stopAll()
        mutableState.update {
            it.copy(
                transportPlaying = false,
                currentStep = -1,
                sourcePlaying = false,
                pendingSourceCommand = PendingSourceCommand.NONE,
                loopingPadIndex = null,
                loopPlayheadFrame = -1,
                scratchingPadIndex = null,
                scratchPlayheadFrame = -1,
                sourceScratchActive = false,
                scratchSpeed = 0f,
                scratchReturnAvailable = false,
            )
        }
    }

    private fun freshProductionState(): SamplerUiState = BuiltInDrumKits.installStarterKit(
        SamplerUiState(
            statusMessage = "新しい制作を準備しました — BANK BにDUSTY JAZZをセット済み",
        ),
    ).copy(
        projectLaunchTarget = ProjectLaunchTarget.CAPTURE,
        projectLaunchRevision = nextProjectLaunchRevision(),
    )

    private fun nextProjectLaunchRevision(): Long = projectLaunchRevision.incrementAndGet()

    internal fun resumeAfterScratch(target: ScratchReturnTarget): Boolean {
        val current = mutableState.value
        if (!scratchReturnTargetIsValid(target, current)) return false
        return when (target) {
            ScratchReturnTarget.None -> false
            ScratchReturnTarget.Transport -> startTransport(
                statusMessage = "スクラッチからビート再生へ戻りました",
                failurePrefix = "スクラッチ後のビート再開失敗",
                disarmRecording = true,
            )
            is ScratchReturnTarget.PadLoop -> {
                val pad = current.pads[target.padIndex]
                runCatching {
                    triggerPlayerPad(pad, forceLoop = true)
                    current.pads
                        .vocalCompanionPadIndicesForLoopStart(loopPadIndex = target.padIndex)
                        .map(current.pads::get)
                        .forEach { triggerPlayerPad(it, forceLoop = false) }
                }.onSuccess {
                    mutableState.update {
                        it.copy(
                            loopingPadIndex = target.padIndex,
                            loopPlayheadFrame = if (pad.reverse) pad.endFrame - 1 else pad.startFrame,
                            statusMessage = "スクラッチから${('A'.code + pad.bankIndex).toChar()}-%02dループへ戻りました"
                                .format(pad.indexInBank + 1),
                        )
                    }
                }.onFailure {
                    setStatus("スクラッチ後のループ再開失敗: ${it.message ?: it.javaClass.simpleName}")
                }.isSuccess
            }
        }
    }

    private fun startTransport(
        statusMessage: String,
        failurePrefix: String,
        disarmRecording: Boolean = false,
    ): Boolean {
        val current = mutableState.value
        val recordArmedBeforeStart = current.recordArmed
        return runCatching {
            transport.start(current.bpm, current.swing) {
                mutableState.update {
                    it.copy(
                        transportPlaying = true,
                        recordArmed = if (disarmRecording) false else it.recordArmed,
                        currentStep = 0,
                        statusMessage = statusMessage,
                    )
                }
            }
        }.onFailure { error ->
            transport.stop()
            mutableState.update {
                it.copy(
                    transportPlaying = false,
                    recordArmed = if (disarmRecording) recordArmedBeforeStart else it.recordArmed,
                    currentStep = -1,
                    statusMessage = "$failurePrefix: ${error.message ?: error.javaClass.simpleName}",
                )
            }
        }.isSuccess
    }

    private fun observePlaybackPosition() {
        val snapshot = mutableState.value
        if (snapshot.sourcePlaying) {
            val frame = player.sourceFramePosition().coerceIn(0, snapshot.rangeEndFrame)
            val running = player.isSourcePlaying
            mutableState.update { current ->
                if (!current.sourcePlaying) {
                    current
                } else {
                    current.copy(
                        sourcePlayheadFrame = frame,
                        sourcePlaying = running,
                        statusMessage = if (running) current.statusMessage else "元曲の再生が終わりました",
                    )
                }
            }
        }
        val scratchFrame = scratch.currentFrame
        if ((snapshot.scratchingPadIndex != null || snapshot.sourceScratchActive) && scratchFrame >= 0) {
            mutableState.update { current -> current.copy(scratchPlayheadFrame = scratchFrame) }
        }
        snapshot.loopingPadIndex?.let { index ->
            player.padFramePosition(index)?.let { frame ->
                mutableState.update { current ->
                    if (current.loopingPadIndex == index) current.copy(loopPlayheadFrame = frame) else current
                }
            }
        }
    }

    private fun recorderFor(kind: RecordingKind): DesktopAudioRecorder = when (kind) {
        RecordingKind.SOURCE_SYSTEM_AUDIO -> systemAudio
        RecordingKind.SOURCE_MICROPHONE,
        RecordingKind.VOCAL_OVERDUB,
        -> microphone
    }

    override fun close() {
        val recoveryToAwait = synchronized(autosaveLifecycleLock) {
            if (closed) return
            closed = true
            startupRecoveryFuture
        }
        sourceLoadOperations.invalidate()
        closePlayerAfterSourceLoad = true
        // Recovery owns the persistence executor before any close-time save. Wait for
        // its state publication so an initial loading snapshot cannot supersede it.
        awaitPersistenceTask(recoveryToAwait)
        // completeIfCurrent owns the project-operation monitor for its entire publication.
        // Invalidate and wait for any admitted completion before taking the close snapshot.
        projectOperations.invalidate()
        recoveryOperations.invalidate()
        statusOperations.invalidate()
        val closeRequest = synchronized(autosaveLifecycleLock) {
            AutosaveCloseRequest(
                pending = autosaveWork,
                snapshot = AutosaveSnapshot(
                    state = mutableState.value,
                    revision = productionSession.revision,
                ),
                startupRecoveryOutcome = startupRecoveryOutcome,
                startupRecoveryFailureRevision = startupRecoveryFailureRevision,
                startupRecoveryDurableRevision = startupRecoveryDurableRevision,
            )
        }
        runCatching { ioExecutor.shutdownNow() }
        runCatching { playbackMonitor.shutdownNow() }
        runCatching { stopCompetingPlayback(stopAudioEngine = false) }
        runCatching { transport.close() }
        runCatching { scratch.close() }
        runCatching { microphone.close() }
        runCatching { systemAudio.close() }
        if (sourcePlaybackLoadLock.tryLock()) {
            try {
                closeSourcePlayerLocked()
            } finally {
                sourcePlaybackLoadLock.unlock()
            }
        }
        try {
            flushAutosaveOnClose(closeRequest)
        } finally {
            runCatching { persistenceExecutor.shutdownNow() }
        }
    }

    private fun closeSourcePlayerLocked() {
        if (sourcePlayerClosed) return
        runCatching { player.stopAll() }
        runCatching { player.close() }
        sourcePlayerClosed = true
    }

    private sealed interface SourcePlaybackAvailability {
        object Unavailable : SourcePlaybackAvailability
        object Ready : SourcePlaybackAvailability
        data class Pending(val restoreStatus: String) : SourcePlaybackAvailability
        data class Failed(val message: String) : SourcePlaybackAvailability
    }

    private enum class AutosavePhase {
        SCHEDULED,
        RUNNING,
        COMPLETED,
        CANCELLED,
    }

    private enum class StartupRecoveryOutcome {
        NOT_REQUESTED,
        PENDING,
        SUCCEEDED,
        FAILED,
    }

    private data class AutosaveSnapshot(
        val state: SamplerUiState,
        val revision: Long,
    )

    private data class RecoveredAudioHydration(
        val audio: PcmAudio? = null,
        val pitchSemitones: Float = 0f,
        val sourceLoadOperation: Long? = null,
    )

    private data class SourcePcmLoadResult(
        val applied: Boolean,
        val failure: Throwable? = null,
    )

    private class AutosaveWork(
        val snapshot: AutosaveSnapshot,
        var phase: AutosavePhase = AutosavePhase.SCHEDULED,
        var future: ScheduledFuture<*>? = null,
        var savedSuccessfully: Boolean = false,
    )

    private data class AutosaveCloseRequest(
        val pending: AutosaveWork?,
        val snapshot: AutosaveSnapshot,
        val startupRecoveryOutcome: StartupRecoveryOutcome,
        val startupRecoveryFailureRevision: Long?,
        val startupRecoveryDurableRevision: Long?,
    )

    companion object {
        private const val SOURCE_PLAYBACK_PENDING_MESSAGE = "音声の再生を準備しています"

        internal fun defaultAutosaveStore(): AtomicProjectStore {
            val root = System.getenv("LOCALAPPDATA")
                ?.takeIf(String::isNotBlank)
                ?.let(::File)
                ?: File(System.getProperty("user.home"), "AppData/Local")
            return AtomicProjectStore(
                directory = File(root, "ChopLab/projects"),
                maxResidentPcmBytes = AudioResourceLimits.MAX_DESKTOP_PROJECT_PCM_BYTES,
            )
        }
    }
}
