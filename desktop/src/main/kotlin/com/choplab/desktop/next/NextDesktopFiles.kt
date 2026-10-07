package com.choplab.desktop.next

import com.choplab.jvm.OriginalAudioImportPort
import com.choplab.ui.ContinuousCapability
import com.choplab.ui.ContinuousEditorAction
import com.choplab.ui.ContinuousEditorState
import java.awt.Window
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

internal data class NextDroppedFile(val path: Path, val project: Boolean) {
    val capability get() = if (project) ContinuousCapability.OPEN_PROJECT else ContinuousCapability.IMPORT_AUDIO
    fun action(files: NextFileLocations): ContinuousEditorAction = if (project)
        ContinuousEditorAction.OpenProjectFile(files.register(path))
    else ContinuousEditorAction.ImportAudioFile(files.register(path))
}

/** One deliberate source/project replacement. Multiple files belong in the Library's add-files flow. */
internal fun nextDroppedFile(values: List<*>): NextDroppedFile? {
    val file = values.singleOrNull() as? File ?: return null
    val path = file.toPath()
    if (!Files.isRegularFile(path) || !Files.isReadable(path)) return null
    val extension = file.extension.lowercase(Locale.ROOT)
    if (extension != "choplab" && extension !in OriginalAudioImportPort.EXTENSIONS) return null
    return NextDroppedFile(path, project = extension == "choplab")
}

internal fun canUseNextFile(state: ContinuousEditorState, capability: ContinuousCapability): Boolean =
    state.permits(capability) && !state.recordingSource && !state.recordingVoice && !state.recordingHits &&
        !state.startingSourceRecording && !state.startingVoiceRecording && !state.recordingSystemAudio && !state.liveChopping

/** Only this editor window accepts files. Never opens URLs, enumerates folders or silently chooses a first file. */
internal fun installNextFileDrop(
    window: Window,
    state: () -> ContinuousEditorState,
    files: NextFileLocations,
    onAction: (ContinuousEditorAction) -> Unit,
    rejected: () -> Unit,
): AutoCloseable {
    val previous = window.dropTarget
    val target = DropTarget(window, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
        override fun dragEnter(event: DropTargetDragEvent) {
            if (event.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && window.ownedWindows.none { it.isVisible } &&
                (canUseNextFile(state(), ContinuousCapability.IMPORT_AUDIO) || canUseNextFile(state(), ContinuousCapability.OPEN_PROJECT)))
                event.acceptDrag(DnDConstants.ACTION_COPY)
            else event.rejectDrag()
        }
        override fun drop(event: DropTargetDropEvent) {
            if (!event.isDataFlavorSupported(DataFlavor.javaFileListFlavor) || window.ownedWindows.any { it.isVisible }) {
                event.rejectDrop(); return
            }
            event.acceptDrop(DnDConstants.ACTION_COPY)
            val accepted = runCatching {
                val selected = nextDroppedFile(event.transferable.getTransferData(DataFlavor.javaFileListFlavor) as? List<*> ?: emptyList<Any>())
                if (selected == null || !canUseNextFile(state(), selected.capability)) false
                else { onAction(selected.action(files)); true }
            }.getOrDefault(false)
            event.dropComplete(accepted)
            if (!accepted) rejected()
        }
    }, true)
    return AutoCloseable { if (window.dropTarget === target) window.dropTarget = previous }
}
