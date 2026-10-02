@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.ui

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import kotlin.test.*

class LoopOverdubUiTest {
    @Test fun allEightBarLengthsRequireStartAndFinishDiscardStayReachableAtWideAndLargeText() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPAN, Locale.US)) for ((width, height, font) in listOf(
                Triple(1440, 838, 1f), Triple(390, 844, 2f))) {
                Locale.setDefault(locale)
                var state by mutableStateOf(ContinuousEditorFixture.state().copy(stage = ContinuousStage.BEAT,
                    capabilities = ContinuousCapability.entries.toSet()))
                val actions = mutableListOf<ContinuousEditorAction>()
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                    ContinuousEditor(state, { action -> actions += action
                        when (action) {
                            is ContinuousEditorAction.RecordLoopOverdub -> state = state.copy(loopOverdubBars = action.bars, recordingHits = true)
                            ContinuousEditorAction.CancelLoopOverdub -> state = state.copy(loopOverdubBars = 0, recordingHits = false)
                            else -> Unit
                        }
                    }, ContinuousEditorFixture::readout)
                }
                try {
                    scene.settle(); scene.click("ce-pad-details", width, height); scene.click("ce-overdub", width, height)
                    val fixed = listOf("ce-overdub-start", "ce-overdub-close").associateWith { scene.node(it).boundsInWindow }
                    for (bars in 1..8) {
                        scene.click("ce-overdub-bars-$bars", width, height)
                        assertTrue(scene.node("ce-overdub-bars-$bars").config[SemanticsProperties.Selected])
                        assertTrue(actions.none { it is ContinuousEditorAction.RecordLoopOverdub })
                    }
                    fixed.forEach { (tag, bounds) -> assertEquals(bounds, scene.node(tag).boundsInWindow); scene.hit(tag, width, height) }
                    val evidence = File(System.getProperty("choplab.ui.evidenceDir"), "loop-overdub").apply { mkdirs() }
                    scene.render(System.nanoTime()).use { bitmap -> bitmap.encodeToData()!!.use {
                        File(evidence, "${locale.language}-${width}-font${(font * 100).toInt()}.png").writeBytes(it.bytes)
                    } }
                    scene.click("ce-overdub-start", width, height)
                    assertEquals(ContinuousEditorAction.RecordLoopOverdub(8), actions.last())
                    withTimeout(5_000) { while (scene.semanticsOwners.size != 1) scene.settle() }
                    scene.click("ce-pad-details", width, height)
                    scene.click("ce-record-hits", width, height)
                    assertEquals(ContinuousEditorAction.StopHits, actions.last())
                    scene.click("ce-overdub-discard", width, height)
                    assertEquals(ContinuousEditorAction.CancelLoopOverdub, actions.last())
                    assertEquals(0, state.loopOverdubBars)
                } finally { scene.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.node(tag: String) = requireNotNull(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == tag }) { tag }
    private suspend fun ImageComposeScene.settle() { repeat(6) { render(System.nanoTime()).close(); delay(12) } }
    private suspend fun ImageComposeScene.reach(tag: String) {
        fun SemanticsNode.contains(): Boolean = config.getOrNull(SemanticsProperties.TestTag) == tag || children.any { it.contains() }
        repeat(8) {
            val current = node(tag)
            if (current.boundsInWindow.width >= current.size.width - 1 && current.boundsInWindow.height >= current.size.height - 1) return
            for (ancestor in nodes().filter { it.contains() && it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null }) {
                val target = node(tag); val rect = ancestor.boundsInRoot
                if (rect.width <= 0 || rect.height <= 0) continue
                val x = target.positionInRoot.x; val y = target.positionInRoot.y
                val horizontal = ancestor.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
                val vertical = ancestor.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                val dx = if (horizontal != null && (x < rect.left || x + target.size.width > rect.right)) x - rect.left else 0f
                val dy = if (vertical != null && (y < rect.top || y + target.size.height > rect.bottom)) y - rect.top else 0f
                if (dx != 0f || dy != 0f) {
                    assertTrue(ancestor.config[SemanticsActions.ScrollBy].action!!(dx, dy))
                    var previous: Pair<Float?, Float?>? = null
                    for (frame in 0..20) { settle(); val position = horizontal?.value?.invoke() to vertical?.value?.invoke(); if (position == previous) break; previous = position }
                }
            }
            settle()
        }
    }
    private fun ImageComposeScene.hit(tag: String, width: Int, height: Int) {
        val node = node(tag); val rect = node.boundsInWindow
        assertTrue(rect.width >= 48 && rect.height >= 48, "$tag below 48dp: $rect")
        assertEquals(node.size.width.toFloat(), rect.width, 1f, "$tag clipped width")
        assertEquals(node.size.height.toFloat(), rect.height, 1f, "$tag clipped height")
        assertTrue(rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height, "$tag offscreen: $rect")
    }
    private suspend fun ImageComposeScene.click(tag: String, width: Int, height: Int) {
        reach(tag); hit(tag, width, height)
        val center = node(tag).boundsInWindow.center
        sendPointerEvent(PointerEventType.Press, center, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close()
        assertEquals(center, node(tag).boundsInWindow.center, "$tag moved during the press")
        sendPointerEvent(PointerEventType.Release, center, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
    }
}
