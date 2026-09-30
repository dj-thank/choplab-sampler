package com.choplab.core.vocal

import com.choplab.core.model.*
import com.choplab.engine.Tempo
import kotlin.test.*

class RecordingAlignmentTest {
    private val route = AudioRouteIdentity(1, 48_000, 48_000, 1024, 256, AudioClockDomain.INPUT_HARDWARE, AudioClockDomain.OUTPUT_HARDWARE)
    @Test fun manualAndMeasuredCorrectionsAreInvalidatedByEveryRouteRateBufferAndClockChange() {
        val base = RecordingAlignment.unmeasured(route)
        assertEquals(AlignmentStatus.UNMEASURED, base.status)
        val measurement = AlignmentMeasurement(2400, 24, 72.0, 120.0, 12.0, 7.5, ClockCorrelation.MEASURED_RELATION)
        val estimated = base.estimated(route, 2400)
        assertEquals(AlignmentStatus.ESTIMATED, estimated.status)
        assertNull(estimated.measurement)
        for (active in listOf(estimated, base.manual(route, -240), base.measured(route, measurement))) {
            assertNotEquals(0, active.framesFor(route))
            for (next in listOf(route.copy(generation = 2), route.copy(inputRate = 44_100), route.copy(outputRate = 44_100),
                route.copy(inputBufferFrames = 512), route.copy(outputBufferFrames = 512), route.copy(inputClock = AudioClockDomain.HOST_MONOTONIC),
                route.copy(outputClock = AudioClockDomain.ENGINE_RENDER), null)) {
                assertEquals(0, active.framesFor(next), "Identity is rechecked at capture time")
                val invalidated = active.routeChanged(next)
                assertEquals(AlignmentStatus.INVALIDATED, invalidated.status)
                assertEquals(0, invalidated.framesFor(next))
                assertNull(invalidated.measurement)
                assertEquals(0, invalidated.routeChanged(route).framesFor(route), "Returning to the old format does not revive stale calibration")
            }
        }
        assertFailsWith<IllegalArgumentException> { base.measured(route, measurement.copy(correlation = ClockCorrelation.SAME_DOMAIN)) }
        assertFailsWith<IllegalArgumentException> { base.measured(route.copy(inputBufferFrames = null), measurement) }
        assertFailsWith<IllegalArgumentException> { measurement.copy(driftPpm = Double.NaN) }
    }

    @Test fun punchKeepsBothHandlesAndReplacesOnlyTheChosenFramesWithoutShortening() {
        val a = Asset("a".repeat(64), "wav", 800044, 48_000, 2, 100000, "Original")
        val b = a.copy(hash = "b".repeat(64), name = "Punch")
        val project = Project(assets = frozenListOf(a, b), tracks = frozenListOf(Track("v", "Voice", TrackKind.VOCAL)),
            takes = frozenListOf(Take("a", "v", a.hash, FrameRange(0, a.frames), 0), Take("b", "v", b.hash, FrameRange(0, b.frames), 0)))
        val base = VocalCompDraft("comp", frozenListOf(VocalCompSegment("whole", "a", 0, 100000)))
        val plan = VocalPunchPlan.create(24000, 48000, 100000, 1, 2, Tempo())
        assertEquals(0, plan.playbackStart); assertEquals(23760, plan.captureStart); assertEquals(48240, plan.captureEnd)
        val spliced = plan.splice(project, base, "b")
        assertEquals(listOf(0L, 24000L, 48000L), spliced.segments.map { it.startFrame })
        assertEquals(listOf(24000L, 48000L, 100000L), spliced.segments.map { it.endFrame })
        assertEquals(listOf("a", "b", "a"), spliced.segments.map { it.takeId })
        val mixed = VocalCompMix.plan(project, spliced)
        assertEquals(100000, mixed.spans.sumOf { it.end - it.start })
        assertEquals(2, mixed.spans.count { it.ramp && it.from != null && it.to != null })
        val slow = VocalPunchPlan.create(48_000 * 20L, 48_000 * 21L, 48_000 * 30L, 2, 2, Tempo(40_000))
        assertEquals(26, slow.armingSeconds, "Two count-in plus two pre-roll bars at 40 BPM get an explicit bounded deadline")

        val named = VocalCompDraft("comp", frozenListOf(VocalCompSegment("punch", "a", 0, 60000),
            VocalCompSegment("after-punch", "a", 60000, 100000)))
        val repeated = plan.splice(project, named, "b")
        assertEquals(repeated.segments.size, repeated.segments.map { it.id }.distinct().size, "A repeated punch cannot collide with old line IDs")
        val short = VocalPunchPlan.create(0, 10, 100000, 2, 2, Tempo())
        assertEquals(0, short.captureStart)
        val shortMix = VocalCompMix.plan(project, short.splice(project, base, "b"))
        assertEquals(100000, shortMix.spans.sumOf { it.end - it.start }, "Clamping a short crossfade never shortens the song")
    }
}
