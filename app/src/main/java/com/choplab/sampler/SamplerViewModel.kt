package com.choplab.sampler

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.choplab.sampler.audio.AudioDecoder
import com.choplab.sampler.audio.CaptureEventBus
import com.choplab.sampler.audio.MicrophoneRecorder
import com.choplab.sampler.audio.PlaybackCaptureService
import com.choplab.sampler.audio.PlaybackCaptureState
import com.choplab.sampler.audio.PatternRenderer
import com.choplab.sampler.audio.SamplerEngine
import com.choplab.sampler.audio.SamplerPlaybackEngine
import com.choplab.sampler.audio.TransientDetector
import com.choplab.sampler.model.EditHistory
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.SliceRange
import com.choplab.sampler.model.activeSliceRange
import com.choplab.sampler.model.assignRangesToPads
import com.choplab.sampler.model.assignLiveChopToPad
import com.choplab.sampler.model.selectedPadModel
import com.choplab.sampler.model.sliceRanges
import com.choplab.sampler.model.stepKey
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
    private var projectRevision = 0L

    private val mutableUiState = MutableStateFlow(SamplerUiState())
    val uiState: StateFlow<SamplerUiState> = mutableUiState.asStateFlow()

    init {
        engine.start()
        observePlaybackCapture()
        pollTransportStep()
        recoverAutosave()
    }

    fun loadAudio(uri: Uri) {
        viewModelScope.launch {
            mutableUiState.update {
                it.copy(isLoading = true, statusMessage = "音声を解析しています…")
            }
            runCatching { decoder.decode(uri) }
                .onSuccess { audio ->
                    engine.stopSource()
                    editHistory.reset()
                    mutableUiState.update { state ->
                        state.copy(
                            isLoading = false,
                            currentAudio = audio,
                            rangeStartFrame = 0,
                            rangeEndFrame = audio.frameCount,
                            sliceMarkers = emptyList(),
                            activeSliceIndex = null,
                            manualChopEnabled = false,
                            sourcePlaying = false,
                            sourcePlayheadFrame = 0,
                            masterPitchSemitones = 0f,
                            canUndo = false,
                            canRedo = false,
                            statusMessage = "${audio.name} を読み込みました",
                        )
                    }
                    projectRevision++
                    scheduleAutosave()
                }
                .onFailure { throwable ->
                    mutableUiState.update {
                        it.copy(
                            isLoading = false,
                            statusMessage = throwable.message ?: "音声を読み込めませんでした",
                        )
                    }
                }
        }
    }

    fun startMicrophoneRecording() {
        if (microphoneRecorder.isRecording) return
        val directory = File(getApplication<Application>().cacheDir, "captures").apply { mkdirs() }
        val file = File(directory, "microphone_${System.currentTimeMillis()}.wav")
        val result = microphoneRecorder.start(file) { message ->
            viewModelScope.launch {
                mutableUiState.update {
                    it.copy(microphoneRecording = false, statusMessage = message)
                }
            }
        }
        result.onSuccess {
            mutableUiState.update {
                it.copy(
                    microphoneRecording = true,
                    statusMessage = "マイク録音中です。停止すると波形へ読み込みます",
                )
            }
        }.onFailure { throwable ->
            mutableUiState.update {
                it.copy(statusMessage = throwable.message ?: "マイク録音を開始できません")
            }
        }
    }

    fun stopMicrophoneRecording() {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { microphoneRecorder.stop() }
            mutableUiState.update { it.copy(microphoneRecording = false) }
            result.onSuccess { file ->
                loadAudio(Uri.fromFile(file))
            }.onFailure { throwable ->
                setStatus(throwable.message ?: "マイク録音を停止できません")
            }
        }
    }

    fun startSystemAudioCapture(resultCode: Int, resultData: Intent) {
        val application = getApplication<Application>()
        val intent = PlaybackCaptureService.startIntent(application, resultCode, resultData)
        runCatching {
            ContextCompat.startForegroundService(application, intent)
        }.onSuccess {
            mutableUiState.update {
                it.copy(
                    systemAudioRecording = true,
                    statusMessage = "端末音声を録音中です。録音可能なアプリの音だけが取り込まれます",
                )
            }
        }.onFailure { throwable ->
            mutableUiState.update {
                it.copy(
                    systemAudioRecording = false,
                    statusMessage = throwable.message ?: "端末音声録音サービスを開始できませんでした",
                )
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
        assignRanges(ranges.take(SamplerConfig.PADS_PER_BANK), "${ranges.size.coerceAtMost(16)}スライスを連続割り当てしました")
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

    fun selectPad(globalIndex: Int) {
        if (globalIndex !in 0 until SamplerConfig.PAD_COUNT) return
        mutableUiState.update {
            it.copy(
                selectedPad = globalIndex,
                selectedBank = globalIndex / SamplerConfig.PADS_PER_BANK,
            )
        }
    }

    fun triggerPad(globalIndex: Int) {
        val state = mutableUiState.value
        if (state.sourcePlaying) {
            assignLiveChop(globalIndex)
            return
        }
        val pad = state.pads.getOrNull(globalIndex) ?: return
        if (!pad.isAssigned) return
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
        if (mutableUiState.value.sourcePlaying) return
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
            mutableUiState.update {
                it.copy(sourcePlaying = false, statusMessage = "停止中 — PADでチョップを演奏できます")
            }
            return
        }

        if (state.transportPlaying) {
            engine.stopTransport()
        }
        val start = state.sourcePlayheadFrame
            .takeIf { it in 0 until audio.frameCount }
            ?: 0
        engine.playSource(audio, start, state.masterPitchSemitones)
        mutableUiState.update {
            it.copy(
                sourcePlaying = true,
                transportPlaying = false,
                currentStep = -1,
                sourcePlayheadFrame = start,
                statusMessage = "サンプリング中 — 「ここだ」でPADを叩いてください",
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

    fun toggleSelectedPadReverse() = updateSelectedPad {
        it.copy(reverse = !it.reverse)
    }

    fun toggleSelectedPadPlayMode() = updateSelectedPad {
        it.copy(
            playMode = if (it.playMode == PadPlayMode.ONE_SHOT) PadPlayMode.GATE else PadPlayMode.ONE_SHOT,
        )
    }

    fun setSelectedPadChokeGroup(group: Int) = updateSelectedPad {
        it.copy(chokeGroup = group.coerceIn(0, 4))
    }

    fun clearSelectedPad() {
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
            val filtered = state.activeSteps.filterNot { key ->
                key / SamplerConfig.STEP_COUNT == state.selectedPad
            }.toSet()
            state.copy(activeSteps = filtered, statusMessage = "選択PADのシーケンスを消去しました")
        }
        syncPattern()
    }

    fun clearAllPattern() {
        commitEdit { it.copy(activeSteps = emptySet(), statusMessage = "パターンを全消去しました") }
        syncPattern()
    }

    fun stopAllSounds() {
        engine.stopAllVoices()
    }

    fun exportPattern(destination: Uri) {
        val snapshot = mutableUiState.value
        if (snapshot.activeSteps.isEmpty()) {
            setStatus("先にシーケンサーへステップを配置してください")
            return
        }
        val hasAudibleStep = snapshot.activeSteps.any { key ->
            val padIndex = key / SamplerConfig.STEP_COUNT
            snapshot.pads.getOrNull(padIndex)?.isAssigned == true
        }
        if (!hasAudibleStep) {
            setStatus("ステップが配置されたPADにサンプルがありません")
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
        viewModelScope.launch {
            mutableUiState.update { it.copy(isLoading = true, statusMessage = "プロジェクトを保存しています…") }
            runCatching {
                withContext(Dispatchers.IO) {
                    val application = getApplication<Application>()
                    application.contentResolver.openOutputStream(destination, "w")?.use { output ->
                        ProjectArchiveCodec.write(snapshot, output)
                    } ?: error("保存先を開けません")
                }
            }.onSuccess {
                mutableUiState.update {
                    it.copy(isLoading = false, statusMessage = "プロジェクトを保存しました")
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
                    mutableUiState.update {
                        it.copy(isLoading = false, statusMessage = "編集中のため読み込み結果を破棄しました")
                    }
                    return@onSuccess
                }
                editHistory.reset()
                projectRevision++
                applyProjectState(restored, "プロジェクトを開きました")
                scheduleAutosave()
            }.onFailure { throwable ->
                mutableUiState.update {
                    it.copy(
                        isLoading = false,
                        statusMessage = throwable.message ?: "プロジェクトを開けませんでした",
                    )
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
            masterPitchSemitones == other.masterPitchSemitones

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
            systemAudioRecording = false,
            sourcePlaying = false,
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
                runCatching { autosaveStore.save(snapshot) }.exceptionOrNull()
            }
            if (failure != null && projectRevision == revision) {
                mutableUiState.update {
                    it.copy(statusMessage = failure.message ?: "自動保存できませんでした")
                }
            }
        }
    }

    private fun recoverAutosave() {
        val revisionAtStart = projectRevision
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { autosaveStore.load() } }
            if (projectRevision != revisionAtStart) return@launch
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

    private fun syncPattern() {
        val state = mutableUiState.value
        engine.setPattern(state.activeSteps, state.bpm, state.swing)
    }

    private fun observePlaybackCapture() {
        viewModelScope.launch {
            CaptureEventBus.state.collect { captureState ->
                when (captureState) {
                    PlaybackCaptureState.Idle -> Unit
                    PlaybackCaptureState.Recording -> mutableUiState.update {
                        it.copy(
                            systemAudioRecording = true,
                            statusMessage = "端末音声を録音中です",
                        )
                    }
                    is PlaybackCaptureState.Completed -> {
                        mutableUiState.update {
                            it.copy(
                                systemAudioRecording = false,
                                statusMessage = "端末音声録音を読み込んでいます…",
                            )
                        }
                        CaptureEventBus.reset()
                        loadAudio(Uri.fromFile(captureState.file))
                    }
                    is PlaybackCaptureState.Error -> {
                        mutableUiState.update {
                            it.copy(
                                systemAudioRecording = false,
                                statusMessage = captureState.message,
                            )
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
                val snapshot = mutableUiState.value
                if (
                    snapshot.currentStep != step ||
                    snapshot.sourcePlaying != sourceIsPlaying ||
                    (sourceFrame >= 0 && snapshot.sourcePlayheadFrame != sourceFrame)
                ) {
                    mutableUiState.update { state ->
                        state.copy(
                            currentStep = step,
                            sourcePlaying = sourceIsPlaying,
                            sourcePlayheadFrame = sourceFrame.takeIf { it >= 0 }
                                ?: state.sourcePlayheadFrame,
                            statusMessage = if (state.sourcePlaying && !sourceIsPlaying) {
                                "曲の再生が終わりました — PADでチョップを演奏できます"
                            } else {
                                state.statusMessage
                            },
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
