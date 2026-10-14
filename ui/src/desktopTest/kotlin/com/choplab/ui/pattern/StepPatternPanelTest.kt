@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.ui.pattern

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.*
import com.choplab.ui.CEStepPatternsDialog
import com.choplab.ui.ContinuousEditorAction
import com.choplab.core.model.Pattern
import com.choplab.core.model.frozenListOf
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import kotlin.test.*

class StepPatternPanelTest {
    @Test fun headerFooterAndEscapeUseTheSameDraftAndQueueCloseConfirmation() = runBlocking {
        for (dirty in listOf(false, true)) for (entrance in listOf("ce-step-patterns-close", "pattern-close", "escape")) {
            val f = PatternTestFixture()
            var closed = false
            val scene = ImageComposeScene(width = 1200, height = 1000, coroutineContext = coroutineContext) {
                MaterialTheme { CEStepPatternsDialog(f.controller) { if (it == ContinuousEditorAction.CloseStepPatterns) closed = true } }
            }
            try {
                scene.settle()
                assertTrue(f.action(PatternAction.Queue)); assertTrue(f.action(PatternAction.FirstBar(3)))
                if (dirty) { assertTrue(f.action(PatternAction.Name("draft"))); assertTrue(f.action(PatternAction.Toggle(0))) }
                scene.settle()
                val before = f.controller.state.value
                if (entrance == "escape") {
                    scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyDown)); scene.sendKeyEvent(KeyEvent(Key.Escape, KeyEventType.KeyUp)); scene.settle()
                } else scene.click(entrance)
                assertFalse(closed); assertTrue(f.controller.state.value.closeConfirmation, "dirty=$dirty close=$entrance")
                scene.click("pattern-close-keep"); assertEquals(before, f.controller.state.value)
                scene.click("ce-step-patterns-close"); scene.click("pattern-close-discard")
                assertTrue(closed); assertEquals(PatternPhase.CLOSED, f.controller.state.value.phase)
                assertEquals(0L, f.document.value.revision); assertTrue(f.edits.isEmpty())
            } finally { scene.close(); f.close() }
        }
    }
    @Test fun queuedRangeShowsTheInclusiveLastBarForOneAndSeveralRepeatedPatterns() = runBlocking {
        val previous = Locale.getDefault()
        try { for (locale in listOf(Locale.JAPAN, Locale.US)) {
            Locale.setDefault(locale)
            val f = PatternTestFixture(PatternTestFixture.project().copy(patterns =
                frozenListOf(Pattern("pattern-1"), Pattern("pattern-2", bars = 3))))
            val scene = ImageComposeScene(width = 1200, height = 1000, coroutineContext = coroutineContext) {
                MaterialTheme { StepPatternPanel(f.controller, {}) }
            }
            try {
                scene.settle(); assertTrue(f.action(PatternAction.Queue)); scene.settle()
                assertEquals(1, f.controller.state.value.lastQueuedBar)
                assertTrue(f.action(PatternAction.FirstBar(3))); assertTrue(f.action(PatternAction.Repeats(4)))
                assertTrue(f.action(PatternAction.Select("pattern-2"))); assertTrue(f.action(PatternAction.Queue)); scene.settle()
                assertEquals(15, f.controller.state.value.lastQueuedBar)
                val label = scene.tag("pattern-queue-range")!!.config[SemanticsProperties.Text].joinToString { it.text }
                assertTrue(label.contains("3")); assertTrue(label.contains("15"))
                assertTrue(f.edits.isEmpty())
            } finally { scene.close(); f.close() }
        } } finally { Locale.setDefault(previous) }
    }
    @Test fun jaEnDesktopAndPhoneLargeTextCanEditTheLastTripletStepSavePlaceAndClose() = runBlocking<Unit> {
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
                    scene.reach("pattern-grid-160", width, height)
                    scene.click("pattern-grid-160")
                    scene.click("pattern-columns-64")
                    scene.click("pattern-page-next")
                    scene.click("pattern-page-next")
                    assertEquals(8, fixture.controller.state.value.draft.bars)
                    assertEquals(192, fixture.controller.state.value.steps)
                    assertEquals(2, fixture.controller.state.value.page)
                    scene.reach("pattern-grid", width, height, target = false)
                    scene.tag("pattern-grid")!!.config[SemanticsActions.ScrollBy].action!!.invoke(100_000f, 0f)
                    scene.settle()
                    val last = scene.tag("pattern-step-191")!!.boundsInRoot
                    assertTrue(last.height >= 48 && last.width >= 48 && last.left >= 0 && last.right <= width)
                    scene.capture("triplet-last-${locale.language}-${width}-font${(font * 100).toInt()}.png")
                    scene.click("pattern-step-191")
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
                    assertEquals(30_560L, fixture.document.value.project.clips.single().startTick)
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
