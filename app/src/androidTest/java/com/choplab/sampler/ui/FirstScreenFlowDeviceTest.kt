package com.choplab.sampler.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.choplab.sampler.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FirstScreenFlowDeviceTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun pristineEntryExposesOwnAudioRecordingAndExplicitDemoWithLargeTargets() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("まず、自分の音を入れる")).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNode(hasText("まず、自分の音を入れる")).assertIsDisplayed()
        composeRule.onNode(hasContentDescription("曲を読み込む", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasContentDescription("制作を開く", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasContentDescription("マイクで録る", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasContentDescription("端末音声を録る", substring = true)).assertIsDisplayed()
        val demo = composeRule.onNode(hasContentDescription("デモを試す", substring = true))
        demo.assertIsDisplayed()

        val minimumTargetPx = 48f * composeRule.density.density
        listOf(
            composeRule.onNode(hasContentDescription("曲を読み込む", substring = true)),
            composeRule.onNode(hasContentDescription("制作を開く", substring = true)),
            composeRule.onNode(hasContentDescription("マイクで録る", substring = true)),
            composeRule.onNode(hasContentDescription("端末音声を録る", substring = true)),
            demo,
        ).forEach { action ->
            val bounds = action.fetchSemanticsNode().boundsInRoot
            assertTrue("First-screen action width must be at least 48 dp", bounds.width >= minimumTargetPx - 1f)
            assertTrue("First-screen action height must be at least 48 dp", bounds.height >= minimumTargetPx - 1f)
        }

        demo.performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(hasText("B DRUMS", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNode(hasText("B DRUMS", substring = true)).assertIsDisplayed()
        composeRule.onNode(hasText("B01", substring = true)).assertIsDisplayed()
    }
}
