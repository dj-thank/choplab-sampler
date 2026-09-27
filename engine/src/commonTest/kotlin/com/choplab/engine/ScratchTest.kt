package com.choplab.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Scratching a PAD or the original by hand: heard only while moved, within its range, and without clicks. */
class ScratchTest {
    /** A slow, loud sine: its steepest natural step between frames is 0.5 × 2π / 400 ≈ 0.008. */
    private fun sine(frames: Int = 8_192) = PcmAsset.fromMono(FloatArray(frames) { (.5 * sin(2 * PI * it / 400)).toFloat() })
    private fun largestStep(output: FloatArray, from: Int, to: Int): Float {
        var largest = 0f
        for (frame in maxOf(1, from) until to) for (channel in 0..1) {
            largest = maxOf(largest, abs(output[frame * 2 + channel] - output[(frame - 1) * 2 + channel]))
        }
        return largest
    }
    private fun monitor(asset: PcmAsset): EngineCore {
        val engine = EngineCore(EngineProgram(listOf(Pad(0, asset))))
        engine.controls.offer(EngineCommand.SetOriginalSource(0, 0, OriginalSource(asset)))
        return engine
    }
    private fun EngineCore.eventFor(orderId: Long): EngineEventType? {
        val event = MutableEngineEvent()
        var type: EngineEventType? = null
        while (events.poll(event)) if (event.orderId == orderId) type = event.type
        return type
    }

    @Test fun theHandIsSilentAtRestAndStaysInItsRange() {
        val engine = monitor(sine())
        engine.render(FloatArray(400 * 2))
        // HAND begins independently while SOURCE stays paused.
        engine.controls.offer(EngineCommand.ScratchOriginalStart(400, 2, 1_000.0, 500, 3_000))
        val held = FloatArray(1_000 * 2)
        engine.render(held)
        assertFalse(engine.originalPlaying)
        assertEquals(1_000.0, engine.handSourceFrame)
        assertTrue(held.drop(200 * 2).all { it == 0f }, "Silent at rest once the old playback faded")
        // Moving it forward 1 000 frames over 1 000 output frames plays it at normal speed.
        engine.controls.offer(EngineCommand.ScratchOriginalPosition(1_400, 3, 2_000.0, 1_000))
        val moved = FloatArray(1_200 * 2)
        engine.render(moved)
        assertEquals(2_000.0, engine.handSourceFrame)
        assertTrue(moved.slice(200 * 2 until 800 * 2).any { abs(it) > .3f }, "Heard while it moves")
        // Stopped at 2 400, faded over 96 frames, past the limiter's 72-frame look-ahead.
        assertTrue(moved.drop(1_180 * 2).all { it == 0f }, "Silent again once it stops")
        // The range holds it: a target far past its end is taken as its end, reached on time at about normal speed.
        engine.controls.offer(EngineCommand.ScratchOriginalPosition(2_600, 4, 20_000.0, 1_000))
        engine.render(FloatArray(500 * 2))
        assertTrue(engine.handSourceFrame in 2_490.0..2_510.0, "Halfway to the range end: ${engine.handSourceFrame}")
        engine.render(FloatArray(700 * 2))
        val atEnd = engine.handSourceFrame
        assertTrue(atEnd in 2_998.0..2_999.0, "Held at the range's last frame: $atEnd")
        // A fling faster than eight times normal speed moves at that limit and arrives late.
        engine.controls.offer(EngineCommand.ScratchOriginalPosition(3_800, 5, 500.0, 100))
        engine.render(FloatArray(200 * 2))
        assertTrue(abs(engine.handSourceFrame - (atEnd - 800)) <= 1, "Eight times 100 frames back: ${engine.handSourceFrame}")
        assertEquals(EngineEventType.APPLIED, engine.eventFor(5))
        // Closing the cut silences a moving scratch.
        engine.controls.offer(EngineCommand.ScratchOriginalCut(4_000, 6, 0f))
        engine.controls.offer(EngineCommand.ScratchOriginalPosition(4_000, 7, 1_000.0, 2_000))
        val cut = FloatArray(1_000 * 2)
        engine.render(cut)
        assertTrue(cut.drop(200 * 2).all { it == 0f })
        // Letting go releases HAND without changing or starting SOURCE.
        engine.controls.offer(EngineCommand.ScratchOriginalEnd(5_000, 8))
        engine.render(FloatArray(100 * 2))
        assertEquals(-1.0, engine.handSourceFrame)
        assertFalse(engine.originalPlaying)
        assertEquals(0L, engine.originalSourceFrame)
    }

    @Test fun aScratchStartsAndStopsWithoutClicks() {
        val asset = sine()
        // A PAD scratch: moving at normal speed from frame 100 to 1 100, then held still.
        val pad = OfflineRender.render(EngineProgram(listOf(Pad(0, asset, attackFrames = 0))), listOf(
            EngineCommand.ScratchStart(0, 0, 0, 1_100.0),
            EngineCommand.ScratchPosition(100, 1, 2_100.0, 1_000),
            EngineCommand.ScratchEnd(1_600, 2)), 2_000)
        assertTrue(pad.any { abs(it) > .3f })
        // Far below the half-amplitude jump that starting or stopping at a crest would make without the fades.
        assertTrue(largestStep(pad, 0, 2_000) < .03f, "PAD scratch step ${largestStep(pad, 0, 2_000)}")

        val engine = monitor(asset)
        engine.controls.offer(EngineCommand.ScratchOriginalStart(0, 1, 1_100.0, 0, 8_192))
        engine.controls.offer(EngineCommand.ScratchOriginalPosition(100, 2, 2_100.0, 1_000))
        engine.controls.offer(EngineCommand.ScratchOriginalEnd(1_600, 3))
        val original = FloatArray(2_000 * 2)
        engine.render(original)
        assertTrue(original.any { abs(it) > .3f })
        assertTrue(largestStep(original, 0, 2_000) < .03f, "Original scratch step ${largestStep(original, 0, 2_000)}")
    }

    @Test fun lettingGoOfAStillPlatterStaysSilent() {
        val asset = sine()
        // Moved, held still until silent, then let go: the last movement does not start again.
        val pad = OfflineRender.render(EngineProgram(listOf(Pad(0, asset, attackFrames = 0))), listOf(
            EngineCommand.ScratchStart(0, 0, 0, 1_000.0),
            EngineCommand.ScratchPosition(0, 1, 1_500.0, 500),
            EngineCommand.ScratchEnd(1_000, 2)), 2_000)
        assertTrue(pad.take(500 * 2).any { abs(it) > .3f })
        assertTrue(pad.drop(800 * 2).all { it == 0f }, "The PAD stays silent")

        val engine = monitor(asset)
        engine.controls.offer(EngineCommand.ScratchOriginalStart(0, 1, 1_000.0, 0, 8_192))
        engine.controls.offer(EngineCommand.ScratchOriginalPosition(0, 2, 1_500.0, 500))
        engine.controls.offer(EngineCommand.ScratchOriginalEnd(1_000, 3))
        val original = FloatArray(2_000 * 2)
        engine.render(original)
        assertTrue(original.take(500 * 2).any { abs(it) > .3f })
        assertTrue(original.drop(800 * 2).all { it == 0f }, "The original stays silent")
    }

    @Test fun aFlingFasterThanEightTimesNormalSpeedMovesAtThatLimit() {
        val asset = sine()
        fun pad(target: Double) = OfflineRender.render(EngineProgram(listOf(Pad(0, asset, attackFrames = 0))), listOf(
            EngineCommand.ScratchStart(0, 0, 0, 100.0), EngineCommand.ScratchPosition(0, 1, target, 500)), 1_000)
        val limit = pad(100.0 + 8 * 500)
        assertTrue(limit.any { abs(it) > .1f })
        assertContentEquals(limit, pad(8_000.0), "A PAD fling moves at eight times normal speed instead of falling silent")

        fun original(target: Double): FloatArray {
            val engine = monitor(asset)
            engine.controls.offer(EngineCommand.ScratchOriginalStart(0, 1, 100.0, 0, 8_192))
            engine.controls.offer(EngineCommand.ScratchOriginalPosition(0, 2, target, 500))
            return FloatArray(1_000 * 2).also { engine.render(it) }
        }
        val limited = original(100.0 + 8 * 500)
        assertTrue(limited.any { abs(it) > .1f })
        assertContentEquals(limited, original(8_000.0), "The original likewise")
    }

    @Test fun aFastMovementStopsWithoutAClick() {
        // A low tone with a loud high one on top. Moving six times faster, the reader filters the high one out; reading
        // the resting frame with the full band would bring it back at once, a click at nearly full level.
        val asset = PcmAsset.fromMono(FloatArray(8_192) { (.4 * sin(2 * PI * it / 400) + .4 * sin(PI * it / 2 + PI / 4)).toFloat() })
        val pad = OfflineRender.render(EngineProgram(listOf(Pad(0, asset, attackFrames = 0))), listOf(
            EngineCommand.ScratchStart(0, 0, 0, 1_000.0), EngineCommand.ScratchPosition(0, 1, 2_800.0, 300)), 1_000)
        assertTrue(largestStep(pad, 200, 1_000) < .06f, "PAD step ${largestStep(pad, 200, 1_000)}")

        val engine = monitor(asset)
        engine.controls.offer(EngineCommand.ScratchOriginalStart(0, 1, 1_000.0, 0, 8_192))
        engine.controls.offer(EngineCommand.ScratchOriginalPosition(0, 2, 2_800.0, 300))
        val original = FloatArray(1_000 * 2)
        engine.render(original)
        assertTrue(largestStep(original, 200, 1_000) < .06f, "Original step ${largestStep(original, 200, 1_000)}")
    }

    @Test fun aSoundingPadIsTakenWhereItPlaysAndASilentOneWhereAsked() {
        val asset = sine()
        val engine = EngineCore(EngineProgram(listOf(Pad(0, asset, mode = PlayMode.LOOP, attackFrames = 0))))
        engine.controls.offer(EngineCommand.Trigger(0, 0, 0))
        engine.render(FloatArray(1_000 * 2))
        assertEquals(-1.0, engine.scratchFrame)
        engine.controls.offer(EngineCommand.ScratchStart(1_000, 1, 0, 5_000.0))
        engine.render(FloatArray(10 * 2))
        assertEquals(1_000.0, engine.scratchFrame, "Taken at its loop's playhead")
        val snapshot = EngineSnapshot()
        assertTrue(engine.readout.copyInto(snapshot))
        assertEquals(1_000.0, snapshot.scratchFrame, "Published for the hand to start from")
        engine.controls.offer(EngineCommand.ScratchEnd(1_010, 2))
        engine.render(FloatArray(10 * 2))
        assertEquals(-1.0, engine.scratchFrame)
        val silent = EngineCore(EngineProgram(listOf(Pad(0, asset))))
        silent.controls.offer(EngineCommand.ScratchStart(0, 0, 0, 5_000.0))
        silent.render(FloatArray(10 * 2))
        assertEquals(5_000.0, silent.scratchFrame)
    }

    @Test fun sourcePlaybackIsIndependentAndANewSourceEndsTheHand() {
        val asset = sine()
        val engine = monitor(asset)
        engine.controls.offer(EngineCommand.ScratchOriginalStart(0, 1, 100.0, 0, 8_192))
        engine.controls.offer(EngineCommand.PlayOriginalSource(10, 2))
        engine.render(FloatArray(110 * 2))
        assertTrue(engine.originalPlaying)
        assertEquals(100L, engine.originalSourceFrame, "SOURCE starts at its own cursor")
        assertEquals(100.0, engine.handSourceFrame, "HAND keeps its own cursor")
        engine.controls.offer(EngineCommand.ScratchOriginalStart(110, 3, 500.0, 0, 8_192))
        engine.controls.offer(EngineCommand.SetOriginalSource(120, 4, OriginalSource(asset, 1_000)))
        engine.controls.offer(EngineCommand.ScratchOriginalPosition(130, 5, 1_050.0, 100))
        engine.render(FloatArray(100 * 2))
        assertEquals(1_090L, engine.originalSourceFrame, "The replacement SOURCE keeps playing normally")
        assertEquals(-1.0, engine.handSourceFrame)
        assertEquals(EngineEventType.INVALID_COMMAND, engine.eventFor(5), "A move after the new source is refused")
        engine.controls.offer(EngineCommand.ScratchOriginalEnd(230, 6))
        engine.render(FloatArray(100 * 2))
        assertTrue(engine.originalPlaying, "Letting go does not pause the replacement SOURCE")
        assertEquals(1_190L, engine.originalSourceFrame)
        // Without a source there is nothing to scratch.
        val empty = EngineCore(EngineProgram(listOf(Pad(0, asset))))
        empty.controls.offer(EngineCommand.ScratchOriginalStart(0, 0, 0.0, 0, 100))
        empty.render(FloatArray(10 * 2))
        assertEquals(EngineEventType.ASSET_MISS, empty.eventFor(0))
    }
}
