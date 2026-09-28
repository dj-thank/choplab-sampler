package com.choplab.desktop.audio.wasapi

import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WasapiPcmRingTest {
    @Test
    fun wrapPartialBackpressureAndWholePacketRefusalKeepFrameOrder() {
        val ring = WasapiPcmRing(17)
        val first = FloatArray(24) { if (it % 2 == 0) it + .25f else -it - .5f }
        assertTrue(ring.offerPacket(first, 12))
        assertFalse(ring.offerPacket(first, 6))
        val head = FloatArray(14)
        assertEquals(7, ring.readInto(head, 7))
        assertContentEquals(first.copyOf(14), head)
        assertTrue(ring.offerPacket(first, 12))
        val result = FloatArray(34)
        assertEquals(17, ring.readInto(result, 17))
        assertContentEquals(first.copyOfRange(14, 24) + first, result)
        assertEquals(0, ring.availableFrames)
    }

    @Test
    fun invalidByteRangeAlignmentAndNonFiniteSamplesCannotPublishAnInvalidPrefix() {
        val ring = WasapiPcmRing(17)
        val bytes = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).apply {
            putFloat(.5f); putFloat(-.3f); putFloat(Float.NaN); putFloat(.7f); putFloat(1.4f); putFloat(-1.8f)
        }.array()
        assertFailsWith<IllegalArgumentException> { ring.offerBytes(bytes, -1, 8) }
        assertFailsWith<IllegalArgumentException> { ring.offerBytes(bytes, 17, 8) }
        assertFailsWith<IllegalArgumentException> { ring.offerBytes(bytes, 0, 7) }
        assertEquals(WasapiFault.NON_FINITE_PCM, assertFailsWith<WasapiStreamException> { ring.offerBytes(bytes, 0, 24) }.failure.fault)
        assertEquals(0, ring.availableFrames)
        assertEquals(8, ring.offerBytes(bytes, 16, 8))
        val result = FloatArray(2)
        assertEquals(1, ring.readInto(result, 1))
        assertContentEquals(floatArrayOf(1.4f, -1.8f), result)
    }

    @Test
    fun warmedRenderHandoffAllocatesZeroBytesOnTheCallingThread() {
        val bean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        assertTrue(bean.isThreadAllocatedMemorySupported)
        bean.isThreadAllocatedMemoryEnabled = true
        val ring = WasapiPcmRing(4096)
        val bytes = ByteArray(192 * 8)
        val scratch = FloatArray(192 * 2)
        repeat(20_000) { ring.offerBytes(bytes, 0, bytes.size); ring.readInto(scratch, 192) }
        val thread = Thread.currentThread().id
        val before = bean.getThreadAllocatedBytes(thread)
        repeat(10_000) { ring.offerBytes(bytes, 0, bytes.size); ring.readInto(scratch, 192) }
        val allocated = bean.getThreadAllocatedBytes(thread) - before
        assertEquals(0L, allocated, "Fixed PCM handoff bytes over 10,000 x 192-frame blocks")
    }
}
