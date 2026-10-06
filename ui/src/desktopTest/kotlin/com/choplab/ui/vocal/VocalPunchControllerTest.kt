package com.choplab.ui.vocal

import com.choplab.core.DocumentState
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class VocalPunchControllerTest {
    @Test fun replacingTheCompositionWaiterKeepsOwnershipUntilRecordingAndSaveActuallyFinish() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val progress = MutableStateFlow(VocalPunchProgress())
        var calls = 0; var stops = 0
        val asset = Asset("a".repeat(64), "wav", 44 + 480 * 4, 48_000, 1, 480, "Voice", AssetRole.ORIGINAL)
        val controller = VocalPunchController(MutableStateFlow(DocumentState(Project(), 0)), progress, object : VocalPunchActions {
            override suspend fun record(request: VocalPunchRequest, expectedRevision: Long): PunchCompletion {
                calls++; entered.complete(Unit); finish.await()
                return PunchCompletion(VocalPunchResult(VocalCapturedSession(asset, listOf(VocalCapturedPass(FrameRange(0,480),0)))), true)
            }
            override fun stop() { stops++ }
        }, this, 0, 48000)
        try {
            val oldComposition = async { controller.record() }
            entered.await(); oldComposition.cancelAndJoin()
            assertTrue(controller.state.value.busy)
            assertFalse(controller.record()); assertEquals(1, calls)
            controller.requestClose()
            assertTrue(controller.state.value.closing); assertFalse(controller.state.value.closed); assertEquals(1, stops)
            finish.complete(Unit); controller.closeAndJoin()
            assertTrue(controller.state.value.closed); assertEquals(1, controller.state.value.saved)
            assertFalse(controller.state.value.busy); assertNull(controller.state.value.problem)
        } finally { finish.complete(Unit); controller.closeAndJoin() }
    }
}
