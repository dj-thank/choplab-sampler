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
import com.choplab.sampler.audio.TransientDetector
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.SliceRange
import com.choplab.sampler.model.activeSliceRange
import com.choplab.sampler.model.selectedPadModel
import com.choplab.sampler.model.sliceRanges
import com.choplab.sampler.model.stepKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SamplerViewModel(application: Application) : AndroidViewModel(application) {
    private val decoder = AudioDecoder(application)
    private val microphoneRecorder = MicrophoneRecorder()
    private val engine = SamplerEngine(application) { message ->
        viewModelScope.launch { setStatus(message) }
    }

    private val mutableUiState = MutableStateFlow(SamplerUiState())
    val uiState: StateFlow<SamplerUiState> = mutableUiState.asStateFlow()

    init {
        engine.start()
        observePlaybackCapture()
        pollTransportStep()
    }

    fun loadAudio(uri: Uri) {
        viewModelScope.launch {
            mutableUiState.update {
                it.copy(isLoading = true, statusMessage = "音声を解析しています…")
            }
            runCatching { decoder.decode(uri) }
                .onSuccess { audio ->
                    mutableUiState.update { state ->
                        state.copy(
                            isLoading = false,
                            currentAudio = audio,
                            rangeStartFrame = 0,
                            rangeEndFrame = audio.frameCount,
                            sliceMarkers = emptyList(),
                            activeSliceIndex = null,
                            manualChopEnabled = false,
                            statusMessage = "${audio.name} を読み込みました",
                        )
                    }
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
        mutableUiState.update { state ->
            val audio = state.currentAudio ?: return@update state
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
        mutableUiState.update { state ->
            val audio = state.currentAudio ?: return@update state
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
        mutableUiState.update { state ->
            val audio = state.currentAudio ?: return@update state
            state.copy(
                rangeStartFrame = 0,
                rangeEndFrame = audio.frameCount,
                sliceMarkers = emptyList(),
                activeSliceIndex = null,
            )
        }
    }

    fun toggleManualChop() {
        mutableUiState.update { it.copy(manualChopEnabled = !it.manualChopEnabled) }
    }

    fun addSliceMarker(frame: Int) {
        mutableUiState.update { state ->
            val audio = state.currentAudio ?: return@update state
            val minimum = minimumSliceFrames(audio.sampleRate)
            if (state.rangeEndFrame - state.rangeStartFrame < minimum * 2) {
                return@update state.copy(statusMessage = "選択範囲が短く、チョップ位置を追加できません")
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
                return@update state.copy(statusMessage = "既存スライスに近すぎます")
            }
            state.copy(
                sliceMarkers = (state.sliceMarkers + safe).distinct().sorted(),
                activeSliceIndex = null,
                statusMessage = "チョップ位置を追加しました",
            )
        }
    }

    fun clearSliceMarkers() {
        mutableUiState.update {
            it.copy(sliceMarkers = emptyList(), activeSliceIndex = null, statusMessage = "チョップ位置を消去しました")
        }
    }

    fun moveSliceMarker(markerIndex: Int, frame: Int) {
        mutableUiState.update { state ->
            val audio = state.currentAudio ?: return@update state
            if (markerIndex !in state.sliceMarkers.indices) return@update state

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
            if (lower > upper) return@update state

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
        mutableUiState.update { state ->
            if (state.sliceMarkers.isEmpty()) {
                return@update state.copy(statusMessage = "削除できるチョップ境界がありません")
            }
            val sliceIndex = state.activeSliceIndex
                ?: return@update state.copy(statusMessage = "先に波形上のスライスを選択してください")
            val markerIndex = if (sliceIndex < state.sliceMarkers.size) sliceIndex else sliceIndex - 1
            if (markerIndex !in state.sliceMarkers.indices) return@update state

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
        mutableUiState.update { state ->
            val audio = state.currentAudio ?: return@update state
            if (state.rangeEndFrame <= state.rangeStartFrame) return@update state
            val length = state.rangeEndFrame - state.rangeStartFrame
            val maximumByLength = length / minimumSliceFrames(audio.sampleRate)
            if (maximumByLength < 2) {
                return@update state.copy(statusMessage = "選択範囲が短く、等分チョップできません")
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
                mutableUiState.update { state ->
                    if (
                        state.currentAudio?.id != audio.id ||
                        state.rangeStartFrame != snapshot.rangeStartFrame ||
                        state.rangeEndFrame != snapshot.rangeEndFrame
                    ) {
                        return@update state.copy(
                            isLoading = false,
                            statusMessage = "範囲が変更されたため、自動チョップ結果を破棄しました",
                        )
                    }
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
        mutableUiState.update { state ->
            val audio = state.currentAudio ?: return@update state
            val mutablePads = state.pads.toMutableList()
            val bankStart = state.selectedBank * SamplerConfig.PADS_PER_BANK
            var indexInBank = state.selectedPad - bankStart
            val changed = mutableListOf<PadModel>()

            ranges.forEach { range ->
                val globalIndex = bankStart + indexInBank
                val oldPad = mutablePads[globalIndex]
                val newPad = oldPad.copy(
                    audio = audio,
                    startFrame = range.startFrame,
                    endFrame = range.endFrame,
                )
                mutablePads[globalIndex] = newPad
                changed += newPad
                indexInBank = (indexInBank + 1) % SamplerConfig.PADS_PER_BANK
            }

            padsToSync = changed
            val nextPad = if (state.autoNextPad) bankStart + indexInBank else state.selectedPad
            val currentSlice = state.activeSliceIndex
            val nextSlice = if (
                state.autoNextPad &&
                ranges.size == 1 &&
                currentSlice != null
            ) {
                (currentSlice + 1).takeIf { it < state.sliceRanges().size }
                    ?: currentSlice
            } else {
                currentSlice
            }
            state.copy(
                pads = mutablePads,
                selectedPad = nextPad,
                activeSliceIndex = nextSlice,
                statusMessage = message,
            )
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
        val pad = state.pads.getOrNull(globalIndex) ?: return
        if (!pad.isAssigned) return
        engine.triggerPad(globalIndex)

        if (state.recordArmed && state.transportPlaying) {
            val step = engine.currentStep.get()
            if (step in 0 until SamplerConfig.STEP_COUNT) {
                mutableUiState.update { current ->
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

    fun toggleAutoNext() {
        mutableUiState.update { it.copy(autoNextPad = !it.autoNextPad) }
    }

    fun setSelectedPadPitch(value: Float) = updateSelectedPad {
        it.copy(pitchSemitones = value.coerceIn(-24f, 24f))
    }

    fun setSelectedPadTone(value: Float) = updateSelectedPad {
        it.copy(tone = value.coerceIn(0f, 1f))
    }

    fun setSelectedPadGain(value: Float) = updateSelectedPad {
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
        mutableUiState.update { state ->
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

    private fun updateSelectedPad(transform: (PadModel) -> PadModel) {
        var changed: PadModel? = null
        mutableUiState.update { state ->
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
        mutableUiState.update { state ->
            val key = stepKey(state.selectedPad, stepIndex)
            val updated = if (key in state.activeSteps) state.activeSteps - key else state.activeSteps + key
            state.copy(activeSteps = updated)
        }
        syncPattern()
    }

    fun setBpm(value: Float) {
        mutableUiState.update { it.copy(bpm = value.coerceIn(40f, 240f)) }
        syncPattern()
    }

    fun setSwing(value: Float) {
        mutableUiState.update { it.copy(swing = value.coerceIn(50f, 75f)) }
        syncPattern()
    }

    fun toggleTransport() {
        val playing = mutableUiState.value.transportPlaying
        if (playing) {
            engine.stopTransport()
            mutableUiState.update { it.copy(transportPlaying = false, currentStep = -1) }
        } else {
            syncPattern()
            engine.startTransport()
            mutableUiState.update { it.copy(transportPlaying = true) }
        }
    }

    fun toggleRecordArm() {
        mutableUiState.update { it.copy(recordArmed = !it.recordArmed) }
    }

    fun clearSelectedPadPattern() {
        mutableUiState.update { state ->
            val filtered = state.activeSteps.filterNot { key ->
                key / SamplerConfig.STEP_COUNT == state.selectedPad
            }.toSet()
            state.copy(activeSteps = filtered, statusMessage = "選択PADのシーケンスを消去しました")
        }
        syncPattern()
    }

    fun clearAllPattern() {
        mutableUiState.update { it.copy(activeSteps = emptySet(), statusMessage = "パターンを全消去しました") }
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

    fun setStatus(message: String) {
        mutableUiState.update { it.copy(statusMessage = message) }
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
                val step = engine.currentStep.get()
                if (mutableUiState.value.currentStep != step) {
                    mutableUiState.update { it.copy(currentStep = step) }
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
        const val ZERO_CROSSING_SEARCH_SECONDS = 0.004f
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
