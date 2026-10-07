@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.desktop.next

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.choplab.ui.resources.*
import java.nio.file.Files
import java.util.Locale
import javax.swing.JButton
import javax.swing.JPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.stringResource
import kotlin.test.*

class NextDisplaySettingsTest {
    @Test fun scaleAppliesWithoutLosingDraftsAndLanguageStartsAfterReopening() = runBlocking {
        val original = Locale.getDefault()
        val directory = Files.createTempDirectory("display-settings-test-")
        val file = directory.resolve("display.properties")
        val settings = NextDisplaySettings(file, Locale.JAPANESE)
        var scale = 0f
        var mounted = 0
        val scene = ImageComposeScene(width = 500, height = 100, coroutineContext = coroutineContext) {
            NextDisplayEnvironment {
                remember { mounted++ }
                val current = LocalDensity.current.fontScale
                SideEffect { scale = current }
                Text(stringResource(Res.string.ce_stop_all))
            }
        }
        fun texts(): List<String> {
            fun read(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::read)
            return scene.semanticsOwners.flatMap { read(it.unmergedRootSemanticsNode) }
        }
        suspend fun settle() { repeat(8) { scene.render(System.nanoTime()).close(); delay(10) } }
        try {
            settle(); assertTrue("全停止" in texts()); assertEquals(1f, scale)
            assertTrue(settings.change(NextDisplayPreferences(NextDisplayLanguage.ENGLISH, 2f)))
            settle(); assertTrue("全停止" in texts()); assertEquals(2f, scale)
            assertEquals(settings.preferences.value, NextDisplaySettings.read(file))
            assertTrue(settings.change(NextDisplayPreferences(NextDisplayLanguage.JAPANESE, 1.3f)))
            settle(); assertTrue("全停止" in texts()); assertEquals(1.3f, scale)
            assertTrue(settings.change(NextDisplayPreferences(NextDisplayLanguage.SYSTEM, 1f)))
            settle(); assertTrue("全停止" in texts()); assertEquals(1f, scale)
            assertEquals(setOf("version=1", "language=system", "scale=1.0"), Files.readAllLines(file).toSet())
            assertTrue(settings.change(NextDisplayPreferences(NextDisplayLanguage.ENGLISH, 2f)))
            settle(); assertTrue("全停止" in texts()); assertEquals(2f, scale)
            assertEquals(1, mounted, "Language changes must preserve the editor and its pending inputs")
        } finally { scene.close(); settings.close(); Locale.setDefault(original) }
        val reopened = NextDisplaySettings(file, Locale.JAPANESE)
        val nextScene = ImageComposeScene(width = 500, height = 100, coroutineContext = coroutineContext) {
            NextDisplayEnvironment {
                val current = LocalDensity.current.fontScale
                SideEffect { scale = current }
                Text(stringResource(Res.string.ce_stop_all))
            }
        }
        try {
            repeat(8) { nextScene.render(System.nanoTime()).close(); delay(10) }
            fun read(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::read)
            assertTrue("Stop all" in nextScene.semanticsOwners.flatMap { read(it.unmergedRootSemanticsNode) })
            assertEquals(2f, scale)
        } finally { nextScene.close(); reopened.close(); Locale.setDefault(original); directory.toFile().deleteRecursively() }
    }

    @Test fun corruptPreferencesArePreservedAndFailedWritesKeepCurrentDisplay() = runBlocking {
        val original = Locale.getDefault()
        val directory = Files.createTempDirectory("display-settings-bad-")
        val file = directory.resolve("display.properties")
        Files.writeString(file, "invalid")
        try {
            NextDisplaySettings(file).use { settings ->
                assertTrue(settings.problem.value)
                assertEquals(NextDisplayPreferences(), settings.preferences.value)
                assertEquals("invalid", Files.readString(file))
            }
            val blocker = directory.resolve("not-directory"); Files.writeString(blocker, "keep")
            NextDisplaySettings(blocker.resolve("display.properties")).use { settings ->
                assertFalse(settings.change(NextDisplayPreferences(NextDisplayLanguage.ENGLISH, 2f)))
                assertEquals(NextDisplayPreferences(), settings.preferences.value)
                assertEquals("keep", Files.readString(blocker))
            }
        } finally { Locale.setDefault(original); directory.toFile().deleteRecursively() }
    }

    @Test fun swingTypographyScalesOwnedControlsAndReservesTheirText() {
        val button = JButton("閉じる (Escape)")
        val untouched = JButton("outside")
        val previous = button.font.size2D
        val other = untouched.font.size2D
        val panel = JPanel().apply { add(button) }
        applyNextDialogTypography(panel, 2f)
        assertEquals(previous * 2, button.font.size2D)
        assertEquals(other, untouched.font.size2D)
        assertTrue(button.preferredSize.width >= button.getFontMetrics(button.font).stringWidth(button.text) + 32)
        assertTrue(button.preferredSize.height >= 48)
    }
}
