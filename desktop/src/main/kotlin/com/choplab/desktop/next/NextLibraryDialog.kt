@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.choplab.desktop.next

import com.choplab.desktop.isMacOsHost
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.choplab.sampler.source.LocalAudioLibrary
import kotlinx.coroutines.suspendCancellableCoroutine
import com.choplab.ui.resources.*
import com.choplab.ui.resources.Res
import com.choplab.library.resources.*
import com.choplab.library.resources.Res as LibraryRes
import org.jetbrains.compose.resources.getString
import java.awt.*
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.swing.*
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.coroutines.resume

/** Native file-selection surface. Worker-owned validation/import never runs on the event thread. */
internal object NextLibraryDialog {
    suspend fun choose(parent: Window?, directory: Path, validate: (java.io.File) -> Unit,
                       onRendered: () -> Unit = {}): NextLibrary.Selection? {
        val labels = mapOf(
            "bundle_limit" to getString(Res.string.ce_library_bundle_limit),
            "title" to getString(Res.string.ce_library_title),
            "add" to getString(Res.string.ce_library_add),
            "export" to getString(Res.string.ce_library_export),
            "cancel" to getString(Res.string.ce_library_cancel),
            "close" to getString(Res.string.ce_library_close),
            "ready" to getString(Res.string.ce_library_ready),
            "selecting" to getString(Res.string.ce_library_selecting),
            "selected" to getString(Res.string.ce_library_selected),
            "loading" to getString(Res.string.ce_library_loading),
            "importing" to getString(Res.string.ce_library_importing),
            "failed" to getString(Res.string.ce_library_failed),
            "cancelled" to getString(Res.string.ce_library_cancelled),
            "exporting" to getString(Res.string.ce_library_exporting),
            "exported" to getString(Res.string.ce_library_exported),
            "add_title" to getString(Res.string.ce_library_add_title),
            "limit" to getString(Res.string.ce_library_limit),
            "save_title" to getString(Res.string.ce_library_save_title),
            "overwrite" to getString(Res.string.ce_library_overwrite),
            "retry" to getString(LibraryRes.string.music_retry_failed),
            "refresh" to getString(LibraryRes.string.music_refresh),
            "read_failed" to getString(LibraryRes.string.music_read_failed),
            "unreadable" to getString(LibraryRes.string.music_unreadable),
            "added_counts" to getString(LibraryRes.string.music_added_counts),
            "partial_counts" to getString(LibraryRes.string.music_partial_counts),
            "export_selected" to getString(LibraryRes.string.music_export_selected),
            "export_hint" to getString(LibraryRes.string.music_export_hint),
            "clear_export" to getString(LibraryRes.string.music_export_clear),
            "catalog_window" to getString(LibraryRes.string.music_catalog_window),
            "older" to getString(LibraryRes.string.music_older_items),
            "newer" to getString(LibraryRes.string.music_newer_items),
            "failure_MISSING" to getString(LibraryRes.string.music_failure_missing),
            "failure_ACCESS" to getString(LibraryRes.string.music_failure_access),
            "failure_EMPTY" to getString(LibraryRes.string.music_failure_empty),
            "failure_TOO_LARGE" to getString(LibraryRes.string.music_failure_large),
            "failure_INVALID_AUDIO" to getString(LibraryRes.string.music_failure_invalid),
            "failure_CORRUPT" to getString(LibraryRes.string.music_failure_corrupt),
            "repair" to getString(LibraryRes.string.music_repair_original),
            "repaired" to getString(LibraryRes.string.music_repaired_count),
            "tag_fallback" to getString(LibraryRes.string.music_tag_fallback),
            "failure_CAPACITY" to getString(LibraryRes.string.music_failure_capacity))
        fun label(key: String, vararg args: Any) = String.format(Locale.getDefault(), labels.getValue(key), *args)
        return suspendCancellableCoroutine { answer ->
            SwingUtilities.invokeLater {
                if (!answer.isActive) return@invokeLater
                val dialog = JDialog(parent, label("title"), Dialog.ModalityType.DOCUMENT_MODAL)
                val browsing = NextLibraryBrowseSessions.forDirectory(directory)
                val library = NextLibrary(directory, browsing.catalogOffset, validate)
                val content = androidx.compose.ui.awt.ComposePanel().apply {
                    name = "next-library-browser"
                    setContent {
                        NextDisplayEnvironment {
                        val state by library.state.collectAsState()
                        androidx.compose.runtime.SideEffect(onRendered)
                        com.choplab.sampler.ui.theme.ChopLabTheme {
                            if (state.status != NextLibrary.Status.LOADING || state.items.isNotEmpty()) androidx.compose.runtime.key(state.catalogOffset) {
                                com.choplab.sampler.ui.LibraryBrowserPanel(state.items, !state.busy, { library.select(it) },
                                    exportSelection = state.exportItems.map { it.id }.toSet(),
                                    onExportToggle = { library.toggleExport(it) }, onExportPage = { library.addExportItems(it) },
                                    browser = browsing.browser(state.catalogOffset))
                            }
                        }
                        }
                    }
                }
                fun wrapped() = JTextArea().apply { isEditable = false; lineWrap = true; wrapStyleWord = true; isOpaque = false; rows = 2 }
                val status = wrapped()
                val readNotice = wrapped().apply { name = "next-library-read-notice" }
                val metadataNotice = wrapped().apply { name = "next-library-tag-notice" }
                val repair = JButton(label("repair"))
                val failures = wrapped().apply { name = "next-library-failures"; rows = 3 }
                val failureScroll = JScrollPane(failures)
                fun button(label: String, id: String) = JButton(label).apply {
                    name = id; preferredSize = Dimension(preferredSize.width.coerceAtLeast(110), 48)
                }
                val add = button(label("add"), "next-library-add")
                val export = button(label("export"), "next-library-export")
                val cancel = button(label("cancel"), "next-library-cancel")
                val close = button(label("close"), "next-library-close")
                val retry = button(label("retry"), "next-library-retry")
                val reload = button(label("refresh"), "next-library-refresh")
                val clear = button(label("clear_export"), "next-library-clear-export")
                val older = button(label("older"), "next-library-older")
                val newer = button(label("newer"), "next-library-newer")
                val windowLabel = wrapped()
                val catalogWindow = JPanel(BorderLayout(8, 8)).apply {
                    add(newer, BorderLayout.WEST); add(windowLabel, BorderLayout.CENTER); add(older, BorderLayout.EAST)
                }
                var selected: (NextLibrary.Selection) -> Unit = {}
                fun refresh() {
                    val state = library.state.value
                    add.isEnabled = !state.busy
                    export.isEnabled = !state.busy && state.exportItems.isNotEmpty()
                    export.text = label("export_selected", state.exportItems.size)
                    clear.isEnabled = !state.busy && state.exportItems.isNotEmpty()
                    retry.isEnabled = !state.busy && state.failures.isNotEmpty()
                    repair.isEnabled = !state.busy && state.failures.any { it.reason == NextLibrary.FailureReason.CORRUPT && it.entryTitle == null }
                    repair.isVisible = state.failures.any { it.reason == NextLibrary.FailureReason.CORRUPT && it.entryTitle == null }
                    if (!state.busy && !state.readFailed) browsing.catalogOffset = state.catalogOffset
                    reload.isEnabled = !state.busy
                    older.isEnabled = !state.busy && state.hasOlder; newer.isEnabled = !state.busy && state.hasNewer
                    catalogWindow.isVisible = state.catalogTotal > LocalAudioLibrary.MAX_ITEMS
                    windowLabel.text = label("catalog_window", state.catalogOffset + 1,
                        minOf(state.catalogOffset + LocalAudioLibrary.MAX_ITEMS, state.catalogTotal), state.catalogTotal)
                    readNotice.isVisible = state.readFailed || state.unreadable > 0
                    readNotice.text = if (state.readFailed) label("read_failed") else label("unreadable", state.unreadable)
                    metadataNotice.isVisible = state.tagFallbacks > 0 || state.repaired > 0
                    metadataNotice.text = listOfNotNull(if (state.tagFallbacks > 0) label("tag_fallback", state.tagFallbacks) else null,
                        if (state.repaired > 0) label("repaired", state.repaired) else null).joinToString("\n")
                    failureScroll.isVisible = state.failures.isNotEmpty()
                    failures.text = state.failures.joinToString("\n") { "${it.name} — ${label("failure_${it.reason}")}" }
                    cancel.isEnabled = state.busy
                    status.text = when (state.status) {
                        NextLibrary.Status.BUNDLE_LIMIT -> label("bundle_limit")
                        NextLibrary.Status.READY -> label("ready")
                        NextLibrary.Status.SELECTING -> label("selecting")
                        NextLibrary.Status.SELECTED -> label("selected")
                        NextLibrary.Status.LOADING -> label("loading")
                        NextLibrary.Status.LOAD_FAILED -> label("read_failed")
                        NextLibrary.Status.IMPORTING -> label("importing", state.position, state.total)
                        NextLibrary.Status.ADDED -> label("added_counts", state.completed, state.reused)
                        NextLibrary.Status.PARTLY_ADDED -> label("partial_counts", state.completed, state.reused, state.failed)
                        NextLibrary.Status.FAILED -> label("failed")
                        NextLibrary.Status.CANCELLED -> label("cancelled")
                        NextLibrary.Status.EXPORTING -> label("exporting")
                        NextLibrary.Status.EXPORTED -> label("exported")
                    }
                    if (!state.busy) state.selection?.let(selected)
                    dialog.contentPane.revalidate()
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
                                JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) library.export(target, library.state.value.exportItems.map { it.id })
                        refresh()
                    }
                }
                cancel.addActionListener { library.cancel(); refresh() }
                retry.addActionListener { library.retryFailures(); refresh() }
                repair.addActionListener { library.repairFailures(); refresh() }
                reload.addActionListener { library.refresh(); refresh() }
                clear.addActionListener { library.clearExportSelection(); refresh() }
                older.addActionListener { library.refresh(library.state.value.catalogOffset + LocalAudioLibrary.MAX_ITEMS); refresh() }
                newer.addActionListener { library.refresh((library.state.value.catalogOffset - LocalAudioLibrary.MAX_ITEMS).coerceAtLeast(0)); refresh() }
                close.addActionListener { finish(null) }
                dialog.defaultCloseOperation = WindowConstants.DO_NOTHING_ON_CLOSE
                dialog.addWindowListener(object : WindowAdapter() { override fun windowClosing(e: WindowEvent) { finish(null) } })
                answer.invokeOnCancellation { SwingUtilities.invokeLater { finish(null) } }
                dialog.contentPane = JPanel(BorderLayout(8, 8)).apply {
                    border = BorderFactory.createEmptyBorder(12, 12, 12, 12)
                    add(catalogWindow, BorderLayout.NORTH)
                    add(content, BorderLayout.CENTER)
                    add(JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        add(wrapped().apply { text = label("export_hint") })
                        add(status); add(readNotice); add(metadataNotice); add(failureScroll); add(repair)
                        add(JPanel(GridLayout(0, 3, 8, 8)).apply { listOf(add, export, clear, retry, reload, cancel, close).forEach { add(it) } })
                    }, BorderLayout.SOUTH)
                }
                configureNextDialog(dialog, Dimension(900, 740), Dimension(680, 580)) { finish(null) }
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
