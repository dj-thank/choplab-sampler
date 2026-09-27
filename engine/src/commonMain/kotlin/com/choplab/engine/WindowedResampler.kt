package com.choplab.engine

import kotlin.math.floor

/** Worker-only windows using the same 512-tap kernel and absolute clock as [OfflineResampler]. */
class WindowedResampler(val inputRate: Int, val sourceFrames: Int, val outputRate: Int = EngineFormat.SAMPLE_RATE) {
    val outputFrames: Int
    private val table: SincTable?
    init {
        require(inputRate in 8_000..192_000 && outputRate in 8_000..192_000 && sourceFrames > 0)
        val frames = (sourceFrames.toLong() * outputRate + inputRate - 1) / inputRate
        require(frames <= Int.MAX_VALUE)
        outputFrames = frames.toInt()
        val nyquist = minOf(inputRate, outputRate) / 2.0
        val pass = minOf(20_000.0, nyquist * .90)
        table = if (inputRate == outputRate) null else SincTable(512, 4096, (pass + nyquist) / (2 * inputRate), 14.0)
    }
    fun inputStart(outputStart: Int): Int {
        require(outputStart in 0 until outputFrames)
        return (floor(outputStart.toDouble() * inputRate / outputRate).toLong() - if (table == null) 0 else 255)
            .coerceAtLeast(0).toInt()
    }
    fun inputEnd(outputEnd: Int): Int {
        require(outputEnd in 1..outputFrames)
        return (floor((outputEnd - 1).toDouble() * inputRate / outputRate).toLong() + if (table == null) 1 else 257)
            .coerceAtMost(sourceFrames.toLong()).toInt()
    }
    /** [input] is stereo over exactly [inputStart(outputStart), inputEnd(outputStart + frameCount)). */
    fun render(input: FloatArray, outputStart: Int, frameCount: Int): FloatArray {
        require(frameCount > 0 && outputStart >= 0 && outputStart.toLong() + frameCount <= outputFrames)
        val first = inputStart(outputStart)
        val last = inputEnd(outputStart + frameCount)
        require(input.size == (last - first) * 2 && input.all { it.isFinite() })
        if (table == null) return input.copyOf()
        val window = PcmAsset.fromInterleaved(input)
        val output = FloatArray(frameCount * 2)
        for (frame in 0 until frameCount) {
            // Subtract only the integer window origin. Fractional phases retain the full-file oracle's clock.
            val position = (outputStart.toLong() + frame).toDouble() * inputRate / outputRate - first
            output[frame * 2] = table.read(window, position, 0, 0, window.frameCount, false).toFloat()
            output[frame * 2 + 1] = table.read(window, position, 1, 0, window.frameCount, false).toFloat()
        }
        return output
    }
}
