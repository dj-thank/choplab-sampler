package com.choplab.ui.separation

import com.choplab.core.separation.*
import kotlinx.coroutines.*
import com.choplab.ui.separation.FourStemTestFixture.Companion.waitUntil
import kotlin.test.*

class FourStemControllerTest {
    @Test fun memoryRefusalRetainsItsSourceButRetryAndHostArmingNeverDisplayAnOldReceipt() = runBlocking<Unit> {
        val f = FourStemTestFixture()
        try {
            f.failure = SeparationProblem.LOW_MEMORY
            assertTrue(f.controller.start()); waitUntil { f.controller.state.value.phase == FourStemPhase.FAILED }
            assertEquals(f.memoryReceipt, f.controller.state.value.memoryReceipt)
            assertEquals(SeparationProblem.LOW_MEMORY, f.controller.state.value.problem)
            f.guardProblem = SeparationProblem.RECORDING
            assertTrue(f.controller.start()); waitUntil { f.controller.state.value.phase == FourStemPhase.FAILED }
            assertEquals(SeparationProblem.RECORDING, f.controller.state.value.problem)
            assertEquals(1, f.calls.get()); assertNull(f.controller.state.value.memoryReceipt)
            f.guardProblem = null; f.memoryReceipt = null; f.failure = SeparationProblem.RAM_UNAVAILABLE
            assertTrue(f.controller.start()); waitUntil { f.controller.state.value.phase == FourStemPhase.FAILED }
            assertNull(f.controller.state.value.memoryReceipt)
            assertEquals(f.project, f.document.value.project); assertEquals(0, f.applies.get())
        } finally { f.close() }
    }

    @Test fun prepareIsNotAnEditAndExplicitPlacementUsesTheSelectedMixBarAndOneSnapshot() = runBlocking<Unit> {
        val f = FourStemTestFixture()
        try {
            assertTrue(f.controller.settings(StemMix.INSTRUMENTAL, 2, true))
            assertTrue(f.controller.start()); waitUntil { f.controller.state.value.phase == FourStemPhase.READY }
            assertTrue(f.downloaded); assertEquals(f.project, f.document.value.project); assertEquals(0, f.applies.get())
            assertEquals(44_100L, f.controller.state.value.progress?.completedFrames)
            assertTrue(f.controller.apply()); assertEquals(1, f.applies.get())
            assertEquals(4, f.applied!!.clips.size); assertTrue(f.applied!!.clips.all { it.startTick == 3840L })
            assertEquals(listOf(false, false, false, true), f.applied!!.tracks.map { it.mute })
            assertEquals(f.project.source, f.document.value.project.source)
            assertFalse(f.controller.apply()); assertEquals(1, f.applies.get())
        } finally { f.close(); f.close() }
        assertEquals(1, f.closes.get())
    }

    @Test fun cancelStaleRecordingAndCloseDiscardLateWorkAndKeepTheSong() = runBlocking<Unit> {
        for (reason in listOf("cancel", "stale", "recording", "close")) {
            val f = FourStemTestFixture().apply { release = CompletableDeferred() }
            try {
                assertTrue(f.controller.start()); withTimeout(5_000) { f.entered.await() }
                when (reason) {
                    "cancel" -> f.controller.cancel()
                    "stale" -> f.document.value = f.document.value.copy(revision = 9)
                    "recording" -> f.availability.value = FourStemAvailability.RECORDING
                    "close" -> f.controller.close()
                }
                waitUntil { f.controller.state.value.phase in listOf(FourStemPhase.CANCELLING, FourStemPhase.CLOSED) }
                assertFalse(f.controller.start()); assertFalse(f.controller.apply())
                f.release!!.complete(Unit); withTimeout(5_000) { f.returned.await() }
                waitUntil { f.controller.state.value.phase in listOf(FourStemPhase.FAILED, FourStemPhase.CLOSED) }
                assertNull(f.controller.state.value.prepared); assertEquals(0, f.applies.get()); assertEquals(f.project, f.document.value.project)
                assertEquals(1, f.calls.get())
            } finally { f.release!!.complete(Unit); f.close() }
            assertEquals(1, f.closes.get())
        }
    }

    @Test fun aHostArmingGuardAndAtomicApplyRefuseBeforeTheDerivedRecordingFlowCatchesUp() = runBlocking<Unit> {
        val f = FourStemTestFixture()
        try {
            f.guardProblem = SeparationProblem.RECORDING
            assertTrue(f.controller.start()); waitUntil { f.controller.state.value.phase == FourStemPhase.FAILED }
            assertEquals(SeparationProblem.RECORDING, f.controller.state.value.problem); assertEquals(0, f.calls.get())
            assertEquals(FourStemAvailability.EDITABLE, f.availability.value)
            f.guardProblem = null
            assertTrue(f.controller.start()); waitUntil { f.controller.state.value.phase == FourStemPhase.READY }
            f.liveRecording = true
            assertFalse(f.controller.apply()); assertEquals(0, f.applies.get()); assertEquals(f.project, f.document.value.project)
        } finally { f.close() }
    }

    @Test fun closeCancelsAnApplyWaitingForTheHostsSerializedGuard() = runBlocking<Unit> {
        val f = FourStemTestFixture().apply { applyRelease = CompletableDeferred() }
        try {
            assertTrue(f.controller.start()); waitUntil { f.controller.state.value.phase == FourStemPhase.READY }
            val applying = async { runCatching { f.controller.apply() } }
            withTimeout(5_000) { f.applyEntered.await() }
            f.controller.close(); f.controller.close()
            assertIs<CancellationException>(withTimeout(5_000) { applying.await() }.exceptionOrNull())
            assertEquals(0, f.applies.get()); assertEquals(f.project, f.document.value.project)
            assertEquals(FourStemPhase.CLOSED, f.controller.state.value.phase)
        } finally { f.applyRelease!!.complete(Unit); f.close() }
        assertEquals(1, f.closes.get())
    }
}
