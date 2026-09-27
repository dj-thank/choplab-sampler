@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.layout.width
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.ByteArrayInputStream
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.*

class ContinuousBankPadEditorTest {
    private val output = File(System.getProperty("choplab.ui.evidenceDir")).resolve("bank-pad-editor").apply { mkdirs() }

    @Test fun normalBankDisplayUsesCommittedNameRoleAndColorThenFollowsUndo() = runBlocking {
        val h = BankPadEditorHarness()
        val scene = ImageComposeScene(width = 390, height = 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
            val document by h.studio.document.collectAsState()
            val bank = document.project.banks[0]
            CETheme { CEBankMetadataButton(bank.id, bank.name, bank.color, bank.role, true, {}, Modifier.width(300.dp)) }
        }
        fun color(): Int = scene.render(System.nanoTime()).use { image -> requireNotNull(image.encodeToData()).use { data ->
            val bounds = scene.tag("ce-bank-0").boundsInWindow
            ImageIO.read(ByteArrayInputStream(data.bytes)).getRGB(bounds.center.x.toInt(), bounds.bottom.toInt() - 4) and 0xffffff
        } }
        try {
            scene.settle()
            assertEquals(0x4477aa, color())
            h.controller.dispatch(BankPadEditAction.OpenBank)
            h.change(BankPadEditField.NAME, "太鼓")
            h.change(BankPadEditField.ROLE, "Rhythm")
            h.change(BankPadEditField.COLOR, "#FAAA20")
            scene.settle()
            assertEquals(0x4477aa, color(), "Draft colors do not repaint the normal BANK selector")
            assertFalse(scene.tag("ce-bank-0").config[SemanticsProperties.ContentDescription].single().contains("Rhythm"))
            assertTrue(h.controller.dispatch(BankPadEditAction.Apply))
            scene.settle()
            val description = scene.tag("ce-bank-0").config[SemanticsProperties.ContentDescription].single()
            assertTrue(description.contains("太鼓") && description.contains("Rhythm"))
            assertEquals(0xfaaa20, color())
            scene.fullHit("ce-bank-0", 390, 844)
            scene.capture("bank-committed-font200.png")
            assertTrue(h.studio.dispatch(com.choplab.core.Action.Undo).accepted)
            scene.settle()
            assertEquals(0x4477aa, color())
            assertFalse(scene.tag("ce-bank-0").config[SemanticsProperties.ContentDescription].single().contains("Rhythm"))
        } finally { scene.close(); h.close() }
    }

    @Test fun japaneseAndEnglishEditorsKeepRealInputsAndFixedActionsReachableOnWideAndCompactAtFontTwo() = runBlocking {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) for ((width, height, fontScale) in listOf(
                Triple(1440, 1024, 1f), Triple(390, 844, 2f), Triple(844, 390, 2f))) {
                Locale.setDefault(locale)
                val h = BankPadEditorHarness()
                var stops = 0
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, fontScale), coroutineContext = coroutineContext) {
                    val state by h.controller.view.collectAsState()
                    val send = { action: BankPadEditAction -> h.scope.launch { h.controller.dispatch(action) }; Unit }
                    CETheme {
                        CEBankPadEditButtons(ContinuousEditorFixture.state(ContinuousStage.BEAT), send, null)
                        CEBankPadEditor(state, send, null) { stops++ }
                    }
                }
                try {
                    val key = "${locale.language}-${width}x$height-font${(fontScale * 100).toInt()}"
                    scene.settle()
                    scene.click("ce-bank-edit")
                    assertNotNull(h.controller.view.value.draft)
                    scene.setText("name", if (locale == Locale.JAPANESE) "太鼓" else "Drums")
                    scene.setText("color", "#FAAA20")
                    scene.setText("role", if (locale == Locale.JAPANESE) "リズム" else "Rhythm")
                    for (tag in listOf("ce-bank-pad-cancel", "ce-bank-pad-apply", "ce-bank-pad-stop")) scene.fullHit(tag, width, height)
                    scene.capture("bank-$key.png")
                    scene.click("ce-bank-pad-stop")
                    assertEquals(1, stops)
                    assertEquals(0, h.studio.document.value.revision)
                    scene.click("ce-bank-pad-apply")
                    assertNull(h.controller.view.value.draft)
                    assertEquals(0xfaaa20, h.studio.document.value.project.banks[0].color)
                    assertEquals(1, h.studio.document.value.revision)

                    scene.click("ce-pad-sound-edit")
                    scene.setText("pan", "-75")
                    scene.setText("attack", "10")
                    scene.setText("decay", "200")
                    scene.setText("sustain", "60")
                    scene.setText("release", "20")
                    for (tag in listOf("ce-bank-pad-cancel", "ce-bank-pad-apply", "ce-bank-pad-stop")) scene.fullHit(tag, width, height)
                    scene.capture("pad-$key.png")
                    assertEquals(1, h.studio.document.value.revision, "Typing never commits partial values")
                    scene.click("ce-bank-pad-apply")
                    assertNull(h.controller.view.value.draft)
                    assertEquals(2, h.studio.document.value.revision)
                    val pad = h.studio.document.value.project.pads[0]
                    assertEquals(-.75f, pad.pan); assertEquals(480, pad.attackFrames)
                    assertEquals(9600, pad.decayFrames); assertEquals(.6f, pad.sustainLevel); assertEquals(960, pad.releaseFrames)

                    scene.click("ce-bank-edit")
                    scene.setText("name", "Cancelled")
                    scene.click("ce-bank-pad-cancel")
                    assertNull(h.controller.view.value.draft)
                    assertEquals(2, h.studio.document.value.revision)
                    assertNotEquals("Cancelled", h.studio.document.value.project.banks[0].name)
                } finally { scene.close(); h.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun invalidAndStaleDraftsDisableApplyAndRetainAnAccessibleCancel() = runBlocking {
        val h = BankPadEditorHarness()
        val scene = ImageComposeScene(width = 390, height = 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
            val state by h.controller.view.collectAsState()
            CETheme { CEBankPadEditor(state, { action -> h.scope.launch { h.controller.dispatch(action) } }, null) {} }
        }
        try {
            h.controller.dispatch(BankPadEditAction.OpenPad); scene.settle()
            scene.setText("pan", "Infinity")
            assertTrue(BankPadEditField.PAN in h.controller.view.value.invalidFields)
            assertNotNull(scene.tag("ce-bank-pad-apply").config.getOrNull(SemanticsProperties.Disabled))
            scene.click("ce-bank-pad-apply")
            assertEquals(0, h.studio.document.value.revision)
            scene.setText("pan", "50")
            assertNull(scene.tag("ce-bank-pad-apply").config.getOrNull(SemanticsProperties.Disabled))
            h.studio.dispatch(com.choplab.core.Action.SelectPad(1))
            scene.click("ce-bank-pad-apply")
            assertEquals(BankPadEditProblem.STALE, h.controller.view.value.problem)
            assertNotNull(scene.tag("ce-bank-pad-apply").config.getOrNull(SemanticsProperties.Disabled))
            scene.reach("ce-bank-pad-problem")
            assertTrue(scene.tag("ce-bank-pad-problem").config.getOrNull(SemanticsProperties.Text)?.isNotEmpty() == true)
            scene.capture("pad-stale-font200.png")
            scene.fullHit("ce-bank-pad-cancel", 390, 844)
            scene.click("ce-bank-pad-cancel")
            assertNull(h.controller.view.value.draft)
            assertEquals(0, h.studio.document.value.revision)
        } finally { scene.close(); h.close() }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(value: String) = requireNotNull(nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }) { value }
    private suspend fun ImageComposeScene.settle() { repeat(8) { render(System.nanoTime()).close(); delay(12) } }
    private fun ImageComposeScene.fullHit(value: String, width: Int, height: Int) {
        val node = tag(value)
        val bounds = node.boundsInWindow
        assertTrue(bounds.width >= node.size.width - 1 && bounds.height >= node.size.height - 1, "$value clipped: $bounds / ${node.size}")
        assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height, "$value outside window: $bounds")
        assertTrue(bounds.width >= 48 && bounds.height >= 48, "$value has a small actual hit area: $bounds")
    }
    private suspend fun ImageComposeScene.reach(value: String) {
        val scroll = tag("ce-bank-pad-fields")
        val target = tag(value)
        val dy = if (target.positionInRoot.y < scroll.boundsInRoot.top || target.positionInRoot.y + target.size.height > scroll.boundsInRoot.bottom)
            target.positionInRoot.y - scroll.boundsInRoot.top else 0f
        if (dy != 0f) {
            requireNotNull(scroll.config.getOrNull(SemanticsActions.ScrollBy)?.action)(0f, dy)
            val axis = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
            var previous = -1f
            for (i in 0..12) { settle(); val current = axis.value(); if (current == previous) break; previous = current }
        }
        val reached = tag(value)
        assertTrue(reached.boundsInWindow.width >= reached.size.width - 1 && reached.boundsInWindow.height >= reached.size.height - 1,
            "$value cannot be reached: ${reached.boundsInWindow} / ${reached.size}")
    }
    private suspend fun ImageComposeScene.setText(field: String, text: String) {
        val value = "ce-bank-pad-$field"
        reach(value)
        assertTrue(tag(value).boundsInWindow.height >= 48)
        assertTrue(requireNotNull(tag(value).config.getOrNull(SemanticsActions.SetText)?.action)(AnnotatedString(text)))
        settle()
        assertEquals(text, tag(value).config[SemanticsProperties.EditableText].text)
    }
    private suspend fun ImageComposeScene.click(value: String) {
        val center: Offset = tag(value).boundsInWindow.center
        sendPointerEvent(PointerEventType.Press, center, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close()
        sendPointerEvent(PointerEventType.Release, center, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
    }
    private fun ImageComposeScene.capture(name: String) = render(System.nanoTime()).use { image ->
        requireNotNull(image.encodeToData()).use { File(output, name).writeBytes(it.bytes) }
    }
}
