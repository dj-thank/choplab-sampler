package com.choplab.sampler.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Pcm16ArrayBuilderTest {
    @Test
    fun decodedPcmCannotGrowPastTheHardFrameLimit() {
        val builder = Pcm16ArrayBuilder(initialCapacity = 1, maximumSize = 3)

        builder.append(-1f)
        builder.append(0f)
        builder.append(1f)

        assertEquals(3, builder.size)
        assertEquals(3, builder.toArray().size)
        assertThrows(IllegalStateException::class.java) { builder.append(0f) }
    }
}
