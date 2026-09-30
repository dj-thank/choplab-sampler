package com.choplab.engine

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
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

    @Test fun optionalPanMatchesTheLiveVoiceWithoutBakingGainEnvelopeOrBankMix() {
        for (pan in listOf(-1f, -.8f, 0f, .8f, 1f)) for (transformed in listOf(false, true)) {
            fun pad(gain: Float, attack: Int, decay: Int, sustain: Float, release: Int, bankGain: Float, bankPan: Float) =
                Pad(0, asset, 100, 1_100, gain = gain, pan = pan, attackFrames = attack, decayFrames = decay,
                    sustainLevel = sustain, releaseFrames = release, mixGain = bankGain, mixPan = bankPan,
                    pitchSemitones = if (transformed) -3.0 else 0.0, reverse = transformed, tone = if (transformed) .4f else 1f)
            val source = pad(.23f, 500, 300, .2f, 900, .47f, -pan)
            val voice = pad(1f, 0, 0, 1f, 1, 1f, 0f)
            assertContentEquals(PadRender.render(voice), PadRender.render(source), "The existing unpanned API keeps its bytes")
            val baked = PadRender.render(source, bakePan = true)
            assertEquals(PadRender.frames(source) * 2, baked.size)
            val played = OfflineRender.render(EngineProgram(listOf(voice)), listOf(EngineCommand.Trigger(0, 0, 0)), baked.size / 2)
            // The placement renderer has no envelope; only the live voice's natural release is excluded.
            for (i in 0 until baked.size - 4)
                assertEquals(played[i], baked[i], 1e-6f, "Pan $pan, transformed $transformed, sample $i")
        }
    }
}
