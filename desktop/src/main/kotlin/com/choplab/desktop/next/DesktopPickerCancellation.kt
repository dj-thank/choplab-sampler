package com.choplab.desktop.next

import kotlinx.coroutines.CancellableContinuation
import javax.swing.SwingUtilities

/** One continuation owns the file picker and its subsequent overwrite dialog, one at a time. */
internal class DesktopPickerCancellation(answer: CancellableContinuation<*>) {
    // Both dialog hand-off and disposal run on the event thread, including modal nested event loops.
    private var cancelCurrent: (() -> Unit)? = null

    init {
        answer.invokeOnCancellation {
            SwingUtilities.invokeLater {
                val cancel = cancelCurrent
                cancelCurrent = null
                cancel?.invoke()
            }
        }
    }

    fun own(cancel: () -> Unit) {
        check(SwingUtilities.isEventDispatchThread())
        cancelCurrent = cancel
    }

    fun clear() {
        check(SwingUtilities.isEventDispatchThread())
        cancelCurrent = null
    }
}
