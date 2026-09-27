@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.ui.pattern

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import kotlin.test.*

class StepPatternPanelTest {
    @Test fun jaEnDesktopAndPhoneLargeTextCanEditTheLastStepSavePlaceAndClose() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPAN, Locale.US)) for ((width, height, font) in listOf(Triple(1200, 1000, 1f), Triple(390, 844, 2f))) {
                Locale.setDefault(locale)
                val fixture = PatternTestFixture(selectedPad = 1)
                var closed = false
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                    MaterialTheme { StepPatternPanel(fixture.controller, { closed = true }) }
                }
                try {
                    scene.settle()
                    scene.click("pattern-bars-8")
                    scene.click("pattern-columns-64")
                    scene.click("pattern-page-next")
                    assertEquals(8, fixture.controller.state.value.draft.bars)
                    assertEquals(1, fixture.controller.state.value.page)
                    scene.reach("pattern-grid", width, height, target = false)
                    scene.tag("pattern-grid")!!.config[SemanticsActions.ScrollBy].action!!.invoke(100_000f, 0f)
                    scene.settle()
                    val last = scene.tag("pattern-step-127")!!.boundsInRoot
                    assertTrue(last.height >= 48 && last.width >= 48 && last.left >= 0 && last.right <= width)
                    scene.click("pattern-step-127")
                    assertEquals(1, fixture.controller.state.value.draft.notes.single().padId)
                    assertTrue(fixture.document.value.project.patterns.first().notes.isEmpty())
                    scene.reach("pattern-save", width, height)
                    scene.click("pattern-save")
                    assertEquals(8, fixture.document.value.project.patterns.first().bars)
                    assertEquals(1, fixture.edits.size)
                    scene.reach("pattern-queue", width, height)
                    scene.click("pattern-queue")
                    scene.reach("pattern-place", width, height)
                    scene.capture("${locale.language}-${width}-font${(font * 100).toInt()}.png")
                    scene.click("pattern-place")
                    withTimeout(3_000) { while (fixture.document.value.project.clips.isEmpty()) { scene.settle() } }
                    assertEquals(2, fixture.edits.size)
                    assertEquals(30_480L, fixture.document.value.project.clips.single().startTick)
                    val labels = scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                    assertFalse(labels.any { Regex("%[1-9]\\$").containsMatchIn(it) }, "Resource substitutions are resolved")
                    scene.reach("pattern-close", width, height)
                    scene.click("pattern-close")
                    assertTrue(closed)
                    assertEquals(PatternPhase.CLOSED, fixture.controller.state.value.phase)
                } finally { scene.close(); fixture.close() }
            }
        } finally { Locale.setDefault(previous) }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(value: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }
    private suspend fun ImageComposeScene.settle() { repeat(8) { render(System.nanoTime()).close(); delay(12) } }
    private suspend fun ImageComposeScene.click(value: String) { tag(value)!!.config[SemanticsActions.OnClick].action!!.invoke(); settle() }
    private suspend fun ImageComposeScene.reach(value: String, width: Int, height: Int, target: Boolean = true) {
        val node = requireNotNull(tag(value))
        val vertical = nodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
        vertical.config[SemanticsActions.ScrollBy].action!!.invoke(0f, node.positionInRoot.y - height / 3f)
        settle()
        val bounds = tag(value)!!.boundsInRoot
        assertTrue(bounds.top >= 0 && bounds.bottom <= height && bounds.left >= 0 && bounds.right <= width, "$value reachable: $bounds")
        if (target) assertTrue(bounds.height >= 48)
    }
    private fun ImageComposeScene.capture(name: String) {
        val output = File(System.getProperty("choplab.ui.evidenceDir"), "step-pattern").apply { mkdirs() }
        render(System.nanoTime()).use { bitmap -> bitmap.encodeToData()!!.use { File(output, name).writeBytes(it.bytes) } }
    }
}
