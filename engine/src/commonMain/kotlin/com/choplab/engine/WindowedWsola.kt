package com.choplab.engine

import kotlin.math.*

/** Worker only. Reads at most 4096 stereo frames into caller-owned storage. */
fun interface WsolaReader { fun read(first: Int, frames: Int, destination: FloatArray) }

/** Linked-stereo WSOLA with fixed memory, including long sources. Never used in render(). */
object WindowedWsola {
    const val VERSION = 1
    const val WORKSPACE_BYTES = (4096L + 2048L) * 8

    fun process(reader: WsolaReader, inputFrames: Int, outputFrames: Int,
                emit: (FloatArray, Int) -> Unit, checkCancelled: () -> Unit = {}, progress: (Int, Int) -> Unit = { _, _ -> }) {
        require(inputFrames > 0 && outputFrames > 0)
        val input = FloatArray(4096 * 2)
        fun read(first: Int, count: Int) {
            checkCancelled(); require(count in 1..4096 && first >= 0 && first.toLong() + count <= inputFrames)
            reader.read(first, count, input)
            for (i in 0 until count * 2) require(input[i].isFinite()) { "Non-finite PCM" }
        }
        if (inputFrames == outputFrames) {
            var at = 0
            while (at < inputFrames) {
                val count = minOf(4096, inputFrames - at)
                read(at, count); emit(input, count); at += count; progress(at, outputFrames)
            }
            checkCancelled(); return
        }
        require(minOf(inputFrames, outputFrames) >= 128) { "Too little audio for time stretching" }
        val window = minOf(2048, minOf(inputFrames, outputFrames) / 2).let { it - it % 2 }
        val hop = window / 2
        val search = minOf(256, hop / 2)
        val tail = FloatArray(window * 2)
        read(0, window); input.copyInto(tail, 0, 0, window * 2)
        var previousInput = 0
        var previousOutput = 0
        var next = minOf(hop, outputFrames - window)
        while (true) {
            checkCancelled()
            val advance = next - previousOutput
            emit(tail, advance)
            val overlap = window - advance
            tail.copyInto(tail, 0, advance * 2, window * 2)
            val last = next == outputFrames - window
            val expected = (next.toDouble() * (inputFrames - window) / (outputFrames - window)).roundToInt()
            val low = if (last) inputFrames - window else maxOf(previousInput + 1, expected - search).coerceAtMost(inputFrames - window)
            val high = if (last) low else maxOf(low, minOf(inputFrames - window, expected + search))
            read(low, high - low + window)
            var chosen = expected.coerceIn(low, high)
            fun score(candidate: Int): Double {
                var dot = 0.0; var a2 = 0.0; var b2 = 0.0
                var frame = 0
                while (frame < overlap) {
                    for (channel in 0..1) {
                        val a = input[(candidate - low + frame) * 2 + channel].toDouble()
                        val b = tail[frame * 2 + channel].toDouble()
                        dot += a * b; a2 += a * a; b2 += b * b
                    }
                    frame += 8
                }
                return (if (a2 * b2 < 1e-20) 0.0 else dot / sqrt(a2 * b2)) - abs(candidate - expected) * 1e-9
            }
            if (!last) {
                var best = score(chosen)
                fun consider(candidate: Int) { val value = score(candidate); if (value > best) { best = value; chosen = candidate } }
                var at = low
                while (at <= high) { consider(at); at += 4 }
                val center = chosen
                for (at in maxOf(low, center - 3)..minOf(high, center + 3)) consider(at)
            }
            for (i in 0 until window) {
                val weight = if (i < overlap) (i + 1).toFloat() / (overlap + 1) else 1f
                for (channel in 0..1) {
                    val at = i * 2 + channel
                    tail[at] = tail[at] * (1f - weight) + input[(chosen - low + i) * 2 + channel] * weight
                }
            }
            previousInput = chosen; previousOutput = next
            progress(next, outputFrames)
            if (last) { emit(tail, window); break }
            next = minOf(next + hop, outputFrames - window)
        }
        checkCancelled(); progress(outputFrames, outputFrames)
    }
}
