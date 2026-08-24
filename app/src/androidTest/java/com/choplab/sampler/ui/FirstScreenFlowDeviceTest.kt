package com.choplab.sampler.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.choplab.sampler.audio.BuiltInDrumKits
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.ensurePlayablePadSelected as ensurePlayablePadSelectedState
import com.choplab.sampler.ui.theme.ChopLabTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

@RunWith(AndroidJUnit4::class)
class FirstScreenFlowDeviceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun inMemoryPristineEntryExposesOwnAudioRecordingAndExplicitDemoWithLargeTargets() {
        setPristineDeck()

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

    @Test
    fun largeTextCanReachDemoAndBothBeatSurfacesByScrolling() {
        setPristineDeck(fontScale = 2f)

        composeRule.onNode(hasContentDescription("デモを試す", substring = true))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        val selectedPad = composeRule.onNode(hasText("B01", substring = true)).performScrollTo()
        selectedPad.assertIsDisplayed()
        val minimumTargetPx = 48f * composeRule.density.density
        val bounds = selectedPad.fetchSemanticsNode().boundsInRoot
        assertTrue("Large-text PAD width must remain at least 48 dp", bounds.width >= minimumTargetPx - 1f)
        assertTrue("Large-text PAD height must remain at least 48 dp", bounds.height >= minimumTargetPx - 1f)

        composeRule.onNode(hasContentDescription("並べる詳細", substring = true))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNode(hasContentDescription("クイック", substring = true))
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun setPristineDeck(fontScale: Float = 1f): MutableState<SamplerUiState> {
        val state = mutableStateOf(BuiltInDrumKits.installStarterKit(SamplerUiState()))
        val controller = noOpController {
            state.value = ensurePlayablePadSelectedState(state.value)
        }
        composeRule.setContent {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(baseDensity.density, fontScale),
            ) {
                ChopLabTheme {
                    OtohiroiDeck(
                        state = state.value,
                        onImportAudio = {},
                        onToggleMicrophoneRecording = {},
                        onToggleVocalRecording = {},
                        onToggleSystemAudioRecording = {},
                        onExportBeat = {},
                        onOpenProject = {},
                        onSaveProject = {},
                        viewModel = controller,
                    )
                }
            }
        }
        return state
    }

    private fun noOpController(onEnsurePlayablePadSelected: () -> Unit): SamplerDeckController {
        val handler = java.lang.reflect.InvocationHandler { proxy, method, arguments ->
            when (method.name) {
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NoOpSamplerDeckController"
                SamplerDeckController::ensurePlayablePadSelected.name -> {
                    onEnsurePlayablePadSelected()
                    null
                }
                else -> null
            }
        }
        return Proxy.newProxyInstance(
            SamplerDeckController::class.java.classLoader,
            arrayOf(SamplerDeckController::class.java),
            handler,
        ) as SamplerDeckController
    }
}
