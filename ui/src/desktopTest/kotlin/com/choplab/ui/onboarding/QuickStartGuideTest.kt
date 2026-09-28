@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.ui.onboarding

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.choplab.ui.*
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.test.*

class QuickStartGuideTest {
    @Test fun jaAndEnKeepGuideActionsReachableAndKeyboardFocusSafeAtDesktopAndCompactFontTwo() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPAN, Locale.US)) for ((width, height, font) in listOf(Triple(1440, 838, 1f), Triple(390, 844, 2f))) {
                Locale.setDefault(locale)
                var remembered = 0
                val actions = mutableListOf<ContinuousEditorAction>()
                val controller = QuickStartController(this, true, { false }, { remembered++ })
                val state = ContinuousEditorFixture.state().copy(stage = ContinuousStage.CAPTURE,
                    capabilities = ContinuousCapability.entries.toSet(), originalPlaying = false)
                val motion = object : MotionDurationScale { override val scaleFactor = 2f }
                val scene = ImageComposeScene(width, height, density = Density(1f, font), coroutineContext = coroutineContext + motion) {
                    ContinuousEditor(state, actions::add, quickStart = controller)
                }
                try {
                    scene.until { scene.tag("next-help-close")?.config?.getOrNull(SemanticsProperties.Focused) == true }
                    assertTrue(actions.isEmpty(), "Showing a guide must never start an editor action")
                    val fixed = listOf("next-help-import", "next-help-stop", "next-help-close").associateWith { scene.tag(it)!!.boundsInWindow }
                    fixed.keys.forEach { scene.hit(it, width, height) }
                    assertTrue(scene.nodes().count { it.config.getOrNull(SemanticsProperties.Heading) != null } >= 6)
                    assertTrue(scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.any {
                        it.text == if (locale.language == "ja") "はじめの一曲" else "Your first song"
                    })
                    // Initial Enter closes safely; importing requires a separate focused or pointer action.
                    scene.key(Key.Enter); scene.closed()
                    assertTrue(actions.isEmpty()); assertEquals(1, remembered)
                    scene.click("next-help-open"); scene.until { scene.tag("next-help-close")?.config?.getOrNull(SemanticsProperties.Focused) == true }
                    // Real Tab traversal stays in the modal and reaches all three fixed actions.
                    val focused = mutableSetOf<String>()
                    repeat(6) {
                        scene.key(Key.Tab)
                        scene.nodes().filter { it.config.getOrNull(SemanticsProperties.Focused) == true }
                            .mapNotNullTo(focused) { it.config.getOrNull(SemanticsProperties.TestTag) }
                    }
                    assertTrue(focused.containsAll(fixed.keys), "Tab focus: $focused")
                    val scroll = requireNotNull(scene.tag("next-help-content"))
                    val axis = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
                    val end = axis.maxValue()
                    assertTrue(end > 0f)
                    assertTrue(requireNotNull(scroll.config[SemanticsActions.ScrollBy].action)(0f, end))
                    scene.until { abs(axis.value() - end) <= 1f }
                    fixed.forEach { (tag, bounds) -> scene.hit(tag, width, height); assertEquals(bounds, scene.tag(tag)!!.boundsInWindow) }
                    val last = requireNotNull(scene.tag("next-help-reopen")).boundsInWindow
                    assertTrue(last.top >= scroll.boundsInWindow.top && last.bottom <= scroll.boundsInWindow.bottom + .5f)
                    scene.capture("${locale.language}-${width}-font${(font * 100).toInt()}.png")
                    scene.click("next-help-stop"); assertEquals(listOf<ContinuousEditorAction>(ContinuousEditorAction.StopAll), actions)
                    assertTrue(controller.state.value.open)
                    scene.key(Key.Escape); scene.closed()
                    scene.click("next-help-open"); scene.until { scene.tag("next-help-import") != null }
                    scene.click("next-help-import"); scene.closed()
                    assertEquals(listOf(ContinuousEditorAction.StopAll, ContinuousEditorAction.ImportAudio), actions)
                    assertEquals(3, remembered)
                } finally { scene.close(); controller.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun unavailableImportStaysDisabledWhileStopAndCloseStillWork() = runBlocking<Unit> {
        val controller = QuickStartController(this, true, { false }, {})
        var imports = 0; var stops = 0
        val scene = ImageComposeScene(390, 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
            QuickStartGuide(controller, false, { imports++ }, { stops++ })
        }
        try {
            scene.until { scene.tag("next-help-import") != null }
            assertNotNull(scene.tag("next-help-import")!!.config.getOrNull(SemanticsProperties.Disabled))
            scene.click("next-help-import"); assertEquals(0, imports)
            scene.click("next-help-stop"); assertEquals(1, stops)
            scene.click("next-help-close"); scene.closed(); assertEquals(0, imports)
        } finally { scene.close(); controller.close() }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(value: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }
    private suspend fun ImageComposeScene.settle() { repeat(4) { render(System.nanoTime()).close(); delay(12) } }
    private suspend fun ImageComposeScene.until(condition: () -> Boolean) { withTimeout(5_000) { do { settle() } while (!condition()) } }
    private suspend fun ImageComposeScene.closed() = until { tag("next-quick-start") == null && semanticsOwners.size == 1 }
    private suspend fun ImageComposeScene.key(key: Key) {
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)); sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp)); settle()
    }
    private fun ImageComposeScene.hit(value: String, width: Int, height: Int) {
        val node = requireNotNull(tag(value)); val b = node.boundsInWindow
        assertTrue(node.size.width >= 48 && node.size.height >= 48, "$value target ${node.size}")
        assertEquals(node.size.width.toFloat(), b.width, .5f, "$value clipped width")
        assertEquals(node.size.height.toFloat(), b.height, .5f, "$value clipped height")
        assertTrue(b.left >= 0 && b.top >= 0 && b.right <= width && b.bottom <= height, "$value outside window $b")
    }
    private suspend fun ImageComposeScene.click(value: String) {
        val point = requireNotNull(tag(value)).boundsInWindow.center
        sendPointerEvent(PointerEventType.Press, point, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close()
        assertEquals(point, requireNotNull(tag(value)).boundsInWindow.center)
        sendPointerEvent(PointerEventType.Release, point, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
    }
    private fun ImageComposeScene.capture(name: String) {
        val directory = File(System.getProperty("choplab.ui.evidenceDir"), "quick-start").apply { mkdirs() }
        render(System.nanoTime()).use { bitmap -> bitmap.encodeToData()!!.use { File(directory, name).writeBytes(it.bytes) } }
    }
}
