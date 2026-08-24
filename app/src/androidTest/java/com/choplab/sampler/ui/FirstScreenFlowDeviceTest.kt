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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.choplab.sampler.audio.BuiltInDrumKits
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.ProjectLaunchTarget
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.ensurePlayablePadSelected as ensurePlayablePadSelectedState
import com.choplab.sampler.ui.theme.ChopLabTheme
import org.junit.Assert.assertEquals
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

        composeRule.onNode(hasText("B01", substring = true)).performScrollTo().assertIsDisplayed()
        val selectedPad = composeRule.onNode(
            hasContentDescription("PAD 01 割り当て済み", substring = true),
        ).performScrollTo()
        selectedPad.assertIsDisplayed()
        val minimumTargetPx = 48f * composeRule.density.density
        val bounds = selectedPad.fetchSemanticsNode().boundsInRoot
        assertTrue("Large-text PAD width must remain at least 48 dp", bounds.width >= minimumTargetPx - 1f)
        assertTrue("Large-text PAD height must remain at least 48 dp", bounds.height >= minimumTargetPx - 1f)
        composeRule.onNode(
            hasContentDescription(
                "PAD 01 割り当て済み。再生モード ONE SHOT。素材タイプ DRM",
                substring = true,
            ),
        ).assertIsDisplayed()

        composeRule.onNode(hasContentDescription("並べる詳細", substring = true))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        composeRule.onNode(hasContentDescription("クイック", substring = true))
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun largeTextSwipeStartingOnPadScrollsWithoutSelectingOrPlayingIt() {
        val padActions = mutableListOf<String>()
        setPristineDeck(fontScale = 2f, onPadAction = { padActions += it })

        composeRule.onNode(hasContentDescription("デモを試す", substring = true))
            .performScrollTo()
            .performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(
                hasContentDescription("PAD 01 割り当て済み", substring = true),
            ).fetchSemanticsNodes().isNotEmpty()
        }

        val pad = composeRule.onNode(
            hasContentDescription("PAD 01 割り当て済み", substring = true),
        ).performScrollTo()
        val topBeforeSwipe = pad.fetchSemanticsNode().boundsInRoot.top
        composeRule.runOnIdle { padActions.clear() }

        pad.performTouchInput { swipeUp(durationMillis = 600) }
        composeRule.waitForIdle()

        val topAfterSwipe = composeRule.onNode(
            hasContentDescription("PAD 01 割り当て済み", substring = true),
        ).fetchSemanticsNode().boundsInRoot.top
        assertTrue("The swipe should move the large-text workspace", topAfterSwipe < topBeforeSwipe - 1f)
        composeRule.runOnIdle { assertTrue("A scroll gesture must not dispatch PAD actions", padActions.isEmpty()) }

        val tappedPad = composeRule.onNode(
            hasContentDescription("PAD 01 割り当て済み", substring = true),
        ).performScrollTo()
        tappedPad.performTouchInput {
            down(center)
            up()
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(
                listOf("selectPlayablePad", "triggerPad", "releasePad"),
                padActions,
            )
        }
    }

    @Test
    fun largeTextGateScrollCancellationAndLongPressRecompositionReleaseExactlyOnce() {
        val padActions = mutableListOf<Pair<String, Long>>()
        val state = setPristineDeck(
            fontScale = 2f,
            onPadAction = { padActions += it to composeRule.mainClock.currentTime },
        )

        composeRule.onNode(hasContentDescription("デモを試す", substring = true))
            .performScrollTo()
            .performClick()
        composeRule.runOnIdle {
            val gateIndex = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            state.value = state.value.copy(
                pads = state.value.pads.map { pad ->
                    if (pad.globalIndex == gateIndex) pad.copy(playMode = PadPlayMode.GATE) else pad
                },
            )
            padActions.clear()
        }

        val gateDescription = hasContentDescription(
            "PAD 01 割り当て済み。再生モード GATE",
            substring = true,
        )
        val gatePad = composeRule.onNode(gateDescription).performScrollTo()
        val topBeforeSwipe = gatePad.fetchSemanticsNode().boundsInRoot.top
        gatePad.performTouchInput { swipeUp(durationMillis = 80) }
        composeRule.waitForIdle()
        val topAfterSwipe = composeRule.onNode(gateDescription).fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "The GATE swipe should move the large-text workspace",
            topAfterSwipe < topBeforeSwipe - 1f,
        )
        composeRule.runOnIdle {
            assertTrue("A parent-cancelled GATE must not acquire trigger ownership", padActions.isEmpty())
        }

        composeRule.onNode(gateDescription).performScrollTo().performTouchInput { longClick() }
        composeRule.waitForIdle()
        composeRule.onNode(hasText("切り位置", substring = true)).assertIsDisplayed()

        composeRule.runOnIdle {
            val actionNames = padActions.map { it.first }
            assertEquals(1, actionNames.count { it == "triggerPad" })
            assertEquals(1, actionNames.count { it == "releasePad" })
            val triggerTime = padActions.first { it.first == "triggerPad" }.second
            val releaseTime = padActions.first { it.first == "releasePad" }.second
            assertTrue(
                "Opening trim must release after the triggered GATE",
                actionNames.indexOf("releasePad") > actionNames.indexOf("triggerPad"),
            )
            assertTrue("A deferred GATE hold must not trigger and release in one frame", releaseTime > triggerTime)
        }
    }

    private fun setPristineDeck(
        fontScale: Float = 1f,
        onPadAction: (String) -> Unit = {},
    ): MutableState<SamplerUiState> {
        val state = mutableStateOf(
            BuiltInDrumKits.installStarterKit(SamplerUiState()).copy(
                projectLaunchTarget = ProjectLaunchTarget.CAPTURE,
            ),
        )
        val controller = noOpController(
            onEnsurePlayablePadSelected = {
                state.value = ensurePlayablePadSelectedState(state.value)
            },
            onPadAction = onPadAction,
        )
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

    private fun noOpController(
        onEnsurePlayablePadSelected: () -> Unit,
        onPadAction: (String) -> Unit,
    ): SamplerDeckController {
        val handler = java.lang.reflect.InvocationHandler { proxy, method, arguments ->
            when (method.name) {
                "equals" -> proxy === arguments?.firstOrNull()
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "NoOpSamplerDeckController"
                SamplerDeckController::ensurePlayablePadSelected.name -> {
                    onEnsurePlayablePadSelected()
                    null
                }
                SamplerDeckController::selectPlayablePad.name,
                SamplerDeckController::triggerPad.name,
                SamplerDeckController::releasePad.name -> {
                    onPadAction(method.name)
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
