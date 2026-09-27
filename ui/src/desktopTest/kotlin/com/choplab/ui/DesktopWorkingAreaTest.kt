@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import kotlin.test.*

/** The installed Mac launcher was clamped to 1440×870 outer / 1440×838 content, measured on its bundled JRE. */
class DesktopWorkingAreaTest {
    @Test fun allSixteenPadsAndControlsFitTheMeasuredInitialContentWithoutScrolling() = runBlocking<Unit> {
        checkEditor(font = 1f, initiallyVisible = true)
    }

    @Test fun largeTextKeepsEveryPadReachableAndTransportFixedInTheMeasuredContent() = runBlocking<Unit> {
        checkEditor(font = 2f, initiallyVisible = false)
    }

    private suspend fun checkEditor(font: Float, initiallyVisible: Boolean) {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPAN, Locale.US)) {
                Locale.setDefault(locale)
                val actions = mutableListOf<ContinuousEditorAction>()
                val state = mutableStateOf(ContinuousEditorFixture.state().copy(canUndo = true,
                    capabilities = ContinuousCapability.entries.toSet()))
                val scene = ImageComposeScene(width = WIDTH, height = HEIGHT, density = Density(1f, font),
                    coroutineContext = kotlin.coroutines.coroutineContext) {
                    ContinuousEditor(state.value, { action ->
                        actions += action
                        if (action is ContinuousEditorAction.SelectPad) state.value = state.value.copy(selectedPadId = action.padId)
                    }, ContinuousEditorFixture::readout)
                }
                try {
                    scene.settle()
                    scene.capture("${locale.language}-font${(font * 100).toInt()}-initial.png")
                    val scroll = scene.tag("ce-pads-pane").config[SemanticsProperties.VerticalScrollAxisRange]
                    if (initiallyVisible) {
                        assertEquals(0f, scroll.value())
                        for (id in 0..15) {
                            scene.fullHit("ce-pad-$id", 64)
                            val bounds = scene.tag("ce-pad-$id").boundsInRoot
                            assertEquals(bounds.width, bounds.height, .5f)
                        }
                        for (tag in listOf("ce-original-play", "ce-source-monitor", "ce-undo", "ce-stop-all", "ce-song-stop")) scene.fullHit(tag)
                    }
                    val fixedStop = scene.tag("ce-stop-all").boundsInRoot
                    val fixedSongStop = scene.tag("ce-song-stop").boundsInRoot
                    scene.fullHit("ce-stop-all"); scene.fullHit("ce-song-stop"); scene.fullHit("ce-undo")
                    for (id in 0..15) {
                        if (!initiallyVisible) scene.reach("ce-pad-$id")
                        scene.fullHit("ce-pad-$id", 64)
                        val before = actions.size
                        scene.click("ce-pad-$id")
                        assertEquals(listOf(ContinuousEditorAction.SelectPad(id)) +
                            if (id < 4) listOf(ContinuousEditorAction.TapPad(id)) else emptyList(), actions.drop(before))
                        if (initiallyVisible) assertEquals(0f, scroll.value())
                        assertEquals(fixedStop, scene.tag("ce-stop-all").boundsInRoot)
                        assertEquals(fixedSongStop, scene.tag("ce-song-stop").boundsInRoot)
                    }
                    // Footer actions may scroll in this shorter native content area; the instrument stays complete above them.
                    for (tag in listOf("ce-pad-details", "ce-bank-0", "ce-add-drums", "ce-record-hits", "ce-record-voice",
                        "ce-scratch", "ce-lyrics-open")) { scene.reach(tag); scene.fullHit(tag) }
                    for ((tag, expected) in listOf("ce-original-play" to ContinuousEditorAction.PlayOriginal,
                        "ce-undo" to ContinuousEditorAction.Undo, "ce-song-stop" to ContinuousEditorAction.StopSong,
                        "ce-stop-all" to ContinuousEditorAction.StopAll)) {
                        scene.reach(tag); scene.fullHit(tag); scene.click(tag)
                        assertEquals(expected, actions.last())
                    }
                    scene.reach("ce-source-monitor"); scene.fullHit("ce-source-monitor"); scene.click("ce-source-monitor")
                    val gain = assertIs<ContinuousEditorAction.SetOriginalMonitorGain>(actions.last())
                    assertTrue(gain.gain in .1f.. .9f, "A pointer on the slider changes the actual monitoring level")
                    scene.capture("${locale.language}-font${(font * 100).toInt()}-checked.png")
                } finally { scene.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(value: String) = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == value }
    private fun ImageComposeScene.fullHit(value: String, minimum: Int = 48) {
        val node = tag(value)
        val bounds = node.boundsInRoot
        assertTrue(node.size.width >= minimum && node.size.height >= minimum, "$value target: ${node.size}")
        assertEquals(node.size.width.toFloat(), bounds.width, .5f, "$value width")
        assertEquals(node.size.height.toFloat(), bounds.height, .5f, "$value height")
        assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= WIDTH && bounds.bottom <= HEIGHT, "$value clipped: $bounds")
    }
    private suspend fun ImageComposeScene.settle() { repeat(8) { render(System.nanoTime()).close(); delay(12) } }
    private suspend fun ImageComposeScene.reach(value: String) {
        fun SemanticsNode.contains(): Boolean = config.getOrNull(SemanticsProperties.TestTag) == value || children.any { it.contains() }
        repeat(3) {
            for (ancestor in nodes().filter { it.contains() && it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null }) {
                val node = tag(value)
                val area = ancestor.boundsInRoot
                if (area.width <= 0 || area.height <= 0) continue
                val x = node.positionInRoot.x
                val y = node.positionInRoot.y
                val dx = if (ancestor.config.contains(SemanticsProperties.HorizontalScrollAxisRange) &&
                    (x < area.left || x + node.size.width > area.right)) x - area.left else 0f
                val dy = if (ancestor.config.contains(SemanticsProperties.VerticalScrollAxisRange) &&
                    (y < area.top || y + node.size.height > area.bottom)) y - area.top else 0f
                if (dx != 0f || dy != 0f) {
                    ancestor.config[SemanticsActions.ScrollBy].action!!.invoke(dx, dy)
                    var previous: Pair<Float?, Float?>? = null
                    for (frame in 0..12) {
                        settle()
                        val position = ancestor.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.value?.invoke() to
                            ancestor.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()
                        if (position == previous) break
                        previous = position
                    }
                }
            }
        }
    }
    private suspend fun ImageComposeScene.click(value: String) {
        val center = tag(value).boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, center, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close()
        sendPointerEvent(PointerEventType.Release, center, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
    }
    private fun ImageComposeScene.capture(name: String) {
        val output = File(System.getProperty("choplab.ui.evidenceDir"), "desktop-working-area").apply { mkdirs() }
        render(System.nanoTime()).use { image -> image.encodeToData()!!.use { File(output, name).writeBytes(it.bytes) } }
    }
    private companion object { const val WIDTH = 1440; const val HEIGHT = 838 }
}
