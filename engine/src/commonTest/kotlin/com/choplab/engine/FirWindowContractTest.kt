package com.choplab.engine

import kotlin.test.*

class FirWindowContractTest {
    @Test fun contiguousReadsMatchThePerTapOracleBitForBitAtEveryRegionAndPageEdge() {
        val samples = FloatArray(8192 * 2) { if (it % 2 == 0) ((it * 37) % 997 - 499) / 997f else ((it * 19) % 641 - 421) / 641f }
        val resident = PcmAsset.fromInterleaved(samples)
        val cache = PagedPcm(8192, pageFrames = 256, maximumPages = 32)
        repeat(32) { page -> assertTrue(cache.publish(page, samples.copyOfRange(page * 512, (page + 1) * 512))) }
        val paged = PcmAsset.paged(cache)
        val reader = PitchInterpolator()
        val cursor = PcmReadCursor()
        val speeds = listOf(-8.0, -6.0, -4.0, -2.5, -1.0, 0.0, .5, 1.0, 1.125, 1.25, 1.5, 2.0, 3.0, 8.0)
        for ((start, end) in listOf(0 to 8192, 93 to 7897, 253 to 260, 4094 to 4096)) {
            val positions = listOf(start - .5, start.toDouble(), start + .37, start + 63.5,
                (start + end) / 2.0 + .125, end - 63.75, end - .5, end.toDouble(), 255.5, 256.5, 4095.5, 4096.5)
            for (loop in listOf(false, true)) for (crossfade in listOf(0, 5, 64)) for (speed in speeds) for (position in positions) {
                for (asset in listOf(resident, paged)) for (channel in 0..1) {
                    val oracle = reader.read(asset, position, speed, channel, start, end, loop, crossfade)
                    cursor.reset()
                    val actual = reader.read(asset, position, speed, channel, start, end, loop, crossfade, cursor)
                    assertEquals(oracle.toRawBits(), actual.toRawBits(),
                        "range=$start..$end at=$position speed=$speed loop=$loop fade=$crossfade channel=$channel paged=${asset.pages != null}")
                    assertFalse(cursor.missing)
                    cursor.clear()
                    assertNull(cursor.samples)
                }
            }
        }
    }

    @Test fun aBorrowedPageIsImmutableUntilTheFrameEndsAndMissingOrFailedPagesNeverMasqueradeAsWindows() {
        val cache = PagedPcm(256 * 12, pageFrames = 256, maximumPages = 4)
        val asset = PcmAsset.paged(cache)
        val input = FloatArray(512) { it / 1024f }
        assertTrue(cache.publish(0, input))
        val cursor = PcmReadCursor()
        val window = assertNotNull(asset.span(30, 128, cursor))
        assertEquals(60, cursor.spanOffset)
        input.fill(-1f)
        repeat(11) { page -> cache.publish(page + 1, FloatArray(512) { -(page + 1).toFloat() }) }
        assertEquals(30 / 512f, window[cursor.spanOffset])
        assertSame(window, asset.span(30, 128, cursor), "Eviction cannot replace the page already borrowed by this output frame")
        assertEquals(4, cache.statistics().loadedPages)
        cursor.clear()
        assertNull(asset.span(30, 128, cursor))
        assertNull(asset.span(240, 128, cursor), "A cross-page kernel uses the existing per-tap path")
        val misses = asset.cacheMisses
        assertEquals(0f, asset.at(30, 0, cursor))
        assertTrue(cursor.missing)
        assertEquals(misses + 1, asset.cacheMisses)
        cache.fail(); cursor.reset()
        assertNull(asset.span(11 * 256, 128, cursor))
        cache.close(); cursor.reset()
        assertNull(asset.span(11 * 256, 128, cursor))
        assertNull(cursor.samples)
    }
}
