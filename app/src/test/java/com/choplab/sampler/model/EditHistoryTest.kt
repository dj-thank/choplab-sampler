package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditHistoryTest {
    @Test
    fun undoRedoIsBoundedAndRestoresTheLatestMusicState() {
        val history = EditHistory(maxEntries = 2)
        val bpm92 = SamplerUiState(bpm = 92f)
        val bpm100 = bpm92.copy(bpm = 100f)
        val bpm110 = bpm92.copy(bpm = 110f)
        val bpm120 = bpm92.copy(bpm = 120f)

        history.record(bpm92)
        history.record(bpm100)
        history.record(bpm110)

        assertTrue(history.canUndo)
        assertEquals(110f, history.undo(bpm120)?.bpm)
        assertEquals(100f, history.undo(bpm110)?.bpm)
        assertNull(history.undo(bpm100))
        assertFalse(history.canUndo)

        assertEquals(110f, history.redo(bpm100)?.bpm)
        assertEquals(120f, history.redo(bpm110)?.bpm)
        assertNull(history.redo(bpm120))
        assertFalse(history.canRedo)
    }

    @Test
    fun repeatedSliderUpdatesWithTheSameKeyBecomeOneUndoStep() {
        val history = EditHistory(maxEntries = 40)
        val start = SamplerUiState(bpm = 92f)

        history.record(start, mergeKey = "bpm")
        history.record(start.copy(bpm = 93f), mergeKey = "bpm")
        history.record(start.copy(bpm = 94f), mergeKey = "bpm")

        val restored = history.undo(start.copy(bpm = 95f))

        assertEquals(92f, restored?.bpm)
        assertFalse(history.canUndo)
        assertTrue(history.canRedo)
    }

    @Test
    fun resetDropsUndoAndRedoStacks() {
        val history = EditHistory(maxEntries = 40)
        val start = SamplerUiState()
        history.record(start)
        history.undo(start.copy(bpm = 100f))

        history.reset()

        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun manualChopModeIsPartOfTheRestoredEditableState() {
        val history = EditHistory(maxEntries = 40)
        val before = SamplerUiState(manualChopEnabled = false)
        val after = before.copy(manualChopEnabled = true)
        history.record(before)

        val restored = history.undo(after)

        assertFalse(requireNotNull(restored).manualChopEnabled)
    }

    @Test
    fun undoNeverRestoresRuntimeOnlySourceCommandIntent() {
        val history = EditHistory(maxEntries = 40)
        val pendingStart = SamplerUiState(
            pendingSourceCommand = PendingSourceCommand.START,
            bpm = 92f,
        )
        history.record(pendingStart)

        val restored = requireNotNull(history.undo(SamplerUiState(bpm = 100f)))

        assertFalse(restored.sourcePlaying)
        assertEquals(PendingSourceCommand.NONE, restored.pendingSourceCommand)
    }
}
