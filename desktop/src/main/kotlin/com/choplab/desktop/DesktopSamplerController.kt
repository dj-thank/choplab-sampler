package com.choplab.desktop

import com.choplab.desktop.audio.DesktopWavDecoder
import com.choplab.desktop.audio.DesktopMicrophoneRecorder
import com.choplab.desktop.audio.DesktopPatternRenderer
import com.choplab.desktop.audio.JavaSoundWavPlayer
import com.choplab.sampler.audio.BuiltInDrumKits
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadTrimSnapshot
import com.choplab.sampler.model.RecordingKind
import com.choplab.sampler.model.RecordingPhase
import com.choplab.sampler.model.RecordingSession
import com.choplab.sampler.model.RepeatGrid
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.assignLiveChopToPad
import com.choplab.sampler.model.clearPadSteps
import com.choplab.sampler.model.replacePadSteps
import com.choplab.sampler.model.selectPlayableBank
import com.choplab.sampler.model.selectPlayablePad
import com.choplab.sampler.model.selectPlayablePadPage
import com.choplab.sampler.model.sliceRanges
import com.choplab.sampler.model.stopAllPlaybackState
import com.choplab.sampler.model.beginRecordingSession
import com.choplab.sampler.model.endRecordingSession
import com.choplab.sampler.model.failRecordingSession
import com.choplab.sampler.model.nextVocalPadIndex
import com.choplab.sampler.model.observeRecordingSession
import com.choplab.sampler.model.isActive
import com.choplab.sampler.model.togglePadStep
import com.choplab.sampler.ui.SamplerDeckController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.Instant

/**
 * JVM shell for the shared deck. Audio/filesystem/permission work stays here;
 * all state shapes and editing operations come from the shared module.
 */
class DesktopSamplerController(
    private val player: JavaSoundWavPlayer,
    private val microphone: DesktopMicrophoneRecorder = DesktopMicrophoneRecorder(),
) : SamplerDeckController, AutoCloseable {
    private val mutableState = MutableStateFlow(SamplerUiState())
    val state: StateFlow<SamplerUiState> = mutableState.asStateFlow()

    fun loadWav(file: File) {
        val audio = DesktopWavDecoder.decode(file)
        player.load(file)
        mutableState.value = SamplerUiState(
            statusMessage = "${file.name}を読み込みました。チョップで音を切ってください",
            currentAudio = audio,
            rangeEndFrame = audio.frameCount,
        )
    }

    fun toggleMicrophoneRecording() = toggleRecording(RecordingKind.SOURCE_MICROPHONE)
    fun toggleSystemAudioRecording() = setStatus("Windows端末音声アダプターを準備中です")
    fun toggleVocalRecording() = toggleRecording(RecordingKind.VOCAL_OVERDUB)
    fun exportBeat() = setStatus("保存先を選択してください")
    fun exportBeat(outputFile: File) {
        val state = mutableState.value
        runCatching {
            DesktopPatternRenderer.renderFourBars(outputFile, state.pads, state.activeSteps, state.bpm, state.swing)
        }.onSuccess {
            setStatus("${outputFile.name}を書き出しました")
        }.onFailure {
            setStatus("WAV書き出し失敗: ${it.message ?: it.javaClass.simpleName}")
        }
    }
    fun openProject() = setStatus("制作ファイルを選択してください")
    fun saveProject() = setStatus("制作保存アダプターを準備中です")

    private fun toggleRecording(kind: RecordingKind) {
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
            "capture-${Instant.now().toEpochMilli()}.wav",
        )
        mutableState.value = beginRecordingSession(mutableState.value, kind)
        microphone.start(output).onSuccess {
            mutableState.update { observeRecordingSession(it, kind).copy(statusMessage = "録音中です。停止ボタンで素材にします") }
        }.onFailure { error ->
            mutableState.update { failRecordingSession(it, kind).copy(statusMessage = "録音開始失敗: ${error.message ?: error.javaClass.simpleName}") }
        }
    }

    private fun stopRecording(kind: RecordingKind) {
        mutableState.update { it.copy(recordingSession = (it.recordingSession as? RecordingSession.Active)?.copy(phase = RecordingPhase.STOPPING) ?: it.recordingSession) }
        microphone.stop().onSuccess { file ->
            val audio = runCatching { DesktopWavDecoder.decode(file) }.getOrElse { failure ->
                mutableState.update { state -> endRecordingSession(state, kind).copy(statusMessage = "録音読込失敗: ${failure.message ?: failure.javaClass.simpleName}") }
                return@onSuccess
            }
            val current = mutableState.value
            val next = if (kind == RecordingKind.VOCAL_OVERDUB) {
                val target = current.pads.nextVocalPadIndex()
                if (target == null) {
                    current.copy(statusMessage = "VOICE BANKが満杯です", recordingSession = RecordingSession.Idle)
                } else {
                    current.copy(
                        pads = current.pads.toMutableList().also { pads ->
                            pads[target] = pads[target].copy(audio = audio, startFrame = 0, endFrame = audio.frameCount, contentKind = com.choplab.sampler.model.PadContentKind.VOCAL)
                        },
                        selectedBank = 3,
                        selectedPad = target,
                        statusMessage = "VOICE PAD ${target + 1}に録音しました",
                        recordingSession = RecordingSession.Idle,
                    )
                }
            } else {
                current.copy(currentAudio = audio, rangeStartFrame = 0, rangeEndFrame = audio.frameCount, sourcePlayheadFrame = 0, statusMessage = "マイク録音を読み込みました。チョップで音を切ってください", recordingSession = RecordingSession.Idle)
            }
            mutableState.value = next
            player.load(file)
        }.onFailure { error ->
            mutableState.update { endRecordingSession(it, kind).copy(statusMessage = "録音停止失敗: ${error.message ?: error.javaClass.simpleName}") }
        }
    }

    fun setStatus(message: String) = mutableState.update { it.copy(statusMessage = message) }

    override fun resetProject() {
        player.stop()
        mutableState.value = SamplerUiState(statusMessage = "音声を読み込むか録音してください")
    }

    override fun stopAllSounds() {
        player.stop()
        mutableState.update(::stopAllPlaybackState)
    }

    override fun stopActiveRecording() {
        val active = mutableState.value.recordingSession as? RecordingSession.Active
        if (active == null) setStatus("録音は停止しています") else if (active.kind == RecordingKind.SOURCE_SYSTEM_AUDIO) {
            setStatus("Windows端末音声録音は現在準備中です")
        } else {
            stopRecording(active.kind)
        }
    }
    override fun stopSourceForWorkspaceChange() = stopAllSounds()
    override fun ensurePlayablePadSelected() = mutableState.update { it.copy(selectedPad = it.pads.firstOrNull(PadModel::isAssigned)?.globalIndex ?: it.selectedPad) }
    override fun prepareDefaultChopDestination() = Unit
    override fun restartSourcePlayback() = toggleSource(true)

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
        val start = state.sourcePlayheadFrame.coerceIn(0, (state.rangeEndFrame - 1).coerceAtLeast(0))
        mutableState.value = assignLiveChopToPad(state, index, start).state
    }

    override fun triggerPad(index: Int) {
        val pad = mutableState.value.pads.getOrNull(index)
        if (pad?.isAssigned == true) {
            player.playPcm(requireNotNull(pad.audio), pad.startFrame, pad.endFrame)
            mutableState.update { it.copy(selectedPad = index, statusMessage = "PAD ${index + 1}を再生中です") }
        } else {
            selectPad(index)
        }
    }
    override fun releasePad(index: Int) = Unit
    override fun previewPad(index: Int) = triggerPad(index)
    override fun playSourceFrom(frame: Int) = seekSourcePlayback(frame).also { toggleSource(true) }
    override fun seekSourcePlayback(frame: Int) = mutableState.update { it.copy(sourcePlayheadFrame = frame.coerceIn(0, it.rangeEndFrame)) }
    override fun toggleSourcePlayback() = toggleSource(!mutableState.value.sourcePlaying)
    override fun toggleChopPlayback() = toggleSource(!mutableState.value.sourcePlaying)

    override fun beginScratch() = setStatus("スクラッチを開始しました")
    override fun beginSourceScratch() = setStatus("元曲のスクラッチを開始しました")
    override fun updateScratchSpeed(speed: Float) = Unit
    override fun endScratch() = setStatus("スクラッチを停止しました")

    private fun toggleSource(shouldPlay: Boolean) {
        if (mutableState.value.currentAudio == null) return
        if (shouldPlay) player.play() else player.stop()
        mutableState.update { it.copy(sourcePlaying = shouldPlay, statusMessage = if (shouldPlay) "元曲を再生中です" else "元曲を停止しました") }
    }

    override fun setMasterPitch(value: Float) = mutableState.update { it.copy(masterPitchSemitones = value.coerceIn(-24f, 24f)) }
    override fun setSelectedPadPitch(value: Float) = updateSelected { it.copy(pitchSemitones = value.coerceIn(-24f, 24f)) }
    override fun setSelectedPadTone(value: Float) = updateSelected { it.copy(tone = value.coerceIn(0f, 1f)) }
    override fun setSelectedPadGain(value: Float) = updateSelected { it.copy(gain = value.coerceIn(0f, 1.5f)) }
    override fun setSelectedPadStartFrame(frame: Int) = updateSelected { pad -> pad.copy(startFrame = frame.coerceIn(0, (pad.endFrame - 1).coerceAtLeast(0))) }
    override fun setSelectedPadEndFrame(frame: Int) = updateSelected { pad -> pad.copy(endFrame = frame.coerceAtLeast(pad.startFrame + 1)) }
    override fun restoreSelectedPadTrim(snapshot: PadTrimSnapshot) = updateSelected { it.copy(startFrame = snapshot.startFrame, endFrame = snapshot.endFrame) }
    override fun toggleSelectedPadReverse() = updateSelected { it.copy(reverse = !it.reverse) }
    override fun toggleSelectedPadPlayMode() = updateSelected { it.copy(playMode = it.playMode.next()) }
    override fun setSelectedPadChokeGroup(group: Int) = updateSelected { it.copy(chokeGroup = group.coerceIn(0, 4)) }
    override fun clearSelectedPad() = updateSelected { PadModel(it.globalIndex) }

    override fun fillSelectedPadPattern(grid: RepeatGrid) = mutableState.update { state ->
        state.copy(activeSteps = state.activeSteps.replacePadSteps(state.selectedPad, grid))
    }
    override fun clearSelectedPadPattern() = mutableState.update { it.copy(activeSteps = it.activeSteps.clearPadSteps(it.selectedPad)) }
    override fun toggleStep(step: Int) = mutableState.update { state ->
        val pad = state.pads[state.selectedPad]
        state.copy(activeSteps = state.activeSteps.togglePadStep(pad, step))
    }
    override fun clearAllPattern() = mutableState.update { it.copy(activeSteps = emptySet()) }
    override fun toggleBeatLoopControl() = setStatus("ビートループ設定を切り替えました")
    override fun toggleTransport() = mutableState.update { it.copy(transportPlaying = !it.transportPlaying) }
    override fun toggleRecordArm() = mutableState.update { it.copy(recordArmed = !it.recordArmed) }
    override fun undoEdit() = setStatus("戻せる操作はありません")
    override fun redoEdit() = setStatus("やり直せる操作はありません")

    override fun applyBuiltInDrumKit(kitId: String, replaceExisting: Boolean) = mutableState.update { state ->
        val kitPads = BuiltInDrumKits.createBankPads(kitId, 1)
        val pads = state.pads.toMutableList()
        kitPads.forEach { pad -> pads[pad.globalIndex] = pad }
        state.copy(pads = pads, statusMessage = "${kitId}をBANK Bへセットしました")
    }

    override fun setBpm(value: Float) = mutableState.update { it.copy(bpm = value.coerceIn(40f, 240f)) }
    override fun setSwing(value: Float) = mutableState.update { it.copy(swing = value.coerceIn(50f, 75f)) }
    override fun addSliceMarker(frame: Int) = mutableState.update { it.copy(sliceMarkers = (it.sliceMarkers + frame).distinct().sorted()) }
    override fun selectSliceAt(frame: Int) = mutableState.update { it.copy(activeSliceIndex = it.sliceRanges().indexOfFirst { range -> frame in range.startFrame until range.endFrame }.takeIf { index -> index >= 0 }) }
    override fun setRangeStart(frame: Int) = mutableState.update { it.copy(rangeStartFrame = frame.coerceAtLeast(0)) }
    override fun setRangeEnd(frame: Int) = mutableState.update { it.copy(rangeEndFrame = frame.coerceAtLeast(1)) }
    override fun moveSliceMarker(markerIndex: Int, frame: Int) = mutableState.update { state -> state.copy(sliceMarkers = state.sliceMarkers.toMutableList().also { if (markerIndex in it.indices) it[markerIndex] = frame }.sorted()) }

    private fun updateSelected(transform: (PadModel) -> PadModel) {
        mutableState.update { state -> state.copy(pads = state.pads.toMutableList().also { it[state.selectedPad] = transform(it[state.selectedPad]) }) }
    }

    override fun close() {
        microphone.close()
        player.close()
    }
}

private fun com.choplab.sampler.model.PadPlayMode.next() = when (this) {
    com.choplab.sampler.model.PadPlayMode.ONE_SHOT -> com.choplab.sampler.model.PadPlayMode.GATE
    com.choplab.sampler.model.PadPlayMode.GATE -> com.choplab.sampler.model.PadPlayMode.LOOP
    com.choplab.sampler.model.PadPlayMode.LOOP -> com.choplab.sampler.model.PadPlayMode.ONE_SHOT
}
