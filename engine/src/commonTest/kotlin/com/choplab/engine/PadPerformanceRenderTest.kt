package com.choplab.engine

import kotlin.math.sin
import kotlin.test.*

class PadPerformanceRenderTest {
    private val asset = PcmAsset.fromInterleaved(FloatArray(2_400 * 2) { i ->
        (sin((i / 2) * .031) * if (i % 2 == 0) .12 else -.07).toFloat()
    })

    @Test fun gateAndLoopReleasesMatchTheLiveEngineIncludingEnvelopeAndStereo() {
        for (mode in listOf(PlayMode.GATE, PlayMode.LOOP, PlayMode.ONE_SHOT)) {
            val pad = Pad(0, asset, 100, 2_000, mode = mode, gain = .7f, pan = .25f,
                pitchSemitones = -3.0, reverse = true, tone = .4f,
                attackFrames = 32, releaseFrames = 96, decayFrames = 120, sustainLevel = .6f)
            val release = if (mode == PlayMode.LOOP) 3_000 else 480
            val rendered = PadPerformanceRender.render(pad, release, 4_000)
            assertEquals((release + 96) * 2, rendered.size)
            val live = OfflineRender.render(EngineProgram(listOf(pad)), listOf(
                EngineCommand.Trigger(0, 0, 0), EngineCommand.Release(release.toLong(), 1, 0)), 4_000)
            for (i in rendered.indices) assertEquals(live[i], rendered[i], 1e-6f, "$mode sample $i")
            for (i in rendered.size until live.size) assertEquals(0f, live[i], 1e-6f)
        }
    }

    @Test fun naturalEndAndTimelineBoundaryAreBoundedAndInvalidLoopsAreRejected() {
        val pad = Pad(0, asset, 0, 2_400)
        val natural = PadPerformanceRender.render(pad, null, 4_000)
        assertEquals(4_800, natural.size)
        val bounded = PadPerformanceRender.render(pad, null, 480)
        assertContentEquals(natural.copyOf(960), bounded)
        assertFailsWith<IllegalArgumentException> { PadPerformanceRender.render(pad, -1, 480) }
        assertFailsWith<IllegalArgumentException> { PadPerformanceRender.render(pad, null, PadRender.MAX_FRAMES + 1) }
        assertFailsWith<IllegalArgumentException> { PadPerformanceRender.render(Pad(0, asset, 0, 2_400, mode = PlayMode.LOOP), null, 480) }
    }
}
