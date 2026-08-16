package com.choplab.sampler.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.choplab.sampler.model.PcmAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceWaveformDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun deterministicTwoPointerGesturesAndAccessibilityActionsChangeTheViewport() {
        setDeterministicWaveform()
        val waveform = waveformNode()

        val wholeSource = waveform.viewportDescription()
        assertEquals("全体表示。0から999フレーム", wholeSource)
        assertFalse(waveform.invokeCustomAction("前の範囲を表示"))
        assertFalse(waveform.invokeCustomAction("全体表示に戻す"))

        waveform.performTouchInput {
            pinch(
                start0 = center - Offset(width * 0.15f, 0f),
                end0 = center - Offset(width * 0.35f, 0f),
                start1 = center + Offset(width * 0.15f, 0f),
                end1 = center + Offset(width * 0.35f, 0f),
                durationMillis = 500,
            )
        }
        composeRule.waitForIdle()
        val zoomed = waveform.viewportDescription()
        assertTrue(zoomed?.startsWith("拡大表示。") == true)

        waveform.performTouchInput {
            val left = center - Offset(width * 0.20f, 0f)
            val right = center + Offset(width * 0.20f, 0f)
            down(0, left)
            down(1, right)
            updatePointerTo(0, left + Offset(width * 0.15f, 0f))
            updatePointerTo(1, right + Offset(width * 0.15f, 0f))
            move(250)
            up(0)
            up(1)
        }
        composeRule.waitForIdle()
        val panned = waveform.viewportDescription()
        assertNotEquals("Two-pointer pan should move the viewport", zoomed, panned)

        assertTrue(waveform.invokeCustomAction("前の範囲を表示"))
        composeRule.waitForIdle()
        val previous = waveform.viewportDescription()
        assertNotEquals(panned, previous)

        assertTrue(waveform.invokeCustomAction("次の範囲を表示"))
        composeRule.waitForIdle()
        assertNotEquals(previous, waveform.viewportDescription())

        assertTrue(waveform.invokeCustomAction("全体表示に戻す"))
        composeRule.waitForIdle()
        assertEquals(wholeSource, waveform.viewportDescription())
        assertFalse(waveform.invokeCustomAction("全体表示に戻す"))
    }

    @Test
    fun deterministicHandlesExposeUnclipped48DpTargetsAndExactlyReversibleActions() {
        setDeterministicWaveform()
        val waveformBounds = waveformNode().fetchSemanticsNode().boundsInRoot
        val handles = listOf(
            handleNode("選択開始ハンドル"),
            handleNode("選択終了ハンドル"),
            handleNode("チョップ1 の位置"),
        )
        val minimumTargetPx = 48f * composeRule.density.density

        handles.forEach { handle ->
            val bounds = handle.fetchSemanticsNode().boundsInRoot
            assertTrue("Handle width should be at least 48 dp", bounds.width >= minimumTargetPx - 1f)
            assertTrue("Handle height should be at least 48 dp", bounds.height >= minimumTargetPx - 1f)
            assertTrue("Handle should remain inside the waveform horizontally", bounds.left >= waveformBounds.left)
            assertTrue("Handle should remain inside the waveform horizontally", bounds.right <= waveformBounds.right)
            assertTrue("Handle should remain inside the waveform vertically", bounds.top >= waveformBounds.top)
            assertTrue("Handle should remain inside the waveform vertically", bounds.bottom <= waveformBounds.bottom)
        }

        handles[0].assertExactlyReversibleNudge("少し後へ", "少し前へ")
        handles[1].assertExactlyReversibleNudge("少し前へ", "少し後へ")
        handles[2].assertExactlyReversibleNudge("少し後へ", "少し前へ")
    }

    @Test
    fun endpointHandlesStayFullyTouchableAndRejectOutOfRangeActions() {
        setDeterministicWaveform(startFrame = 0, endFrame = 1_000, markerFrame = 1)
        val waveformBounds = waveformNode().fetchSemanticsNode().boundsInRoot
        val start = handleNode("選択開始ハンドル")
        val end = handleNode("選択終了ハンドル")
        val marker = handleNode("チョップ1 の位置")

        listOf(start, end, marker).forEach { handle ->
            val bounds = handle.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.left >= waveformBounds.left)
            assertTrue(bounds.right <= waveformBounds.right)
            assertTrue(bounds.top >= waveformBounds.top)
            assertTrue(bounds.bottom <= waveformBounds.bottom)
        }
        assertFalse(start.invokeCustomAction("少し前へ"))
        assertFalse(end.invokeCustomAction("少し後へ"))
        assertFalse(marker.invokeCustomAction("少し前へ"))
        start.assertExactlyReversibleNudge("少し後へ", "少し前へ")
        end.assertExactlyReversibleNudge("少し前へ", "少し後へ")
        marker.assertExactlyReversibleNudge("少し後へ", "少し前へ")
    }

    private fun setDeterministicWaveform(
        startFrame: Int = 100,
        endFrame: Int = 900,
        markerFrame: Int = 500,
    ) {
        val fixture = PcmAudio(
            id = 1L,
            name = "device-test.wav",
            samples = ShortArray(1_000) { index -> ((index % 100) * 300 - 15_000).toShort() },
            sampleRate = 1_000,
        )
        composeRule.setContent {
            var start by remember { mutableIntStateOf(startFrame) }
            var end by remember { mutableIntStateOf(endFrame) }
            val markers = remember { mutableStateListOf(markerFrame) }
            MaterialTheme {
                WaveformEditor(
                    audio = fixture,
                    rangeStartFrame = start,
                    rangeEndFrame = end,
                    sliceMarkers = markers,
                    activeSlice = null,
                    manualChopEnabled = true,
                    onRangeStartChange = { start = it.coerceIn(0, end - 1) },
                    onRangeEndChange = { end = it.coerceIn(start + 1, fixture.frameCount) },
                    onSliceMarkerChange = { index, frame -> markers[index] = frame.coerceIn(1, fixture.frameCount - 1) },
                    onWaveformTap = {},
                    canvasHeight = 220.dp,
                    showViewportControls = false,
                    showTimeReadout = false,
                    showInteractionHint = false,
                )
            }
        }
        composeRule.waitForIdle()
    }

    private fun waveformNode(): SemanticsNodeInteraction = composeRule.onNode(
        hasContentDescription("音声波形", substring = true),
        useUnmergedTree = true,
    )

    private fun handleNode(description: String): SemanticsNodeInteraction = composeRule.onNode(
        hasContentDescription(description),
        useUnmergedTree = true,
    )

    private fun SemanticsNodeInteraction.viewportDescription(): String? =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun SemanticsNodeInteraction.assertExactlyReversibleNudge(
        forwardLabel: String,
        reverseLabel: String,
    ) {
        val initial = requireNotNull(handlePositionDescription())
        assertTrue("$forwardLabel should change the handle", invokeCustomAction(forwardLabel))
        composeRule.waitForIdle()
        assertNotEquals(initial, handlePositionDescription())
        assertTrue("$reverseLabel should change the handle", invokeCustomAction(reverseLabel))
        composeRule.waitForIdle()
        assertEquals(initial, handlePositionDescription())
    }

    private fun SemanticsNodeInteraction.handlePositionDescription(): String? =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun SemanticsNodeInteraction.invokeCustomAction(label: String): Boolean {
        val action = fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions)
            .orEmpty()
            .firstOrNull { it.label == label }
        assertNotNull("Accessibility action $label should be exposed", action)
        return action!!.action()
    }
}
