package com.choplab.sampler.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCaptureOperationTest {
    @Test
    fun completionAfterResetKeepsTheCaptureStartEpochStale() {
        val projectOperations = ProjectOperationEpoch()
        val capture = SourceCaptureOperation(projectOperations)
        val started = capture.begin()

        projectOperations.invalidate()
        val completed = capture.consumeOrBegin()

        assertEquals(started, completed)
        assertFalse(projectOperations.isCurrent(completed))
    }

    @Test
    fun restoredServiceCompletionCanCreateAValidOperation() {
        val projectOperations = ProjectOperationEpoch()
        val capture = SourceCaptureOperation(projectOperations)

        val completed = capture.consumeOrBegin()

        assertTrue(projectOperations.isCurrent(completed))
    }

    @Test
    fun oldFailureCannotDiscardANewerCapture() {
        val projectOperations = ProjectOperationEpoch()
        val capture = SourceCaptureOperation(projectOperations)
        val older = capture.begin()
        val newer = capture.begin()

        capture.discard(older)

        assertEquals(newer, capture.consumeOrBegin())
    }
}
