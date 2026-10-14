package com.choplab.desktop.next

import androidx.compose.runtime.*
import androidx.compose.ui.window.MenuBarScope
import com.choplab.ui.*
import com.choplab.ui.resources.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Device IDs are session-only. Choosing a route is explicit, and the backend retains the final recording gate. */
@Composable
internal fun MenuBarScope.NextMacAudioMenus(backend: NextBackend, state: ContinuousEditorState, blocked: Boolean) {
    val audio = backend.macAudio ?: return
    val scope = rememberCoroutineScope()
    val selection by audio.selection.collectAsState()
    var devices by remember { mutableStateOf<MacAudioDevices?>(null) }
    var changing by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    suspend fun refresh() {
        try { devices = audio.devices(); failed = false }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { failed = true }
    }
    LaunchedEffect(audio) { refresh() }
    val allowed = !blocked && !changing && canUseNextFile(state, ContinuousCapability.RELOAD_AUDIO) && !state.vocalPreview
    fun choose(input: Int, output: Int, reconnect: Boolean = false) {
        if (!allowed) return
        changing = true
        scope.launch {
            try {
                failed = if (reconnect) !backend.reconnectMacAudio() else !backend.chooseMacAudioDevices(input, output)
                devices = audio.devices()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed = true }
            finally { changing = false }
        }
    }
    val default = stringResource(Res.string.next_mac_audio_default)
    Menu(stringResource(Res.string.next_audio_menu)) {
        Menu(stringResource(Res.string.next_mac_audio_input), enabled = allowed) {
            devices?.inputs?.forEach { device ->
                CheckboxItem(if (device.systemDefault) default else device.label, checked = selection.inputId == device.id,
                    onCheckedChange = { choose(device.id, selection.outputId) })
            }
        }
        Menu(stringResource(Res.string.next_mac_audio_output), enabled = allowed) {
            devices?.outputs?.forEach { device ->
                CheckboxItem(if (device.systemDefault) default else device.label, checked = selection.outputId == device.id,
                    onCheckedChange = { choose(selection.inputId, device.id) })
            }
        }
        Separator()
        Item(stringResource(Res.string.next_mac_audio_reset_defaults), enabled = allowed,
            onClick = { choose(0, 0) })
        Item(stringResource(Res.string.next_mac_audio_refresh), enabled = !changing && !blocked,
            onClick = { scope.launch { changing = true; try { refresh() } finally { changing = false } } })
        Item(stringResource(Res.string.next_audio_retry), enabled = allowed,
            onClick = { choose(selection.inputId, selection.outputId, reconnect = true) })
        if (failed) Item(stringResource(Res.string.next_mac_audio_failed), enabled = false, onClick = {})
        else if (devices == null) Item(stringResource(Res.string.next_mac_audio_loading), enabled = false, onClick = {})
    }
}
