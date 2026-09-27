@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.ui.mixer

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.choplab.core.Action
import com.choplab.ui.BankPadEditorHarness
import com.choplab.ui.CETheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Locale
import kotlin.test.*

class ContinuousMixerPanelTest {
    private val output = File(System.getProperty("choplab.ui.evidenceDir")).resolve("mixer").apply { mkdirs() }

    @Test fun realInputsAndFixedStopCancelApplyRemainReachableInBothLanguagesAndLargeText() = runBlocking {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) for ((width, height, scale) in listOf(
                Triple(1440, 1024, 1f), Triple(390, 844, 2f), Triple(844, 390, 2f))) {
                Locale.setDefault(locale)
                val h = BankPadEditorHarness()
                val controller = MixerEditorController(h.studio) { intent, revision -> h.studio.dispatch(Action.Edit(intent, revision)).accepted }
                var stops = 0
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, scale), coroutineContext = coroutineContext) {
                    val state by controller.view.collectAsState()
                    CETheme {
                        CEMixerButton(true, { h.scope.launch { controller.dispatch(MixerAction.Open()) } })
                        CEMixerPanel(state, { action -> h.scope.launch { controller.dispatch(action) } }, null, { stops++ })
                    }
                }
                try {
                    val key = "${locale.language}-${width}x$height-font${(scale * 100).toInt()}"
                    scene.settle(); scene.click("ce-mixer-open")
                    scene.setText("gain", "70")
                    scene.setText("pan", "-30")
                    scene.setText("low_db", "2")
                    scene.reach("ce-mixer-filter-low_pass"); scene.fullHit("ce-mixer-filter-low_pass", width, height)
                    assertTrue(scene.tag("ce-mixer-filter-low_pass").config[SemanticsProperties.ContentDescription].single().isNotBlank())
                    scene.click("ce-mixer-filter-low_pass")
                    scene.setText("cutoff", "1200")
                    scene.reach("ce-mixer-switch-compressor"); scene.fullHit("ce-mixer-switch-compressor", width, height)
                    assertTrue(scene.tag("ce-mixer-switch-compressor").config[SemanticsProperties.ContentDescription].single().isNotBlank())
                    scene.click("ce-mixer-switch-compressor")
                    scene.setText("threshold", "-20")
                    scene.setText("delay_send", "30")
                    scene.setText("reverb_send", "40")
                    for (tag in listOf("ce-mixer-cancel", "ce-mixer-apply", "ce-mixer-stop")) scene.fullHit(tag, width, height)
                    scene.capture("bank-$key.png")
                    scene.click("ce-mixer-stop"); assertEquals(1, stops)
                    assertEquals(0, h.studio.document.value.revision)
                    scene.click("ce-mixer-apply")
                    assertNull(controller.view.value.draft)
                    val route = h.studio.document.value.project.banks[0].trackId
                    val track = h.studio.document.value.project.tracks.single { it.id == route }
                    assertEquals(.7f, track.gain); assertEquals(-.3f, track.pan)
                    assertTrue(track.fx.insert.compressor.enabled); assertEquals(.4f, track.fx.reverbSend)
                    assertEquals(1, h.studio.document.value.revision)
                    controller.dispatch(MixerAction.Open(MixerTarget.Master)); scene.settle()
                    scene.reach("ce-mixer-switch-delay"); scene.click("ce-mixer-switch-delay")
                    scene.setText("delay_time", "450")
                    scene.setText("feedback", "45")
                    scene.reach("ce-mixer-switch-reverb"); scene.click("ce-mixer-switch-reverb")
                    scene.setText("reverb_decay", "2.5")
                    scene.setText("reverb_return", "80")
                    for (tag in listOf("ce-mixer-cancel", "ce-mixer-apply", "ce-mixer-stop")) scene.fullHit(tag, width, height)
                    scene.capture("master-$key.png")
                    scene.click("ce-mixer-cancel")
                    assertNull(controller.view.value.draft)
                    assertEquals(1, h.studio.document.value.revision)
                    assertFalse(h.studio.document.value.project.mix.delay.enabled)
                } finally { scene.close(); h.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun invalidDraftAndDocumentChangeCannotApplyButCanAlwaysCloseOrStop() = runBlocking {
        val h = BankPadEditorHarness()
        val controller = MixerEditorController(h.studio) { intent, revision -> h.studio.dispatch(Action.Edit(intent, revision)).accepted }
        var stops = 0
        val scene = ImageComposeScene(width = 320, height = 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
            val state by controller.view.collectAsState()
            CETheme { CEMixerPanel(state, { action -> h.scope.launch { controller.dispatch(action) } }, null, { stops++ }) }
        }
        try {
            controller.dispatch(MixerAction.Open()); scene.settle()
            scene.setText("gain", "NaN")
            assertNotNull(scene.tag("ce-mixer-apply").config.getOrNull(SemanticsProperties.Disabled))
            scene.click("ce-mixer-apply"); assertEquals(0, h.studio.document.value.revision)
            scene.setText("gain", "75")
            h.studio.dispatch(Action.Edit(com.choplab.core.edit.Intent.Rename("Changed")))
            controller.documentChanged(); scene.settle()
            assertNotNull(scene.tag("ce-mixer-apply").config.getOrNull(SemanticsProperties.Disabled))
            scene.fullHit("ce-mixer-stop", 320, 844); scene.click("ce-mixer-stop"); assertEquals(1, stops)
            scene.fullHit("ce-mixer-cancel", 320, 844); scene.click("ce-mixer-cancel")
            assertNull(controller.view.value.draft)
            assertEquals(h.initial.mix, h.studio.document.value.project.mix)
            assertNull(h.studio.document.value.project.banks[0].trackId)
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
        val scroll = tag("ce-mixer-fields")
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
        val value = "ce-mixer-$field"
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
