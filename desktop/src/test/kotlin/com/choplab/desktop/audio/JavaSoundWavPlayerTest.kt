package com.choplab.desktop.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import kotlin.test.Test
import kotlin.test.assertEquals

class JavaSoundWavPlayerTest {
    @Test
    fun forceLoopKeepsTheLegacyNonIntegralRenderBoundaryWhenThePadIsOneShot() {
        val audio = PcmAudio(
            name = "short",
            samples = shortArrayOf(8_000, 16_000),
            sampleRate = 48_000,
        )
        val pad = PadModel(
            globalIndex = 0,
            audio = audio,
            startFrame = 0,
            endFrame = 2,
            pitchSemitones = -5f,
            reverse = true,
        )

        val oneShot = renderDesktopPadPcm(pad, PadPlayMode.ONE_SHOT)
        val forcedLoop = renderDesktopPadPcm(pad, PadPlayMode.LOOP)

        assertEquals(2, oneShot.size)
        assertEquals(3, forcedLoop.size)
        assertEquals(0.toShort(), forcedLoop.last())
    }
}
