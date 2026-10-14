package com.choplab.desktop.next

import kotlinx.coroutines.*
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlin.test.*

class DesktopPickerCancellationTest {
    @Test fun cancellingOverwriteClosesOnlyTheCurrentDialog() = runBlocking<Unit> {
        val ready = CompletableDeferred<Unit>()
        var pickerClosed = 0
        var overwriteClosed = 0
        val choosing = launch {
            suspendCancellableCoroutine<Unit> { answer ->
                val cancellation = DesktopPickerCancellation(answer)
                SwingUtilities.invokeLater {
                    cancellation.own { pickerClosed++ }
                    cancellation.clear()
                    cancellation.own { overwriteClosed++ }
                    ready.complete(Unit)
                }
            }
        }
        try {
            withTimeout(2000) { ready.await() }
            choosing.cancelAndJoin()
            SwingUtilities.invokeAndWait { }
            assertEquals(0, pickerClosed)
            assertEquals(1, overwriteClosed)
        } finally { choosing.cancelAndJoin() }
    }

    @Test fun cancellingPickerClosesItWithoutAnOverwriteDialog() = runBlocking<Unit> {
        val ready = CompletableDeferred<Unit>()
        var pickerClosed = 0
        val choosing = launch {
            suspendCancellableCoroutine<Unit> { answer ->
                val cancellation = DesktopPickerCancellation(answer)
                SwingUtilities.invokeLater {
                    cancellation.own { pickerClosed++ }
                    ready.complete(Unit)
                }
            }
        }
        try {
            withTimeout(2000) { ready.await() }
            choosing.cancelAndJoin()
            SwingUtilities.invokeAndWait { }
            assertEquals(1, pickerClosed)
        } finally { choosing.cancelAndJoin() }
    }

    @Test fun overwriteHandOffCanResumeTheSameContinuationNormally() = runBlocking<Unit> {
        var cancelled = false
        val result = withTimeout(2000) {
            suspendCancellableCoroutine<Boolean> { answer ->
                val cancellation = DesktopPickerCancellation(answer)
                SwingUtilities.invokeLater {
                    cancellation.own { cancelled = true }
                    cancellation.clear()
                    cancellation.own { cancelled = true }
                    cancellation.clear()
                    answer.resume(true)
                }
            }
        }
        SwingUtilities.invokeAndWait { }
        assertTrue(result)
        assertFalse(cancelled)
    }
}
