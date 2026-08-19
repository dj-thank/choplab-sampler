package com.choplab.desktop

import com.choplab.desktop.audio.DesktopWavDecoder
import com.choplab.desktop.audio.JavaSoundWavPlayer
import com.choplab.sampler.audio.BuiltInDrumKits
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadTrimSnapshot
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
import com.choplab.sampler.model.togglePadStep
import com.choplab.sampler.ui.SamplerDeckController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/**
 * JVM shell for the shared deck. Audio/filesystem/permission work stays here;
 * all state shapes and editing operations come from the shared module.
 */
class DesktopSamplerController(
    private val player: JavaSoundWavPlayer,
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

    fun toggleMicrophoneRecording() = setStatus("Windows録音アダプターを準備中です")
    fun toggleSystemAudioRecording() = setStatus("Windows端末音声アダプターを準備中です")
    fun toggleVocalRecording() = setStatus("Windowsボーカル録音アダプターを準備中です")
    fun exportBeat() = setStatus("WAV書き出しアダプターを準備中です")
    fun openProject() = setStatus("制作ファイルを選択してください")
    fun saveProject() = setStatus("制作保存アダプターを準備中です")

    fun setStatus(message: String) = mutableState.update { it.copy(statusMessage = message) }

    override fun resetProject() {
        player.stop()
        mutableState.value = SamplerUiState(statusMessage = "音声を読み込むか録音してください")
    }

    override fun stopAllSounds() {
        player.stop()
        mutableState.update(::stopAllPlaybackState)
    }

    override fun stopActiveRecording() = setStatus("録音は停止しています")
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

    override fun close() = player.close()
}

private fun com.choplab.sampler.model.PadPlayMode.next() = when (this) {
    com.choplab.sampler.model.PadPlayMode.ONE_SHOT -> com.choplab.sampler.model.PadPlayMode.GATE
    com.choplab.sampler.model.PadPlayMode.GATE -> com.choplab.sampler.model.PadPlayMode.LOOP
    com.choplab.sampler.model.PadPlayMode.LOOP -> com.choplab.sampler.model.PadPlayMode.ONE_SHOT
}
