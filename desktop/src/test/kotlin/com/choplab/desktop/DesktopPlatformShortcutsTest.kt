package com.choplab.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopPlatformShortcutsTest {
    private val macProperties = listOf(
        "apple.laf.useScreenMenuBar",
        "apple.awt.application.name",
        "apple.awt.application.appearance",
    )
    private val savedProperties = macProperties.associateWith { System.getProperty(it) }

    @AfterTest
    fun restoreProperties() {
        savedProperties.forEach { (key, value) ->
            if (value == null) System.clearProperty(key) else System.setProperty(key, value)
        }
    }

    @Test
    fun hostDetectionOnlyAcceptsMacOs() {
        assertTrue(isMacOsHost("Mac OS X"))
        assertFalse(isMacOsHost("Windows 11"))
        assertFalse(isMacOsHost("Linux"))
    }

    @Test
    fun windowsKeepsTheExistingControlAccelerators() {
        val expected = mapOf(
            DesktopMenuCommand.OPEN_LIBRARY to KeyShortcut(Key.O, ctrl = true),
            DesktopMenuCommand.OPEN_PROJECT to KeyShortcut(Key.O, ctrl = true, shift = true),
            DesktopMenuCommand.SAVE_PROJECT to KeyShortcut(Key.S, ctrl = true),
            DesktopMenuCommand.EXPORT_WAV to KeyShortcut(Key.E, ctrl = true),
            DesktopMenuCommand.QUIT to KeyShortcut(Key.F4, alt = true),
            DesktopMenuCommand.UNDO to KeyShortcut(Key.Z, ctrl = true),
            DesktopMenuCommand.REDO to KeyShortcut(Key.Y, ctrl = true),
        )
        assertEquals(expected, DesktopMenuCommand.entries.associateWith { desktopMenuShortcut(it, macOs = false) })
    }

    @Test
    fun macOsUsesCommandAndPlatformQuitAndRedo() {
        val expected = mapOf(
            DesktopMenuCommand.OPEN_LIBRARY to KeyShortcut(Key.O, meta = true),
            DesktopMenuCommand.OPEN_PROJECT to KeyShortcut(Key.O, meta = true, shift = true),
            DesktopMenuCommand.SAVE_PROJECT to KeyShortcut(Key.S, meta = true),
            DesktopMenuCommand.EXPORT_WAV to KeyShortcut(Key.E, meta = true),
            DesktopMenuCommand.QUIT to KeyShortcut(Key.Q, meta = true),
            DesktopMenuCommand.UNDO to KeyShortcut(Key.Z, meta = true),
            DesktopMenuCommand.REDO to KeyShortcut(Key.Z, meta = true, shift = true),
        )
        assertEquals(expected, DesktopMenuCommand.entries.associateWith { desktopMenuShortcut(it, macOs = true) })
    }

    @Test
    fun commandChordsOnPadKeysNeverTriggerPads() {
        val owner = DesktopPadKeyOwner()
        listOf(Key.Q, Key.S, Key.E, Key.Z).forEach { key ->
            assertNull(
                owner.press(
                    key = key,
                    visiblePadIndices = (0 until 16).toList(),
                    playablePadIndices = (0 until 16).toSet(),
                    inputEnabled = true,
                    meta = true,
                ),
            )
        }
    }

    @Test
    fun hostPropertiesApplyOnlyOnMacAndKeepExplicitLauncherValues() {
        macProperties.forEach(System::clearProperty)
        applyMacOsHostProperties("おとひろい", macOs = false)
        macProperties.forEach { assertNull(System.getProperty(it)) }

        System.setProperty("apple.laf.useScreenMenuBar", "false")
        applyMacOsHostProperties("おとひろい", macOs = true)
        assertEquals("false", System.getProperty("apple.laf.useScreenMenuBar"))
        assertEquals("おとひろい", System.getProperty("apple.awt.application.name"))
        assertEquals("system", System.getProperty("apple.awt.application.appearance"))
    }
}
