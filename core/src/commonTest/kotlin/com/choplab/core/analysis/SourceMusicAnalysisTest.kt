package com.choplab.core.analysis

import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class SourceMusicAnalysisTest {
    @Test fun periodicAttacksKeepTheKnownTempoAmongHalfAndDoubleCandidates() {
        for (bpm in listOf(40.0, 97.125, 123.0, 180.0, 240.0)) {
            val result = analyse(12) { frame ->
                val phase = (frame % (48_000 * 60 / bpm)).toInt()
                val kick = if (phase < 4_800) sin(phase * 2 * PI * 78 / 48_000) * exp(-phase / 650.0) * .7 else 0.0
                kick.toFloat()
            }
            assertTrue(result.tempos.any { abs(it.milliBpm / 1000.0 - bpm) < 1.0 }, "$bpm -> ${result.tempos}")
            assertTrue(result.tempos.size <= 3)
            assertTrue(result.keys.isEmpty(), "One drum fundamental does not establish a key: $bpm -> ${result.keys}")
        }
    }

    @Test fun tonalChordsHaveTransposableCandidatesWithoutCancellingOppositeChannels() {
        for ((root, minor) in listOf(0 to false, 3 to false, 9 to true)) {
            val notes = listOf(60 + root, 60 + root + if (minor) 3 else 4, 67 + root)
            val result = analyse(6) { frame ->
                notes.withIndex().sumOf { (index, note) -> sin(frame * 2 * PI * 440 * 2.0.pow((note - 69) / 12.0) / 48_000) * if (index == 0) .2 else .13 }.toFloat()
            }
            assertTrue(result.keys.any { it.tonic == root && it.mode == if (minor) KeyMode.MINOR else KeyMode.MAJOR }, "$root $minor -> ${result.keys}")
        }
    }

    @Test fun silenceNoiseShortAudioAndSustainedSingleNotesDoNotInventUsefulCandidates() {
        assertEquals(emptyList(), analyse(6) { 0f }.tempos)
        assertEquals(emptyList(), analyse(6) { 0f }.keys)
        val short = analyse(2) { sin(it * .1).toFloat() }
        assertTrue(short.keys.isEmpty() && short.tempos.isEmpty())
        val random = Random(4891)
        val noise = analyse(8) { (random.nextFloat() - .5f) * .6f }
        assertTrue(noise.keys.isEmpty() && noise.tempos.isEmpty(), "Noise: $noise")
        for (hz in listOf(55.0, 440.0)) {
            val tone = analyse(6) { (.3 * sin(it * 2 * PI * hz / 48_000)).toFloat() }
            assertTrue(tone.keys.isEmpty() && tone.tempos.isEmpty(), "Single $hz Hz: $tone")
        }
    }

    @Test fun windowingDoesNotChangeResultsAndCancellationAndInputBoundsAreEnforced() {
        val audio = FloatArray(48_000 * 6 * 2) { i ->
            val frame = i / 2; val phase = frame % 24_000
            (if (phase < 2_400) sin(phase * .03) * exp(-phase / 400.0) else 0.0).toFloat()
        }
        fun result(chunk: Int): SourceMusicResult {
            val analysis = SourceMusicAnalysis()
            for (first in audio.indices step chunk) analysis.accept(audio.copyOfRange(first, min(audio.size, first + chunk)))
            return analysis.finish()
        }
        assertEquals(result(8_192), result(962))
        assertFailsWith<IllegalArgumentException> { SourceMusicAnalysis().accept(floatArrayOf(0f)) }
        assertFailsWith<IllegalArgumentException> { SourceMusicAnalysis().accept(floatArrayOf(Float.NaN, 0f)) }
        val cancelled = SourceMusicAnalysis()
        assertFailsWith<IllegalStateException> { cancelled.accept(audio) { error("Cancelled") } }
        val full = SourceMusicAnalysis()
        repeat(SourceMusicAnalysis.MAX_FRAMES / 4_800) { full.accept(FloatArray(9_600)) }
        assertFailsWith<IllegalArgumentException> { full.accept(floatArrayOf(0f, 0f)) }
        assertEquals(SourceMusicAnalysis.MAX_FRAMES, full.finish().frames)
        assertFailsWith<IllegalStateException> { full.finish() }
    }

    private fun analyse(seconds: Int, sample: (Int) -> Float): SourceMusicResult {
        val analysis = SourceMusicAnalysis()
        for (first in 0 until seconds * 48_000 step 4_800) {
            analysis.accept(FloatArray(9_600) { i -> sample(first + i / 2) * if (i % 2 == 0) 1f else -1f })
        }
        return analysis.finish()
    }
}
