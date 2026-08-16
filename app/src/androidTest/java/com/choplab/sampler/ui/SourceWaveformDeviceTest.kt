package com.choplab.sampler.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.choplab.sampler.MainActivity
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SourceWaveformDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun twoFingerPinchPanAndTalkBackResetExposeViewportChanges() {
        val waveform = composeRule.onNode(
            hasContentDescription("音声波形", substring = true),
            useUnmergedTree = true,
        )
        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching { waveform.fetchSemanticsNode() }.isSuccess
        }

        val initial = waveform.viewportDescription()
        assertNotNull("TalkBack should announce the waveform viewport", initial)

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
        assertNotEquals("A true two-pointer pinch should change the viewport", initial, zoomed)

        waveform.performTouchInput {
            val left = center - Offset(width * 0.18f, 0f)
            val right = center + Offset(width * 0.18f, 0f)
            down(0, left)
            down(1, right)
            moveTo(0, left + Offset(width * 0.20f, 0f), 250)
            moveTo(1, right + Offset(width * 0.20f, 0f), 250)
            up(0)
            up(1)
        }
        composeRule.waitForIdle()
        val panned = waveform.viewportDescription()
        assertNotEquals("A true two-pointer pan should move the viewport", zoomed, panned)

        val customActions = waveform.fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions)
            .orEmpty()
        val previousAction = customActions.firstOrNull { it.label == "前の範囲を表示" }
        val nextAction = customActions.firstOrNull { it.label == "次の範囲を表示" }
        val resetAction = customActions.firstOrNull { it.label == "全体表示に戻す" }
        assertNotNull("TalkBack previous-range action should be exposed", previousAction)
        assertNotNull("TalkBack next-range action should be exposed", nextAction)
        assertNotNull("TalkBack reset action should be exposed", resetAction)

        assertTrue("TalkBack previous-range action should succeed", previousAction!!.action())
        composeRule.waitForIdle()
        val previous = waveform.viewportDescription()
        assertNotEquals("Previous range should move the viewport", panned, previous)

        assertTrue("TalkBack next-range action should succeed", nextAction!!.action())
        composeRule.waitForIdle()
        val next = waveform.viewportDescription()
        assertNotEquals("Next range should move the viewport", previous, next)

        assertTrue("TalkBack reset action should succeed", resetAction!!.action())
        composeRule.waitForIdle()
        val reset = waveform.viewportDescription()
        assertTrue("Reset should announce the whole source", reset?.contains("全体表示") == true)
    }

    @Test
    fun selectionAndChopHandlesExpose48DpTargetsAndReversibleTalkBackNudges() {
        val start = composeRule.onNode(
            hasContentDescription("選択開始ハンドル"),
            useUnmergedTree = true,
        )
        val end = composeRule.onNode(
            hasContentDescription("選択終了ハンドル"),
            useUnmergedTree = true,
        )
        val chop = composeRule.onNode(
            hasContentDescription("チョップ1 の位置"),
            useUnmergedTree = true,
        )
        composeRule.waitUntil(timeoutMillis = 15_000) {
            listOf(start, end, chop).all { runCatching { it.fetchSemanticsNode() }.isSuccess }
        }

        val minimumTargetPx = 48f * composeRule.activity.resources.displayMetrics.density
        listOf(start, end, chop).forEach { handle ->
            assertTrue(
                "Each waveform handle should expose at least a 48 dp touch target",
                handle.fetchSemanticsNode().boundsInRoot.width >= minimumTargetPx - 1f,
            )
        }

        start.assertReversibleNudge(forwardLabel = "少し後へ", reverseLabel = "少し前へ")
        end.assertReversibleNudge(forwardLabel = "少し前へ", reverseLabel = "少し後へ")
        chop.assertReversibleNudge(forwardLabel = "少し後へ", reverseLabel = "少し前へ")
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.viewportDescription(): String? =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertReversibleNudge(
        forwardLabel: String,
        reverseLabel: String,
    ) {
        val initial = handlePositionDescription()
        assertNotNull("TalkBack should announce the handle position", initial)
        assertTrue("$forwardLabel should succeed", invokeCustomAction(forwardLabel))
        composeRule.waitForIdle()
        val moved = handlePositionDescription()
        assertNotEquals("$forwardLabel should move the handle", initial, moved)
        assertTrue("$reverseLabel should succeed", invokeCustomAction(reverseLabel))
        composeRule.waitForIdle()
        val restored = handlePositionDescription()
        val initialFrame = initial!!.substringBefore("フレーム").toInt()
        val restoredFrame = restored!!.substringBefore("フレーム").toInt()
        assertTrue(
            "The reverse action should restore the handle: initial=$initial moved=$moved restored=$restored",
            kotlin.math.abs(restoredFrame - initialFrame) <= 100,
        )
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.handlePositionDescription(): String? =
        fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.invokeCustomAction(label: String): Boolean {
        val action = fetchSemanticsNode().config
            .getOrNull(SemanticsActions.CustomActions)
            .orEmpty()
            .firstOrNull { it.label == label }
        assertNotNull("TalkBack action $label should be exposed", action)
        return action!!.action()
    }
}
