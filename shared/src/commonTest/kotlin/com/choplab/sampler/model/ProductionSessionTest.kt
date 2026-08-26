package com.choplab.sampler.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProductionSessionTest {
    @Test
    fun projectCommandCommitsHistoryRevisionAndUndoRedoTogether() {
        val session = ProductionSession()
        val initial = SamplerUiState()

        val applied = session.commit(
            session.planCommand(initial, ProductionCommand.ToggleSelectedPadPerformanceMode),
        )

        assertEquals(ProductionMutation.PROJECT, applied.mutation)
        assertEquals(1L, applied.revision)
        assertTrue(applied.persistenceRequired)
        assertTrue(applied.state.canUndo)
        assertFalse(applied.state.canRedo)

        val undone = assertNotNull(session.undo(applied.state))
        assertEquals(2L, undone.revision)
        assertEquals(PadPlayMode.ONE_SHOT, undone.state.pads[0].playMode)
        assertTrue(undone.state.canRedo)

        val redone = assertNotNull(session.redo(undone.state))
        assertEquals(3L, redone.revision)
        assertEquals(PadPlayMode.GATE, redone.state.pads[0].playMode)
        assertTrue(redone.state.canUndo)
    }

    @Test
    fun historyPlanPreviewsWithoutConsumptionAndCancelKeepsTheFrontier() {
        val session = ProductionSession()
        val initial = SamplerUiState()
        val edited = session.applyEdit(initial, initial.copy(bpm = 126f))

        val plan = assertNotNull(session.planUndo(edited.state))

        assertEquals(92f, plan.restoredState.bpm)
        assertEquals(1L, session.revision)
        assertTrue(session.canUndo)
        assertFalse(session.canRedo)

        session.cancel(plan)

        assertEquals(1L, session.revision)
        assertTrue(session.canUndo)
        assertFalse(session.canRedo)
        assertFailsWith<IllegalArgumentException> { session.commit(plan) }

        val undone = assertNotNull(session.undo(edited.state))
        assertEquals(92f, undone.state.bpm)
        assertEquals(2L, undone.revision)
        assertTrue(undone.state.canRedo)
    }

    @Test
    fun busyRuntimeOwnersCannotPreviewOrConsumeHistory() {
        val session = ProductionSession()
        val initial = SamplerUiState()
        val edited = session.applyEdit(initial, initial.copy(bpm = 126f))

        val stalePlan = assertNotNull(session.planUndo(edited.state))
        val loading = edited.state.copy(isLoading = true)
        assertNull(session.planUndo(loading))
        assertFailsWith<IllegalArgumentException> { session.commit(stalePlan) }
        assertNull(session.undo(loading))
        assertEquals(1L, session.revision)
        assertTrue(session.canUndo)
        assertFalse(session.canRedo)

        val recording = edited.state.copy(
            recordingSession = RecordingSession.Active(
                RecordingKind.VOCAL_OVERDUB,
                RecordingPhase.RECORDING,
            ),
        )
        assertNull(session.planUndo(recording))
        assertNull(session.undo(recording))
        assertEquals(1L, session.revision)
        assertTrue(session.canUndo)
        assertFalse(session.canRedo)

        val undone = assertNotNull(session.undo(edited.state))
        assertEquals(92f, undone.state.bpm)
        assertEquals(2L, session.revision)
        assertFalse(session.canUndo)
        assertTrue(session.canRedo)

        val redoLoading = undone.state.copy(isLoading = true)
        assertNull(session.planRedo(redoLoading))
        assertNull(session.redo(redoLoading))
        assertEquals(2L, session.revision)
        assertTrue(session.canRedo)

        val redone = assertNotNull(session.redo(undone.state))
        assertEquals(126f, redone.state.bpm)
    }

    @Test
    fun committedHistoryPlansAreExactOnceAndRedoCanBePreviewed() {
        val session = ProductionSession()
        val initial = SamplerUiState()
        val edited = session.applyEdit(initial, initial.copy(bpm = 126f))
        val undoPlan = assertNotNull(session.planUndo(edited.state))

        val undone = session.commit(undoPlan)

        assertEquals(92f, undone.state.bpm)
        assertEquals(2L, undone.revision)
        assertFailsWith<IllegalArgumentException> { session.commit(undoPlan) }

        val redoPlan = assertNotNull(session.planRedo(undone.state))
        assertEquals(126f, redoPlan.restoredState.bpm)
        val redone = session.commit(redoPlan)
        assertEquals(126f, redone.state.bpm)
        assertEquals(3L, redone.revision)
    }

    @Test
    fun staleAndCrossSessionHistoryPlansCannotConsumeTheFrontier() {
        val owner = ProductionSession()
        val other = ProductionSession()
        val initial = SamplerUiState()
        val edited = owner.applyEdit(initial, initial.copy(bpm = 126f))
        val crossSessionPlan = assertNotNull(owner.planUndo(edited.state))

        assertFailsWith<IllegalArgumentException> { other.commit(crossSessionPlan) }
        assertTrue(owner.canUndo)
        assertFalse(owner.canRedo)
        owner.cancel(crossSessionPlan)

        val stalePlan = assertNotNull(owner.planUndo(edited.state))
        val status = owner.applyEdit(edited.state, edited.state.copy(statusMessage = "newer"))

        assertFailsWith<IllegalArgumentException> { owner.commit(stalePlan) }
        assertEquals(1L, owner.revision)
        assertTrue(owner.canUndo)
        assertFalse(owner.canRedo)

        val undone = assertNotNull(owner.undo(status.state))
        assertEquals(92f, undone.state.bpm)
    }

    @Test
    fun quickSketchIsOneUndoRedoAndPersistenceUnit() {
        val session = ProductionSession()
        val audio = PcmAudio(
            name = "sketch.wav",
            samples = ShortArray(1_600) { frame ->
                if ((frame / 100) % 2 == 0) (-2_000).toShort() else 2_000.toShort()
            },
            sampleRate = 8_000,
        )
        val initial = SamplerUiState(currentAudio = audio, rangeEndFrame = audio.frameCount)

        val applied = session.commit(
            session.planCommand(initial, ProductionCommand.CreateQuickSketch),
        )

        assertEquals(1L, applied.revision)
        assertTrue(applied.persistenceRequired)
        assertEquals(8, applied.state.pads.take(8).count(PadModel::isAssigned))
        assertEquals(8, applied.state.activeSteps.size)

        val undone = assertNotNull(session.undo(applied.state))
        assertEquals(initial.pads, undone.state.pads)
        assertEquals(initial.activeSteps, undone.state.activeSteps)
        assertEquals(initial.sliceMarkers, undone.state.sliceMarkers)

        val redone = assertNotNull(session.redo(undone.state))
        assertEquals(applied.state.pads, redone.state.pads)
        assertEquals(applied.state.activeSteps, redone.state.activeSteps)
        assertEquals(applied.state.sliceMarkers, redone.state.sliceMarkers)
    }

    @Test
    fun sessionSelectionDoesNotCreateRevisionHistoryOrPersistence() {
        val session = ProductionSession()
        val audio = PcmAudio(name = "selection.wav", samples = ShortArray(400), sampleRate = 1_000)
        val state = SamplerUiState(
            currentAudio = audio,
            rangeEndFrame = audio.frameCount,
            sliceMarkers = listOf(200),
        )

        val transition = session.commit(
            session.planCommand(state, ProductionCommand.SelectSliceAt(250)),
        )

        assertEquals(ProductionMutation.SESSION, transition.mutation)
        assertEquals(0L, transition.revision)
        assertFalse(transition.persistenceRequired)
        assertEquals(1, transition.state.activeSliceIndex)
        assertFalse(transition.state.canUndo)
        assertNull(session.undo(transition.state))
    }

    @Test
    fun cancelledBlockingPlanCannotMutateHistoryOrBeCommittedLater() {
        val session = ProductionSession()
        val audio = PcmAudio(name = "loop.wav", samples = ShortArray(400), sampleRate = 1_000)
        val loopPad = PadModel(0, audio, 0, audio.frameCount, playMode = PadPlayMode.LOOP)
        val state = SamplerUiState(
            pads = List(SamplerConfig.PAD_COUNT) { index -> if (index == 0) loopPad else PadModel(index) },
            selectedPad = 0,
            loopingPadIndex = 0,
        )
        val plan = session.planCommand(state, ProductionCommand.ToggleSelectedPadPerformanceMode)

        assertTrue(plan.effects.first() is ProductionEffect.StopPad)
        session.cancel(plan)

        assertEquals(0L, session.revision)
        assertFalse(session.canUndo)
        assertFailsWith<IllegalArgumentException> { session.commit(plan) }
    }

    @Test
    fun statusOnlyEditIsSessionMutationWhileMergeKeyCoalescesProjectHistory() {
        val session = ProductionSession()
        val initial = SamplerUiState()

        val status = session.applyEdit(initial, initial.copy(statusMessage = "案内"))
        assertEquals(ProductionMutation.SESSION, status.mutation)
        assertEquals(0L, status.revision)
        assertFalse(status.state.canUndo)

        val first = session.applyEdit(status.state, status.state.copy(bpm = 120f), mergeKey = "bpm")
        val second = session.applyEdit(first.state, first.state.copy(bpm = 130f), mergeKey = "bpm")
        assertEquals(2L, second.revision)

        val undone = assertNotNull(session.undo(second.state))
        assertEquals(92f, undone.state.bpm)
    }

    @Test
    fun newerSessionMutationInvalidatesAnOlderCommandPlan() {
        val session = ProductionSession()
        val state = SamplerUiState()
        val plan = session.planCommand(state, ProductionCommand.ToggleSelectedPadPerformanceMode)

        session.applyEdit(state, state.copy(statusMessage = "newer"))

        assertFailsWith<IllegalArgumentException> { session.commit(plan) }
        assertEquals(0L, session.revision)
    }

    @Test
    fun commandPlanCannotBeCommittedByAnotherProductionSession() {
        val owner = ProductionSession()
        val other = ProductionSession()
        val plan = owner.planCommand(
            SamplerUiState(),
            ProductionCommand.ToggleSelectedPadPerformanceMode,
        )

        assertFailsWith<IllegalArgumentException> { other.commit(plan) }
        assertEquals(0L, other.revision)
        assertFalse(other.canUndo)

        owner.cancel(plan)
        assertEquals(0L, owner.revision)
    }

    @Test
    fun replacingProjectResetsHistoryAndCanOptionallyPreserveRevision() {
        val session = ProductionSession()
        val initial = SamplerUiState()
        val edited = session.applyEdit(initial, initial.copy(bpm = 120f))
        assertTrue(edited.state.canUndo)

        val replaced = session.replaceProject(SamplerUiState(bpm = 80f))
        assertEquals(2L, replaced.revision)
        assertFalse(replaced.state.canUndo)

        val restored = session.replaceProject(
            SamplerUiState(bpm = 100f),
            persistenceRequired = false,
            recoveredRevision = 12L,
        )
        assertEquals(13L, restored.revision)
        assertEquals(ProductionMutation.PROJECT, restored.mutation)
        assertFalse(restored.persistenceRequired)
    }
}
