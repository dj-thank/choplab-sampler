package com.choplab.desktop.next

import com.choplab.desktop.source.DesktopYoutubeBackend
import com.choplab.sampler.source.YoutubeSource
import com.choplab.sampler.source.YoutubeSourceBackend
import com.choplab.sampler.source.OnlineSourcePhase
import com.choplab.ui.resources.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jetbrains.compose.resources.getString
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Path
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import kotlin.coroutines.resume

/** An owned native chooser. A lookup never downloads or replaces the current original. */
internal object NextOnlineDialog {
    suspend fun choose(parent: Window?, directory: Path, validate: (java.io.File) -> Unit,
                       backend: YoutubeSourceBackend = DesktopYoutubeBackend(originalAudio = true)): NextLibrary.Selection? {
        val labels = listOf(
            Res.string.ce_online, Res.string.ce_online_query, Res.string.ce_online_search,
            Res.string.ce_online_acquire, Res.string.ce_library_cancel, Res.string.ce_library_close,
            Res.string.ce_online_ready, Res.string.ce_online_searching, Res.string.ce_online_candidates,
            Res.string.ce_online_empty, Res.string.ce_online_downloading, Res.string.ce_online_failed,
            Res.string.ce_library_cancelled, Res.string.online_saved, Res.string.online_use, Res.string.online_save,
            Res.string.online_save_only
        ).map { getString(it) }
        return suspendCancellableCoroutine { answer ->
            SwingUtilities.invokeLater {
                if (!answer.isActive) return@invokeLater
                val online = NextOnline(directory, validate, backend)
                val dialog = JDialog(parent, labels[0], Dialog.ModalityType.DOCUMENT_MODAL)
                val query = JTextField().apply { name = "next-online-query"; accessibleContext.accessibleName = labels[1] }
                val model = DefaultListModel<YoutubeSource>()
                val list = JList(model).apply {
                    name = "next-online-candidates"; fixedCellHeight = 56
                    selectionMode = ListSelectionModel.SINGLE_SELECTION
                    accessibleContext.accessibleName = labels[8]
                    cellRenderer = object : DefaultListCellRenderer() {
                        override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, selected: Boolean, focus: Boolean): Component {
                            val source = value as YoutubeSource
                            return super.getListCellRendererComponent(list,
                                "${index + 1}. ${source.title} — ${source.author}", index, selected, focus)
                        }
                    }
                }
                fun button(index: Int, id: String) = JButton(labels[index]).apply {
                    name = id; preferredSize = Dimension(preferredSize.width.coerceAtLeast(130), 48)
                }
                val search = button(2, "next-online-search")
                val acquire = button(15, "next-online-acquire")
                val useOriginal = button(14, "next-online-use")
                val cancel = button(4, "next-online-cancel")
                val close = button(5, "next-online-close")
                val status = JLabel(labels[6])
                var shown = emptyList<YoutubeSource>()
                var searched = ""
                var finished = false
                var finishing: (NextLibrary.Selection?) -> Unit = {}
                fun refresh() {
                    val state = online.state.value
                    if (shown != state.candidates) {
                        shown = state.candidates
                        model.clear(); shown.forEach(model::addElement)
                    }
                    search.isEnabled = !state.busy && query.text.trim().length in 1..240
                    acquire.isEnabled = !state.busy && list.selectedValue != null && query.text.trim() == searched
                    cancel.isEnabled = state.busy
                    useOriginal.isEnabled = !state.busy && state.saved != null && query.text.trim() == searched
                    status.text = when (state.phase) {
                        OnlineSourcePhase.READY -> labels[6]
                        OnlineSourcePhase.SEARCHING, OnlineSourcePhase.INSPECTING -> labels[7]
                        OnlineSourcePhase.CANDIDATES, OnlineSourcePhase.DETAILS -> if (state.candidates.isEmpty()) labels[9] else labels[8]
                        OnlineSourcePhase.DOWNLOADING, OnlineSourcePhase.SAVING -> "${labels[10]} ${state.progress}%"
                        OnlineSourcePhase.SAVED -> labels[13]
                        OnlineSourcePhase.FAILED -> labels[11]
                        OnlineSourcePhase.CANCELLED, OnlineSourcePhase.CLOSED -> labels[12]
                    }
                }
                val timer = Timer(100) { refresh() }
                finishing = { selection ->
                    if (!finished) {
                        finished = true; timer.stop(); online.close(); dialog.dispose()
                        if (answer.isActive) answer.resume(selection)
                    }
                }
                fun lookup() {
                    val input = query.text.trim()
                    if (input.length in 1..240 && online.search(input)) searched = input
                    refresh()
                }
                search.addActionListener { lookup() }; query.addActionListener { lookup() }
                acquire.addActionListener { list.selectedValue?.let { online.acquire(it.id); refresh() } }
                useOriginal.addActionListener {
                    if (useOriginal.isEnabled) online.state.value.saved?.let { finishing(NextLibrary.Selection(it.path, it.title, it.hash)) }
                }
                cancel.addActionListener { online.cancel(); refresh() }
                close.addActionListener { finishing(null) }
                list.addListSelectionListener { if (!it.valueIsAdjusting) refresh() }
                query.document.addDocumentListener(object : DocumentListener {
                    override fun insertUpdate(e: DocumentEvent) = refresh()
                    override fun removeUpdate(e: DocumentEvent) = refresh()
                    override fun changedUpdate(e: DocumentEvent) = refresh()
                })
                dialog.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                dialog.addWindowListener(object : WindowAdapter() { override fun windowClosing(e: WindowEvent) { finishing(null) } })
                answer.invokeOnCancellation { SwingUtilities.invokeLater { finishing(null) } }
                dialog.contentPane = JPanel(BorderLayout(8, 8)).apply {
                    border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
                    add(JPanel(BorderLayout(8, 8)).apply {
                        add(JLabel(labels[1]), BorderLayout.NORTH); add(query, BorderLayout.CENTER); add(search, BorderLayout.EAST)
                    }, BorderLayout.NORTH)
                    add(JScrollPane(list), BorderLayout.CENTER)
                    add(JPanel(BorderLayout(8, 8)).apply {
                        add(JPanel(GridLayout(2, 1, 8, 8)).apply { add(status); add(JLabel(labels[16])) }, BorderLayout.NORTH)
                        add(JPanel(GridLayout(1, 4, 8, 8)).apply { listOf(acquire, useOriginal, cancel, close).forEach { add(it) } }, BorderLayout.SOUTH)
                    }, BorderLayout.SOUTH)
                }
                dialog.minimumSize = Dimension(720, 440); dialog.setSize(840, 580); dialog.setLocationRelativeTo(parent)
                refresh(); timer.start(); dialog.isVisible = true
            }
        }
    }
}
