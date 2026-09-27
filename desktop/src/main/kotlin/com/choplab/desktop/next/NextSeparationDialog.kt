package com.choplab.desktop.next

import com.choplab.ui.resources.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.resources.getString
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import javax.swing.*
import kotlin.coroutines.resume

internal object NextSeparationDialog {
    suspend fun choose(parent: Window?, create: (String) -> NextSeparation): NextLibrary.Selection? {
        val labels = listOf(Res.string.ce_separate, Res.string.ce_separate_ready, Res.string.ce_separate_running,
            Res.string.ce_separate_done, Res.string.ce_separate_failed, Res.string.ce_separate_use,
            Res.string.ce_library_cancel, Res.string.ce_library_close, Res.string.ce_library_cancelled,
            Res.string.ce_separate_name).map { getString(it) }
        return suspendCancellableCoroutine { answer -> SwingUtilities.invokeLater {
            if (!answer.isActive) return@invokeLater
            val job = create(labels[9])
            val dialog = JDialog(parent, labels[0], Dialog.ModalityType.DOCUMENT_MODAL)
            val status = JTextArea(labels[1]).apply { isEditable = false; lineWrap = true; wrapStyleWord = true; background = dialog.background }
            val progress = JProgressBar(0, 100)
            fun button(label: String, id: String) = JButton(label).apply { name = id; preferredSize = Dimension(240, 48) }
            val start = button(labels[0], "next-separation-start")
            val use = button(labels[5], "next-separation-use")
            val cancel = button(labels[6], "next-separation-cancel")
            val close = button(labels[7], "next-separation-close")
            var finished = false
            fun refresh() {
                val state = job.state.value
                start.isEnabled = !state.busy
                use.isEnabled = !state.busy && state.result != null
                cancel.isEnabled = state.busy
                progress.value = state.progress
                status.text = when (state.status) {
                    NextSeparation.Status.READY -> labels[1]
                    NextSeparation.Status.RUNNING -> "${labels[2]} ${state.progress}%"
                    NextSeparation.Status.DONE -> labels[3]
                    NextSeparation.Status.FAILED -> labels[4]
                    NextSeparation.Status.CANCELLED -> labels[8]
                }
            }
            val timer = Timer(100) { refresh() }
            fun finish(result: NextLibrary.Selection?) {
                if (finished) return
                finished = true; timer.stop(); job.close(); dialog.dispose()
                if (answer.isActive) answer.resume(result)
            }
            start.addActionListener { job.start(); refresh() }
            cancel.addActionListener { job.cancel(); refresh() }
            use.addActionListener { if (!job.state.value.busy) job.state.value.result?.let { finish(it) } }
            close.addActionListener { finish(null) }
            dialog.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
            dialog.addWindowListener(object : WindowAdapter() { override fun windowClosing(e: WindowEvent) { finish(null) } })
            answer.invokeOnCancellation { SwingUtilities.invokeLater { finish(null) } }
            dialog.contentPane = JPanel(BorderLayout(12, 12)).apply {
                border = BorderFactory.createEmptyBorder(16, 16, 16, 16)
                add(JScrollPane(status), BorderLayout.CENTER)
                add(JPanel(BorderLayout(8, 8)).apply {
                    add(progress, BorderLayout.NORTH)
                    add(JPanel(GridLayout(2, 2, 8, 8)).apply { listOf(start, use, cancel, close).forEach { add(it) } }, BorderLayout.CENTER)
                }, BorderLayout.SOUTH)
            }
            dialog.minimumSize = Dimension(580, 300); dialog.setSize(640, 340); dialog.setLocationRelativeTo(parent)
            refresh(); timer.start(); dialog.isVisible = true
        } }
    }
}
