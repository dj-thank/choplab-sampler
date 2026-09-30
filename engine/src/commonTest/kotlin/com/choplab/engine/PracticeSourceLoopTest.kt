package com.choplab.engine

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.*

class PracticeSourceLoopTest {
    @Test fun endpointBlendPreservesPeriodStereoAndBlockIdentityIncludingShortRanges() {
        for (period in listOf(1, 2, 17, 96, 481, 4096)) {
            val source = PcmAsset.fromInterleaved(FloatArray(period * 2) { i ->
                val sample = (.1 + .06 * sin(i / 2 * .031)).toFloat()
                if (i % 2 == 0) sample else -.37f * sample
            })
            fun render(block: Int): FloatArray {
                val engine = EngineCore(EngineProgram(emptyList()))
                try {
                    engine.controls.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(source, loop = true, loopCrossfadeFrames = 480)))
                    engine.controls.offer(EngineCommand.PlayOriginalSource(0, 1))
                    val total = 3 * period + 512
                    val output = FloatArray(total * 2)
                    var at = 0
                    while (at < total) {
                        val count = minOf(block, total - at)
                        engine.render(output, at, count); at += count
                    }
                    assertEquals((total % period).toLong(), engine.originalSourceFrame)
                    assertTrue(engine.originalPlaying)
                    return output
                } finally { engine.close() }
            }
            val expected = render(1)
            for (block in listOf(17, 96, 192, 480, 4096)) assertContentEquals(expected, render(block), "period=$period block=$block")
            val settled = 256
            for (i in settled until expected.size / 2 - period) {
                assertEquals(expected[i*2], expected[(i+period)*2], "No frames are removed at the seam")
                assertEquals(expected[i*2+1], expected[(i+period)*2+1])
                assertEquals(expected[i*2]*-.37f, expected[i*2+1], 3e-8f)
            }
            // Independent of interpolation implementation: both endpoint samples meet at the same midpoint.
            val voice = OriginalSourceVoice()
            voice.set(OriginalSource(source, loop = true, loopCrossfadeFrames = 480), 0); voice.play()
            val interpolator = PitchInterpolator()
            val warm = ((96 + period - 1) / period + 1) * period
            repeat(warm + period) { voice.render(interpolator) }
            val last = voice.outputLeft
            voice.render(interpolator)
            assertTrue(abs(voice.outputLeft-last) <= 1e-7, "seam residual: period=$period")
        }
    }
}
