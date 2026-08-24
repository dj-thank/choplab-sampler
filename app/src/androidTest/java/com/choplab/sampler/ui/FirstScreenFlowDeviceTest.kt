package com.choplab.sampler.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.choplab.sampler.audio.BuiltInDrumKits
import com.choplab.sampler.model.PcmAudio
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

    @Test
    fun largeTextOneShotBeatSwipeCancelsPadAndTapStillPlaysIt() {
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
        assertTrue(
            "The swipe should move the large-text BEAT workspace",
            topAfterSwipe < topBeforeSwipe - 1f,
        )
        composeRule.runOnIdle {
            assertTrue("A BEAT scroll gesture must not dispatch PAD actions", padActions.isEmpty())
        }

        composeRule.onNode(
            hasContentDescription("PAD 01 割り当て済み", substring = true),
        ).performScrollTo().performTouchInput {
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
    fun largeTextGateModeChangeRestartsArbitrationAndHoldReleasesOnPointerUp() {
        val padActions = mutableListOf<Pair<String, Long>>()
        setLargeTextGateDeck(
            onPadAction = { padActions += it to composeRule.mainClock.currentTime },
        )

        val swipePad = gatePad().performScrollTo()
        val topBeforeSwipe = swipePad.fetchSemanticsNode().boundsInRoot.top
        swipePad.performTouchInput { swipeUp(durationMillis = 80) }
        composeRule.waitForIdle()

        val topAfterSwipe = gatePad().fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "The swipe should move the large-text GATE workspace",
            topAfterSwipe < topBeforeSwipe - 1f,
        )
        composeRule.runOnIdle {
            assertTrue(
                "A GATE drag canceled before activation must dispatch no PAD action",
                padActions.isEmpty(),
            )
        }

        val heldGatePad = gatePad().performScrollTo()
        composeRule.mainClock.autoAdvance = false
        try {
            heldGatePad.performTouchInput { down(center) }
            composeRule.mainClock.advanceTimeBy(200)
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(
                    listOf("selectPlayablePad", "triggerPad"),
                    padActions.map { it.first },
                )
            }

            heldGatePad.performTouchInput { up() }
            composeRule.waitForIdle()

            composeRule.runOnIdle {
                assertEquals(
                    listOf("selectPlayablePad", "triggerPad", "releasePad"),
                    padActions.map { it.first },
                )
                val triggerTime = padActions.first { it.first == "triggerPad" }.second
                val releaseTime = padActions.first { it.first == "releasePad" }.second
                assertTrue(
                    "A deferred GATE hold must not trigger and release in one frame",
                    releaseTime > triggerTime,
                )
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun largeTextShortGateTapKeepsAnAudiblePreviewInterval() {
        val padActions = mutableListOf<Pair<String, Long>>()
        setLargeTextGateDeck(
            onPadAction = { padActions += it to composeRule.mainClock.currentTime },
        )

        val shortGatePad = gatePad().performScrollTo()
        composeRule.mainClock.autoAdvance = false
        try {
            shortGatePad.performTouchInput {
                down(center)
                up()
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(
                    listOf("selectPlayablePad", "triggerPad"),
                    padActions.map { it.first },
                )
            }

            composeRule.mainClock.advanceTimeBy(96)
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(
                    listOf("selectPlayablePad", "triggerPad", "releasePad"),
                    padActions.map { it.first },
                )
                val triggerTime = padActions.first { it.first == "triggerPad" }.second
                val releaseTime = padActions.first { it.first == "releasePad" }.second
                assertTrue(
                    "A short GATE preview must remain active for at least 80 ms",
                    releaseTime - triggerTime >= 80L,
                )
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun largeTextGateLongPressRecompositionReleasesTriggerOwnershipExactlyOnce() {
        val padActions = mutableListOf<Pair<String, Long>>()
        setLargeTextGateDeck(
            onPadAction = { padActions += it to composeRule.mainClock.currentTime },
        )

        val heldGatePad = gatePad().performScrollTo()
        composeRule.mainClock.autoAdvance = false
        try {
            heldGatePad.performTouchInput { down(center) }
            composeRule.mainClock.advanceTimeBy(600)
            composeRule.waitForIdle()

            composeRule.onNode(hasText("切り位置", substring = true)).assertIsDisplayed()
            composeRule.runOnIdle {
                val actionNames = padActions.map { it.first }
                assertEquals(1, actionNames.count { it == "triggerPad" })
                assertEquals(1, actionNames.count { it == "releasePad" })
                assertTrue(
                    "Opening trim must release after the triggered GATE",
                    actionNames.indexOf("releasePad") > actionNames.indexOf("triggerPad"),
                )
            }

            // The grid disappeared while the injected pointer was held; cancel the shared injector state.
            composeRule.onRoot().performTouchInput { cancel() }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun largeTextChopSwipeStartingOnEmptyPadScrollsWithoutCapturingIt() {
        val padActions = mutableListOf<String>()
        val audio = PcmAudio(
            id = 99L,
            name = "gesture-source.wav",
            samples = ShortArray(4_800),
            sampleRate = 48_000,
        )
        setDeck(
            initialState = SamplerUiState(
                currentAudio = audio,
                rangeEndFrame = audio.frameCount,
                sourcePlaying = true,
                projectLaunchTarget = ProjectLaunchTarget.CHOP,
            ),
            fontScale = 2f,
            onPadAction = { padActions += it },
        )

        val pad = composeRule.onNode(
            hasContentDescription("PAD 01 空", substring = true),
        ).performScrollTo()
        val topBeforeSwipe = pad.fetchSemanticsNode().boundsInRoot.top

        pad.performTouchInput { swipeUp(durationMillis = 600) }
        composeRule.waitForIdle()

        val topAfterSwipe = composeRule.onNode(
            hasContentDescription("PAD 01 空", substring = true),
        ).fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "The swipe should move the large-text CHOP workspace",
            topAfterSwipe < topBeforeSwipe - 1f,
        )
        composeRule.runOnIdle {
            assertTrue("A CHOP scroll gesture must not dispatch PAD actions", padActions.isEmpty())
        }

        composeRule.onNode(
            hasContentDescription("PAD 01 空", substring = true),
        ).performScrollTo().performTouchInput {
            down(center)
            up()
        }
        composeRule.waitForIdle()

        composeRule.runOnIdle {
            assertEquals(listOf("selectPad", "capturePad"), padActions)
        }

        composeRule.runOnIdle { padActions.clear() }
        val heldEmptyPad = composeRule.onNode(
            hasContentDescription("PAD 01 空", substring = true),
        ).performScrollTo()
        composeRule.mainClock.autoAdvance = false
        try {
            heldEmptyPad.performTouchInput { down(center) }
            composeRule.mainClock.advanceTimeBy(600)
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertTrue(
                    "Holding an empty CHOP pad must still wait for pointer-up",
                    padActions.isEmpty(),
                )
            }

            heldEmptyPad.performTouchInput { up() }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(listOf("selectPad", "capturePad"), padActions)
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun normalTextBeatPadStillTriggersOnPressDown() {
        val padActions = mutableListOf<String>()
        setPristineDeck(onPadAction = { padActions += it })

        composeRule.onNode(hasContentDescription("デモを試す", substring = true)).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodes(
                hasContentDescription("PAD 01 割り当て済み", substring = true),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        val pad = composeRule.onNode(
            hasContentDescription("PAD 01 割り当て済み", substring = true),
        )
        composeRule.runOnIdle { padActions.clear() }

        pad.performTouchInput {
            down(center)
            advanceEventTime(100)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(listOf("selectPlayablePad", "triggerPad"), padActions)
        }

        pad.performTouchInput { up() }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(
                listOf("selectPlayablePad", "triggerPad", "releasePad"),
                padActions,
            )
        }
    }

    private fun setPristineDeck(
        fontScale: Float = 1f,
        onPadAction: (String) -> Unit = {},
    ): MutableState<SamplerUiState> = setDeck(
        initialState = BuiltInDrumKits.installStarterKit(SamplerUiState()).copy(
            projectLaunchTarget = ProjectLaunchTarget.CAPTURE,
        ),
        fontScale = fontScale,
        onPadAction = onPadAction,
    )

    private fun setDeck(
        initialState: SamplerUiState,
        fontScale: Float,
        onPadAction: (String) -> Unit,
    ): MutableState<SamplerUiState> {
        val state = mutableStateOf(initialState)
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

    private fun setLargeTextGateDeck(onPadAction: (String) -> Unit) {
        val state = setPristineDeck(fontScale = 2f, onPadAction = onPadAction)
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
        }
    }

    private fun gatePad() = composeRule.onNode(
        hasContentDescription("PAD 01 割り当て済み", substring = true),
    )

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
                SamplerDeckController::selectPad.name,
                SamplerDeckController::selectPlayablePad.name,
                SamplerDeckController::capturePad.name,
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
