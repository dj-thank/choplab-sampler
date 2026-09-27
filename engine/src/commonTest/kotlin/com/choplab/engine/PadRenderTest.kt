package com.choplab.engine

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/** A PAD rendered for the song sounds as its voice plays it: range, reverse, pitch and tone. */
class PadRenderTest {
    /** Left and right differ, so a swapped channel shows. */
    private val asset = PcmAsset.fromInterleaved(FloatArray(4_000 * 2) { (.5 * sin(2 * PI * (it / 2) / 97) * if (it % 2 == 0) 1 else -1).toFloat() })

    @Test fun anUntouchedPadRendersItsRangeAndAReversedOneBackwards() {
        val plain = PadRender.render(Pad(0, asset, 100, 1_100))
        assertEquals(1_000 * 2, plain.size)
        for (i in 0 until 1_000) for (c in 0..1) assertEquals(asset.sample(100 + i, c), plain[i * 2 + c])
        val reversed = PadRender.render(Pad(0, asset, 100, 1_100, reverse = true))
        assertEquals(1_000 * 2, reversed.size)
        for (i in 0 until 1_000) for (c in 0..1) assertEquals(asset.sample(1_099 - i, c), reversed[i * 2 + c])
    }

    @Test fun aPitchedDarkenedPadRendersWhatItsVoicePlays() {
        val pad = Pad(0, asset, 100, 1_100, pitchSemitones = 12.0, tone = .5f, attackFrames = 0, releaseFrames = 1)
        val rendered = PadRender.render(pad)
        assertEquals(500 * 2, rendered.size, "An octave up halves its length")
        // Played once from its PAD, well below the limiter's ceiling; its voice fades only its last frame.
        val played = OfflineRender.render(EngineProgram(listOf(pad)), listOf(EngineCommand.Trigger(0, 0, 0)), 500)
        for (i in 0 until 499 * 2) assertEquals(played[i], rendered[i], 1e-6f, "Sample $i")
    }
}
