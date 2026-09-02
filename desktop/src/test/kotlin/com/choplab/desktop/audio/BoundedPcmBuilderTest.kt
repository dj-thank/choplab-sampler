package com.choplab.desktop.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BoundedPcmBuilderTest {
    @Test
    fun bulkAppendMatchesSampleBySampleAppendAndGrowsPastTheInitialCapacity() {
        val source = ShortArray(1_000) { (it - 500).toShort() }
        val single = BoundedPcmBuilder(initialFrameCapacity = 4, maximumFrames = 2_000, channelCount = 1)
        val bulk = BoundedPcmBuilder(initialFrameCapacity = 4, maximumFrames = 2_000, channelCount = 1)

        source.forEach(single::append)
        bulk.appendAll(source, 700)
        bulk.appendAll(source.copyOfRange(700, 1_000), 300)

        assertContentEquals(single.toArray(), bulk.toArray())
        assertEquals(1_000, bulk.toArray().size)
    }

    @Test
    fun bulkAppendRejectsSamplesBeyondTheDecodedBoundLikeSingleAppend() {
        val stereo = BoundedPcmBuilder(initialFrameCapacity = 2, maximumFrames = 10, channelCount = 2)
        stereo.appendAll(ShortArray(20) { 1 }, 20)

        assertFailsWith<IllegalStateException> { stereo.appendAll(ShortArray(2), 2) }
        assertFailsWith<IllegalStateException> { stereo.append(1) }
        assertEquals(20, stereo.toArray().size)
    }

    @Test
    fun bulkAppendIgnoresAnEmptyChunkAndValidatesTheCount() {
        val builder = BoundedPcmBuilder(initialFrameCapacity = 1, maximumFrames = 4, channelCount = 1)
        builder.appendAll(ShortArray(3), 0)
        assertEquals(0, builder.toArray().size)
        assertFailsWith<IllegalArgumentException> { builder.appendAll(ShortArray(3), 4) }
    }
}
