package com.choplab.engine

import kotlin.math.*
import kotlin.test.*

class WindowedWsolaTest {
    private fun render(input: FloatArray, frames: Int): FloatArray {
        val output = FloatArray(frames * 2); var written = 0
        WindowedWsola.process(WsolaReader { first, count, into ->
            assertTrue(count <= 4096); input.copyInto(into, 0, first * 2, (first + count) * 2)
        }, input.size / 2, frames, { samples, count ->
            assertTrue(count in 1..4096); samples.copyInto(output, written * 2, 0, count * 2); written += count
        })
        assertEquals(frames, written); return output
    }
    @Test fun boundedStreamMatchesTheExistingOfflineOracleAndUnityIsBitExact() {
        val input = FloatArray(48_000 * 2) { (.2 * sin(2 * PI * 220 * (it / 2) / 48_000) * if (it % 2 == 0) 1.0 else -.4).toFloat() }
        for (speed in listOf(.6, .8, 1.0, 1.2, 1.6)) {
            val frames = (48_000 / speed).roundToInt()
            assertContentEquals(OfflineWsola.stretch(input, frames), render(input, frames), "speed=$speed")
        }
        val bits = FloatArray(8193 * 2) { if (it % 7 == 0) -0f else (it % 103 - 50) / 32f }
        val unity = render(bits, bits.size / 2)
        bits.indices.forEach { assertEquals(bits[it].toRawBits(), unity[it].toRawBits()) }
    }
    @Test fun tempoExtremesKeepTonalPitchAndStereoWithoutClippingHeadroom() {
        val input = FloatArray(48_000 * 2) { (1.2 * sin(2 * PI * 220 * (it / 2) / 48_000) * if (it % 2 == 0) 1.0 else -.4).toFloat() }
        for (speed in listOf(1.0 / 6, .5, 2.0, 6.0)) {
            val frames = (48_000 / speed).roundToInt(); val output = render(input, frames)
            assertEquals(input.first(), output.first()); assertEquals(input.last(), output.last())
            assertTrue(output.all { it.isFinite() && abs(it) <= 1.20001f })
            for (i in 0 until frames) assertEquals(-.4f * output[i * 2], output[i * 2 + 1], 1e-6f)
            // Endpoint grains retain the original boundary phase; measure pitch on the settled middle.
            for (i in 1 until frames) assertTrue(abs(output[i * 2] - output[(i - 1) * 2]) < .05f, "No boundary step")
            val start = minOf(2048, frames / 3); val end = frames - start
            val crossings = (start + 1 until end).filter { output[(it - 1) * 2] <= 0f && output[it * 2] > 0f }.map {
                val a = output[(it - 1) * 2]; val b = output[it * 2]
                it - 1.0 - a / (b - a)
            }
            val frequency = (crossings.size - 1) * 48_000.0 / (crossings.last() - crossings.first())
            assertTrue(abs(frequency - 220) < 2, "speed=$speed frequency=$frequency")
        }
    }
    @Test fun linkedTransientTimingAndSilentChannelIdentitySurviveStretch() {
        val input = FloatArray(48_000 * 2)
        for (at in listOf(12_000, 24_000, 36_000)) for (i in 0 until 300)
            input[(at + i) * 2] = (.8 * sin(PI * i / 299)).toFloat()
        for (speed in listOf(.75, 1.25)) {
            val frames = (48_000 / speed).roundToInt(); val output = render(input, frames)
            assertTrue((0 until frames).all { output[it * 2 + 1] == 0f }, "No L/R crosstalk")
            for (at in listOf(12_000, 24_000, 36_000)) {
                val center = (at / speed).roundToInt()
                val peak = (maxOf(0, center - 2048) until minOf(frames, center + 2048)).maxOf { output[it * 2] }
                assertTrue(peak > .3f, "A transient vanished at $at / speed $speed")
            }
            assertTrue(output.all { it in 0f..0.80001f })
        }
    }
    @Test fun longStreamAndCancellationStayBoundedWithoutAnOutputSizedArray() {
        val frames = 400 * 48_000; var readMax = 0; var written = 0
        WindowedWsola.process(WsolaReader { _, count, into ->
            readMax = maxOf(readMax, count); into.fill(.25f, 0, count * 2)
        }, frames, frames * 3 / 4, { samples, count ->
            for (i in 0 until count * 2) assertTrue(abs(samples[i] - .25f) < 1e-7)
            written += count
        })
        assertEquals(frames * 3 / 4, written); assertTrue(readMax <= 4096)
        class Cancelled : RuntimeException()
        var checks = 0
        assertFailsWith<Cancelled> { WindowedWsola.process(WsolaReader { _, count, into -> into.fill(0f, 0, count * 2) }, 48_000, 60_000,
            { _, _ -> }, { if (++checks == 5) throw Cancelled() }) }
        assertFailsWith<IllegalArgumentException> { render(floatArrayOf(Float.NaN, 0f), 1) }
        assertFailsWith<IllegalArgumentException> { render(FloatArray(100), 100) }
    }
}
