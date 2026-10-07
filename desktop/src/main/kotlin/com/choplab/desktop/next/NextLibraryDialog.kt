@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.choplab.desktop.next

import com.choplab.desktop.isMacOsHost
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.choplab.sampler.source.AudioLibraryItem
import com.choplab.sampler.source.LocalAudioLibrary
import kotlinx.coroutines.suspendCancellableCoroutine
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.getString
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.coroutines.resume

/** Native file-selection surface. Worker-owned validation/import never runs on the event thread. */
internal object NextLibraryDialog {
    suspend fun choose(parent: Window?, directory: Path, validate: (java.io.File) -> Unit,
                       onRendered: () -> Unit = {}): NextLibrary.Selection? {
        val labels = mapOf(
            "bundle_limit" to getString(Res.string.ce_library_bundle_limit),
            "title" to getString(Res.string.ce_library_title),
            "items" to getString(Res.string.ce_library_items),
            "filter_hint" to getString(Res.string.ce_library_filter_hint),
            "add" to getString(Res.string.ce_library_add),
            "export" to getString(Res.string.ce_library_export),
            "use" to getString(Res.string.ce_library_use),
            "cancel" to getString(Res.string.ce_library_cancel),
            "close" to getString(Res.string.ce_library_close),
            "ready" to getString(Res.string.ce_library_ready),
            "selecting" to getString(Res.string.ce_library_selecting),
            "selected" to getString(Res.string.ce_library_selected),
            "loading" to getString(Res.string.ce_library_loading),
            "importing" to getString(Res.string.ce_library_importing),
            "added" to getString(Res.string.ce_library_added),
            "partial" to getString(Res.string.ce_library_partial),
            "failed" to getString(Res.string.ce_library_failed),
            "cancelled" to getString(Res.string.ce_library_cancelled),
            "exporting" to getString(Res.string.ce_library_exporting),
            "exported" to getString(Res.string.ce_library_exported),
            "add_title" to getString(Res.string.ce_library_add_title),
            "limit" to getString(Res.string.ce_library_limit),
            "save_title" to getString(Res.string.ce_library_save_title),
            "overwrite" to getString(Res.string.ce_library_overwrite),
            "filter" to getString(Res.string.ce_library_filter))
        fun label(key: String, vararg args: Any) = String.format(Locale.getDefault(), labels.getValue(key), *args)
        return suspendCancellableCoroutine { answer ->
            SwingUtilities.invokeLater {
                if (!answer.isActive) return@invokeLater
                val dialog = JDialog(parent, label("title"), Dialog.ModalityType.DOCUMENT_MODAL)
                val library = NextLibrary(directory, validate)
                val content = androidx.compose.ui.awt.ComposePanel().apply {
                    name = "next-library-browser"
                    setContent {
                        val state by library.state.collectAsState()
                        androidx.compose.runtime.SideEffect(onRendered)
                        com.choplab.sampler.ui.theme.ChopLabTheme {
                            com.choplab.sampler.ui.LibraryBrowserPanel(state.items, !state.busy, { library.select(it) })
                        }
                    }
                }
                val status = JLabel(" ")
                fun button(label: String, id: String) = JButton(label).apply {
                    name = id; preferredSize = Dimension(preferredSize.width.coerceAtLeast(110), 48)
                }
                val add = button(label("add"), "next-library-add")
                val export = button(label("export"), "next-library-export")
                val cancel = button(label("cancel"), "next-library-cancel")
                val close = button(label("close"), "next-library-close")
                var selected: (NextLibrary.Selection) -> Unit = {}
                fun refresh() {
                    val state = library.state.value
                    add.isEnabled = !state.busy; export.isEnabled = !state.busy && state.items.isNotEmpty()
                    cancel.isEnabled = state.busy
                    status.text = when (state.status) {
                        NextLibrary.Status.BUNDLE_LIMIT -> label("bundle_limit")
                        NextLibrary.Status.READY -> label("ready")
                        NextLibrary.Status.SELECTING -> label("selecting")
                        NextLibrary.Status.SELECTED -> label("selected")
                        NextLibrary.Status.LOADING -> label("loading")
                        NextLibrary.Status.IMPORTING -> label("importing", state.position, state.total)
                        NextLibrary.Status.ADDED -> label("added", state.completed)
                        NextLibrary.Status.PARTLY_ADDED -> label("partial", state.completed, state.failed)
                        NextLibrary.Status.FAILED -> label("failed")
                        NextLibrary.Status.CANCELLED -> label("cancelled")
                        NextLibrary.Status.EXPORTING -> label("exporting")
                        NextLibrary.Status.EXPORTED -> label("exported")
                    }
                    if (!state.busy) state.selection?.let(selected)
                }
                val timer = Timer(100) { refresh() }
                var finished = false
                fun finish(selection: NextLibrary.Selection?) {
                    if (finished) return
                    finished = true; timer.stop(); library.close(); content.dispose(); dialog.dispose()
                    if (answer.isActive) answer.resume(selection)
                }
                selected = { finish(it) }
                add.addActionListener {
                    val files = pick(dialog, false, label("add_title"))
                    if (files.size > 128) JOptionPane.showMessageDialog(dialog,
                        label("limit"))
                    else if (files.isNotEmpty()) { library.add(files); refresh() }
                }
                export.addActionListener {
                    pick(dialog, true, label("save_title")).firstOrNull()?.let { path ->
                        val target = if (path.fileName.toString().endsWith(".choplib", true)) path else path.resolveSibling(path.fileName.toString() + ".choplib")
                        if (!Files.exists(target) || JOptionPane.showConfirmDialog(dialog,
                                label("overwrite"), dialog.title,
                                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) library.export(target)
                        refresh()
                    }
                }
                cancel.addActionListener { library.cancel(); refresh() }
                close.addActionListener { finish(null) }
                dialog.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                dialog.addWindowListener(object : WindowAdapter() { override fun windowClosing(e: WindowEvent) { finish(null) } })
                answer.invokeOnCancellation { SwingUtilities.invokeLater { finish(null) } }
                dialog.contentPane = JPanel(BorderLayout(8, 8)).apply {
                    border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
                    add(content, BorderLayout.CENTER)
                    add(JPanel(BorderLayout(8, 8)).apply {
                        add(status, BorderLayout.NORTH)
                        add(JPanel(GridLayout(2, 3, 8, 8)).apply { listOf(add, export, cancel, close).forEach { add(it) } }, BorderLayout.CENTER)
                    }, BorderLayout.SOUTH)
                }
                dialog.minimumSize = Dimension(680, 540); dialog.setSize(900, 700); dialog.setLocationRelativeTo(parent)
                refresh(); timer.start(); dialog.isVisible = true
            }
        }

    }


    private fun pick(parent: Dialog, save: Boolean, title: String): List<Path> {
        val extensions = if (save) setOf("choplib") else LocalAudioLibrary.extensions + setOf("choplib", "zip")
        if (isMacOsHost()) {
            val chooser = FileDialog(parent, title, if (save) FileDialog.SAVE else FileDialog.LOAD)
            try {
                chooser.isMultipleMode = !save
                chooser.filenameFilter = java.io.FilenameFilter { _, name -> extensions.any { name.endsWith(".$it", true) } }
                chooser.isVisible = true
                return chooser.files.map { it.toPath() }.ifEmpty {
                    listOfNotNull(chooser.file?.let { Path.of(chooser.directory, it) })
                }
            } finally { chooser.dispose() }
        }
        val chooser = JFileChooser().apply {
            dialogTitle = title; isMultiSelectionEnabled = !save
            fileFilter = FileNameExtensionFilter(extensions.joinToString(", "), *extensions.toTypedArray())
            isAcceptAllFileFilterUsed = false
        }
        val result = if (save) chooser.showSaveDialog(parent) else chooser.showOpenDialog(parent)
        return if (result != JFileChooser.APPROVE_OPTION) emptyList()
            else chooser.selectedFiles.toList().ifEmpty { listOfNotNull(chooser.selectedFile) }.map { it.toPath() }
    }
}
