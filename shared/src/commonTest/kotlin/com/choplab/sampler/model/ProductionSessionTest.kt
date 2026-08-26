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
