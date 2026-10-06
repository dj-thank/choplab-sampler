package com.choplab.engine

import kotlin.math.PI
import kotlin.math.cos

/**
 * Offline: a PAD's range as its voice reads it, with its pitch, reverse and tone, at unity gain and without envelope,
 * pan or limiter by default. Interleaved stereo at the engine rate, for placing a transformed PAD on the song as plain audio.
 */
object PadRender {
    /** The most frames one render may produce: what the engine can hold resident. */
    const val MAX_FRAMES = (EngineFormat.MAX_RESIDENT_BYTES / 8).toInt()

    /** How many frames [render] produces: as many as the voice reads before it leaves the range. */
    fun frames(pad: Pad): Int {
        var count = 0
        var position = start(pad)
        while (position >= pad.startFrame && position < pad.endFrame) {
            require(++count <= MAX_FRAMES) { "Rendered PAD exceeds the resident budget" }
            position += pad.step
        }
        return count
    }

    fun render(pad: Pad, prepared: (List<PcmWindow>, () -> Unit) -> Unit = { windows, render -> require(windows.isEmpty()); render() }): FloatArray =
        render(pad, bakePan = false, prepared = prepared)

    /** Opt in to the PAD's own pan before the destination BANK fader. Gain, envelope and BANK mix remain outside. */
    fun render(pad: Pad, bakePan: Boolean,
               prepared: (List<PcmWindow>, () -> Unit) -> Unit = { windows, render -> require(windows.isEmpty()); render() }): FloatArray {
        val frames = frames(pad)
        val output = FloatArray(frames * 2)
        val leftPan = if (bakePan && pad.pan > 0f) cos(pad.pan * PI / 2).toFloat() else 1f
        val rightPan = if (bakePan && pad.pan < 0f) cos(-pad.pan * PI / 2).toFloat() else 1f
        val interpolator = PitchInterpolator()
        val cursor = PcmReadCursor()
        var position = start(pad)
        var toneLeft = 0.0
        var toneRight = 0.0
        var first = 0
        while (first < frames) {
            val end = minOf(frames, first + 512)
            prepared(PcmWindow.pad(pad, position, pad.step, end - first, false)) {
                cursor.reset()
                for (frame in first until end) {
                var left = interpolator.read(pad.asset, position, pad.step, 0, pad.startFrame, pad.endFrame, false, pad.loopCrossfadeFrames, cursor)
                var right = interpolator.read(pad.asset, position, pad.step, 1, pad.startFrame, pad.endFrame, false, pad.loopCrossfadeFrames, cursor)
                if (pad.toneAlpha < 1.0) {
                    toneLeft += pad.toneAlpha * (left - toneLeft); left = toneLeft
                    toneRight += pad.toneAlpha * (right - toneRight); right = toneRight
                }
                output[frame * 2] = (left * leftPan).toFloat()
                output[frame * 2 + 1] = (right * rightPan).toFloat()
                position += pad.step
                }
                check(!cursor.missing) { "PCM missing during PAD render" }
                cursor.clear()
            }
            first = end
        }
        return output
    }

    private fun start(pad: Pad) = if (pad.reverse) pad.endFrame - 1.0 else pad.startFrame.toDouble()
}
