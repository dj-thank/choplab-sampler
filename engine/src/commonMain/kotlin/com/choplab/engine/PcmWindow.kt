package com.choplab.engine

import kotlin.math.floor

/** Worker-side range plan. It is never constructed from render. */
data class PcmWindow(val asset: PcmAsset, val first: Int, val end: Int) {
    init { require(first >= 0 && end > first && end <= asset.frameCount) }
    companion object {
        /** Includes the 128-tap pitch filter and both loop seam samples, in absolute source frames. */
        fun pad(pad: Pad, position: Double, speed: Double, frames: Int, loop: Boolean = pad.mode == PlayMode.LOOP): List<PcmWindow> {
            require(frames in 1..4096)
            if (pad.asset.pages == null) return emptyList()
            val other = position + speed * (frames - 1)
            val first = floor(minOf(position, other)).toLong() - 64
            val end = floor(maxOf(position, other)).toLong() + 66
            val low = pad.startFrame.toLong()
            val high = pad.endFrame.toLong()
            val result = mutableListOf<PcmWindow>()
            fun add(a: Long, b: Long) {
                val from = maxOf(low, a).toInt(); val until = minOf(high, b).toInt()
                if (until > from) result.add(PcmWindow(pad.asset, from, until))
            }
            if (!loop) add(first, end)
            else {
                val length = high - low
                if (end - first >= length) add(low, high)
                else {
                    val wrapped = low + ((first - low) % length + length) % length
                    add(wrapped, wrapped + end - first)
                    if (wrapped + end - first > high) add(low, low + wrapped + end - first - high)
                }
                // Crossfade reads the fixed opposite endpoint, not another long range.
                if (pad.loopCrossfadeFrames > 0) { add(low, low + 1); add(high - 1, high) }
            }
            return result
        }
    }
}

data class OfflinePcmBlock(val frames: Int, val windows: List<PcmWindow>)
