package com.choplab.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut

internal fun isMacOsHost(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    osName.startsWith("Mac", ignoreCase = true)

internal enum class DesktopMenuCommand {
    OPEN_LIBRARY,
    OPEN_PROJECT,
    SAVE_PROJECT,
    EXPORT_WAV,
    QUIT,
    UNDO,
    REDO,
}

/** Menu accelerators follow the host convention: Command on macOS, Control elsewhere. */
internal fun desktopMenuShortcut(command: DesktopMenuCommand, macOs: Boolean = isMacOsHost()): KeyShortcut {
    fun primary(key: Key, shift: Boolean = false) =
        if (macOs) KeyShortcut(key, meta = true, shift = shift) else KeyShortcut(key, ctrl = true, shift = shift)
    return when (command) {
        DesktopMenuCommand.OPEN_LIBRARY -> primary(Key.O)
        DesktopMenuCommand.OPEN_PROJECT -> primary(Key.O, shift = true)
        DesktopMenuCommand.SAVE_PROJECT -> primary(Key.S)
        DesktopMenuCommand.EXPORT_WAV -> primary(Key.E)
        DesktopMenuCommand.QUIT -> if (macOs) primary(Key.Q) else KeyShortcut(Key.F4, alt = true)
        DesktopMenuCommand.UNDO -> primary(Key.Z)
        DesktopMenuCommand.REDO -> if (macOs) primary(Key.Z, shift = true) else primary(Key.Y)
    }
}

/**
 * macOS reads these before AWT starts. Explicit `-D` values win so a launcher can still override them.
 */
internal fun applyMacOsHostProperties(appName: String, macOs: Boolean = isMacOsHost()) {
    if (!macOs) return
    mapOf(
        "apple.laf.useScreenMenuBar" to "true",
        "apple.awt.application.name" to appName,
        "apple.awt.application.appearance" to "system",
    ).forEach { (key, value) -> if (System.getProperty(key) == null) System.setProperty(key, value) }
}
