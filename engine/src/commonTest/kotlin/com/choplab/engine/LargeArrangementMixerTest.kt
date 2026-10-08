package com.choplab.engine

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.*

class LargeArrangementMixerTest {
    @Test fun sixtyFourTrackFxStemsAndReturnsKeepStereoIdentityAcrossPartitionsAndSelectedStemExport() {
        val source = PcmAsset.fromInterleaved(FloatArray(256) {
            if (it % 2 == 0) .005f * cos(it * .031f) else -.002f * sin(it * .043f)
        })
        val channels = List(64) { bus -> TrackFx(MixInsert(eq = MixEq(midDb = bus % 7 - 3f)), .2f, .3f) }
        val mixer = MixerProgram(channels, MixSettings(MixDelay(true, 97, .2f), MixReverb(true, .1f)))
        val program = EngineProgram(arrangement = Arrangement(List(64) { bus ->
            ArrangementClip("clip-$bus", source, (bus / 32) * 128L, trackIndex = bus)
        }), mixer = mixer)
        assertEquals(32, program.arrangement!!.maximumOverlap)
        assertEquals(64, MixerProgram.UNROUTED_BUS)
        assertEquals(67, MixerProgram.STEM_COUNT)
        val frames = 3_100
        fun render(block: Int, selected: Int? = null): Pair<FloatArray, FloatArray> {
            val engine = EngineCore(program, EngineConfig(outputMode = EngineOutputMode.EXPORT))
            val master = FloatArray(frames * 2)
            val stems = FloatArray(frames * MixerProgram.STEM_COUNT * 2)
            try {
                selected?.let(engine::selectTrackStemForExport)
                assertEquals(OfferResult.ACCEPTED, engine.controls.offer(EngineCommand.StartSequence(0, 1)))
                var at = 0
                while (at < frames) {
                    val count = minOf(block, frames - at)
                    engine.render(master, at, count, stems, at)
                    at += count
                }
                assertEquals(0, engine.pcmUnderrunFrames)
                return master to stems
            } finally { engine.close() }
        }
        val reference = render(480)
        for (block in listOf(1, 17, 96, 192, 4096)) {
            val actual = render(block)
            assertContentEquals(reference.first, actual.first, "master block=$block")
            assertContentEquals(reference.second, actual.second, "stems block=$block")
        }
        for (bus in listOf(0, 15, 16, 31, 32, 63)) {
            val isolated = render(17, bus).second
            var leftEnergy = 0.0; var rightEnergy = 0.0
            repeat(frames) { at ->
                val sample = (at * MixerProgram.STEM_COUNT + bus) * 2
                assertEquals(reference.second[sample], isolated[sample], "bus=$bus frame=$at left")
                assertEquals(reference.second[sample + 1], isolated[sample + 1], "bus=$bus frame=$at right")
                leftEnergy += isolated[sample].toDouble() * isolated[sample]
                rightEnergy += isolated[sample + 1].toDouble() * isolated[sample + 1]
            }
            assertTrue(leftEnergy > 0 && rightEnergy > 0)
            assertNotEquals(leftEnergy, rightEnergy)
        }
        for (bus in listOf(MixerProgram.DELAY_RETURN, MixerProgram.REVERB_RETURN)) {
            assertTrue((0 until frames).any { reference.second[(it * MixerProgram.STEM_COUNT + bus) * 2] != 0f })
        }
    }
}
