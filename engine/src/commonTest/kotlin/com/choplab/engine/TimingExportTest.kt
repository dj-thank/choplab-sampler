package com.choplab.engine

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TimingExportTest {
    @Test fun oneHourRationalTimingDoesNotAccumulateFrameError() {
        for (bpm in intArrayOf(40_000, 40_001, 93_125, 127_357, 239_999, 240_000)) {
            for (swing in intArrayOf(500, 637, 750)) {
                val clock = SequenceClock(Tempo(bpm, swing))
                var elapsed = 0L
                var tick = 0L
                while (elapsed < 48_000L * 3600) {
                    val distance = clock.framesUntil(tick)
                    clock.advance(distance.toInt())
                    elapsed += distance
                    // Independent rational reference: eighth pairs plus the swung half-eighth.
                    val pair = tick / 480
                    val numerator = (pair * 480_000 + if (tick % 480 == 0L) 0L else 480L * swing) * 3000
                    val reference = (numerator + bpm - 1) / bpm
                    assertEquals(reference, elapsed, "bpm=$bpm swing=$swing tick=$tick")
                    tick += 240
                }
            }
        }
    }

    @Test fun tempoChangeKeepsSubTickRemainder() {
        val clock = SequenceClock(Tempo(93_125, 667))
        clock.advance(12345)
        val before = 12345L * 93125
        assertEquals(before, clock.tickNumerator)
        clock.setTempo(Tempo(137_531, 667))
        clock.advance(23456)
        assertEquals(before + 23456L * 137531, clock.tickNumerator)
        val target = SequenceClock.targetNumerator(4000, 667)
        val reference = ((target - clock.tickNumerator).coerceAtLeast(0) + 137530) / 137531
        assertEquals(reference, clock.framesUntil(4000))
    }

    @Test fun sequenceFiresAtExactFramesIncludingFractionalTempoAndSwing() {
        val impulse = PcmAsset.fromInterleaved(floatArrayOf(.2f, -.1f))
        val program = EngineProgram(listOf(Pad(0, impulse)), Pattern(960,
            listOf(SequenceNote(0, 0), SequenceNote(240, 0), SequenceNote(480, 0), SequenceNote(720, 0))), Tempo(239_999, 667))
        val output = OfflineRender.render(program, listOf(EngineCommand.StartSequence(0, 0)), 30000)
        val actual = ArrayList<Int>()
        for (frame in 0 until output.size / 2) if (output[frame * 2] != 0f) actual.add(frame)
        val expected = ArrayList<Int>()
        for (tick in 0L..8000L step 240) {
            val exact = (SequenceClock.targetNumerator(tick, 667) + 239998) / 239999
            if (exact < 30000) expected.add(exact.toInt())
        }
        assertEquals(expected, actual)
    }

    @Test fun integerEndpointsAreCorrectForBothDepthsAndSeedIsPartitionInvariant() {
        for (bits in intArrayOf(16, 24)) {
            val input = floatArrayOf(-2f, -1f, 0f, 1f, 2f)
            val encoded = ByteArray(input.size * bits / 8)
            PcmQuantizer(1, bits, false).encode(input, encoded)
            val scale = 1 shl (bits - 1)
            val expected = intArrayOf(-scale, -scale, 0, scale - 1, scale - 1)
            for (i in input.indices) assertEquals(expected[i], decode(encoded, i, bits))
            val silence = FloatArray(100_000)
            val whole = ByteArray(silence.size * bits / 8)
            val divided = ByteArray(whole.size)
            PcmQuantizer(0x13579, bits).encode(silence, whole)
            val encoder = PcmQuantizer(0x13579, bits)
            var offset = 0
            while (offset < silence.size) {
                val size = minOf(17, silence.size - offset)
                encoder.encode(silence, divided, offset, size, offset * bits / 8)
                offset += size
            }
            assertContentEquals(whole, divided)
            var sum = 0.0; var energy = 0.0; var correlation = 0.0; var previous = 0
            for (i in silence.indices) {
                val value = decode(whole, i, bits)
                assertTrue(value in -1..1)
                sum += value; energy += value * value; correlation += value * previous; previous = value
            }
            val mean = sum / silence.size
            val variance = energy / silence.size - mean * mean
            assertTrue(abs(mean) < .01, "dither mean=$mean")
            assertTrue(variance in .23.. .27, "quantized TPDF variance=$variance")
            assertTrue(abs(correlation / silence.size) < .01, "dither serial correlation")
        }
    }

    @Test fun offlineTailLengthAndLatencyAreExplicit() {
        val sample = PcmAsset.fromInterleaved(floatArrayOf(.25f, -.125f))
        val program = EngineProgram(listOf(Pad(0, sample)))
        val result = OfflineRender.render(program, listOf(EngineCommand.Trigger(4, 0, 0)), 10, 3)
        assertEquals(26, result.size)
        assertEquals(.25f, result[8]); assertEquals(-.125f, result[9])
        assertEquals(2, result.count { it != 0f })
    }

    private fun decode(bytes: ByteArray, sample: Int, bits: Int): Int {
        val i = sample * bits / 8
        val value = (bytes[i].toInt() and 255) or ((bytes[i + 1].toInt() and 255) shl 8) or
            if (bits == 24) (bytes[i + 2].toInt() shl 16) else 0
        return if (bits == 16) value.toShort().toInt() else value
    }
}
