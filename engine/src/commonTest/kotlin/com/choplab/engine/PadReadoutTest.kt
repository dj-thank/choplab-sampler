package com.choplab.engine

import kotlin.test.*

class PadReadoutTest {
    @Test fun padMasksCoverBothBanksAndExcludeOriginalAudition() {
        val asset = PcmAsset.fromInterleaved(FloatArray(2048) { .1f })
        val engine = EngineCore(EngineProgram(listOf(Pad(0, asset, mode = PlayMode.LOOP), Pad(100, asset, mode = PlayMode.LOOP))))
        val block = FloatArray(512)
        val view = EngineSnapshot()
        engine.controls.offer(EngineCommand.SetOriginalSource(0, 1, OriginalSource(asset, loop = true)))
        engine.controls.offer(EngineCommand.PlayOriginalSource(0, 2))
        engine.render(block)
        assertTrue(engine.readout.copyInto(view))
        assertEquals(0L, view.playingPadsLow)
        engine.controls.offer(EngineCommand.Trigger(engine.frame, 3, 0))
        engine.controls.offer(EngineCommand.Trigger(engine.frame, 4, 100))
        engine.render(block)
        assertTrue(engine.readout.copyInto(view))
        assertEquals(1L, view.playingPadsLow)
        assertEquals(1L shl 36, view.playingPadsHigh)
        engine.controls.offer(EngineCommand.Stop(engine.frame, 5))
        engine.render(block)
        assertTrue(engine.readout.copyInto(view))
        assertEquals(0L, view.playingPadsLow)
        assertEquals(0L, view.playingPadsHigh)
        assertTrue(view.originalPlaying)
    }
}
