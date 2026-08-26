package com.choplab.sampler.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductionCommandTest {
    @Test
    fun stereoZeroCrossingUsesAudioFramesInsteadOfInterleavedSampleOffsets() {
        val audio = PcmAudio(
            name = "stereo-crossing",
            samples = ShortArray(400 * 2) { sample ->
                if (sample / 2 < 100) (-2_000).toShort() else 2_000.toShort()
            },
            sampleRate = 8_000,
            channelCount = 2,
        )

        assertEquals(
            100,
            snapFrameToZeroCrossing(audio, targetFrame = 104, lowerBound = 0, upperBound = audio.frameCount),
        )
    }

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
    fun sourceRangeEndUsesTheSameZeroCrossingAndMarkerFilteringPolicy() {
        val audio = crossingAudio()
        val state = SamplerUiState(
            currentAudio = audio,
            rangeStartFrame = 0,
            rangeEndFrame = audio.frameCount,
            sliceMarkers = listOf(100, 300),
            activeSliceIndex = 2,
        )

        val result = reduceProductionCommand(
            state,
            ProductionCommand.SetSourceRangeEnd(frame = 246),
        )

        assertEquals(ProductionMutation.PROJECT, result.mutation)
        assertEquals("range-end", result.mergeKey)
        assertEquals(250, result.state.rangeEndFrame)
        assertEquals(listOf(100), result.state.sliceMarkers)
        assertNull(result.state.activeSliceIndex)
    }

    @Test
    fun movingSliceMarkerIsBoundedAndCarriesAStableMergeKey() {
        val audio = PcmAudio(
            name = "marker.wav",
            samples = ShortArray(400) { frame -> if (frame < 180) -2_000 else 2_000 },
            sampleRate = 1_000,
        )
        val state = SamplerUiState(
            currentAudio = audio,
            rangeStartFrame = 0,
            rangeEndFrame = audio.frameCount,
            sliceMarkers = listOf(100, 300),
        )

        val result = reduceProductionCommand(
            state,
            ProductionCommand.MoveSliceMarker(markerIndex = 0, frame = 184),
        )

        assertEquals(ProductionMutation.PROJECT, result.mutation)
        assertEquals("slice-marker-0", result.mergeKey)
        assertEquals(listOf(180, 300), result.state.sliceMarkers)
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

    @Test
    fun quickSketchCreatesEightSafeMelodyPadsAndPreservesOtherBanks() {
        val audio = quickSketchAudio()
        val drum = PadModel(
            globalIndex = SamplerConfig.PADS_PER_BANK,
            audio = audio,
            startFrame = 0,
            endFrame = 120,
            contentKind = PadContentKind.DRUM,
        )
        val originalPads = List(SamplerConfig.PAD_COUNT) { index ->
            if (index == drum.globalIndex) drum else PadModel(index)
        }
        val preservedSteps = setOf(
            stepKey(drum.globalIndex, 0),
            stepKey(SamplerConfig.PADS_PER_BANK * 2, 5),
        )
        val state = SamplerUiState(
            currentAudio = audio,
            rangeStartFrame = 0,
            rangeEndFrame = audio.frameCount,
            selectedBank = 2,
            selectedPad = SamplerConfig.PADS_PER_BANK * 2,
            pads = originalPads,
            activeSteps = preservedSteps,
        )

        val result = reduceProductionCommand(state, ProductionCommand.CreateQuickSketch)

        assertEquals(ProductionMutation.PROJECT, result.mutation)
        assertEquals(0, result.state.selectedBank)
        assertEquals(0, result.state.selectedPad)
        assertEquals((1 until 8).map { it * 200 }, result.state.sliceMarkers)
        val sketchPads = result.state.pads.take(8)
        assertTrue(sketchPads.all { it.audio?.id == audio.id && it.isAssigned })
        assertEquals(
            List(8) { index -> index * 200 to (index + 1) * 200 },
            sketchPads.map { it.startFrame to it.endFrame },
        )
        assertTrue(result.state.pads.subList(8, SamplerConfig.PADS_PER_BANK).none(PadModel::isAssigned))
        assertEquals(originalPads.drop(SamplerConfig.PADS_PER_BANK), result.state.pads.drop(SamplerConfig.PADS_PER_BANK))
        assertEquals(
            preservedSteps + List(8) { index -> stepKey(index, index * 2) },
            result.state.activeSteps,
        )
        assertTrue("元に戻せます" in result.state.statusMessage)
        assertEquals(8, result.effects.filterIsInstance<ProductionEffect.RefreshPad>().size)
        assertEquals(ProductionEffect.RefreshPattern, result.effects.last())
    }

    @Test
    fun quickSketchRejectsExistingMelodyWorkWithoutChangingProject() {
        val audio = quickSketchAudio()
        val assignedA32 = PadModel(
            globalIndex = SamplerConfig.PADS_PER_BANK - 1,
            audio = audio,
            startFrame = 0,
            endFrame = 200,
        )
        val withAssignedPad = SamplerUiState(
            currentAudio = audio,
            rangeEndFrame = audio.frameCount,
            pads = List(SamplerConfig.PAD_COUNT) { index ->
                if (index == assignedA32.globalIndex) assignedA32 else PadModel(index)
            },
        )
        val withStaleStep = SamplerUiState(
            currentAudio = audio,
            rangeEndFrame = audio.frameCount,
            activeSteps = setOf(stepKey(12, 4)),
        )

        val assignedResult = reduceProductionCommand(withAssignedPad, ProductionCommand.CreateQuickSketch)
        val stepResult = reduceProductionCommand(withStaleStep, ProductionCommand.CreateQuickSketch)

        assertEquals(ProductionMutation.SESSION, assignedResult.mutation)
        assertEquals(withAssignedPad.pads, assignedResult.state.pads)
        assertEquals(withAssignedPad.activeSteps, assignedResult.state.activeSteps)
        assertTrue(assignedResult.effects.isEmpty())
        assertTrue("変更していません" in assignedResult.state.statusMessage)
        assertEquals(ProductionMutation.SESSION, stepResult.mutation)
        assertEquals(withStaleStep.pads, stepResult.state.pads)
        assertEquals(withStaleStep.activeSteps, stepResult.state.activeSteps)
        assertTrue(stepResult.effects.isEmpty())
    }

    @Test
    fun quickSketchRejectsShortRangeAndExistingMarkers() {
        val shortAudio = quickSketchAudio(frameCount = minimumChopFrames(8_000) * 8 - 1)
        val markedAudio = quickSketchAudio()
        val shortState = SamplerUiState(
            currentAudio = shortAudio,
            rangeEndFrame = shortAudio.frameCount,
        )
        val markedState = SamplerUiState(
            currentAudio = markedAudio,
            rangeEndFrame = markedAudio.frameCount,
            sliceMarkers = listOf(200),
        )

        val shortResult = reduceProductionCommand(shortState, ProductionCommand.CreateQuickSketch)
        val markedResult = reduceProductionCommand(markedState, ProductionCommand.CreateQuickSketch)

        assertEquals(ProductionMutation.SESSION, shortResult.mutation)
        assertTrue(shortResult.state.pads.none(PadModel::isAssigned))
        assertFalse(shortResult.state.activeSteps.isNotEmpty())
        assertTrue("短" in shortResult.state.statusMessage)
        assertEquals(ProductionMutation.SESSION, markedResult.mutation)
        assertEquals(markedState.sliceMarkers, markedResult.state.sliceMarkers)
        assertTrue("変更していません" in markedResult.state.statusMessage)
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

    private fun quickSketchAudio(frameCount: Int = 1_600): PcmAudio = PcmAudio(
        name = "quick-sketch.wav",
        samples = ShortArray(frameCount) { frame ->
            if ((frame / 100) % 2 == 0) (-2_000).toShort() else 2_000.toShort()
        },
        sampleRate = 8_000,
    )
}
