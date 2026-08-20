package com.choplab.desktop.audio

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopSystemAudioRecorderTest {
    @Test
    fun acceptsKnownDriverLoopbackNames() {
        assertTrue(DesktopSystemAudioRecorder.looksLikeLoopback("Stereo Mix", "Realtek Audio"))
        assertTrue(DesktopSystemAudioRecorder.looksLikeLoopback("ステレオ ミキサー", "再生デバイス"))
        assertTrue(DesktopSystemAudioRecorder.looksLikeLoopback("Monitor Loopback", "USB interface"))
    }

    @Test
    fun refusesAnOrdinaryMicrophone() {
        assertFalse(DesktopSystemAudioRecorder.looksLikeLoopback("Microphone Array", "Intel Smart Sound"))
    }

    @Test
    fun triesCommonStereoLoopbackFormatsBeforeMonoFallbacks() {
        val formats = DesktopSystemAudioRecorder.candidateFormats()
        assertTrue(formats.first().channels == 2)
        assertTrue(formats.any { it.sampleRate == 44_100f && it.channels == 2 })
        assertTrue(formats.last().channels == 1)
    }
}
