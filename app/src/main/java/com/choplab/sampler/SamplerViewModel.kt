package com.choplab.sampler

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.choplab.sampler.audio.AudioDecoder
import com.choplab.sampler.audio.BuiltInDrumKits
import com.choplab.sampler.audio.CaptureEventBus
import com.choplab.sampler.audio.MicrophoneRecorder
import com.choplab.sampler.audio.PlaybackCaptureService
import com.choplab.sampler.audio.PlaybackCaptureState
import com.choplab.sampler.audio.PatternRenderer
import com.choplab.sampler.audio.SamplerEngine
import com.choplab.sampler.audio.SamplerPlaybackEngine
import com.choplab.sampler.audio.TransientDetector
import com.choplab.sampler.model.EditHistory
import com.choplab.sampler.model.DrumKitApplyDecision
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPressAction
import com.choplab.sampler.model.PadSurfaceMode
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PadTrimBoundary
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.ProjectOperationEpoch
import com.choplab.sampler.model.RepeatGrid
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.SliceRange
import com.choplab.sampler.model.SourceCaptureOperation
import com.choplab.sampler.model.SourcePlaybackRequest
import com.choplab.sampler.model.activeSliceRange
import com.choplab.sampler.model.assignLiveChopToPad
import com.choplab.sampler.model.assignRangesToPads
import com.choplab.sampler.model.audibleStepKeys
import com.choplab.sampler.model.clearPadSteps
import com.choplab.sampler.model.drumKitApplyDecision
import com.choplab.sampler.model.ensurePlayablePadSelected as ensurePlayablePadSelectedState
import com.choplab.sampler.model.hasAudiblePatternContent
import com.choplab.sampler.model.nextVocalPadIndex
import com.choplab.sampler.model.replacePadSteps
import com.choplab.sampler.model.replaceSourceAudio
import com.choplab.sampler.model.resetProjectState
import com.choplab.sampler.model.resolvePadPressAction
import com.choplab.sampler.model.selectPlayableBank as selectPlayableBankState
import com.choplab.sampler.model.selectPlayablePad as selectPlayablePadState
import com.choplab.sampler.model.selectPlayablePadPage as selectPlayablePadPageState
import com.choplab.sampler.model.selectSourceRangeForScratch
import com.choplab.sampler.model.selectedPadModel
import com.choplab.sampler.model.sliceRanges
import com.choplab.sampler.model.sourcePlaybackStartFrame
import com.choplab.sampler.model.sourcePlaybackAppliedStatusMessage
import com.choplab.sampler.model.sourcePlaybackRequestFeedback
import com.choplab.sampler.model.sourceScratchRange
import com.choplab.sampler.model.stepKey
import com.choplab.sampler.model.trimPadBoundary
import com.choplab.sampler.persistence.AtomicProjectStore
import com.choplab.sampler.persistence.ProjectArchiveCodec
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

class SamplerViewModel(application: Application) : AndroidViewModel(application) {
    private val decoder = AudioDecoder(application)
    private val microphoneRecorder = MicrophoneRecorder()
    private val engine: SamplerPlaybackEngine = SamplerEngine(application) { message ->
        viewModelScope.launch { setStatus(message) }
    }
    private val editHistory = EditHistory(maxEntries = MAX_HISTORY_ENTRIES)
    private val autosaveStore = AtomicProjectStore(File(application.filesDir, "projects"))
    private var autosaveJob: Job? = null
    private var scratchIdleJob: Job? = null
    private var projectRevision = 0L
    private val projectOperations = ProjectOperationEpoch()
    private val microphoneSourceCapture = SourceCaptureOperation(projectOperations)
    private val systemSourceCapture = SourceCaptureOperation(projectOperations)
    private val vocalCapture = SourceCaptureOperation(projectOperations)

    private val mutableUiState = MutableStateFlow(SamplerUiState())
    val uiState: StateFlow<SamplerUiState> = mutableUiState.asStateFlow()

    init {
        engine.start()
        observePlaybackCapture()
        pollTransportStep()
        recoverAutosave()
    }

    fun loadAudio(uri: Uri) = loadAudio(uri, projectOperations.begin())

    private fun loadAudio(uri: Uri, operation: Long) {
        viewModelScope.launch {
            if (!projectOperations.isCurrent(operation)) return@launch
            mutableUiState.update {
                it.copy(isLoading = true, statusMessage = "音声を解析しています…")
            }
            runCatching { decoder.decode(uri) }
                .onSuccess { audio ->
                    projectOperations.completeIfCurrent(operation) {
                        engine.stopSource()
                        engine.stopTransport()
                        engine.stopAllVoices()
                        editHistory.reset()
                        val replaced = replaceSourceAudio(mutableUiState.value, audio)
                        mutableUiState.value = replaced
                        engine.updateAllPads(replaced.pads)
                        syncPattern()
                        projectRevision++
                        scheduleAutosave()
                    }
                }
                .onFailure { throwable ->
                    projectOperations.completeIfCurrent(operation) {
                        mutableUiState.update {
                            it.copy(
                                isLoading = false,
                                statusMessage = throwable.message ?: "音声を読み込めませんでした",
                            )
                        }
                    }
                }
        }
    }

    fun startMicrophoneRecording() {
        if (microphoneRecorder.isRecording) return
        val operation = microphoneSourceCapture.begin()
        val directory = File(getApplication<Application>().cacheDir, "captures").apply { mkdirs() }
        val file = File(directory, "microphone_${System.currentTimeMillis()}.wav")
        val result = microphoneRecorder.start(file) { message ->
            viewModelScope.launch {
                projectOperations.completeIfCurrent(operation) {
                    mutableUiState.update {
                        it.copy(microphoneRecording = false, statusMessage = message)
                    }
                }
            }
        }
        result.onSuccess {
            projectOperations.completeIfCurrent(operation) {
                mutableUiState.update {
                    it.copy(
                        microphoneRecording = true,
                        statusMessage = "マイク録音中です。停止すると波形へ読み込みます",
                    )
                }
            }
        }.onFailure { throwable ->
            microphoneSourceCapture.discard(operation)
            projectOperations.completeIfCurrent(operation) {
                mutableUiState.update {
                    it.copy(statusMessage = throwable.message ?: "マイク録音を開始できません")
                }
            }
        }
    }

    fun stopMicrophoneRecording() {
        val operation = microphoneSourceCapture.consumeOrBegin()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { microphoneRecorder.stop() }
            projectOperations.completeIfCurrent(operation) {
                mutableUiState.update { it.copy(microphoneRecording = false) }
            }
            result.onSuccess { file ->
                loadAudio(Uri.fromFile(file), operation)
            }.onFailure { throwable ->
                projectOperations.completeIfCurrent(operation) {
                    setStatus(throwable.message ?: "マイク録音を停止できません")
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
        val operation = vocalCapture.begin()
        val directory = File(getApplication<Application>().cacheDir, "captures").apply { mkdirs() }
        val file = File(directory, "vocal_${System.currentTimeMillis()}.wav")
        microphoneRecorder.start(file) { message ->
            viewModelScope.launch {
                projectOperations.completeIfCurrent(operation) {
                    mutableUiState.update {
                        it.copy(vocalOverdubRecording = false, statusMessage = message)
                    }
                }
            }
        }.onSuccess {
            projectOperations.completeIfCurrent(operation) {
                state.loopingPadIndex?.let(engine::stopPad)
                state.pads.filter { it.isAssigned && it.contentKind == PadContentKind.VOCAL }
                    .forEach { engine.triggerPad(it.globalIndex) }
                engine.startPadLoop(loopPadIndex)
                mutableUiState.update {
                    it.copy(
                        vocalOverdubRecording = true,
                        loopingPadIndex = loopPadIndex,
                        loopPlayheadFrame = loopPad.startFrame,
                        statusMessage = "声を録音中 — ヘッドホン推奨 / もう一度押すとテイクを保存",
                    )
                }
            }
        }.onFailure { throwable ->
            vocalCapture.discard(operation)
            projectOperations.completeIfCurrent(operation) {
                setStatus(throwable.message ?: "声の録音を開始できません")
            }
        }
    }

    fun stopVocalOverdubRecording() {
        val operation = vocalCapture.consumeOrBegin()
        viewModelScope.launch {
            val stopped = withContext(Dispatchers.IO) { microphoneRecorder.stop() }
            projectOperations.completeIfCurrent(operation) {
                mutableUiState.update { it.copy(vocalOverdubRecording = false) }
            }
            stopped.onSuccess { file ->
                runCatching { decoder.decode(Uri.fromFile(file)) }
                    .onSuccess { audio ->
                        projectOperations.completeIfCurrent(operation) {
                            assignVocalTake(audio)
                        }
                    }
                    .onFailure { throwable ->
                        projectOperations.completeIfCurrent(operation) {
                            setStatus(throwable.message ?: "録音した声を読み込めませんでした")
                        }
                    }
            }.onFailure { throwable ->
                projectOperations.completeIfCurrent(operation) {
                    setStatus(throwable.message ?: "声の録音を停止できません")
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

    fun applyBuiltInDrumKit(kitId: String, replaceExisting: Boolean = false) {
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

    fun beginScratch() {
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
        state.loopingPadIndex?.let(engine::stopPad)
        state.pads.filter { it.isAssigned && it.contentKind == PadContentKind.VOCAL }
            .forEach { engine.stopPad(it.globalIndex) }
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
                statusMessage = "指を左右へ動かしてスクラッチ",
            )
        }
    }

    fun beginSourceScratch() {
        val state = mutableUiState.value
        val audio = state.currentAudio
        val range = state.sourceScratchRange()
        if (audio == null || range == null) {
            setStatus("先に元曲の波形でスクラッチ範囲を選んでください")
            return
        }
        engine.stopSource()
        state.loopingPadIndex?.let(engine::stopPad)
        state.pads.filter { it.isAssigned && it.contentKind == PadContentKind.VOCAL }
            .forEach { engine.stopPad(it.globalIndex) }
        engine.beginSourceScratch(audio, range.startFrame, range.endFrame)
        mutableUiState.update {
            it.copy(
                sourcePlaying = false,
                loopingPadIndex = null,
                loopPlayheadFrame = -1,
                scratchingPadIndex = null,
                scratchPlayheadFrame = range.startFrame,
                sourceScratchActive = true,
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

    fun updateScratchSpeed(speed: Float) {
        engine.updateScratchSpeed(speed)
        scratchIdleJob?.cancel()
        scratchIdleJob = viewModelScope.launch {
            delay(SCRATCH_IDLE_TIMEOUT_MS)
            engine.updateScratchSpeed(0f)
        }
    }

    fun endScratch() {
        scratchIdleJob?.cancel()
        scratchIdleJob = null
        engine.endScratch()
        mutableUiState.update {
            it.copy(
                scratchingPadIndex = null,
                scratchPlayheadFrame = -1,
                sourceScratchActive = false,
                statusMessage = "スクラッチ停止 — ビートループで通常再生へ戻れます",
            )
        }
    }

    fun startSystemAudioCapture(resultCode: Int, resultData: Intent) {
        val operation = systemSourceCapture.begin()
        val application = getApplication<Application>()
        val intent = PlaybackCaptureService.startIntent(application, resultCode, resultData)
        runCatching {
            ContextCompat.startForegroundService(application, intent)
        }.onSuccess {
            projectOperations.completeIfCurrent(operation) {
                mutableUiState.update {
                    it.copy(
                        systemAudioRecording = true,
                        statusMessage = "端末音声を録音中です。録音可能なアプリの音だけが取り込まれます",
                    )
                }
            }
        }.onFailure { throwable ->
            systemSourceCapture.discard(operation)
            projectOperations.completeIfCurrent(operation) {
                mutableUiState.update {
                    it.copy(
                        systemAudioRecording = false,
                        statusMessage = throwable.message ?: "端末音声録音サービスを開始できませんでした",
                    )
                }
            }
        }
    }

    fun stopSystemAudioCapture() {
        val application = getApplication<Application>()
        application.startService(PlaybackCaptureService.stopIntent(application))
        setStatus("端末音声録音を終了しています…")
    }

    fun setRangeStart(frame: Int) {
        commitEdit(mergeKey = "range-start") { state ->
            val audio = state.currentAudio ?: return@commitEdit state
            val minimum = minimumSliceFrames(audio.sampleRate)
            val upper = (state.rangeEndFrame - minimum).coerceAtLeast(0)
            val start = snapToZeroCrossing(
                audio = audio,
                targetFrame = frame.coerceIn(0, upper),
                lowerBound = 0,
                upperBound = upper,
            )
            state.copy(
                rangeStartFrame = start,
                sliceMarkers = state.sliceMarkers.filter { it > start && it < state.rangeEndFrame },
                activeSliceIndex = null,
            )
        }
    }

    fun setRangeEnd(frame: Int) {
        commitEdit(mergeKey = "range-end") { state ->
            val audio = state.currentAudio ?: return@commitEdit state
            val minimum = minimumSliceFrames(audio.sampleRate)
            val lower = (state.rangeStartFrame + minimum).coerceAtMost(audio.frameCount)
            val end = snapToZeroCrossing(
                audio = audio,
                targetFrame = frame.coerceIn(lower, audio.frameCount),
                lowerBound = lower,
                upperBound = audio.frameCount,
            )
            state.copy(
                rangeEndFrame = end,
                sliceMarkers = state.sliceMarkers.filter { it > state.rangeStartFrame && it < end },
                activeSliceIndex = null,
            )
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

    fun resetProject() {
        projectOperations.invalidate()
        val reset = resetProjectState(mutableUiState.value)
        autosaveJob?.cancel()
        engine.stopSource()
        engine.stopTransport()
        engine.stopAllVoices()
        runCatching { microphoneRecorder.stop() }
        runCatching {
            val application = getApplication<Application>()
            application.startService(PlaybackCaptureService.stopIntent(application))
        }
        CaptureEventBus.reset()
        editHistory.reset()
        projectRevision++
        val revision = projectRevision
        mutableUiState.value = reset
        engine.updateAllPads(reset.pads)
        syncPattern()
        autosaveJob = viewModelScope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { autosaveStore.save(reset, revision) }.exceptionOrNull()
            }
            if (failure != null && projectRevision == revision) {
                mutableUiState.update {
                    it.copy(statusMessage = failure.message ?: "リセット状態を保存できませんでした")
                }
            }
        }
    }

    fun toggleManualChop() {
        commitEdit { it.copy(manualChopEnabled = !it.manualChopEnabled) }
    }

    fun addSliceMarker(frame: Int) {
        commitEdit { state ->
            val audio = state.currentAudio ?: return@commitEdit state
            val minimum = minimumSliceFrames(audio.sampleRate)
            if (state.rangeEndFrame - state.rangeStartFrame < minimum * 2) {
                return@commitEdit state.copy(statusMessage = "選択範囲が短く、チョップ位置を追加できません")
            }
            val safe = snapToZeroCrossing(
                audio = audio,
                targetFrame = frame.coerceIn(
                    state.rangeStartFrame + minimum,
                    state.rangeEndFrame - minimum,
                ),
                lowerBound = state.rangeStartFrame + minimum,
                upperBound = state.rangeEndFrame - minimum,
            )
            val existingPoints = listOf(state.rangeStartFrame) +
                state.sliceMarkers.filter { it in (state.rangeStartFrame + 1) until state.rangeEndFrame } +
                state.rangeEndFrame
            if (existingPoints.any { kotlin.math.abs(it - safe) < minimum }) {
                return@commitEdit state.copy(statusMessage = "既存スライスに近すぎます")
            }
            state.copy(
                sliceMarkers = (state.sliceMarkers + safe).distinct().sorted(),
                activeSliceIndex = null,
                statusMessage = "チョップ位置を追加しました",
            )
        }
    }

    fun clearSliceMarkers() {
        commitEdit {
            it.copy(sliceMarkers = emptyList(), activeSliceIndex = null, statusMessage = "チョップ位置を消去しました")
        }
    }

    fun moveSliceMarker(markerIndex: Int, frame: Int) {
        commitEdit(mergeKey = "slice-marker-$markerIndex") { state ->
            val audio = state.currentAudio ?: return@commitEdit state
            if (markerIndex !in state.sliceMarkers.indices) return@commitEdit state

            val minimum = minimumSliceFrames(audio.sampleRate)
            val lower = if (markerIndex == 0) {
                state.rangeStartFrame + minimum
            } else {
                state.sliceMarkers[markerIndex - 1] + minimum
            }
            val upper = if (markerIndex == state.sliceMarkers.lastIndex) {
                state.rangeEndFrame - minimum
            } else {
                state.sliceMarkers[markerIndex + 1] - minimum
            }
            if (lower > upper) return@commitEdit state

            val markers = state.sliceMarkers.toMutableList()
            markers[markerIndex] = snapToZeroCrossing(
                audio = audio,
                targetFrame = frame.coerceIn(lower, upper),
                lowerBound = lower,
                upperBound = upper,
            )
            state.copy(
                sliceMarkers = markers,
                statusMessage = "チョップ境界 ${markerIndex + 1} を調整しました",
            )
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

    fun selectSliceAt(frame: Int) {
        mutableUiState.update { state ->
            val ranges = state.sliceRanges()
            val selected = ranges.indexOfFirst { range ->
                frame >= range.startFrame && (frame < range.endFrame || range == ranges.lastOrNull())
            }
            state.copy(activeSliceIndex = selected.takeIf { it >= 0 })
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
                if (
                    current.currentAudio?.id != audio.id ||
                    current.rangeStartFrame != snapshot.rangeStartFrame ||
                    current.rangeEndFrame != snapshot.rangeEndFrame
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
        engine.preview(audio, range.startFrame, range.endFrame)
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

    fun selectBank(bankIndex: Int) {
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

    fun selectPad(globalIndex: Int) {
        if (globalIndex !in 0 until SamplerConfig.PAD_COUNT) return
        mutableUiState.update {
            it.copy(
                selectedPad = globalIndex,
                selectedBank = globalIndex / SamplerConfig.PADS_PER_BANK,
            )
        }
    }

    fun selectPadPage(pageIndex: Int) {
        if (pageIndex !in 0 until SamplerConfig.PAD_PAGES_PER_BANK) return
        mutableUiState.update { state ->
            val bankStart = state.selectedBank * SamplerConfig.PADS_PER_BANK
            val indexOnPage = state.selectedPad % SamplerConfig.PAD_PAGE_SIZE
            val target = bankStart + pageIndex * SamplerConfig.PAD_PAGE_SIZE + indexOnPage
            state.copy(selectedPad = target)
        }
    }

    fun selectPlayablePad(globalIndex: Int) {
        mutableUiState.update { state -> selectPlayablePadState(state, globalIndex) }
    }

    fun selectPlayablePadPage(pageIndex: Int) {
        mutableUiState.update { state -> selectPlayablePadPageState(state, pageIndex) }
    }

    fun selectPlayableBank(bankIndex: Int) {
        mutableUiState.update { state -> selectPlayableBankState(state, bankIndex) }
    }

    fun ensurePlayablePadSelected() {
        mutableUiState.update(::ensurePlayablePadSelectedState)
    }

    fun capturePad(globalIndex: Int) {
        handlePadPress(globalIndex, PadSurfaceMode.CAPTURE)
    }

    fun triggerPad(globalIndex: Int) {
        handlePadPress(globalIndex, PadSurfaceMode.PERFORMANCE)
    }

    private fun handlePadPress(globalIndex: Int, surfaceMode: PadSurfaceMode) {
        val state = mutableUiState.value
        val pad = state.pads.getOrNull(globalIndex) ?: return
        when (
            resolvePadPressAction(
                sourcePlaying = state.sourcePlaying,
                padAssigned = pad.isAssigned,
                surfaceMode = surfaceMode,
            )
        ) {
            PadPressAction.CAPTURE_CHOP -> {
                assignLiveChop(globalIndex)
                return
            }
            PadPressAction.SELECT_ONLY -> return
            PadPressAction.PLAY_ASSIGNED -> Unit
        }
        if (pad.playMode == PadPlayMode.LOOP && !(state.recordArmed && state.transportPlaying)) {
            toggleBeatLoop(globalIndex)
            return
        }
        engine.triggerPad(globalIndex)

        if (state.recordArmed && state.transportPlaying) {
            val step = engine.currentStep
            if (step in 0 until SamplerConfig.STEP_COUNT) {
                commitEdit { current ->
                    val updated = current.activeSteps + stepKey(globalIndex, step)
                    current.copy(activeSteps = updated)
                }
                syncPattern()
            }
        }
    }

    fun releasePad(globalIndex: Int) {
        engine.releasePad(globalIndex)
    }

    fun toggleSourcePlayback() {
        val state = mutableUiState.value
        val audio = state.currentAudio ?: run {
            setStatus("先に曲を読み込んでください")
            return
        }
        if (state.sourcePlaying) {
            engine.stopSource()
            mutableUiState.update { current ->
                val feedback = sourcePlaybackRequestFeedback(
                    appliedPlaying = current.sourcePlaying,
                    request = SourcePlaybackRequest.STOP,
                )
                current.copy(
                    sourcePlaying = feedback.sourcePlaying,
                    statusMessage = feedback.statusMessage,
                )
            }
            return
        }

        if (state.transportPlaying) {
            engine.stopTransport()
        }
        val start = sourcePlaybackStartFrame(state.sourcePlayheadFrame, audio.frameCount)
        engine.playSource(audio, start, state.masterPitchSemitones)
        mutableUiState.update { current ->
            val feedback = sourcePlaybackRequestFeedback(
                appliedPlaying = current.sourcePlaying,
                request = SourcePlaybackRequest.START,
            )
            current.copy(
                sourcePlaying = feedback.sourcePlaying,
                transportPlaying = false,
                currentStep = -1,
                sourcePlayheadFrame = start,
                statusMessage = feedback.statusMessage,
            )
        }
    }

    fun restartSourcePlayback() {
        val state = mutableUiState.value
        val audio = state.currentAudio ?: run {
            setStatus("先に曲を読み込んでください")
            return
        }
        engine.stopTransport()
        engine.stopSource()
        engine.playSource(audio, 0, state.masterPitchSemitones)
        mutableUiState.update { current ->
            val feedback = sourcePlaybackRequestFeedback(
                appliedPlaying = current.sourcePlaying,
                request = SourcePlaybackRequest.RESTART,
            )
            current.copy(
                sourcePlaying = feedback.sourcePlaying,
                transportPlaying = false,
                currentStep = -1,
                sourcePlayheadFrame = 0,
                statusMessage = feedback.statusMessage,
            )
        }
    }

    fun playSourceFrom(frame: Int) {
        val state = mutableUiState.value
        val audio = state.currentAudio ?: run {
            setStatus("先に曲を読み込んでください")
            return
        }
        val safe = frame.coerceIn(0, audio.frameCount - 1)
        engine.stopTransport()
        engine.playSource(audio, safe, state.masterPitchSemitones)
        mutableUiState.update { current ->
            val feedback = sourcePlaybackRequestFeedback(
                appliedPlaying = current.sourcePlaying,
                request = SourcePlaybackRequest.SEEK,
            )
            current.copy(
                sourcePlaying = feedback.sourcePlaying,
                transportPlaying = false,
                currentStep = -1,
                sourcePlayheadFrame = safe,
                statusMessage = feedback.statusMessage,
            )
        }
    }

    fun seekSourcePlayback(frame: Int) {
        val state = mutableUiState.value
        val audio = state.currentAudio ?: return
        val safe = frame.coerceIn(0, audio.frameCount - 1)
        if (state.sourcePlaying) {
            engine.playSource(audio, safe, state.masterPitchSemitones)
        }
        mutableUiState.update { it.copy(sourcePlayheadFrame = safe) }
    }

    fun setMasterPitch(value: Float) {
        val pitch = value.coerceIn(-12f, 12f)
        val state = mutableUiState.value
        val audio = state.currentAudio
        val frame = engine.currentSourceFrame.takeIf { it >= 0 } ?: state.sourcePlayheadFrame
        if (state.sourcePlaying && audio != null) {
            engine.playSource(audio, frame.coerceIn(0, audio.frameCount - 1), pitch)
        }
        commitEdit(mergeKey = "master-pitch") { it.copy(masterPitchSemitones = pitch) }
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

    fun setSelectedPadPitch(value: Float) = updateSelectedPad("pitch") {
        it.copy(pitchSemitones = value.coerceIn(-24f, 24f))
    }

    fun setSelectedPadTone(value: Float) = updateSelectedPad("tone") {
        it.copy(tone = value.coerceIn(0f, 1f))
    }

    fun setSelectedPadGain(value: Float) = updateSelectedPad("gain") {
        it.copy(gain = value.coerceIn(0f, 1.5f))
    }

    fun setSelectedPadStartFrame(frame: Int) = updateSelectedPad("trim-start") {
        trimPadBoundary(it, PadTrimBoundary.START, frame - it.startFrame)
    }

    fun setSelectedPadEndFrame(frame: Int) = updateSelectedPad("trim-end") {
        trimPadBoundary(it, PadTrimBoundary.END, frame - it.endFrame)
    }

    fun toggleSelectedPadReverse() = updateSelectedPad {
        it.copy(reverse = !it.reverse)
    }

    fun toggleSelectedPadPlayMode() {
        val selectedPad = mutableUiState.value.selectedPad
        if (mutableUiState.value.loopingPadIndex == selectedPad) {
            engine.stopPad(selectedPad)
            mutableUiState.update { it.copy(loopingPadIndex = null, loopPlayheadFrame = -1) }
        }
        updateSelectedPad {
            it.copy(
                playMode = if (it.playMode == PadPlayMode.GATE) PadPlayMode.ONE_SHOT else PadPlayMode.GATE,
            )
        }
    }

    fun toggleSelectedBeatLoop() {
        toggleBeatLoop(mutableUiState.value.selectedPad)
    }

    fun toggleBeatLoopControl() {
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
            state.pads.filter { it.isAssigned && it.contentKind == PadContentKind.VOCAL }
                .forEach { engine.stopPad(it.globalIndex) }
            mutableUiState.update {
                it.copy(
                    loopingPadIndex = null,
                    loopPlayheadFrame = -1,
                    statusMessage = "ビートループを停止しました",
                )
            }
            return
        }

        state.loopingPadIndex?.let(engine::stopPad)
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
            current.copy(
                pads = pads,
                activeSteps = current.activeSteps.clearPadSteps(globalIndex),
            )
        }
        changedPads.forEach(engine::updatePad)
        syncPattern()
        engine.startPadLoop(globalIndex)
        state.pads.filter { it.isAssigned && it.contentKind == PadContentKind.VOCAL }
            .forEach { engine.triggerPad(it.globalIndex) }
        mutableUiState.update {
            it.copy(
                loopingPadIndex = globalIndex,
                loopPlayheadFrame = if (loopPad.reverse) loopPad.endFrame - 1 else loopPad.startFrame,
                statusMessage = "${('A'.code + loopPad.bankIndex).toChar()}-%02d の音声全体をループ中"
                    .format(loopPad.indexInBank + 1),
            )
        }
    }

    fun setSelectedPadChokeGroup(group: Int) = updateSelectedPad {
        it.copy(chokeGroup = group.coerceIn(0, 4))
    }

    fun clearSelectedPad() {
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

    fun toggleStep(stepIndex: Int) {
        if (stepIndex !in 0 until SamplerConfig.STEP_COUNT) return
        commitEdit { state ->
            val key = stepKey(state.selectedPad, stepIndex)
            val updated = if (key in state.activeSteps) state.activeSteps - key else state.activeSteps + key
            state.copy(activeSteps = updated)
        }
        syncPattern()
    }

    fun setBpm(value: Float) {
        commitEdit(mergeKey = "bpm") { it.copy(bpm = value.coerceIn(40f, 240f)) }
        syncPattern()
    }

    fun setSwing(value: Float) {
        commitEdit(mergeKey = "swing") { it.copy(swing = value.coerceIn(50f, 75f)) }
        syncPattern()
    }

    fun toggleTransport() {
        val playing = mutableUiState.value.transportPlaying
        if (playing) {
            engine.stopTransport()
            mutableUiState.update { it.copy(transportPlaying = false, currentStep = -1) }
        } else {
            if (mutableUiState.value.sourcePlaying) {
                engine.stopSource()
            }
            syncPattern()
            engine.startTransport()
            mutableUiState.update { it.copy(transportPlaying = true, sourcePlaying = false) }
        }
    }

    fun toggleRecordArm() {
        mutableUiState.update { it.copy(recordArmed = !it.recordArmed) }
    }

    fun clearSelectedPadPattern() {
        commitEdit { state ->
            val filtered = state.activeSteps.clearPadSteps(state.selectedPad)
            state.copy(activeSteps = filtered, statusMessage = "選択PADのシーケンスを消去しました")
        }
        syncPattern()
    }

    fun fillSelectedPadPattern(repeatGrid: RepeatGrid) {
        val selectedPad = mutableUiState.value.selectedPadModel()
        if (!selectedPad.isAssigned) {
            setStatus("先に音が入ったPADを選んでください")
            return
        }
        if (selectedPad.playMode == PadPlayMode.LOOP) {
            setStatus("ビートループは音声全体を繰り返します。配置は別PADへ追加してください")
            return
        }
        if (selectedPad.contentKind == PadContentKind.VOCAL) {
            setStatus("VOICE TAKE はビート開始時に1回だけ再生します")
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

    fun clearAllPattern() {
        commitEdit { it.copy(activeSteps = emptySet(), statusMessage = "パターンを全消去しました") }
        syncPattern()
    }

    fun stopAllSounds() {
        engine.stopAllVoices()
        mutableUiState.update {
            it.copy(
                loopingPadIndex = null,
                loopPlayheadFrame = -1,
                scratchingPadIndex = null,
                scratchPlayheadFrame = -1,
                sourceScratchActive = false,
                statusMessage = "すべての音を停止しました",
            )
        }
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
        val revision = projectRevision
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
        val operation = projectOperations.begin()
        val revisionAtStart = projectRevision
        viewModelScope.launch {
            mutableUiState.update { it.copy(isLoading = true, statusMessage = "プロジェクトを開いています…") }
            runCatching {
                withContext(Dispatchers.IO) {
                    val application = getApplication<Application>()
                    application.contentResolver.openInputStream(source)?.use(ProjectArchiveCodec::read)
                        ?: error("プロジェクトを開けません")
                }
            }.onSuccess { restored ->
                if (projectRevision != revisionAtStart) {
                    projectOperations.completeIfCurrent(operation) {
                        mutableUiState.update {
                            it.copy(isLoading = false, statusMessage = "編集中のため読み込み結果を破棄しました")
                        }
                    }
                    return@onSuccess
                }
                projectOperations.completeIfCurrent(operation) {
                    editHistory.reset()
                    projectRevision++
                    applyProjectState(restored, "プロジェクトを開きました")
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

    fun undoEdit() {
        val restored = editHistory.undo(mutableUiState.value) ?: run {
            setStatus("戻せる操作はありません")
            return
        }
        projectRevision++
        applyProjectState(restored, "1つ前の操作へ戻しました")
        scheduleAutosave()
    }

    fun redoEdit() {
        val restored = editHistory.redo(mutableUiState.value) ?: run {
            setStatus("やり直せる操作はありません")
            return
        }
        projectRevision++
        applyProjectState(restored, "操作をやり直しました")
        scheduleAutosave()
    }

    fun setStatus(message: String) {
        mutableUiState.update { it.copy(statusMessage = message) }
    }

    private fun commitEdit(
        mergeKey: String? = null,
        transform: (SamplerUiState) -> SamplerUiState,
    ) {
        val before = mutableUiState.value
        val after = transform(before)
        if (after == before) return
        if (before.hasSameEditableContent(after)) {
            mutableUiState.value = after.copy(
                canUndo = editHistory.canUndo,
                canRedo = editHistory.canRedo,
            )
            return
        }
        editHistory.record(before, mergeKey)
        mutableUiState.value = after.copy(
            canUndo = editHistory.canUndo,
            canRedo = editHistory.canRedo,
        )
        projectRevision++
        scheduleAutosave()
    }

    private fun SamplerUiState.hasSameEditableContent(other: SamplerUiState): Boolean =
        currentAudio == other.currentAudio &&
            rangeStartFrame == other.rangeStartFrame &&
            rangeEndFrame == other.rangeEndFrame &&
            sliceMarkers == other.sliceMarkers &&
            activeSliceIndex == other.activeSliceIndex &&
            manualChopEnabled == other.manualChopEnabled &&
            selectedBank == other.selectedBank &&
            selectedPad == other.selectedPad &&
            autoNextPad == other.autoNextPad &&
            pads == other.pads &&
            activeSteps == other.activeSteps &&
            bpm == other.bpm &&
            swing == other.swing &&
            sourcePlayheadFrame == other.sourcePlayheadFrame &&
            masterPitchSemitones == other.masterPitchSemitones &&
            selectedDrumKitId == other.selectedDrumKitId

    private fun applyProjectState(restored: SamplerUiState, message: String) {
        engine.stopSource()
        engine.stopTransport()
        engine.stopAllVoices()
        val stoppedRestoredState = restored.copy(
            isLoading = false,
            statusMessage = message,
            transportPlaying = false,
            recordArmed = false,
            currentStep = -1,
            microphoneRecording = false,
            vocalOverdubRecording = false,
            systemAudioRecording = false,
            sourcePlaying = false,
            loopingPadIndex = null,
            loopPlayheadFrame = -1,
            scratchingPadIndex = null,
            scratchPlayheadFrame = -1,
            sourceScratchActive = false,
            canUndo = editHistory.canUndo,
            canRedo = editHistory.canRedo,
        )
        mutableUiState.value = stoppedRestoredState
        stoppedRestoredState.pads.forEach(engine::updatePad)
        syncPattern()
    }

    private fun scheduleAutosave() {
        val snapshot = mutableUiState.value
        val revision = projectRevision
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(AUTOSAVE_DELAY_MS)
            val failure = withContext(Dispatchers.IO) {
                runCatching { autosaveStore.save(snapshot, revision) }.exceptionOrNull()
            }
            if (failure != null && projectRevision == revision) {
                mutableUiState.update {
                    it.copy(statusMessage = failure.message ?: "自動保存できませんでした")
                }
            }
        }
    }

    private fun recoverAutosave() {
        val operation = projectOperations.begin()
        val revisionAtStart = projectRevision
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { autosaveStore.load() } }
            if (projectRevision != revisionAtStart) return@launch
            projectOperations.completeIfCurrent(operation) {
                result.onSuccess { restored ->
                    if (restored != null) {
                        editHistory.reset()
                        applyProjectState(restored, "前回の自動保存を復元しました")
                    }
                }.onFailure { throwable ->
                    mutableUiState.update {
                        it.copy(statusMessage = throwable.message ?: "前回の自動保存を復元できませんでした")
                    }
                }
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
                        val operation = systemSourceCapture.ensureStarted()
                        projectOperations.completeIfCurrent(operation) {
                            mutableUiState.update {
                                it.copy(
                                    systemAudioRecording = true,
                                    statusMessage = "端末音声を録音中です",
                                )
                            }
                        }
                    }
                    is PlaybackCaptureState.Completed -> {
                        val operation = systemSourceCapture.consumeOrBegin()
                        projectOperations.completeIfCurrent(operation) {
                            mutableUiState.update {
                                it.copy(
                                    systemAudioRecording = false,
                                    statusMessage = "端末音声録音を読み込んでいます…",
                                )
                            }
                        }
                        CaptureEventBus.reset()
                        loadAudio(Uri.fromFile(captureState.file), operation)
                    }
                    is PlaybackCaptureState.Error -> {
                        val operation = systemSourceCapture.consumeOrBegin()
                        projectOperations.completeIfCurrent(operation) {
                            mutableUiState.update {
                                it.copy(
                                    systemAudioRecording = false,
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
                if (
                    snapshot.currentStep != step ||
                    snapshot.sourcePlaying != sourceIsPlaying ||
                    (sourceFrame >= 0 && snapshot.sourcePlayheadFrame != sourceFrame) ||
                    snapshot.loopingPadIndex != loopPad ||
                    snapshot.loopPlayheadFrame != loopFrame ||
                    snapshot.scratchingPadIndex != scratchPad ||
                    snapshot.scratchPlayheadFrame != scratchFrame
                ) {
                    mutableUiState.update { state ->
                        state.copy(
                            currentStep = step,
                            sourcePlaying = sourceIsPlaying,
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

    private fun minimumSliceFrames(sampleRate: Int): Int =
        (sampleRate * 0.008f).toInt().coerceAtLeast(64)

    private fun snapToZeroCrossing(
        audio: PcmAudio,
        targetFrame: Int,
        lowerBound: Int,
        upperBound: Int,
    ): Int {
        val samples = audio.samples
        if (samples.size < 2) return targetFrame.coerceIn(lowerBound, upperBound)

        val safeLower = lowerBound.coerceIn(0, samples.size)
        val safeUpper = upperBound.coerceIn(safeLower, samples.size)
        val target = targetFrame.coerceIn(safeLower, safeUpper)
        if (target == 0 || target == samples.size) return target

        val radius = (audio.sampleRate * ZERO_CROSSING_SEARCH_SECONDS)
            .toInt()
            .coerceIn(32, 1_024)
        val from = maxOf(1, safeLower, target - radius)
        val to = minOf(samples.lastIndex, safeUpper, target + radius)
        if (from > to) return target

        var bestCrossing = -1
        var bestDistance = Int.MAX_VALUE
        for (frame in from..to) {
            val previous = samples[frame - 1].toInt()
            val current = samples[frame].toInt()
            val crossesZero =
                (previous <= 0 && current >= 0) || (previous >= 0 && current <= 0)
            if (crossesZero) {
                val distance = kotlin.math.abs(frame - target)
                if (distance < bestDistance) {
                    bestCrossing = frame
                    bestDistance = distance
                }
            }
        }
        if (bestCrossing >= 0) return bestCrossing

        var quietestFrame = target
        var quietestMagnitude = kotlin.math.abs(samples[target].toInt())
        for (frame in from..to) {
            val magnitude = kotlin.math.abs(samples[frame].toInt())
            if (magnitude < quietestMagnitude) {
                quietestFrame = frame
                quietestMagnitude = magnitude
            }
        }
        return quietestFrame
    }

    private companion object {
        const val EXPORT_BARS = 4
        const val MAX_HISTORY_ENTRIES = 40
        const val AUTOSAVE_DELAY_MS = 900L
        const val ZERO_CROSSING_SEARCH_SECONDS = 0.004f
        const val LIVE_CHOP_LATENCY_SECONDS = 0.06
        const val SCRATCH_IDLE_TIMEOUT_MS = 42L
    }

    override fun onCleared() {
        runCatching { microphoneRecorder.stop() }
        runCatching {
            val application = getApplication<Application>()
            application.startService(PlaybackCaptureService.stopIntent(application))
        }
        engine.shutdown()
        super.onCleared()
    }
}
