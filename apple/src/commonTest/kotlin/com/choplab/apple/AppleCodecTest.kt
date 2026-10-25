package com.choplab.apple

import kotlin.test.*

class AppleCodecTest {
    @Test fun floatWavPreservesStereoAndHeadroom() {
        val samples = floatArrayOf(1.25f, -.75f, -.5f, .25f)
        val output = ArrayOutput()
        IosWav.FloatWriter(output, 2).apply { write(samples); finish() }
        val read = IosWav.read(ArrayInput(output.toByteArray()))
        assertEquals(WavInfo(48_000, 2, 2, 32, true), read.info)
        assertContentEquals(samples, read.samples)
    }
    @Test fun pcm24QuantizesOnceAndRetainsNegativeRightChannel() {
        val output = ArrayOutput()
        IosWav.PcmWriter(output, 2, bits = 24, dither = false).apply {
            write(floatArrayOf(.5f, -.25f, 1f, -1f)); finish()
        }
        val bytes = output.toByteArray()
        assertEquals(56, bytes.size)
        val audio = IosWav.read(ArrayInput(bytes))
        assertEquals(24, audio.info.bits)
        assertContentEquals(floatArrayOf(.5f, -.25f, 8388607f / 8388608f, -1f), audio.samples)
    }
    @Test fun malformedAndTruncatedWavNeverBecomeSuccessfulAudio() {
        val output = ArrayOutput()
        IosWav.FloatWriter(output, 1).apply { write(floatArrayOf(.1f, -.2f)); finish() }
        val good = output.toByteArray()
        assertFails { IosWav.read(ArrayInput(good.copyOf(good.size - 1))) }
        assertFails { IosWav.read(ArrayInput(good + byteArrayOf(1))) }
        val nonfinite = good.copyOf().also { it[44] = 0; it[45] = 0; it[46] = 0x80.toByte(); it[47] = 0x7f }
        assertFails { IosWav.inspect(ArrayInput(nonfinite)) }
        assertFails { IosWav.read(ArrayInput(good), maxDecodedBytes = 4) }
    }
    @Test fun capacityIsRejectedBeforeAnyPcmAllocationAndReturnedOnce() {
        val budget = PcmAdmission(1024)
        val first = budget.reserve(900)
        assertFailsWith<PcmBudgetExceeded> { budget.reserve(125) }
        assertEquals(900L, budget.usedBytes)
        first.shrinkTo(100)
        budget.reserve(924).use { assertEquals(1024L, budget.usedBytes) }
        first.close(); first.close()
        assertEquals(0L, budget.usedBytes)
        assertEquals(1024L, budget.peakBytes)
    }
    @Test fun resamplingAdmissionIncludesNativeAndDefensiveCopies() {
        assertTrue(PcmAdmission.decodeBytes(96_000, 2, 96_000) > 96_000L * 8 + 48_000L * 16)
        val budget = PcmAdmission()
        assertFailsWith<PcmBudgetExceeded> { budget.reserve(PcmAdmission.decodeBytes(30_000_000, 2, 8_000)) }
        assertEquals(0L, budget.usedBytes)
    }
    @Test fun boundedDocumentOutputRejectsGrowth() {
        val out = ArrayOutput(initial = 4, maximum = 8)
        out.write(ByteArray(8), 0, 8)
        assertFails { out.write(byteArrayOf(1), 0, 1) }
        assertEquals(8, out.size)
    }
}
