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
import com.choplab.sampler.model.EditHistory
import com.choplab.sampler.model.DrumKitApplyDecision
import com.choplab.sampler.model.RecordingKind
import com.choplab.sampler.model.RecordingPhase
import com.choplab.sampler.model.RecordingSession
import com.choplab.sampler.model.RepeatGrid
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.assignLiveChopToPad
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
import com.choplab.sampler.model.togglePadStep
import com.choplab.sampler.model.audibleStepKeys
import com.choplab.sampler.model.stepKey
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.sourceScratchRange
import com.choplab.sampler.model.PendingSourceCommand
import com.choplab.sampler.model.ProjectOperationEpoch
import com.choplab.sampler.model.ProjectLaunchTarget
import com.choplab.sampler.model.ScratchReturnTarget
import com.choplab.sampler.model.inferProjectLaunchTarget
import com.choplab.sampler.model.scratchReturnTargetIsValid
import com.choplab.sampler.model.selectScratchReturnTarget
import com.choplab.sampler.ui.SamplerDeckController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.atomic.AtomicLong

/**
 * JVM shell for the shared deck. Audio/filesystem/permission work stays here;
 * all state shapes and editing operations come from the shared module.
 */
class DesktopSamplerController(
    private val player: DesktopSamplerAudioEngine,
    private val microphone: DesktopMicrophoneRecorder = DesktopMicrophoneRecorder(),
    private val systemAudio: DesktopSystemAudioRecorder = DesktopSystemAudioRecorder(),
    private val autosaveStore: AtomicProjectStore? = defaultAutosaveStore(),
    private val autosaveDelayMillis: Long = 900L,
) : SamplerDeckController, AutoCloseable {
    private val mutableState = MutableStateFlow(
        SamplerUiState(
            isLoading = autosaveStore != null,
            statusMessage = if (autosaveStore != null) "前回の制作を確認しています" else "音声を読み込むか録音してください",
        ),
    )
    private val editHistory = EditHistory(maxEntries = 40)
    private val projectOperations = ProjectOperationEpoch()
    private val transport = DesktopTransport(::onTransportStep)
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
    @Volatile private var autosaveFuture: ScheduledFuture<*>? = null
    @Volatile private var scratchIdleFuture: ScheduledFuture<*>? = null
    @Volatile private var scratchReturnTarget: ScratchReturnTarget = ScratchReturnTarget.None
    private val projectLaunchRevision = AtomicLong(0L)
    val state: StateFlow<SamplerUiState> = mutableState.asStateFlow()

    init {
        if (autosaveStore != null) {
            recoverAutosave()
        } else {
            mutableState.value = freshProductionState()
        }
    }

    fun loadWav(file: File) {
        val operation = projectOperations.begin()
        stopCompetingPlayback()
        mutableState.update { it.copy(isLoading = true, statusMessage = "${file.name}を解析しています") }
        ioExecutor.execute {
            runCatching { DesktopWavDecoder.decode(file) }
                .onSuccess { audio ->
                    projectOperations.completeIfCurrent(operation) {
                        editHistory.reset()
                        val playbackFailure = runCatching { player.loadPcm(audio) }.exceptionOrNull()
                        mutableState.value = BuiltInDrumKits.installStarterKit(
                            SamplerUiState(
                                statusMessage = playbackFailure?.let { "音声は読込済みですが再生機器を開けません: ${it.message}" }
                                    ?: "${file.name}を読み込みました。チョップで音を切ってください",
                                currentAudio = audio,
                                rangeEndFrame = audio.frameCount,
                            ),
                        ).copy(
                            projectLaunchTarget = ProjectLaunchTarget.CHOP,
                            projectLaunchRevision = nextProjectLaunchRevision(),
                        )
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
        val operation = projectOperations.begin()
        val snapshot = mutableState.value
        mutableState.update { it.copy(isLoading = true, statusMessage = "4小節WAVを書き出しています") }
        ioExecutor.execute {
            runCatching {
                PatternRenderer.renderToWav(outputFile, snapshot.pads, snapshot.activeSteps, snapshot.bpm, snapshot.swing)
            }.onSuccess {
                projectOperations.completeIfCurrent(operation) {
                    mutableState.update { it.copy(isLoading = false, statusMessage = "${outputFile.name}を書き出しました") }
                }
            }.onFailure { error ->
                projectOperations.completeIfCurrent(operation) {
                    mutableState.update { it.copy(isLoading = false, statusMessage = "WAV書き出し失敗: ${error.message ?: error.javaClass.simpleName}") }
                }
            }
        }
    }
    fun openProject(file: File) {
        val operation = projectOperations.begin()
        stopCompetingPlayback()
        mutableState.update { it.copy(isLoading = true, statusMessage = "${file.name}を開いています") }
        ioExecutor.execute {
            runCatching { DesktopProjectFiles.load(file) }
                .onSuccess { restored ->
                    projectOperations.completeIfCurrent(operation) {
                        editHistory.reset()
                        applyHistoryState(
                            restored = restored,
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
        val operation = projectOperations.begin()
        val snapshot = mutableState.value
        mutableState.update { it.copy(isLoading = true, statusMessage = "制作を保存しています") }
        ioExecutor.execute {
            runCatching { DesktopProjectFiles.save(file, snapshot) }
                .onSuccess { written ->
                    projectOperations.completeIfCurrent(operation) {
                        mutableState.update { it.copy(isLoading = false, statusMessage = "${written.name}を保存しました") }
                    }
                }
                .onFailure { error ->
                    projectOperations.completeIfCurrent(operation) {
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
        if (!beginRecordingSession(before, kind).recordingSession.isActive) {
            setStatus("録音を開始できません")
            return
        }
        stopAllSounds()
        val output = File(
            File(System.getProperty("java.io.tmpdir"), "ChopLab/recordings"),
            "${kind.name.lowercase()}-${Instant.now().toEpochMilli()}.wav",
        )
        mutableState.value = beginRecordingSession(mutableState.value, kind)
        recorderFor(kind).start(output).onSuccess {
            mutableState.update { observeRecordingSession(it, kind).copy(statusMessage = "録音中です。停止ボタンで素材にします") }
        }.onFailure { error ->
            mutableState.update { failRecordingSession(it, kind).copy(statusMessage = "録音開始失敗: ${error.message ?: error.javaClass.simpleName}") }
        }
    }

    private fun stopRecording(kind: RecordingKind) {
        val operation = projectOperations.begin()
        mutableState.update { it.copy(recordingSession = (it.recordingSession as? RecordingSession.Active)?.copy(phase = RecordingPhase.STOPPING) ?: it.recordingSession) }
        ioExecutor.execute {
            val stopped = recorderFor(kind).stop()
            val ownedFile = stopped.getOrNull()
            stopped
                .mapCatching { file -> file to DesktopWavDecoder.decode(file) }
                .onSuccess { (_, audio) ->
                    projectOperations.completeIfCurrent(operation) {
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
                                statusMessage = if (kind == RecordingKind.SOURCE_SYSTEM_AUDIO) {
                                    "端末音声録音を読み込みました。チョップで音を切ってください"
                                } else {
                                    "マイク録音を読み込みました。チョップで音を切ってください"
                                },
                                recordingSession = RecordingSession.Idle,
                            )
                        }
                        editHistory.record(current)
                        mutableState.value = next.copy(canUndo = editHistory.canUndo, canRedo = editHistory.canRedo)
                        runCatching { player.loadPcm(audio) }.onFailure { error ->
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
        projectOperations.invalidate()
        stopCompetingPlayback()
        editHistory.reset()
        mutableState.value = freshProductionState()
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
    override fun stopSourceForWorkspaceChange() = stopAllSounds()
    override fun ensurePlayablePadSelected() = mutableState.update { it.copy(selectedPad = it.pads.firstOrNull(PadModel::isAssigned)?.globalIndex ?: it.selectedPad) }
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
        val pad = mutableState.value.pads.getOrNull(index)
        if (pad?.isAssigned == true) {
            player.triggerPad(pad)
            val beforeRecord = mutableState.value
            if (beforeRecord.recordArmed && beforeRecord.transportPlaying && beforeRecord.currentStep >= 0) {
                commitEdit { it.copy(activeSteps = it.activeSteps + stepKey(index, it.currentStep)) }
            }
            mutableState.update { it.copy(selectedPad = index, statusMessage = "PAD ${index + 1}を再生中です") }
        } else {
            selectPad(index)
        }
    }
    override fun releasePad(index: Int) = player.releasePad(index)
    override fun previewPad(index: Int) {
        stopCompetingPlayback()
        triggerPad(index)
    }
    override fun playSourceFrom(frame: Int) {
        val state = mutableState.value
        if (state.currentAudio == null) return
        val safe = frame.coerceIn(0, state.rangeEndFrame)
        stopCompetingPlayback()
        player.playFrom(safe)
        mutableState.update { it.copy(sourcePlayheadFrame = safe, sourcePlaying = true, statusMessage = "元曲を再生中です") }
    }
    override fun seekSourcePlayback(frame: Int) {
        val safe = frame.coerceIn(0, mutableState.value.rangeEndFrame)
        player.seekSource(safe)
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
            stopCompetingPlayback()
            player.playFrom(state.sourcePlayheadFrame)
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
            player.loadPcm(audio, pitch)
            if (wasPlaying) player.playFrom(frame)
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
    override fun toggleSelectedPadPlayMode() = updateSelected { it.copy(playMode = it.playMode.next()) }
    override fun setSelectedPadChokeGroup(group: Int) = updateSelected { it.copy(chokeGroup = group.coerceIn(0, 4)) }
    override fun clearSelectedPad() {
        val selected = mutableState.value.selectedPad
        player.stopPad(selected)
        commitEdit { state ->
            state.copy(
                pads = state.pads.toMutableList().also { it[selected] = PadModel(selected) },
                activeSteps = state.activeSteps.filterNot { key -> key / SamplerConfig.STEP_COUNT == selected }.toSet(),
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
    override fun clearAllPattern() = commitEdit { it.copy(activeSteps = emptySet()) }
    override fun toggleBeatLoopControl() {
        val state = mutableState.value
        val index = state.loopingPadIndex ?: state.selectedPad
        val pad = state.pads.getOrNull(index)
        if (pad?.isAssigned != true) return setStatus("先に音の入ったPADを選んでください")
        if (state.loopingPadIndex == index) {
            player.stopPad(index)
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
        player.triggerPad(loopPad, forceLoop = true)
        mutableState.value.pads
            .filter { it.isAssigned && it.contentKind == PadContentKind.VOCAL }
            .forEach(player::triggerPad)
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
            transport.start(state.bpm, state.swing)
            mutableState.update { it.copy(transportPlaying = true, currentStep = 0, statusMessage = "ビートを再生中です") }
        }
    }
    override fun toggleRecordArm() = mutableState.update { it.copy(recordArmed = !it.recordArmed) }
    override fun undoEdit() {
        if (rejectEditWhileRecording()) return
        val restored = editHistory.undo(mutableState.value) ?: return setStatus("戻せる操作はありません")
        applyHistoryState(restored, "1つ前の操作へ戻しました")
        scheduleAutosave()
    }
    override fun redoEdit() {
        if (rejectEditWhileRecording()) return
        val restored = editHistory.redo(mutableState.value) ?: return setStatus("やり直せる操作はありません")
        applyHistoryState(restored, "操作をやり直しました")
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
    }

    override fun setBpm(value: Float) {
        commitEdit("bpm") { it.copy(bpm = value.coerceIn(40f, 240f)) }
        transport.updateTempo(mutableState.value.bpm, mutableState.value.swing)
    }
    override fun setSwing(value: Float) {
        commitEdit("swing") { it.copy(swing = value.coerceIn(50f, 75f)) }
        transport.updateTempo(mutableState.value.bpm, mutableState.value.swing)
    }
    override fun addSliceMarker(frame: Int) = commitEdit { it.copy(sliceMarkers = (it.sliceMarkers + frame).distinct().sorted()) }
    override fun selectSliceAt(frame: Int) = commitEdit { it.copy(activeSliceIndex = it.sliceRanges().indexOfFirst { range -> frame in range.startFrame until range.endFrame }.takeIf { index -> index >= 0 }) }
    override fun setRangeStart(frame: Int) = commitEdit("range-start") { it.copy(rangeStartFrame = frame.coerceAtLeast(0)) }
    override fun setRangeEnd(frame: Int) = commitEdit("range-end") { it.copy(rangeEndFrame = frame.coerceAtLeast(1)) }
    override fun moveSliceMarker(markerIndex: Int, frame: Int) = commitEdit("slice-$markerIndex") { state -> state.copy(sliceMarkers = state.sliceMarkers.toMutableList().also { if (markerIndex in it.indices) it[markerIndex] = frame }.sorted()) }

    private fun updateSelected(mergeKey: String? = null, transform: (PadModel) -> PadModel) {
        val selected = mutableState.value.selectedPad
        commitEdit(mergeKey) { state -> state.copy(pads = state.pads.toMutableList().also { it[state.selectedPad] = transform(it[state.selectedPad]) }) }
        val current = mutableState.value
        if (current.loopingPadIndex == selected && current.pads[selected].isAssigned) {
            player.stopPad(selected)
            player.triggerPad(current.pads[selected], forceLoop = true)
        }
    }

    private fun commitEdit(mergeKey: String? = null, transform: (SamplerUiState) -> SamplerUiState) {
        if (mutableState.value.isLoading) return setStatus("現在の処理が終わってから編集してください")
        if (rejectEditWhileRecording()) return
        val before = mutableState.value
        val after = transform(before)
        if (after == before) return
        editHistory.record(before, mergeKey)
        mutableState.value = after.copy(canUndo = editHistory.canUndo, canRedo = editHistory.canRedo)
        scheduleAutosave()
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
            canUndo = editHistory.canUndo,
            canRedo = editHistory.canRedo,
        )
        mutableState.value = next
        next.currentAudio?.let(player::loadPcm)
    }

    private fun scheduleAutosave() {
        val store = autosaveStore ?: return
        val snapshot = mutableState.value
        autosaveFuture?.cancel(false)
        autosaveFuture = persistenceExecutor.schedule(
            {
                runCatching { store.save(snapshot) }.onFailure { error ->
                    mutableState.update { current ->
                        current.copy(statusMessage = "自動保存失敗: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
            },
            autosaveDelayMillis.coerceAtLeast(0L),
            TimeUnit.MILLISECONDS,
        )
    }

    private fun recoverAutosave() {
        val store = autosaveStore ?: return
        persistenceExecutor.execute {
            runCatching { store.load() }
                .onSuccess { restored ->
                    editHistory.reset()
                    if (restored == null) {
                        mutableState.value = freshProductionState()
                        scheduleAutosave()
                    } else {
                        applyHistoryState(
                            restored = restored,
                            message = "前回の自動保存を復元しました",
                            launchTarget = inferProjectLaunchTarget(
                                restored,
                                starterOnly = BuiltInDrumKits.hasUntouchedStarterDrums(restored),
                            ),
                        )
                    }
                }
                .onFailure { error ->
                    mutableState.value = SamplerUiState(
                        statusMessage = "自動保存を復元できません: ${error.message ?: error.javaClass.simpleName}",
                    )
                }
        }
    }

    private fun onTransportStep(step: Int) {
        val snapshot = mutableState.value
        if (!snapshot.transportPlaying) return
        mutableState.update { current -> if (current.transportPlaying) current.copy(currentStep = step) else current }
        val audible = snapshot.activeSteps.audibleStepKeys(snapshot.pads)
        snapshot.pads.forEach { pad ->
            if (stepKey(pad.globalIndex, step) in audible) player.triggerPad(pad)
        }
    }

    private fun stopCompetingPlayback(preserveScratchReturn: Boolean = false) {
        if (!preserveScratchReturn) scratchReturnTarget = ScratchReturnTarget.None
        scratchIdleFuture?.cancel(false)
        scratchIdleFuture = null
        transport.stop()
        scratch.stop()
        player.stopAll()
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

    private fun resumeAfterScratch(target: ScratchReturnTarget): Boolean {
        val current = mutableState.value
        if (!scratchReturnTargetIsValid(target, current)) return false
        return when (target) {
            ScratchReturnTarget.None -> false
            ScratchReturnTarget.Transport -> {
                runCatching { transport.start(current.bpm, current.swing) }
                    .onSuccess {
                        mutableState.update {
                            it.copy(
                                transportPlaying = true,
                                recordArmed = false,
                                currentStep = 0,
                                statusMessage = "スクラッチからビート再生へ戻りました",
                            )
                        }
                    }
                    .onFailure { setStatus("スクラッチ後のビート再開失敗: ${it.message ?: it.javaClass.simpleName}") }
                    .isSuccess
            }
            is ScratchReturnTarget.PadLoop -> {
                val pad = current.pads[target.padIndex]
                runCatching {
                    player.triggerPad(pad, forceLoop = true)
                    current.pads.filter { it.isAssigned && it.contentKind == PadContentKind.VOCAL }
                        .forEach { player.triggerPad(it, forceLoop = false) }
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
        projectOperations.invalidate()
        ioExecutor.shutdownNow()
        autosaveFuture?.cancel(false)
        scratchIdleFuture?.cancel(false)
        persistenceExecutor.shutdownNow()
        playbackMonitor.shutdownNow()
        transport.close()
        scratch.close()
        microphone.close()
        systemAudio.close()
        player.close()
    }

    companion object {
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

private fun com.choplab.sampler.model.PadPlayMode.next() = when (this) {
    com.choplab.sampler.model.PadPlayMode.ONE_SHOT -> com.choplab.sampler.model.PadPlayMode.GATE
    com.choplab.sampler.model.PadPlayMode.GATE -> com.choplab.sampler.model.PadPlayMode.LOOP
    com.choplab.sampler.model.PadPlayMode.LOOP -> com.choplab.sampler.model.PadPlayMode.ONE_SHOT
}
