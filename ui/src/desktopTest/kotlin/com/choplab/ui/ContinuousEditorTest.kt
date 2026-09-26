@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import java.io.File
import java.util.Locale
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

class ContinuousEditorTest {
    private val output = File(System.getProperty("choplab.ui.evidenceDir")).resolve("linked-ui").apply { mkdirs() }

    @Test fun renderExactReferenceStagesAndCompactLargeFonts() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        try {
            for (stage in ContinuousStage.entries) {
                val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
                    ContinuousEditor(ContinuousEditorFixture.state(stage), {}, ContinuousEditorFixture::readout)
                }
                try {
                    scene.settle()
                    assertNotNull(scene.tag("ce-stage-${stage.name}"))
                    if (stage == ContinuousStage.BEAT) {
                        listOf("ce-add-drums", "ce-record-voice", "ce-scratch").forEach { tag ->
                            val bounds = requireNotNull(scene.tag(tag)).boundsInRoot
                            assertTrue(bounds.height >= 48 && bounds.bottom <= 910f, "Reference bottom actions must remain visible: $tag $bounds")
                        }
                        assertTrue(requireNotNull(scene.tag("ce-song-seek")).boundsInRoot.width > 600, "Wide song transport must use the available width")
                        assertTrue(requireNotNull(scene.tag("ce-clip-scratch-1")).boundsInRoot.height >= 93.9f, "All four reference tracks must fit without clipping the last clip")
                    }
                    scene.capture("${stage.name.lowercase()}-desktop.png")
                }
                finally { scene.close() }
            }
            for (font in listOf(1f, 1.3f, 2f)) for (pane in ContinuousPane.entries) {
                val scene = ImageComposeScene(width = 390, height = 844, density = Density(1f, font), coroutineContext = coroutineContext) {
                    ContinuousEditor(ContinuousEditorFixture.state().copy(compactPane = pane), {}, ContinuousEditorFixture::readout)
                }
                try { scene.settle(); assertNotNull(scene.tag("ce-original-wave")); scene.capture("beat-phone-${pane.name.lowercase()}-font${(font * 100).toInt()}.png") }
                finally { scene.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun originalIdentityAndMonitoringRemainSeparateAcrossStagesAndPadSelection() = runBlocking<Unit> {
        val state = mutableStateOf(ContinuousEditorFixture.state(ContinuousStage.CAPTURE))
        val actions = mutableListOf<ContinuousEditorAction>()
        val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
            ContinuousEditor(state.value, { action ->
                actions += action
                if (action is ContinuousEditorAction.Navigate) state.value = state.value.copy(stage = action.stage)
                if (action is ContinuousEditorAction.SelectPad) state.value = state.value.copy(selectedPadId = action.padId)
            }, ContinuousEditorFixture::readout)
        }
        try {
            scene.settle()
            for (stage in listOf(ContinuousStage.CAPTURE, ContinuousStage.CHOP, ContinuousStage.BEAT)) {
                scene.click("ce-nav-${stage.name}")
                val wave = requireNotNull(scene.tag("ce-original-wave"))
                assertTrue(wave.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { "Warm Keys" in it })
            }
            scene.click("ce-pad-3")
            assertEquals("original-warm-keys", state.value.original?.id)
            assertTrue(requireNotNull(scene.tag("ce-original-wave")).config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { "Warm Keys" in it })
            requireNotNull(scene.tag("ce-source-monitor")!!.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(.5f)
            assertEquals(ContinuousEditorAction.SetOriginalMonitorGain(.5f), actions.last())
            requireNotNull(scene.tag("ce-song-monitor")!!.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(.4f)
            assertEquals(ContinuousEditorAction.SetSongMonitorGain(.4f), actions.last())
            assertFalse(actions.any { it is ContinuousEditorAction.PlacePad || it == ContinuousEditorAction.StopOriginal })
        } finally { scene.close() }
    }

    @Test fun dividerAndClipControlsEmitTypedRequestsWithoutMutatingProjection() = runBlocking<Unit> {
        val state = ContinuousEditorFixture.state()
        val actions = mutableListOf<ContinuousEditorAction>()
        val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) { ContinuousEditor(state, actions::add, ContinuousEditorFixture::readout) }
        try {
            scene.settle()
            requireNotNull(scene.tag("ce-divider")!!.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(.55f)
            assertEquals(ContinuousEditorAction.ResizePanes(.55f), actions.last())
            assertEquals(.41f, state.paneFraction)
            scene.click("ce-split")
            assertEquals(ContinuousEditorAction.SplitClip("warm-1", ContinuousEditorFixture.readout().songFrame), actions.last())
            scene.click("ce-duplicate")
            assertEquals(ContinuousEditorAction.DuplicateClip("warm-1"), actions.last())
            scene.click("ce-delete")
            assertEquals(ContinuousEditorAction.DeleteClip("warm-1"), actions.last())
            assertEquals(13, state.clips.size)
        } finally { scene.close() }
    }

    @Test fun clipPointerMoveTrimAndKeyboardRequestsAreReachable() = runBlocking<Unit> {
        val state = ContinuousEditorFixture.state()
        val actions = mutableListOf<ContinuousEditorAction>()
        val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) { ContinuousEditor(state, actions::add, ContinuousEditorFixture::readout) }
        try {
            scene.settle()
            val clip = requireNotNull(scene.tag("ce-clip-warm-1"))
            scene.drag(clip.boundsInRoot.center, Offset(50f, 0f))
            val moved = actions.filterIsInstance<ContinuousEditorAction.MoveClip>().last()
            assertEquals("warm-1", moved.clipId)
            assertTrue(moved.timelineStartFrame > state.selectedClip!!.timelineStartFrame)
            assertEquals(6L * 48_000, state.selectedClip!!.timelineStartFrame)
            val before = requireNotNull(scene.tag("ce-clip-warm-1")).boundsInRoot
            scene.drag(Offset(before.left + 3, before.center.y), Offset(25f, 0f))
            val trimmed = actions.filterIsInstance<ContinuousEditorAction.TrimClip>().last()
            assertTrue(trimmed.sourceStartFrame > 0 && trimmed.sourceEndFrame > trimmed.sourceStartFrame)
            val node = requireNotNull(scene.tag("ce-clip-warm-1"))
            assertTrue(requireNotNull(node.config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke())
            scene.sendKeyEvent(KeyEvent(Key.DirectionRight, KeyEventType.KeyDown))
            scene.sendKeyEvent(KeyEvent(Key.DirectionRight, KeyEventType.KeyUp))
            scene.settle()
            assertEquals(ContinuousEditorAction.MoveClip("warm-1", "melody", 7L * 48_000), actions.last())
        } finally { scene.close() }
    }

    @Test fun sliderDragsCommitOneDocumentEditWhenReleased() = runBlocking<Unit> {
        for ((stage, tag) in listOf(ContinuousStage.CHOP to "ce-source-range", ContinuousStage.BEAT to "ce-clip-gain")) {
            val actions = mutableListOf<ContinuousEditorAction>()
            val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
                ContinuousEditor(ContinuousEditorFixture.state(stage), actions::add, ContinuousEditorFixture::readout)
            }
            try {
                scene.settle()
                val bounds = requireNotNull(scene.tag(tag)) { tag }.boundsInRoot
                // Source range starts full width (thumb at the left edge); clip gain 1.0 of 0..2 sits mid-track.
                val thumb = if (tag == "ce-source-range") Offset(bounds.left + 12f, bounds.center.y) else bounds.center
                scene.drag(thumb, Offset(80f, 0f))
                val edits = actions.filter { it is ContinuousEditorAction.SetSourceRange || it is ContinuousEditorAction.SetClipGain }
                // One gesture is one Undo step; intermediate positions are previews, not document commits.
                assertEquals(1, edits.size, "$tag emitted $edits")
            } finally { scene.close() }
        }
    }

    @Test fun draggingAPadPlacesOnlyOnExplicitDrop() = runBlocking<Unit> {
        val actions = mutableListOf<ContinuousEditorAction>()
        val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) { ContinuousEditor(ContinuousEditorFixture.state(), actions::add, ContinuousEditorFixture::readout) }
        try {
            scene.settle()
            val start = requireNotNull(scene.tag("ce-pad-2")).boundsInRoot.center
            val destination = requireNotNull(scene.tag("ce-clip-voice-1")).boundsInRoot.center
            scene.drag(start, destination - start)
            val placed = actions.filterIsInstance<ContinuousEditorAction.PlacePad>().last()
            assertEquals(2, placed.padId)
            assertEquals("voice", placed.trackId)
            assertTrue(placed.timelineFrame >= 0)
        } finally { scene.close() }
    }

    @Test fun pausedSeekRefreshesReadoutAndUnavailableControlsStayDisabled() = runBlocking<Unit> {
        var clock = ContinuousEditorReadout()
        val refresh = mutableStateOf(0L)
        val state = ContinuousEditorFixture.state().copy(capabilities = emptySet())
        val actions = mutableListOf<ContinuousEditorAction>()
        val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) { ContinuousEditor(state, actions::add, { clock }, refresh.value) }
        try {
            scene.settle()
            scene.click("ce-original-play")
            assertTrue(actions.isEmpty())
            assertNotNull(scene.tag("ce-original-play")!!.config.getOrNull(SemanticsProperties.Disabled))
            assertFalse(scene.tag("ce-original-play")!!.config.getOrNull(SemanticsProperties.StateDescription).isNullOrBlank())
            clock = ContinuousEditorReadout(songFrame = 12L * 48_000)
            refresh.value++
            scene.settle()
            assertTrue(scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.any { "0:12 / 0:24" in it.text })
        } finally { scene.close() }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(value: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }
    private suspend fun ImageComposeScene.settle() { repeat(8) { render(System.nanoTime()).close(); delay(12) } }
    private suspend fun ImageComposeScene.click(tag: String) {
        val center = requireNotNull(tag(tag)) { tag }.boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, center, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close()
        sendPointerEvent(PointerEventType.Release, center, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
    }
    private suspend fun ImageComposeScene.drag(start: Offset, distance: Offset) {
        sendPointerEvent(PointerEventType.Press, start, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        repeat(5) { index ->
            sendPointerEvent(PointerEventType.Move, start + distance * ((index + 1) / 5f), type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true))
            render(System.nanoTime()).close(); delay(15)
        }
        sendPointerEvent(PointerEventType.Release, start + distance, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
    }
    private fun ImageComposeScene.capture(name: String) { render(System.nanoTime()).use { image -> requireNotNull(image.encodeToData()).use { File(output, name).writeBytes(it.bytes) } } }
}
