package com.choplab.ui.onboarding

import kotlinx.coroutines.*
import kotlin.test.*

class QuickStartControllerTest {
    @Test fun firstEmptyProfileShowsOnceAndManualHelpStillOpensOnExistingProjects() = runBlocking {
        var seen = false
        for (auto in listOf(true, true, false)) {
            val controller = QuickStartController(this, auto, { seen }, { seen = true })
            try {
                await { !controller.state.value.loading }
                assertEquals(auto && !seen, controller.state.value.open)
                controller.open(); assertTrue(controller.state.value.open)
                controller.dismiss(); assertFalse(controller.state.value.open)
                await { seen }
            } finally { controller.close() }
        }
    }
    @Test fun lateReadCannotReopenDismissedGuideOrHideManuallyOpenedHelp() = runBlocking {
        for (dismiss in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>(); val result = CompletableDeferred<Boolean>()
            val controller = QuickStartController(this, true, { entered.complete(Unit); result.await() }, {})
            try {
                entered.await()
                if (dismiss) controller.dismiss() else controller.open()
                result.complete(!dismiss)
                await { !controller.state.value.loading }
                assertEquals(!dismiss, controller.state.value.open)
            } finally { result.complete(true); controller.close() }
        }
    }
    @Test fun readAndSaveFailuresNeverTrapTheGuideAndSuccessfulRetryClearsTheNotice() = runBlocking {
        var failSave = true; var writes = 0
        val controller = QuickStartController(this, true, { error("Unavailable profile") }, {
            writes++; check(!failSave) { "Read-only profile" }
        })
        try {
            await { !controller.state.value.loading }
            assertTrue(controller.state.value.open); assertEquals(QuickStartProblem.READ_SETTINGS, controller.state.value.problem)
            controller.dismiss(); await { controller.state.value.problem == QuickStartProblem.SAVE_SETTINGS }
            assertFalse(controller.state.value.open)
            failSave = false; controller.open(); controller.dismiss()
            await { controller.state.value.problem == null }
            assertEquals(2, writes)
        } finally { controller.close() }
    }
    @Test fun closeFencesANonCooperativeLateReadAndDoesNotWritePreferences() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val result = CompletableDeferred<Boolean>(); val returned = CompletableDeferred<Unit>()
        var writes = 0
        val controller = QuickStartController(this, true, {
            withContext(NonCancellable) { entered.complete(Unit); result.await().also { returned.complete(Unit) } }
        }, { writes++ })
        entered.await(); controller.close(); val closed = controller.state.value
        result.complete(false); returned.await(); yield()
        assertEquals(closed, controller.state.value); assertEquals(0, writes)
    }
    private suspend fun await(condition: () -> Boolean) { withTimeout(3_000) { while (!condition()) delay(5) } }
}
