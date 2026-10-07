package com.choplab.desktop.next

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.MenuBarScope
import com.choplab.desktop.DesktopMenuCommand
import com.choplab.desktop.desktopMenuShortcut
import com.choplab.desktop.isMacOsHost
import com.choplab.ui.*
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Native menu actions always go through the editor's recording, work and history ownership gates. */
@Composable
internal fun MenuBarScope.NextDesktopMenus(
    state: ContinuousEditorState,
    closing: Boolean,
    onAction: (ContinuousEditorAction) -> Unit,
    dialogOpen: Boolean = false,
) {
    fun enabled(capability: ContinuousCapability) = !closing && !dialogOpen && canUseNextFile(state, capability)
    Menu(stringResource(Res.string.next_file_menu)) {
        Item(stringResource(Res.string.ce_new_project), enabled = enabled(ContinuousCapability.OPEN_PROJECT),
            shortcut = KeyShortcut(Key.N, meta = isMacOsHost(), ctrl = !isMacOsHost()),
            onClick = { onAction(ContinuousEditorAction.NewProject) })
        Separator()
        Item(stringResource(Res.string.ce_load_audio), enabled = enabled(ContinuousCapability.IMPORT_AUDIO),
            shortcut = KeyShortcut(Key.I, meta = isMacOsHost(), ctrl = !isMacOsHost()),
            onClick = { onAction(ContinuousEditorAction.ImportAudio) })
        Item(stringResource(Res.string.ce_library), enabled = enabled(ContinuousCapability.IMPORT_LIBRARY),
            shortcut = desktopMenuShortcut(DesktopMenuCommand.OPEN_LIBRARY),
            onClick = { onAction(ContinuousEditorAction.ImportLibrary) })
        Item(stringResource(Res.string.ce_open_project), enabled = enabled(ContinuousCapability.OPEN_PROJECT),
            shortcut = desktopMenuShortcut(DesktopMenuCommand.OPEN_PROJECT),
            onClick = { onAction(ContinuousEditorAction.OpenProject) })
        Separator()
        Item(stringResource(Res.string.ce_save_project), enabled = enabled(ContinuousCapability.SAVE_PROJECT),
            shortcut = desktopMenuShortcut(DesktopMenuCommand.SAVE_PROJECT),
            onClick = { onAction(ContinuousEditorAction.SaveProject) })
        Item(stringResource(Res.string.ce_export_wav), enabled = enabled(ContinuousCapability.EXPORT_WAV),
            shortcut = desktopMenuShortcut(DesktopMenuCommand.EXPORT_WAV),
            onClick = { onAction(ContinuousEditorAction.ExportWav) })
        Item(stringResource(Res.string.next_export_stems), enabled = enabled(ContinuousCapability.EXPORT_STEMS),
            onClick = { onAction(ContinuousEditorAction.ExportStems) })
    }
    Menu(stringResource(Res.string.next_edit_menu)) {
        Item(stringResource(Res.string.ce_undo), enabled = enabled(ContinuousCapability.HISTORY) && state.canUndo,
            shortcut = desktopMenuShortcut(DesktopMenuCommand.UNDO), onClick = { onAction(ContinuousEditorAction.Undo) })
        Item(stringResource(Res.string.ce_redo), enabled = enabled(ContinuousCapability.HISTORY) && state.canRedo,
            shortcut = desktopMenuShortcut(DesktopMenuCommand.REDO), onClick = { onAction(ContinuousEditorAction.Redo) })
    }
    Menu(stringResource(Res.string.next_transport_menu)) {
        Item(stringResource(Res.string.ce_stop_all), enabled = !closing,
            shortcut = KeyShortcut(Key.Period, meta = isMacOsHost(), ctrl = !isMacOsHost()),
            onClick = { onAction(ContinuousEditorAction.StopAll) })
    }
}
