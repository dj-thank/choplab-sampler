@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.choplab.core.DocumentState
import com.choplab.core.model.*
import com.choplab.core.chop.*
import com.choplab.ui.chop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.*

class AccessibilityFollowupTest {
    @Test fun keyboardAndNamedTrackActionsPreserveAnOffGridStart() = runBlocking<Unit> {
        val base = ContinuousEditorFixture.state()
        val clip = base.selectedClip!!.copy(timelineStartFrame = 288_001)
        val state = base.copy(clips = listOf(clip))
        val actions = mutableListOf<ContinuousEditorAction>()
        val scene = ImageComposeScene(1440, 1024, Density(1f), coroutineContext = coroutineContext) {
            ContinuousEditor(state, actions::add, ContinuousEditorFixture::readout)
        }
        try {
            scene.settle()
            scene.node("ce-clip-${clip.id}").config[SemanticsActions.RequestFocus].action!!.invoke()
            scene.sendKeyEvent(KeyEvent(Key.DirectionDown, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.DirectionDown, KeyEventType.KeyUp)); scene.settle()
            assertEquals(ContinuousEditorAction.MoveClip(clip.id, "drums", 288_001), actions.last())
            val named = scene.node("ce-clip-${clip.id}").config[SemanticsActions.CustomActions].first { it.label.contains("ドラム") }
            assertTrue(named.action()); assertEquals(ContinuousEditorAction.MoveClip(clip.id, "drums", 288_001), actions.last())
            scene.invoke("ce-clip-track"); scene.invoke("ce-clip-track-voice")
            assertEquals(ContinuousEditorAction.MoveClip(clip.id, "voice", 288_001), actions.last())
            assertEquals(clip, state.clips.single())
        } finally { scene.close() }
    }

    @Test fun focusedClipAndDividerPaintVisibleRingsWithoutChangingSelection() = runBlocking<Unit> {
        val state = ContinuousEditorFixture.state()
        val scene = ImageComposeScene(1440, 1024, Density(1f), coroutineContext = coroutineContext) {
            ContinuousEditor(state, {}, ContinuousEditorFixture::readout)
        }
        fun pixelCount(tag: String, color: Int): Int {
            val b = scene.node(tag).boundsInWindow
            val bytes = scene.render(System.nanoTime()).use { it.encodeToData()!!.use { it.bytes } }
            val image = ImageIO.read(ByteArrayInputStream(bytes))
            return (b.top.toInt().coerceAtLeast(0) until b.bottom.toInt().coerceAtMost(image.height)).sumOf { y ->
                (b.left.toInt().coerceAtLeast(0) until b.right.toInt().coerceAtMost(image.width)).count { x -> image.getRGB(x, y) == color }
            }
        }
        try {
            scene.settle()
            for ((tag, color) in listOf("ce-clip-warm-1" to 0xFFEDE2C8.toInt(), "ce-divider" to 0xFF211D13.toInt())) {
                val before = pixelCount(tag, color)
                assertTrue(scene.node(tag).config[SemanticsActions.RequestFocus].action!!.invoke())
                scene.settle()
                assertTrue(scene.node(tag).config[SemanticsProperties.Focused])
                assertTrue(pixelCount(tag, color) > before + 20, "$tag must visibly paint a focus ring")
                scene.capture("focused-$tag.png")
            }
            assertEquals("warm-1", state.selectedClipId)
        } finally { scene.close() }
    }

    @Test fun choicesExposeSelectionWithoutMarkingPrimaryActionsSelected() = runBlocking<Unit> {
        val base = ContinuousEditorFixture.state()
        val scene = ImageComposeScene(390, 844, Density(1f), coroutineContext = coroutineContext) {
            ContinuousEditor(base, {}, ContinuousEditorFixture::readout)
        }
        try {
            scene.settle()
            assertEquals(base.compactPane == ContinuousPane.PADS, scene.node("ce-pane-pads").config[SemanticsProperties.Selected])
            assertEquals(base.compactPane == ContinuousPane.TIMELINE, scene.node("ce-pane-timeline").config[SemanticsProperties.Selected])
            assertFalse(scene.node("ce-stop-all").config.contains(SemanticsProperties.Selected))
            scene.capture("compact-selected.png")
        } finally { scene.close() }
    }

    @Test fun legalHighGainsStayMonotonicAndTheirLimitsDisableTheCorrectButton() = runBlocking<Unit> {
        val state = ContinuousEditorFixture.state()
        val value = mutableStateOf(4f)
        val changes = mutableListOf<Float>()
        val scene = ImageComposeScene(600, 300, Density(1f), coroutineContext = coroutineContext) {
            CETheme { Column {
                CEAdjustment("Gain", value.value, state, ContinuousCapability.PAD_GAIN, { changes += it; value.value = it }, maximum = 8f)
                CEValueSlider("Clip gain", value.value, state, ContinuousCapability.CLIP_GAIN, changes::add, range = 0f..8f, tag = "gain")
            } }
        }
        fun sign(label: String) = scene.nodes().first { it.config.getOrNull(SemanticsActions.OnClick) != null &&
            it.children.any { child -> child.config.getOrNull(SemanticsProperties.Text)?.any { t -> t.text == label } == true } }
        try {
            scene.settle()
            assertEquals(4f, scene.node("gain").config[SemanticsProperties.ProgressBarRangeInfo].current)
            assertEquals(0f..8f, scene.node("gain").config[SemanticsProperties.ProgressBarRangeInfo].range)
            sign("+").config[SemanticsActions.OnClick].action!!.invoke(); scene.settle()
            assertEquals(4.05f, changes.single())
            sign("−").config[SemanticsActions.OnClick].action!!.invoke(); scene.settle()
            assertEquals(4f, changes.last())
            value.value = 8f; scene.settle(); assertTrue(sign("+").config.contains(SemanticsProperties.Disabled))
            value.value = 0f; scene.settle(); assertTrue(sign("−").config.contains(SemanticsProperties.Disabled))
        } finally { scene.close() }
    }

    @Test fun chopEmergencyStopIsFullyVisibleWithoutScrollingInBothLanguagesAndOrientations() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        val asset = Asset("a".repeat(64), "wav", 100, 48_000, 2, 48_000, "source")
        val document = MutableStateFlow(DocumentState(Project(assets = frozenListOf(asset), source = Source(asset.hash, FrameRange(0, 48_000))), 7))
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = AutoChopController(document, MutableStateFlow(AutoChopAvailability.EDITABLE), null, object : AutoChopActions {
            override suspend fun preview(asset: Asset, range: FrameRange, revision: Long): AutoChopProblem? = null
            override suspend fun stopPreview() = true
            override fun requestStopPreview() { }
            override suspend fun apply(source: Source, markers: FrozenList<Long>, revision: Long): AutoChopProblem? = null
        }, owner)
        try {
            for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) for ((width, height) in listOf(390 to 844, 844 to 390)) for (font in listOf(1f, 2f)) for (auto in listOf(false, true)) {
                Locale.setDefault(locale)
                var stopped = 0
                val scene = ImageComposeScene(width, height, Density(1f, font), coroutineContext = coroutineContext) {
                    CETheme {
                        if (auto) CEAutoChopDialog(controller, {}, { stopped++ })
                        else CELiveChopTimingDialog(LiveChopTimingState(open = true, correction = LiveChopCorrection(LiveChopTimingMode.MANUAL, 15))) {
                            if (it == ContinuousEditorAction.StopAll) stopped++
                        }
                    }
                }
                try {
                    scene.settle()
                    val tag = if (auto) "ce-auto-stop-all" else "ce-live-timing-stop-all"
                    val node = scene.node(tag); val b = node.boundsInWindow
                    val context = "${locale.language} $width×$height font=$font $tag"
                    assertEquals(node.size.height.toFloat(), b.height, .5f, context)
                    assertEquals(node.size.width.toFloat(), b.width, .5f, context)
                    assertTrue(b.width >= 48 && b.height >= 48 && b.left >= 0 && b.top >= 0 && b.right <= width && b.bottom <= height, "$context $b")
                    // A real pointer click must work at the visible location, without a scroll or semantics shortcut.
                    scene.sendPointerEvent(PointerEventType.Press, b.center, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                    scene.render(System.nanoTime()).close()
                    scene.sendPointerEvent(PointerEventType.Release, b.center, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
                    scene.settle(); assertEquals(1, stopped, context)
                    if (font == 2f) scene.capture("stop-${locale.language}-$width-$auto.png")
                } finally { scene.close() }
            }
        } finally { owner.cancel(); Locale.setDefault(previous) }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.node(tag: String) = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private suspend fun ImageComposeScene.settle() { repeat(8) { render(System.nanoTime()).close(); delay(12) } }
    private suspend fun ImageComposeScene.invoke(tag: String) { assertTrue(node(tag).config[SemanticsActions.OnClick].action!!.invoke()); settle() }
    private fun ImageComposeScene.capture(name: String) {
        val out = File(System.getProperty("choplab.ui.evidenceDir"), "ux-followup").apply { mkdirs() }
        render(System.nanoTime()).use { it.encodeToData()!!.use { data -> File(out, name).writeBytes(data.bytes) } }
    }
}
