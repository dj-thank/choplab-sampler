package com.choplab.sampler.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductionCommandTest {
    @Test
    fun sourceRangeUsesSharedMinimumAndZeroCrossingPolicy() {
        val audio = crossingAudio()
        val state = SamplerUiState(
            currentAudio = audio,
            rangeStartFrame = 0,
            rangeEndFrame = audio.frameCount,
            sliceMarkers = listOf(80, 180),
            activeSliceIndex = 1,
        )

        val result = reduceProductionCommand(
            state,
            ProductionCommand.SetSourceRangeStart(frame = 104),
        )

        assertEquals(ProductionMutation.PROJECT, result.mutation)
        assertEquals("range-start", result.mergeKey)
        assertEquals(100, result.state.rangeStartFrame)
        assertEquals(listOf(180), result.state.sliceMarkers)
        assertNull(result.state.activeSliceIndex)
    }

    @Test
    fun invalidMarkerRequestChangesGuidanceWithoutCreatingProjectHistory() {
        val audio = crossingAudio(frameCount = 120)
        val state = SamplerUiState(
            currentAudio = audio,
            rangeStartFrame = 0,
            rangeEndFrame = audio.frameCount,
        )

        val result = reduceProductionCommand(
            state,
            ProductionCommand.AddSliceMarker(frame = 60),
        )

        assertEquals(ProductionMutation.SESSION, result.mutation)
        assertEquals(state.sliceMarkers, result.state.sliceMarkers)
        assertEquals("選択範囲が短く、チョップ位置を追加できません", result.state.statusMessage)
    }

    @Test
    fun selectingSliceIsSessionOnlyAndUsesEndExclusiveRanges() {
        val audio = crossingAudio()
        val state = SamplerUiState(
            currentAudio = audio,
            rangeStartFrame = 0,
            rangeEndFrame = audio.frameCount,
            sliceMarkers = listOf(100),
        )

        val selected = reduceProductionCommand(state, ProductionCommand.SelectSliceAt(100))
        val atExclusiveEnd = reduceProductionCommand(selected.state, ProductionCommand.SelectSliceAt(audio.frameCount))

        assertEquals(ProductionMutation.SESSION, selected.mutation)
        assertEquals(1, selected.state.activeSliceIndex)
        assertEquals(ProductionMutation.SESSION, atExclusiveEnd.mutation)
        assertNull(atExclusiveEnd.state.activeSliceIndex)
    }

    @Test
    fun performanceModeNeverEntersLoopAndReleasesExistingLoopOwnership() {
        val audio = crossingAudio()
        val loopPad = PadModel(
            globalIndex = 0,
            audio = audio,
            startFrame = 0,
            endFrame = audio.frameCount,
            playMode = PadPlayMode.LOOP,
        )
        val state = SamplerUiState(
            currentAudio = audio,
            rangeEndFrame = audio.frameCount,
            pads = List(SamplerConfig.PAD_COUNT) { index -> if (index == 0) loopPad else PadModel(index) },
            selectedPad = 0,
            loopingPadIndex = 0,
            loopPlayheadFrame = 50,
        )

        val result = reduceProductionCommand(
            state,
            ProductionCommand.ToggleSelectedPadPerformanceMode,
        )

        assertEquals(ProductionMutation.PROJECT, result.mutation)
        assertEquals(PadPlayMode.GATE, result.state.pads[0].playMode)
        assertNull(result.state.loopingPadIndex)
        assertEquals(-1, result.state.loopPlayheadFrame)
        assertEquals(3, result.effects.size)
        assertEquals(ProductionEffect.StopPad(0), result.effects[0])
        assertIs<ProductionEffect.RefreshPad>(result.effects[1])
        assertEquals(ProductionEffect.RefreshPattern, result.effects[2])
    }

    @Test
    fun durableCommandsFailClosedDuringLoadingAndRecording() {
        val loading = SamplerUiState(isLoading = true)
        val recording = SamplerUiState(
            recordingSession = RecordingSession.Active(
                kind = RecordingKind.SOURCE_MICROPHONE,
                phase = RecordingPhase.RECORDING,
            ),
        )

        val loadingResult = reduceProductionCommand(
            loading,
            ProductionCommand.ToggleSelectedPadPerformanceMode,
        )
        val recordingResult = reduceProductionCommand(
            recording,
            ProductionCommand.ToggleSelectedPadPerformanceMode,
        )

        assertEquals(ProductionMutation.SESSION, loadingResult.mutation)
        assertEquals(loading.pads, loadingResult.state.pads)
        assertEquals("現在の処理が終わってから編集してください", loadingResult.state.statusMessage)
        assertEquals(ProductionMutation.SESSION, recordingResult.mutation)
        assertEquals(recording.pads, recordingResult.state.pads)
        assertEquals("録音をSTOPしてから編集してください", recordingResult.state.statusMessage)
        assertTrue(loadingResult.effects.isEmpty())
        assertTrue(recordingResult.effects.isEmpty())
    }

    private fun crossingAudio(frameCount: Int = 400): PcmAudio {
        val samples = ShortArray(frameCount) { frame ->
            when {
                frame < 100 -> (-2_000).toShort()
                frame < 250 -> 2_000.toShort()
                else -> (-2_000).toShort()
            }
        }
        return PcmAudio(
            name = "contract.wav",
            samples = samples,
            sampleRate = 1_000,
        )
    }
}
