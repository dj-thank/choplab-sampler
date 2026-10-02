package com.choplab.core.chop

import com.choplab.core.edit.*
import com.choplab.core.model.*
import kotlin.test.*

class AutoChopTest {
    private val settings = AutoChopSettings(AutoChopMode.ATTACK, slices = 16, thresholdDb = -36, minimumGapMs = 50)
    private fun detect(audio: FloatArray, settings: AutoChopSettings = this.settings, window: Int = 4096): AutoChopResult {
        val detector = AttackChopDetector(0, audio.size / 2L, settings)
        var first = 0
        while (first < audio.size) {
            val end = minOf(audio.size, first + window * 2)
            detector.accept(audio.copyOfRange(first, end)); first = end
        }
        return detector.finish()
    }
    private fun pulses(frames: Int, starts: List<Int>, gain: Float = .5f): FloatArray = FloatArray(frames * 2).also { audio ->
        starts.forEach { start -> repeat(240) { n ->
            if (start + n < frames) {
                val value = gain * (1f - n / 240f)
                audio[(start + n) * 2] = value
                audio[(start + n) * 2 + 1] = -value // anti-phase still has an attack
            }
        } }
    }

    @Test fun nativeEqualCutsKeepBothEndsAndApplyOnlyToTheReviewedSourceAsOneUndo() {
        val asset = Asset("a".repeat(64), "wav", 100, 96_000, 2, 1_000, "source")
        val source = Source(asset.hash, FrameRange(13, 991), frozenListOf(20, 40))
        val project = Project(assets = frozenListOf(asset), source = source)
        val markers = assertIs<AutoChopResult.Ready>(AutoChop.equal(source.range, 128)).markers
        assertEquals(127, markers.size)
        val session = EditSession(project)
        val plan = session.plan(Intent.ApplyAutoChop(source, markers))
        plan.effects.indices.forEach { session.acknowledge(plan, it) }; session.commit(plan)
        assertEquals(13, session.project.source!!.slices().first().start)
        assertEquals(991, session.project.source!!.slices().last().end)
        assertTrue(session.project.source!!.slices().all { it.length > 0 })
        assertEquals(project.assets, session.project.assets)
        val undo = session.planUndo()!!
        undo.effects.indices.forEach { session.acknowledge(undo, it) }; session.commit(undo)
        assertEquals(project, session.project)
        assertFailsWith<IllegalArgumentException> { Reducer.reduce(project.copy(source = source.copy(range = FrameRange(12, 991))), Intent.ApplyAutoChop(source, markers)) }
        assertEquals(AutoChopResult.Refused(AutoChopProblem.TOO_SHORT), AutoChop.equal(FrameRange(7, 10), 4))
    }

    @Test fun energyAttacksKeepStereoPolarityAndAreIndependentOfWorkerWindowBoundaries() {
        val expected = listOf(4_800L, 12_003L, 24_010L, 36_024L)
        val audio = pulses(48_000, expected.map(Long::toInt), gain = 1.5f)
        for (window in listOf(1, 17, 4096)) assertEquals(expected, assertIs<AutoChopResult.Ready>(detect(audio, window = window)).markers)
        assertEquals(AutoChopResult.Refused(AutoChopProblem.NO_ATTACKS), detect(pulses(48_000, listOf(12_000), gain = .0001f)))
        assertEquals(listOf(12_000L), assertIs<AutoChopResult.Ready>(detect(pulses(48_000, listOf(12_000, 12_480)))).markers)
    }

    @Test fun minimumGapEndpointsKeepCompleteCutsAndOneSliceNeverKeepsAPartialAttackProposal() {
        val starts = listOf(480, 24_000, 48_000, 72_000)
        val audio = pulses(96_000, starts)
        for ((gap, expected) in listOf(10 to starts, 500 to starts.drop(1))) {
            val result = assertIs<AutoChopResult.Ready>(detect(audio, settings.copy(slices = 128, minimumGapMs = gap)))
            assertEquals(expected.map(Int::toLong), result.markers)
            val boundaries = listOf(0L) + result.markers + 96_000L
            assertTrue(boundaries.zipWithNext().all { (start, end) -> end - start >= gap * 48L })
        }
        assertTrue(assertIs<AutoChopResult.Ready>(AutoChop.equal(FrameRange(13, 991), 1)).markers.isEmpty())
        assertEquals(AutoChopResult.Refused(AutoChopProblem.TOO_DENSE), detect(audio, settings.copy(slices = 1, minimumGapMs = 10)))
    }

    @Test fun silenceSustainedSoundTooManyAttacksTinyRangesAndInvalidAudioNeverBecomePartialCuts() {
        assertEquals(AutoChopResult.Refused(AutoChopProblem.NO_ATTACKS), detect(FloatArray(48_000 * 2)))
        assertEquals(AutoChopResult.Refused(AutoChopProblem.NO_ATTACKS), detect(FloatArray(48_000 * 2) { .4f }))
        assertEquals(AutoChopResult.Refused(AutoChopProblem.TOO_DENSE), detect(pulses(48_000, listOf(4_800, 12_000, 24_000)), settings.copy(slices = 3)))
        assertEquals(AutoChopResult.Refused(AutoChopProblem.TOO_SHORT), detect(FloatArray(96)))
        val bad = pulses(48_000, listOf(12_000)); bad[60_000] = Float.NaN
        assertEquals(AutoChopResult.Refused(AutoChopProblem.INVALID_AUDIO), detect(bad))
    }
}
