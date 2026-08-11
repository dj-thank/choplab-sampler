package com.choplab.sampler.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectOperationEpochTest {
    @Test
    fun resetInvalidatesPendingSourceDecode() {
        val operations = ProjectOperationEpoch()
        val pendingDecode = operations.begin()

        operations.invalidate()

        assertFalse(operations.isCurrent(pendingDecode))
    }

    @Test
    fun newestSourceSelectionWinsOutOfOrderCompletion() {
        val operations = ProjectOperationEpoch()
        val older = operations.begin()
        val newer = operations.begin()
        var appliedSource = "unchanged"

        assertFalse(operations.completeIfCurrent(older) { appliedSource = "older" })
        assertTrue(operations.completeIfCurrent(newer) { appliedSource = "newer" })

        assertEquals("newer", appliedSource)
    }

    @Test
    fun staleFailureCannotClearNewestLoadingState() {
        val operations = ProjectOperationEpoch()
        val older = operations.begin()
        operations.begin()
        var loading = true

        operations.completeIfCurrent(older) { loading = false }

        assertTrue(loading)
    }
}
