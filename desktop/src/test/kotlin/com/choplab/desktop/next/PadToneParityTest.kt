package com.choplab.desktop.next

import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import com.choplab.engine.OfflineRender
import com.choplab.engine.Pad
import com.choplab.engine.PcmAsset
import com.choplab.engine.PlayMode
import com.choplab.sampler.audio.SamplerDspPrimitives
import kotlin.math.sin
import kotlin.test.*

/** The linked editor's PAD tone is the earlier app's tone: the same curve and one-pole low-pass on each channel. */
class PadToneParityTest {
    @Test fun toneFiltersEachChannelLikeTheEarlierApp() {
        val frames = 4_800
        val interleaved = FloatArray(frames * 2) { if (it % 2 == 0) (.4 * sin(it * .9)).toFloat() else (-.3 * sin(it * .05)).toFloat() }
        val source = PcmAsset.fromInterleaved(interleaved)
        for (tone in listOf(0f, .2f, .45f, .7f, .9f, .99f, .995f, 1f)) {
            // A steady loop with no envelope, so the output is the filtered source itself.
            val pad = Pad(0, source, mode = PlayMode.LOOP, attackFrames = 0, loopCrossfadeFrames = 0, tone = tone)
            val rendered = OfflineRender.render(EngineProgram(listOf(pad)), listOf(EngineCommand.Trigger(0, 0, 0)), 4_000)
            // The earlier engine's filter at the linked editor's 48 kHz, starting from silence like a new voice.
            val alpha = SamplerDspPrimitives.toneFilterAlpha(tone, 48_000).toDouble()
            var left = 0.0
            var right = 0.0
            for (frame in 0 until 4_000) {
                val inLeft = interleaved[frame * 2].toDouble()
                val inRight = interleaved[frame * 2 + 1].toDouble()
                if (alpha >= 1.0) { left = inLeft; right = inRight }
                else { left += alpha * (inLeft - left); right += alpha * (inRight - right) }
                assertEquals(left, rendered[frame * 2].toDouble(), 1e-5, "tone $tone left $frame")
                assertEquals(right, rendered[frame * 2 + 1].toDouble(), 1e-5, "tone $tone right $frame")
            }
        }
    }
}
