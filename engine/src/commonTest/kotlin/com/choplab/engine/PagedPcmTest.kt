package com.choplab.engine

import kotlin.test.*

class PagedPcmTest {
    @Test fun longPcmUsesBoundedImmutablePagesAndSignalsMissingData() {
        val cache = PagedPcm(30_000_000, maximumPages = 4)
        val asset = PcmAsset.paged(cache)
        assertEquals(30_000_000, asset.frameCount)
        assertTrue(asset.residentBytes < 2 * 1024 * 1024)
        assertEquals(0f, asset.sample(20_000_000, 0))
        assertEquals(1L, asset.cacheMisses)
        assertEquals(PcmReadStatus.PREFETCHING, cache.statistics().status)
        val requested = cache.nextRequest()
        assertEquals(20_000_000 / cache.pageFrames, requested)
        val samples = FloatArray(cache.pageFrames * 2) { if (it % 2 == 0) 1.234567f else -.987654f }
        cache.publish(requested, samples)
        samples.fill(0f)
        assertEquals(1.234567f.toRawBits(), asset.sample(20_000_000, 0).toRawBits())
        assertEquals((-.987654f).toRawBits(), asset.sample(20_000_000, 1).toRawBits())
        // Eviction cannot rewrite the input or enlarge the cache.
        repeat(20) { page -> cache.publish(page, FloatArray(cache.pageFrames * 2) { page.toFloat() }) }
        assertEquals(4, cache.statistics().loadedPages)
        assertEquals(4L * cache.pageFrames * 8, cache.statistics().retainedBytes)
        assertFalse(cache.request(asset.frameCount))
        assertFalse(cache.request(-1))
    }

    @Test fun queueOverflowFailureAndCloseStayBoundedAndDoNotReturnOldAudio() {
        val cache = PagedPcm(256 * 30, pageFrames = 256, maximumPages = 4, requestCapacity = 4)
        val asset = PcmAsset.paged(cache)
        repeat(4) { assertTrue(cache.request(it * 256)) }
        assertFalse(cache.request(4 * 256))
        assertEquals(1, cache.statistics().droppedRequests)
        assertEquals(4, cache.statistics().pendingRequests)
        assertEquals(0, cache.nextRequest())
        assertTrue(cache.request(4 * 256), "A full queue can retry after the worker makes progress")
        cache.publish(0, FloatArray(512) { .3f })
        assertEquals(.3f, asset.sample(0, 0))
        cache.fail()
        assertEquals(0f, asset.sample(0, 0))
        assertEquals(PcmReadStatus.FAILED, cache.statistics().status)
        assertFalse(cache.publish(1, FloatArray(512)))
        cache.close()
        assertEquals(PcmReadStatus.CLOSED, cache.statistics().status)
        assertFalse(cache.request(10 * 256))
        assertFailsWith<IllegalArgumentException> { cache.publish(0, floatArrayOf(Float.NaN)) }
    }

    @Test fun residentAdmissionCountsPageCapacityAndSharedSourceOnlyOnce() {
        val cache = PagedPcm(30_000_000, maximumPages = 4)
        val data = PcmAsset.paged(cache)
        val program = EngineProgram(listOf(Pad(0, data, 20_000_000, 20_010_000), Pad(1, data, 100, 200)))
        assertEquals(data.residentBytes, program.residentBytes)
        val engine = EngineCore(program, EngineConfig(residentByteLimit = data.residentBytes))
        assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.SetOriginalSource(0, 1, OriginalSource(data))))
        engine.render(FloatArray(2))
        assertEquals(data.residentBytes, engine.residentBytes)
        val other = PcmAsset.paged(PagedPcm(30_000_000, maximumPages = 4))
        assertEquals(OfferResult.PCM_LIMIT, engine.controls.offer(EngineCommand.SetOriginalSource(1, 2, OriginalSource(other))))
    }

    @Test fun windowedResamplingEqualsTheFullFileAtAllWindowBoundaries() {
        for (rate in listOf(44_100, 48_000, 96_000)) {
            val input = FloatArray(7000 * 2) { index ->
                if (index % 2 == 0) ((index * 43) % 997 - 498) / 997f else ((index * 31) % 877 - 438) / 877f
            }
            val oracle = OfflineResampler.resample(input, rate)
            val windows = WindowedResampler(rate, input.size / 2)
            assertEquals(oracle.size / 2, windows.outputFrames)
            var first = 0
            while (first < windows.outputFrames) {
                val count = minOf(137, windows.outputFrames - first)
                val native = input.copyOfRange(windows.inputStart(first) * 2, windows.inputEnd(first + count) * 2)
                val actual = windows.render(native, first, count)
                assertContentEquals(oracle.copyOfRange(first * 2, (first + count) * 2), actual, "rate=$rate, frame=$first")
                first += count
            }
        }
    }
}
