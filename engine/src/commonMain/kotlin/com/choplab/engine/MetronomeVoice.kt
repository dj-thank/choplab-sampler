package com.choplab.engine

import kotlin.math.PI
import kotlin.math.sin

/** Constructed off render. One bounded monitor voice; no oscillators or allocations on the callback. */
internal class MetronomeVoice {
    private val accent = waveform(1_500.0, .24)
    private val beat = waveform(1_000.0, .17)
    private var position = FRAMES
    private var accented = false
    val active: Boolean get() = position < FRAMES

    fun strike(accent: Boolean) { accented = accent; position = 0 }
    fun stop() { position = FRAMES }
    fun next(): Float = if (position < FRAMES) (if (accented) accent else beat)[position++] else 0f

    private fun waveform(hz: Double, level: Double) = FloatArray(FRAMES) { index ->
        val attack = minOf(1.0, index / 24.0)
        val decay = 1.0 - index.toDouble() / FRAMES
        (sin(index * 2 * PI * hz / EngineFormat.SAMPLE_RATE) * level * attack * decay * decay).toFloat()
    }

    private companion object { const val FRAMES = 960 }
}
