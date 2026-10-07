package com.choplab.desktop.next

import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.choplab.desktop.applyMacOsHostProperties
import com.choplab.ui.*
import java.awt.event.ActionEvent
import java.util.Locale
import kotlinx.coroutines.*

/** Exercises this JVM's native menus only. No OS-wide keys or user-window automation. */
object NextDesktopMenuSelfTest {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1 && args[0] in listOf("ja", "en"))
        Locale.setDefault(Locale.forLanguageTag(args[0]))
        applyMacOsHostProperties("ChopLab menu acceptance")
        var failure: Throwable? = null
        var assertions = 0
        application(exitProcessOnExit = false) {
            var recording by remember { mutableStateOf(false) }
            var dialogOpen by remember { mutableStateOf(false) }
            val actions = remember { mutableListOf<ContinuousEditorAction>() }
            val state = ContinuousEditorState(capabilities = ContinuousCapability.entries.toSet(), canUndo = true, canRedo = true,
                recordingSource = recording)
            Window(onCloseRequest = ::exitApplication, title = "ChopLab isolated menu acceptance",
                state = rememberWindowState(width = 640.dp, height = 480.dp)) {
                MenuBar { NextDesktopMenus(state, false, actions::add, dialogOpen = dialogOpen) }
                LaunchedEffect(window) {
                    try {
                        suspend fun awaitMenus(condition: () -> Boolean) = withTimeout(10_000) {
                            while (!condition()) delay(25)
                        }
                        // Compose uses the AWT menu implementation on Mac and Swing on other hosts.
                        fun menuCount() = window.menuBar?.menuCount ?: window.jMenuBar?.menuCount ?: 0
                        fun label(menu: Int) = window.menuBar?.getMenu(menu)?.label ?: window.jMenuBar.getMenu(menu).text
                        fun enabled(menu: Int, item: Int) = window.menuBar?.getMenu(menu)?.getItem(item)?.isEnabled
                            ?: window.jMenuBar.getMenu(menu).getItem(item).isEnabled
                        fun click(menu: Int, item: Int) {
                            val awt = window.menuBar?.getMenu(menu)?.getItem(item)
                            if (awt != null) {
                                check(awt.isEnabled)
                                awt.actionListeners.forEach { it.actionPerformed(ActionEvent(awt, ActionEvent.ACTION_PERFORMED, awt.actionCommand)) }
                            } else window.jMenuBar.getMenu(menu).getItem(item).doClick(0)
                        }
                        awaitMenus { menuCount() == 3 }
                        check(label(0) == if (args[0] == "ja") "ファイル" else "File"); assertions++
                        val expected = listOf(0 to ContinuousEditorAction.NewProject, 2 to ContinuousEditorAction.ImportAudio,
                            3 to ContinuousEditorAction.ImportLibrary, 4 to ContinuousEditorAction.OpenProject,
                            6 to ContinuousEditorAction.SaveProject, 7 to ContinuousEditorAction.ExportWav,
                            8 to ContinuousEditorAction.ExportStems)
                        for ((index, action) in expected) {
                            click(0, index); check(actions.last() == action); assertions++
                        }
                        click(1, 0); check(actions.last() == ContinuousEditorAction.Undo); assertions++
                        click(1, 1); check(actions.last() == ContinuousEditorAction.Redo); assertions++
                        recording = true
                        awaitMenus { !enabled(0, 0) && !enabled(1, 0) }
                        for ((index, _) in expected) { check(!enabled(0, index)); assertions++ }
                        check(!enabled(1, 1)); assertions++
                        click(2, 0); check(actions.last() == ContinuousEditorAction.StopAll); assertions++
                        recording = false
                        awaitMenus { enabled(0, 0) && enabled(1, 0) }
                        assertions++
                        dialogOpen = true
                        awaitMenus { !enabled(0, 0) && !enabled(1, 0) }
                        check(!enabled(0, 6)); assertions++
                        click(2, 0); check(actions.last() == ContinuousEditorAction.StopAll); assertions++
                        check(!enabled(1, 1)); assertions++
                    } catch (error: Throwable) { failure = error }
                    finally { exitApplication() }
                }
            }
        }
        failure?.let { throw it }
        check(assertions == 23)
        println("""{"status":"LOCAL_PASS","scope":"owned-native-desktop-menus","locale":"${args[0]}","assertions":$assertions,"audioStarted":false}""")
    }
}
