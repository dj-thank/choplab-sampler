@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.ui.separation

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.choplab.core.separation.StemMix
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.test.*

class FourStemPanelTest {
    @Test fun acceptedPlacementKeepsDialogAliveUntilApplyCompletesWhileStopRemainsReachable() = runBlocking<Unit> {
        val f = FourStemTestFixture().apply { applyRelease = CompletableDeferred() }
        var open by mutableStateOf(true)
        var stops = 0
        val scene = ImageComposeScene(width = 900, height = 900, coroutineContext = coroutineContext) {
            MaterialTheme { if (open) FourStemDialog(f.controller, { stops++ }, { open = false }) }
        }
        try {
            scene.until { scene.tag("four-stem-close") != null }
            scene.reach("four-stem-start", 900, 900); scene.click("four-stem-start")
            scene.until { f.controller.state.value.phase == FourStemPhase.READY }
            scene.reach("four-stem-apply", 900, 900); scene.click("four-stem-apply")
            scene.until { f.applyEntered.isCompleted && f.controller.state.value.phase == FourStemPhase.APPLYING }
            assertTrue(scene.tag("four-stem-close")!!.config.contains(SemanticsProperties.Disabled))
            scene.click("four-stem-close")
            assertTrue(open); assertEquals(0, f.closes.get()); assertEquals(0, f.applies.get())
            scene.hit("four-stem-stop", 900, 900); scene.click("four-stem-stop")
            assertEquals(1, stops)
            assertEquals(FourStemPhase.APPLYING, f.controller.state.value.phase)
            f.applyRelease!!.complete(Unit)
            scene.until { f.controller.state.value.phase == FourStemPhase.APPLIED }
            assertEquals(1, f.applies.get())
            scene.click("four-stem-close"); scene.until { !open && scene.semanticsOwners.size == 1 }
            assertEquals(1, f.closes.get())
        } finally { scene.close(); f.close() }
    }

    @Test fun jaEnAndLargeFontsReachExplicitChoiceApplyAndFixedStopCloseWithActualPointers() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPAN, Locale.US)) for ((width, height, font) in listOf(Triple(900, 900, 1f), Triple(390, 844, 2f))) {
                Locale.setDefault(locale)
                val f = FourStemTestFixture(); var open by mutableStateOf(true); var stopped = 0
                val motion = object : MotionDurationScale { override val scaleFactor = 2f }
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext + motion) {
                    MaterialTheme { if (open) FourStemDialog(f.controller, { stopped++ }, { open = false }) }
                }
                try {
                    scene.until { scene.tag("four-stem-close") != null }
                    val fixed = listOf("four-stem-stop", "four-stem-close").associateWith { scene.tag(it)!!.boundsInRoot }
                    fixed.keys.forEach { scene.hit(it, width, height) }
                    scene.reach("four-stem-start", width, height); scene.click("four-stem-start")
                    scene.until { f.controller.state.value.phase == FourStemPhase.READY }
                    assertTrue(scene.tag("four-stem-memory")!!.config[SemanticsProperties.Text].single().text.contains("3072"))
                    assertNotNull(scene.tag("four-stem-memory-limit"))
                    assertEquals(0, f.applies.get()); assertEquals(f.project, f.document.value.project)
                    scene.reach("four-stem-mix-ACAPELLA", width, height); scene.click("four-stem-mix-ACAPELLA")
                    scene.until { f.controller.state.value.mix == StemMix.ACAPELLA }
                    scene.reach("four-stem-apply", width, height)
                    fixed.forEach { (tag, bounds) -> assertEquals(bounds, scene.tag(tag)!!.boundsInRoot); scene.hit(tag, width, height) }
                    scene.capture("${locale.language}-${width}-font${(font * 100).toInt()}.png")
                    scene.click("four-stem-apply"); scene.until { f.applies.get() == 1 }
                    assertEquals(listOf(true, true, true, false), f.applied!!.tracks.map { it.mute })
                    scene.click("four-stem-stop"); assertEquals(1, stopped)
                    scene.click("four-stem-close"); scene.until { !open && scene.semanticsOwners.size == 1 }
                    assertEquals(1, f.closes.get())
                } finally { scene.close(); f.close() }
            }
        } finally { Locale.setDefault(previous) }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(value: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }
    private suspend fun ImageComposeScene.settle() { repeat(4) { render(System.nanoTime()).close(); delay(12) } }
    private suspend fun ImageComposeScene.until(condition: () -> Boolean) { withTimeout(5_000) { do { settle() } while (!condition()) }; settle() }
    private fun ImageComposeScene.hit(value: String, width: Int, height: Int) {
        val node = requireNotNull(tag(value)); val bounds = node.boundsInRoot
        assertTrue(node.size.width >= 48 && node.size.height >= 48, "$value target: ${node.size}")
        assertEquals(node.size.width.toFloat(), bounds.width, .5f, "$value clipped width")
        assertEquals(node.size.height.toFloat(), bounds.height, .5f, "$value clipped height")
        assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height, "$value offscreen: $bounds")
    }
    private suspend fun ImageComposeScene.reach(value: String, width: Int, height: Int) {
        repeat(3) {
            val node = requireNotNull(tag(value))
            val scroll = nodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            val area = scroll.boundsInRoot
            if (node.positionInRoot.y < area.top || node.positionInRoot.y + node.size.height > area.bottom) {
                val axis = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
                val distance = node.positionInRoot.y - area.top - area.height / 3f
                val destination = (axis.value() + distance).coerceIn(0f, axis.maxValue())
                assertTrue(requireNotNull(scroll.config[SemanticsActions.ScrollBy].action)(0f, distance))
                until { abs(axis.value() - destination) <= 1f }
            }
        }
        hit(value, width, height)
    }
    private suspend fun ImageComposeScene.click(value: String) {
        val point = requireNotNull(tag(value)).boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, point, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close()
        sendPointerEvent(PointerEventType.Release, point, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
    }
    private fun ImageComposeScene.capture(name: String) {
        val output = File(System.getProperty("choplab.ui.evidenceDir"), "four-stem").apply { mkdirs() }
        render(System.nanoTime()).use { bitmap -> bitmap.encodeToData()!!.use { File(output, name).writeBytes(it.bytes) } }
    }
}
