package com.choplab.sampler.midi

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class DdjMixerTest {
    private fun midi() = DdjMixerMidi {}.also { it.reset(enabled = true) }
    private fun cc(midi: DdjMixerMidi, channel: Int, base: Int, value: Int, reversed: Boolean = false) {
        val high = byteArrayOf((0xb0 + channel).toByte(), base.toByte(), (value shr 7).toByte())
        val low = byteArrayOf((0xb0 + channel).toByte(), (base + 32).toByte(), (value and 127).toByte())
        midi.stream.accept(if (reversed) low + high else high + low)
    }
    private fun note(midi: DdjMixerMidi, channel: Int, number: Int, value: Int = 127, off: Boolean = false) {
        midi.stream.accept(byteArrayOf(((if (off) 0x80 else 0x90) + channel).toByte(), number.toByte(), value.toByte()))
    }
    private fun settle(dsp: DdjMixerDsp, settings: DdjMixerSettings) {
        repeat(16000) { dsp.process(0f, 0f, 0f, 0f, settings) }
    }
    private fun near(expected: Float, actual: Float, tolerance: Float = 0.00001f) {
        assertTrue(abs(expected - actual) <= tolerance, "expected=$expected actual=$actual")
    }
    @Test fun routeAll128PadsUsesPhysicalSidesAndShift() {
        for (bank in 0..3) for (pad in 0..31) {
            assertEquals(if (pad in 0..7 || pad in 16..23) 0 else 1, ddjPadBus(bank * 32 + pad))
        }
        assertEquals(0, ddjPadBus(-1))
    }
    @Test fun settingsRejectNonFiniteAndOutOfRange() {
        assertFailsWith<IllegalArgumentException> { DdjMixerSettings(crossfader = Float.NaN) }
        assertFailsWith<IllegalArgumentException> { DdjMixerSettings(leftHigh = Float.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { DdjMixerSettings(rightFader = -0.1f) }
        assertFailsWith<IllegalArgumentException> { DdjMixerSettings(rightLow = 1.1f) }
        assertFailsWith<IllegalArgumentException> { DdjMixerDsp(0) }
    }
    @Test fun crossfaderLawHasUnityCenterAndExactEndpoints() {
        val center = DdjMixerSettings()
        assertEquals(1f, center.gainLeft); assertEquals(1f, center.gainRight)
        assertEquals(0f, center.copy(crossfader = 0f).gainRight)
        assertEquals(0f, center.copy(crossfader = 1f).gainLeft)
        near(0.25f, center.copy(leftFader = 0.25f).gainLeft)
        for (i in 0..1000) {
            val s = center.copy(crossfader = i / 1000f)
            assertTrue(s.gainLeft in 0f..1f && s.gainRight in 0f..1f)
        }
    }
    @Test fun eqCenterIsUnityAndHasDocumentedRange() {
        assertEquals(1f, DdjMixerSettings.eqGain(0.5f))
        near(0.05011872f, DdjMixerSettings.eqGain(0f))
        near(1.9952623f, DdjMixerSettings.eqGain(1f))
    }
    @Test fun disabledMidiCannotChangeSettings() {
        val m = DdjMixerMidi {}
        cc(m, 6, 31, 8192); note(m, 0, 0x54)
        assertEquals(DdjMixerSettings(), m.settings)
    }
    @Test fun officialCrossfaderUsesChannelSevenAndFresh14bitPairs() {
        val m = midi()
        cc(m, 6, 31, 8192); cc(m, 6, 31, 0)
        assertEquals(0f, m.settings.crossfader)
        // lone high byte must not reuse a stale low byte
        m.stream.accept(byteArrayOf(0xb6.toByte(), 31, 127))
        assertEquals(0f, m.settings.crossfader)
        m.stream.accept(byteArrayOf(0xb6.toByte(), 63, 127))
        assertEquals(1f, m.settings.crossfader)
    }
    @Test fun lowBeforeHighIsAcceptedWithoutCrossChannelContamination() {
        val m = midi()
        cc(m, 6, 31, 8192, reversed = true)
        cc(m, 6, 31, 16383, reversed = true)
        assertEquals(1f, m.settings.crossfader)
        m.stream.accept(byteArrayOf(0xb0.toByte(), 0x13, 127, 0xb1.toByte(), 0x33, 127))
        assertEquals(1f, m.settings.leftFader); assertEquals(1f, m.settings.rightFader)
    }
    @Test fun fadersArePerSideAndRequirePickup() {
        val m = midi()
        cc(m, 0, 0x13, 0)
        assertEquals(1f, m.settings.leftFader)
        cc(m, 0, 0x13, 16383); cc(m, 0, 0x13, 0)
        assertEquals(0f, m.settings.leftFader); assertEquals(1f, m.settings.rightFader)
        cc(m, 1, 0x13, 16383); cc(m, 1, 0x13, 4096)
        near(4096 / 16383f, m.settings.rightFader)
    }
    @Test fun allSixEqControlsUseOfficialAddressesAndRemainIndependent() {
        for (ch in 0..1) for (base in listOf(0x0f, 0x0b, 0x07)) {
            val m = midi(); cc(m, ch, base, 8192); cc(m, ch, base, 0)
            val eq = listOf(m.settings.leftLow, m.settings.leftMid, m.settings.leftHigh,
                m.settings.rightLow, m.settings.rightMid, m.settings.rightHigh)
            val at = ch * 3 + when (base) { 0x0f -> 0; 0x0b -> 1; else -> 2 }
            eq.forEachIndexed { i, v -> assertEquals(if (i == at) 0f else 0.5f, v) }
        }
    }
    @Test fun pickupAndPartialMessagesResetOnDisconnect() {
        val m = midi(); cc(m, 6, 31, 8192)
        m.stream.accept(byteArrayOf(0xb6.toByte(), 31, 127))
        m.setSplitCue(true); note(m, 0, 0x54)
        m.reset(); assertEquals(DdjMixerSettings(), m.settings)
        m.reset(enabled = true)
        m.stream.accept(byteArrayOf(0xb6.toByte(), 63, 127))
        assertEquals(0.5f, m.settings.crossfader)
        cc(m, 6, 31, 0); assertEquals(0.5f, m.settings.crossfader)
    }
    @Test fun headphoneCueButtonsLatchPressAndReleaseAcrossShift() {
        val m = midi()
        note(m, 0, 0x54); note(m, 0, 0x54); note(m, 0, 0x68)
        assertTrue(m.settings.cueLeft)
        note(m, 0, 0x68, 0); note(m, 0, 0x54)
        assertFalse(m.settings.cueLeft)
        note(m, 1, 0x68); note(m, 6, 0x63)
        assertTrue(m.settings.cueRight); assertTrue(m.settings.cueMaster)
        note(m, 6, 0x78, 64, off = true); note(m, 6, 0x78)
        assertFalse(m.settings.cueMaster)
    }
    @Test fun normalCuePlayAndForeignCcNeverToggleHeadphoneCue() {
        val m = midi()
        note(m, 0, 0x0c); note(m, 0, 0x0b); note(m, 4, 0x54)
        cc(m, 2, 31, 8192); cc(m, 2, 31, 0)
        assertEquals(DdjMixerSettings(enabled = true), m.settings)
    }
    @Test fun splittingRequiresExplicitEnabledSessionAction() {
        val m = DdjMixerMidi {}
        m.setSplitCue(true); assertFalse(m.settings.splitCue)
        m.reset(enabled = true)
        note(m, 0, 0x54); assertFalse(m.settings.splitCue)
        m.setSplitCue(true); assertTrue(m.settings.splitCue)
        m.setSplitCue(false); assertFalse(m.settings.splitCue)
    }
    @Test fun disabledDspPreservesStereoSum() {
        val dsp = DdjMixerDsp(48000)
        dsp.process(0.2f, -0.3f, 0.4f, 0.1f, DdjMixerSettings())
        assertEquals(0.2f + 0.4f, dsp.left); assertEquals(-0.3f + 0.1f, dsp.right)
    }
    @Test fun neutralEqIsExactlyTransparentWithArbitraryHistory() {
        val dsp = DdjMixerDsp(48000); val s = DdjMixerSettings(enabled = true)
        dsp.reset(s)
        repeat(5000) {
            val a = sin(it * 0.011).toFloat() * 0.2f
            val b = sin(it * 0.239).toFloat() * 0.3f
            dsp.process(a, -b, b, -a, s)
            assertTrue(a + b == dsp.left); assertTrue(-b - a == dsp.right) // signed zero is acoustically identical
        }
    }
    @Test fun mutedSideIsZeroOnFirstFrameAfterIdleReset() {
        val dsp = DdjMixerDsp(44100)
        val s = DdjMixerSettings(enabled = true, crossfader = 0f)
        dsp.reset(s); dsp.process(0f, 0f, 0.7f, -0.3f, s)
        assertEquals(0f, dsp.left); assertEquals(0f, dsp.right)
        val newPosition = s.copy(crossfader = 1f)
        dsp.reset(newPosition); dsp.process(0.4f, 0.2f, 0f, 0f, newPosition)
        assertEquals(0f, dsp.left); assertEquals(0f, dsp.right)
    }
    @Test fun faderSmoothingIsBoundedAndEventuallyReachesExactMute() {
        val dsp = DdjMixerDsp(48000); val open = DdjMixerSettings(enabled = true)
        dsp.reset(open); val muted = open.copy(leftFader = 0f)
        dsp.process(1f, 1f, 0f, 0f, muted)
        assertTrue(dsp.left in 0.9f..1f)
        settle(dsp, muted); dsp.process(1f, 1f, 0f, 0f, muted)
        assertEquals(0f, dsp.left); assertEquals(0f, dsp.right)
    }
    @Test fun splitHasNoMasterInUnselectedCueEvenOnFirstSample() {
        val dsp = DdjMixerDsp(48000)
        val s = DdjMixerSettings(enabled = true, splitCue = true)
        dsp.reset(s); dsp.process(0.2f, 0.8f, 0f, 0f, s)
        near(0.5f, dsp.left); assertEquals(0f, dsp.right)
    }
    @Test fun splitCueMonitorsMutedBusPreFaderAndPreCrossfader() {
        val dsp = DdjMixerDsp(48000)
        val s = DdjMixerSettings(enabled = true, rightFader = 0f, crossfader = 0f,
            cueRight = true, splitCue = true)
        dsp.reset(s); dsp.process(0.2f, 0.4f, 0.6f, 0.8f, s)
        near(0.3f, dsp.left); near(0.7f, dsp.right)
    }
    @Test fun stereoModeIgnoresCueAndDoesNotCollapseChannels() {
        val dsp = DdjMixerDsp(48000)
        val s = DdjMixerSettings(enabled = true, cueRight = true)
        dsp.reset(s); dsp.process(0.1f, 0.5f, 0.2f, -0.1f, s)
        near(0.3f, dsp.left); near(0.4f, dsp.right)
    }
    @Test fun masterCueEqualsMonoMasterAndStereoRestoresExactly() {
        val dsp = DdjMixerDsp(48000)
        val s = DdjMixerSettings(enabled = true, cueMaster = true, splitCue = true)
        dsp.reset(s); dsp.process(0.4f, -0.2f, 0.1f, 0.5f, s)
        near(0.4f, dsp.left); assertEquals(dsp.left, dsp.right)
        dsp.process(0.4f, -0.2f, 0.1f, 0.5f, s.copy(splitCue = false))
        near(0.5f, dsp.left); near(0.3f, dsp.right)
    }
    @Test fun cueIsPostEqAndUnselectedOtherBusCannotLeak() {
        val dsp = DdjMixerDsp(48000)
        val s = DdjMixerSettings(enabled = true, crossfader = 1f, leftFader = 0f,
            leftLow = 0f, leftMid = 0f, leftHigh = 0f, cueLeft = true, splitCue = true)
        dsp.reset(s); dsp.process(0.5f, 0.5f, 0.9f, 0.9f, s)
        near(0.9f, dsp.left); near(0.5f * DdjMixerSettings.eqGain(0f), dsp.right)
    }
    @Test fun filterResetClearsTailButDoesNotRestoreUnityGain() {
        val dsp = DdjMixerDsp(48000)
        val s = DdjMixerSettings(enabled = true, leftLow = 1f, leftFader = 0.2f)
        dsp.reset(s); repeat(100) { dsp.process(0.5f, 0.5f, 0f, 0f, s) }
        dsp.reset(s); dsp.process(0f, 0f, 0f, 0f, s)
        assertEquals(0f, dsp.left); assertEquals(0f, dsp.right)
    }
    @Test fun nonFiniteInputsCannotPoisonFilterState() {
        val dsp = DdjMixerDsp(48000)
        val s = DdjMixerSettings(enabled = true, leftLow = 1f, rightHigh = 0f)
        dsp.reset(s); dsp.process(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, s)
        assertTrue(dsp.left.isFinite() && dsp.right.isFinite())
        dsp.process(0.2f, 0.1f, -0.1f, 0.3f, s)
        assertTrue(dsp.left.isFinite() && dsp.right.isFinite())
    }
    private fun amplitude(frequency: Double, settings: DdjMixerSettings): Double {
        val dsp = DdjMixerDsp(48000); dsp.reset(settings)
        var square = 0.0
        repeat(48000) { n ->
            val input = (0.1 * sin(2 * PI * frequency * n / 48000)).toFloat()
            dsp.process(input, input, 0f, 0f, settings)
            if (n >= 24000) square += dsp.left * dsp.left
        }
        return sqrt(square / 24000)
    }
    @Test fun lowAndHighBandsHaveRealFrequencySelectiveEffect() {
        val flat = DdjMixerSettings(enabled = true)
        val lowCut = flat.copy(leftLow = 0f)
        val highCut = flat.copy(leftHigh = 0f)
        assertTrue(amplitude(50.0, lowCut) < amplitude(50.0, flat) * 0.4)
        assertTrue(amplitude(12000.0, lowCut) > amplitude(12000.0, flat) * 0.85)
        assertTrue(amplitude(12000.0, highCut) < amplitude(12000.0, flat) * 0.65)
        assertTrue(amplitude(50.0, highCut) > amplitude(50.0, flat) * 0.95)
    }
    @Test fun midBandHasRealFrequencySelectiveEffect() {
        val flat = DdjMixerSettings(enabled = true)
        val midCut = flat.copy(leftMid = 0f)
        assertTrue(amplitude(1000.0, midCut) < amplitude(1000.0, flat) * 0.5)
        assertTrue(amplitude(50.0, midCut) > amplitude(50.0, flat) * 0.85)
    }
    @Test fun extremeMixRemainsFiniteAndLeavesLimitingToEngine() {
        val dsp = DdjMixerDsp(48000)
        val s = DdjMixerSettings(enabled = true, leftLow = 1f, leftMid = 1f, leftHigh = 1f,
            rightLow = 1f, rightMid = 1f, rightHigh = 1f, cueLeft = true, cueRight = true,
            cueMaster = true, splitCue = true)
        dsp.reset(s)
        repeat(10000) {
            dsp.process(1f, -0.5f, 0.5f, 1f, s)
            assertTrue(dsp.left.isFinite() && dsp.right.isFinite())
        }
        assertTrue(dsp.right > 1f) // existing masterSampleForAudioTrack limits AFTER this stage
    }
    @Test fun everyByteSplitOfMixerCcAndCuePreservesMeaning() {
        val bytes = byteArrayOf(0xb6.toByte(), 31, 64, 63, 0, 31, 127, 63, 127,
            0x90.toByte(), 0x54, 127, 0x54, 0)
        for (cut in 0..bytes.size) {
            val m = midi(); m.stream.accept(bytes, 0, cut); m.stream.accept(bytes, cut, bytes.size - cut)
            assertEquals(1f, m.settings.crossfader); assertTrue(m.settings.cueLeft)
        }
    }
}
