package com.choplab.sampler.separation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SeparationMemoryPolicyTest {
    @Test
    fun fourAndEightGigabyteClassDevicesAreAllowed() {
        // Pixel 9a reports 7,752,848 kB; the 4 GB review emulator reports 4,013,820 kB.
        assertNull(SeparationMemoryPolicy.blockedReason(7_752_848L * 1024, systemLowMemory = false))
        assertNull(SeparationMemoryPolicy.blockedReason(4_013_820L * 1024, systemLowMemory = false))
    }

    @Test
    fun smallerDevicesAreRefusedWithTheirSize() {
        assertEquals(
            "この端末のメモリ（約3GB）ではドラム分離を実行できません。4GB以上の端末かPC版で分離してください",
            SeparationMemoryPolicy.blockedReason(2_873_000L * 1024, systemLowMemory = false),
        )
    }

    @Test
    fun thresholdBoundaryIsInclusive() {
        val minimum = SeparationMemoryPolicy.MIN_TOTAL_MEMORY_BYTES
        assertNotNull(SeparationMemoryPolicy.blockedReason(minimum - 1, systemLowMemory = false))
        assertNull(SeparationMemoryPolicy.blockedReason(minimum, systemLowMemory = false))
    }

    @Test
    fun unknownTotalDoesNotBlockButSystemLowMemoryDoes() {
        assertNull(SeparationMemoryPolicy.blockedReason(0, systemLowMemory = false))
        assertNotNull(SeparationMemoryPolicy.blockedReason(0, systemLowMemory = true))
        assertNotNull(SeparationMemoryPolicy.blockedReason(8_000_000_000L, systemLowMemory = true))
    }
}
