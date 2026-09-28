package com.choplab.engine

import kotlin.math.*
import kotlin.test.*

class OfflineWsolaTest {
    private fun tone(frames: Int = 48_000): FloatArray = FloatArray(frames * 2) { sample ->
        (.2 * sin(2 * PI * 220 * (sample / 2) / 48_000.0) * if (sample % 2 == 0) 1.0 else -.4).toFloat()
    }

    @Test fun speedBoundsKeepPitchStereoAndExactFramesWithoutTrimmingEitherEnd() {
        val input = tone()
        for (speed in listOf(.6, .8, 1.0, 1.2, 1.6)) {
            val frames = (48_000 / speed).roundToInt()
            val output = OfflineWsola.stretch(input, frames)
            assertEquals(frames * 2, output.size)
            assertEquals(input.first(), output.first())
            assertEquals(input.last(), output.last())
            assertTrue(output.all { it.isFinite() && abs(it) < .201f })
            for (frame in 0 until frames) assertEquals(-.4f * output[frame * 2], output[frame * 2 + 1], 1e-6f)
            val start = 4800; val end = frames - 4800
            val crossings = (start + 1 until end).count { output[(it - 1) * 2] <= 0f && output[it * 2] > 0f }
            val frequency = crossings * 48_000.0 / (end - start)
            assertTrue(abs(frequency - 220) < 4, "Speed $speed changed pitch to $frequency Hz")
            if (speed == 1.0) assertContentEquals(input, output)
        }
    }

    @Test fun invalidBoundsNonfiniteAndCancellationAreRejectedBeforeAnyPublish() {
        assertFailsWith<IllegalArgumentException> { OfflineWsola.stretch(tone(), 10_000) }
        assertFailsWith<IllegalArgumentException> { OfflineWsola.stretch(tone(), 100_000) }
        assertFailsWith<IllegalArgumentException> { OfflineWsola.stretch(floatArrayOf(Float.NaN, 0f), 1) }
        class Cancelled : RuntimeException()
        var checks = 0
        assertFailsWith<Cancelled> { OfflineWsola.stretch(tone(), 60_000) { if (++checks == 15) throw Cancelled() } }
        checks = 0
        assertFailsWith<Cancelled> { OfflineResampler.resample(tone(), 44_100) { if (++checks == 3) throw Cancelled() } }
    }
}
