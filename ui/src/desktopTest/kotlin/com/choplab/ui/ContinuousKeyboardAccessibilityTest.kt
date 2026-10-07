@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.choplab.core.chop.LiveChopOutput
import com.choplab.core.chop.LiveChopRoute
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.*

/** Real Compose key routing, with synthetic state/action collectors and no audio device. */
class ContinuousKeyboardAccessibilityTest {
    @Test fun liveChopUsesKeyDownPositionOnceAndFocusLossDiscardsIt() = runBlocking {
        val actions = mutableListOf<ContinuousEditorAction>()
        val route = LiveChopRoute(Any(), Any(), 0, 48_000, 2, true, 480, 480)
        val pass = Any()
        var frame = 100L
        var coherent = true
        fun capture() = if (coherent) ContinuousChopGesture(pass, 4, LiveChopOutput(route, frame, frame, frame, true, 0)) else null
        val scene = ImageComposeScene(width = 600, height = 600, coroutineContext = coroutineContext) {
            CETheme { CEPads(ContinuousEditorFixture.state(), actions::add, capture = ::capture) }
        }
        try {
            scene.focus("ce-pad-1")
            scene.key(Key.Enter, KeyEventType.KeyDown)
            frame = 400
            scene.key(Key.Enter, KeyEventType.KeyDown)
            scene.key(Key.Enter, KeyEventType.KeyUp)
            assertEquals(1, actions.size)
            assertEquals(100, (actions.single() as ContinuousEditorAction.CapturePad).gesture.output.sourceFrame)
            scene.key(Key.Spacebar, KeyEventType.KeyDown)
            scene.focus("ce-pad-2")
            scene.key(Key.Spacebar, KeyEventType.KeyUp)
            assertEquals(1, actions.size, "A key released on another PAD must not cut")
            coherent = false
            scene.key(Key.Enter, KeyEventType.KeyDown); coherent = true
            scene.key(Key.Enter, KeyEventType.KeyUp)
            assertEquals(1, actions.size, "No release-time replacement for an incoherent press")
        } finally { scene.close() }
    }

    @Test fun gateHitReleasesOnceOnKeyUpFocusLossAndCapabilityChange() = runBlocking {
        val actions = mutableListOf<ContinuousEditorAction>()
        val state = mutableStateOf(ContinuousEditorFixture.state().let { it.copy(
            pads = it.pads.map { p -> p.copy(mode = ContinuousPadMode.GATE) }) })
        var frame = 100L
        val scene = ImageComposeScene(width = 600, height = 600, coroutineContext = coroutineContext) {
            CETheme { CEPads(state.value, actions::add, hit = { frame }) }
        }
        try {
            scene.focus("ce-pad-1")
            repeat(3) { scene.key(Key.Enter, KeyEventType.KeyDown) }
            assertEquals(1, actions.filterIsInstance<ContinuousEditorAction.BeginHit>().size)
            assertEquals(1, actions.filterIsInstance<ContinuousEditorAction.HoldPad>().size)
            frame = 600
            scene.key(Key.Enter, KeyEventType.KeyUp)
            val end = actions.filterIsInstance<ContinuousEditorAction.EndHit>().single()
            assertFalse(end.cancelled); assertEquals(600, end.songFrame)
            assertEquals(1, actions.filterIsInstance<ContinuousEditorAction.ReleasePad>().size)
            actions.clear()
            scene.key(Key.Spacebar, KeyEventType.KeyDown)
            scene.focus("ce-pad-2")
            scene.key(Key.Spacebar, KeyEventType.KeyUp)
            assertTrue(actions.filterIsInstance<ContinuousEditorAction.EndHit>().single().cancelled)
            assertEquals(1, actions.filterIsInstance<ContinuousEditorAction.ReleasePad>().size)
            actions.clear()
            scene.key(Key.Enter, KeyEventType.KeyDown)
            state.value = state.value.copy(capabilities = emptySet())
            scene.settle(); scene.key(Key.Enter, KeyEventType.KeyUp)
            assertTrue(actions.filterIsInstance<ContinuousEditorAction.EndHit>().single().cancelled)
            assertEquals(1, actions.filterIsInstance<ContinuousEditorAction.ReleasePad>().size)
            val size = actions.size
            scene.key(Key.Spacebar, KeyEventType.KeyDown); scene.key(Key.Spacebar, KeyEventType.KeyUp)
            assertEquals(size, actions.size)
        } finally { scene.close() }
    }

    @Test fun noteRepeatKeyRemainsOneFiniteBeatAndCancelsBeforeRelease() = runBlocking {
        val actions = mutableListOf<ContinuousEditorAction>()
        val scene = ImageComposeScene(width = 600, height = 600, coroutineContext = coroutineContext) {
            CETheme { CEPads(ContinuousEditorFixture.state().copy(noteRepeat = ContinuousNoteRepeat.entries.first { it != ContinuousNoteRepeat.OFF }),
                actions::add, hit = { 200L }) }
        }
        try {
            scene.focus("ce-pad-1")
            repeat(3) { scene.key(Key.Spacebar, KeyEventType.KeyDown) }
            assertTrue(actions.isEmpty())
            scene.key(Key.Spacebar, KeyEventType.KeyUp)
            assertEquals(1, actions.filterIsInstance<ContinuousEditorAction.BeginHit>().size)
            assertEquals(listOf(ContinuousEditorAction.TapPad(1)), actions.filterIsInstance<ContinuousEditorAction.TapPad>())
            scene.key(Key.Enter, KeyEventType.KeyDown); scene.focus("ce-pad-2"); scene.key(Key.Enter, KeyEventType.KeyUp)
            assertEquals(2, actions.size)
        } finally { scene.close() }
    }

    @Test fun waveformKeyboardAndProgressClampAndDisappearWhenReadOnly() = runBlocking {
        val fraction = mutableStateOf(.5f)
        val enabled = mutableStateOf(true)
        val values = mutableListOf<Float>()
        val scene = ImageComposeScene(width = 500, height = 200, coroutineContext = coroutineContext) {
            CETheme { CEWaveform(emptyList(), Modifier.fillMaxWidth().height(150.dp), "Original position", { fraction.value },
                onSeek = if (enabled.value) ({ value -> values += value; fraction.value = value }) else null, tag = "wave") }
        }
        try {
            scene.focus("wave")
            scene.key(Key.DirectionRight, KeyEventType.KeyDown); scene.key(Key.DirectionRight, KeyEventType.KeyUp)
            assertEquals(.51f, fraction.value, .0001f)
            scene.key(Key.MoveHome, KeyEventType.KeyDown)
            scene.key(Key.DirectionLeft, KeyEventType.KeyDown)
            assertEquals(0f, fraction.value)
            scene.key(Key.MoveEnd, KeyEventType.KeyDown)
            scene.key(Key.DirectionRight, KeyEventType.KeyDown)
            assertEquals(1f, fraction.value)
            val change = requireNotNull(scene.tag("wave").config[SemanticsActions.SetProgress].action)
            assertFalse(change(Float.NaN)); assertFalse(change(Float.POSITIVE_INFINITY))
            assertTrue(change(-2f)); assertEquals(0f, fraction.value)
            enabled.value = false; scene.settle()
            assertNull(scene.tag("wave").config.getOrNull(SemanticsActions.SetProgress))
            val size = values.size
            scene.key(Key.DirectionRight, KeyEventType.KeyDown)
            assertEquals(size, values.size)
        } finally { scene.close() }
    }

    @Test fun scratchArrowKeysOnlyNudgeHandAndRespectAvailability() = runBlocking {
        val actions = mutableListOf<ContinuousEditorAction>()
        val state = mutableStateOf(ContinuousEditorFixture.state().copy(originalPlaying = true,
            capabilities = ContinuousCapability.entries.toSet(),
            scratch = ContinuousScratch(ContinuousScratchTarget.ORIGINAL, true, true)))
        val scene = ImageComposeScene(width = 1440, height = 1024, coroutineContext = coroutineContext) {
            CETheme { CEScratchPanel(state.value, actions::add, { ContinuousEditorReadout() }, 0) }
        }
        try {
            scene.focus("ce-scratch-platter")
            repeat(3) { scene.key(Key.DirectionLeft, KeyEventType.KeyDown) }
            scene.key(Key.DirectionLeft, KeyEventType.KeyUp)
            scene.key(Key.DirectionRight, KeyEventType.KeyDown); scene.key(Key.DirectionRight, KeyEventType.KeyUp)
            assertEquals(listOf<ContinuousEditorAction>(ContinuousEditorAction.ScratchNudge(false), ContinuousEditorAction.ScratchNudge(true)), actions)
            assertTrue(state.value.originalPlaying)
            state.value = state.value.copy(capabilities = emptySet()); scene.settle()
            scene.key(Key.DirectionLeft, KeyEventType.KeyDown)
            assertEquals(2, actions.size)
            assertNull(scene.tag("ce-scratch-platter").config.getOrNull(SemanticsActions.CustomActions))
        } finally { scene.close() }
    }

    private fun ImageComposeScene.tag(name: String): SemanticsNode {
        fun find(node: SemanticsNode): SemanticsNode? = if (node.config.getOrNull(SemanticsProperties.TestTag) == name) node
            else node.children.firstNotNullOfOrNull(::find)
        return requireNotNull(semanticsOwners.firstNotNullOfOrNull { find(it.unmergedRootSemanticsNode) }) { name }
    }
    private suspend fun ImageComposeScene.settle() { repeat(5) { render(System.nanoTime()).close(); delay(12) } }
    private suspend fun ImageComposeScene.focus(name: String) {
        settle(); assertTrue(requireNotNull(tag(name).config.getOrNull(SemanticsActions.RequestFocus)?.action).invoke(), name); settle()
    }
    private suspend fun ImageComposeScene.key(key: Key, type: KeyEventType) { sendKeyEvent(KeyEvent(key, type)); settle() }
}
