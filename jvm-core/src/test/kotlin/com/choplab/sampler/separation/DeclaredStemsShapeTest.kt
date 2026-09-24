package com.choplab.sampler.separation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeclaredStemsShapeTest {
    @Test
    fun optimizedStaticShapeIsAccepted() {
        assertTrue(declaredStemsShapeAcceptable(longArrayOf(1, 4, 2, SeparatorSpec.SEGMENT_SAMPLES.toLong())))
    }

    @Test
    fun unoptimizedSymbolicShapeIsAccepted() {
        // Android's low-memory session reports [-1, -1, -1, -1] because shape inference is skipped.
        assertTrue(declaredStemsShapeAcceptable(longArrayOf(-1, -1, -1, -1)))
    }

    @Test
    fun wrongRankOrStemCountIsRejected() {
        assertFalse(declaredStemsShapeAcceptable(longArrayOf(1, 3, 2, SeparatorSpec.SEGMENT_SAMPLES.toLong())))
        assertFalse(declaredStemsShapeAcceptable(longArrayOf(1, 4, 2)))
        assertFalse(declaredStemsShapeAcceptable(null))
    }
}
