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

class NoteRepeatUiTest {
    @Test fun normalBeatEntryReachesAllRatesAndKeepsStopAndCloseFixedInJaEnWideAndLargeText() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPAN, Locale.US)) for ((width, height, font) in listOf(
                Triple(1440, 1024, 1f), Triple(390, 844, 2f))) {
                Locale.setDefault(locale)
                var state by mutableStateOf(ContinuousEditorFixture.state().copy(stage = ContinuousStage.BEAT,
                    capabilities = ContinuousCapability.entries.toSet()))
                val actions = mutableListOf<ContinuousEditorAction>()
                val motion = object : MotionDurationScale { override val scaleFactor = 2f }
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext + motion) {
                    ContinuousEditor(state, { action -> actions += action
                        if (action is ContinuousEditorAction.SetNoteRepeat) state = state.copy(noteRepeat = action.rate)
                    }, ContinuousEditorFixture::readout)
                }
                try {
                    scene.settle(); scene.click("ce-pad-details", width, height); scene.click("ce-note-repeat", width, height)
                    val fixed = listOf("ce-note-repeat-stop", "ce-note-repeat-close").associateWith { scene.node(it).boundsInWindow }
                    fixed.keys.forEach { scene.hit(it, width, height) }
                    for (rate in ContinuousNoteRepeat.entries) {
                        val tag = "ce-note-repeat-${rate.name.lowercase()}"
                        scene.click(tag, width, height)
                        assertEquals(rate, state.noteRepeat)
                        assertTrue(scene.node(tag).config[SemanticsProperties.Selected])
                    }
                    fixed.forEach { (tag, bounds) -> assertEquals(bounds, scene.node(tag).boundsInWindow); scene.hit(tag, width, height) }
                    val evidence = File(System.getProperty("choplab.ui.evidenceDir"), "note-repeat").apply { mkdirs() }
                    scene.render(System.nanoTime()).use { bitmap -> bitmap.encodeToData()!!.use {
                        File(evidence, "${locale.language}-${width}-font${(font * 100).toInt()}.png").writeBytes(it.bytes)
                    } }
                    scene.click("ce-note-repeat-stop", width, height)
                    assertEquals(ContinuousEditorAction.StopAll, actions.last())
                    scene.click("ce-note-repeat-close", width, height)
                    withTimeout(5_000) { while (scene.semanticsOwners.size != 1) scene.settle() }
                    assertTrue(scene.nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == "ce-note-repeat-panel" })
                } finally { scene.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun heldPointerReleasesOnFingerUpAndDisposalAndKeyboardAndAccessibilitySendOneFiniteClick() = runBlocking<Unit> {
        var show by mutableStateOf(true)
        val actions = mutableListOf<ContinuousEditorAction>()
        val state = ContinuousEditorFixture.state().copy(noteRepeat = ContinuousNoteRepeat.SIXTEENTH,
            capabilities = ContinuousCapability.entries.toSet())
        var songFrame = 100L
        val scene = ImageComposeScene(width = 390, height = 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
            CETheme { if (show) CEPads(state, actions::add, hit = { songFrame }) }
        }
        try {
            scene.settle()
            val pad = state.pads.first { it.kind != ContinuousPadKind.EMPTY }.id
            val tag = "ce-pad-$pad"
            scene.hit(tag, 390, 844)
            var center = scene.node(tag).boundsInWindow.center
            scene.sendPointerEvent(PointerEventType.Press, center, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.settle()
            assertEquals(1, actions.count { it == ContinuousEditorAction.HoldPad(pad) })
            assertFalse(actions.any { it is ContinuousEditorAction.TapPad || it is ContinuousEditorAction.ReleasePad })
            songFrame = 12_100
            scene.sendPointerEvent(PointerEventType.Release, center, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
            scene.settle()
            assertEquals(1, actions.count { it == ContinuousEditorAction.ReleasePad(pad) })
            val started = actions.filterIsInstance<ContinuousEditorAction.BeginHit>().single().gesture
            assertEquals(pad, started.padId); assertEquals(100, started.songFrame)
            val ended = actions.filterIsInstance<ContinuousEditorAction.EndHit>().single()
            assertSame(started, ended.gesture); assertFalse(ended.cancelled); assertEquals(12_100, ended.songFrame)
            actions.clear()
            assertTrue(scene.node(tag).config[SemanticsActions.RequestFocus].action!!.invoke())
            for (key in listOf(Key.Enter, Key.Spacebar)) {
                repeat(3) { scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown)) }
                assertEquals(if (key == Key.Enter) 0 else 1, actions.count { it is ContinuousEditorAction.TapPad })
                scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp)); scene.settle()
            }
            assertEquals(2, actions.count { it == ContinuousEditorAction.TapPad(pad) })
            assertTrue(scene.node(tag).config[SemanticsActions.OnClick].action!!.invoke()); scene.settle()
            assertEquals(3, actions.count { it == ContinuousEditorAction.TapPad(pad) })
            assertFalse(actions.any { it is ContinuousEditorAction.HoldPad })
            actions.clear()
            center = scene.node(tag).boundsInWindow.center
            scene.sendPointerEvent(PointerEventType.Press, center, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            scene.settle(); show = false; scene.settle()
            assertEquals(1, actions.count { it == ContinuousEditorAction.ReleasePad(pad) })
            assertTrue(actions.filterIsInstance<ContinuousEditorAction.EndHit>().single().cancelled)
        } finally { scene.close() }
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
