package com.choplab.sampler.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
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
    fun largeTextActivatedGateTapKeepsMinimumPreviewFromActualTrigger() {
        val padActions = mutableListOf<Pair<String, Long>>()
        setLargeTextGateDeck(
            onPadAction = { padActions += it to composeRule.mainClock.currentTime },
        )

        val gate = gatePad().performScrollTo()
        composeRule.mainClock.autoAdvance = false
        try {
            gate.performTouchInput { down(center) }
            composeRule.mainClock.advanceTimeBy(136)
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(
                    listOf("selectPlayablePad", "triggerPad"),
                    padActions.map { it.first },
                )
            }

            // This physical up is only about 16 ms after delayed activation. It must not release
            // until the 80 ms minimum measured from the actual trigger has elapsed.
            gate.performTouchInput { up() }
            composeRule.waitForIdle()
            composeRule.mainClock.advanceTimeBy(48)
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(0, padActions.count { it.first == "releasePad" })
            }

            composeRule.mainClock.advanceTimeBy(48)
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(1, padActions.count { it.first == "releasePad" })
                val triggerTime = padActions.single { it.first == "triggerPad" }.second
                val releaseTime = padActions.single { it.first == "releasePad" }.second
                assertTrue(
                    "An activated GATE tap must retain 80 ms from its actual trigger",
                    releaseTime - triggerTime >= 80L,
                )
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun largeTextGateRetriggerIsNotCutByAnOlderPreviewRelease() {
        val padActions = mutableListOf<Pair<String, Long>>()
        setLargeTextGateDeck(
            onPadAction = { padActions += it to composeRule.mainClock.currentTime },
        )

        val gate = gatePad().performScrollTo()
        composeRule.mainClock.autoAdvance = false
        try {
            gate.performTouchInput {
                down(center)
                up()
            }
            composeRule.mainClock.advanceTimeBy(40)
            gate.performTouchInput {
                down(center)
                up()
            }
            composeRule.waitForIdle()

            composeRule.runOnIdle {
                assertEquals(2, padActions.count { it.first == "triggerPad" })
                assertEquals(0, padActions.count { it.first == "releasePad" })
            }

            // The first tap's 80 ms timer has elapsed, but its stale generation must not release
            // the voice started by the second tap 40 ms later.
            composeRule.mainClock.advanceTimeBy(48)
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(0, padActions.count { it.first == "releasePad" })
            }

            composeRule.mainClock.advanceTimeBy(48)
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(1, padActions.count { it.first == "releasePad" })
                val secondTriggerTime = padActions.filter { it.first == "triggerPad" }[1].second
                val releaseTime = padActions.single { it.first == "releasePad" }.second
                assertTrue(
                    "The newest retrigger must retain its complete 80 ms preview",
                    releaseTime - secondTriggerTime >= 80L,
                )
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun largeTextGateClaimsMovementAfterActivationUntilPhysicalPointerUp() {
        val padActions = mutableListOf<Pair<String, Long>>()
        setLargeTextGateDeck(
            onPadAction = { padActions += it to composeRule.mainClock.currentTime },
        )

        val gate = gatePad().performScrollTo()
        val topBeforeHold = gate.fetchSemanticsNode().boundsInRoot.top
        val dragDistance = gate.fetchSemanticsNode().boundsInRoot.height * 0.4f
        composeRule.mainClock.autoAdvance = false
        try {
            gate.performTouchInput { down(center) }
            composeRule.mainClock.advanceTimeBy(200)
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(
                    listOf("selectPlayablePad", "triggerPad"),
                    padActions.map { it.first },
                )
            }

            gate.performTouchInput {
                moveBy(Offset(0f, -dragDistance), delayMillis = 64)
            }
            composeRule.waitForIdle()

            val topAfterMove = gatePad().fetchSemanticsNode().boundsInRoot.top
            assertEquals(
                "Movement after delayed GATE activation must not scroll the parent",
                topBeforeHold,
                topAfterMove,
                1f,
            )
            composeRule.runOnIdle {
                assertEquals(
                    "An activated GATE must remain owned until physical up",
                    listOf("selectPlayablePad", "triggerPad"),
                    padActions.map { it.first },
                )
            }

            gate.performTouchInput { up() }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(
                    listOf("selectPlayablePad", "triggerPad", "releasePad"),
                    padActions.map { it.first },
                )
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun largeTextGateMovementCancelsTrimButRetainsOwnershipUntilPhysicalUp() {
        val padActions = mutableListOf<Pair<String, Long>>()
        setLargeTextGateDeck(
            onPadAction = { padActions += it to composeRule.mainClock.currentTime },
        )

        val gate = gatePad().performScrollTo()
        val dragDistance = gate.fetchSemanticsNode().boundsInRoot.height * 0.4f
        composeRule.mainClock.autoAdvance = false
        try {
            gate.performTouchInput { down(center) }
            composeRule.mainClock.advanceTimeBy(200)
            composeRule.waitForIdle()
            gate.performTouchInput {
                moveBy(Offset(0f, -dragDistance), delayMillis = 64)
            }
            composeRule.mainClock.advanceTimeBy(400)
            composeRule.waitForIdle()

            assertTrue(
                "Movement beyond touch slop must suppress GATE trim navigation",
                composeRule.onAllNodes(
                    hasText("切り位置", substring = true),
                ).fetchSemanticsNodes().isEmpty(),
            )
            composeRule.runOnIdle {
                assertEquals(
                    "A moved GATE must remain owned and audible beyond long-press timeout",
                    listOf("selectPlayablePad", "triggerPad"),
                    padActions.map { it.first },
                )
            }

            gate.performTouchInput { up() }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(
                    listOf("selectPlayablePad", "triggerPad", "releasePad"),
                    padActions.map { it.first },
                )
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun largeTextGateRejectsAQuickHorizontalDragReleasedOutsideThePad() {
        val padActions = mutableListOf<String>()
        setLargeTextGateDeck(onPadAction = { padActions += it })

        val gate = gatePad().performScrollTo()
        composeRule.mainClock.autoAdvance = false
        try {
            gate.performTouchInput {
                down(center)
                moveBy(Offset(width.toFloat(), 0f), delayMillis = 40)
                up()
            }
            composeRule.waitForIdle()
            composeRule.mainClock.advanceTimeBy(200)
            composeRule.waitForIdle()

            composeRule.runOnIdle {
                assertTrue(
                    "A displaced pre-activation release must not trigger or record a PAD",
                    padActions.isEmpty(),
                )
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun largeTextGateDeferredReleaseCannotCutANewerControllerTrigger() {
        val padActions = mutableListOf<String>()
        lateinit var controller: SamplerDeckController
        setLargeTextGateDeck(
            onPadAction = { padActions += it },
            onControllerReady = { controller = it },
        )

        val gateIndex = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
        val gate = gatePad().performScrollTo()
        composeRule.mainClock.autoAdvance = false
        try {
            gate.performTouchInput {
                down(center)
                up()
            }
            composeRule.mainClock.advanceTimeBy(40)
            composeRule.runOnIdle { controller.triggerPad(gateIndex) }
            composeRule.mainClock.advanceTimeBy(96)
            composeRule.waitForIdle()

            composeRule.runOnIdle {
                assertEquals(2, padActions.count { it == "triggerPad" })
                assertEquals(
                    "The pointer timer must not release a newer keyboard/controller trigger",
                    0,
                    padActions.count { it == "releasePad" },
                )
                controller.releasePad(gateIndex)
            }
            composeRule.runOnIdle {
                assertEquals(1, padActions.count { it == "releasePad" })
            }
        } finally {
            composeRule.mainClock.autoAdvance = true
        }
    }

    @Test
    fun largeTextGateUsesTheInitiatingPointerForQuickAndActivatedRelease() {
        val padActions = mutableListOf<String>()
        setLargeTextGateDeck(onPadAction = { padActions += it })

        val gate = gatePad().performScrollTo()
        composeRule.mainClock.autoAdvance = false
        try {
            gate.performTouchInput {
                down(0, center)
                down(1, center + Offset(8f, 0f))
                up(0)
            }
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(
                    listOf("selectPlayablePad", "triggerPad"),
                    padActions,
                )
            }
            composeRule.mainClock.advanceTimeBy(96)
            composeRule.waitForIdle()
            composeRule.runOnIdle {
                assertEquals(1, padActions.count { it == "releasePad" })
            }
            composeRule.onRoot().performTouchInput { cancel() }

            composeRule.runOnIdle { padActions.clear() }
            val activatedGate = gatePad().performScrollTo()
            activatedGate.performTouchInput { down(0, center) }
            composeRule.mainClock.advanceTimeBy(136)
            composeRule.waitForIdle()
            activatedGate.performTouchInput {
                down(1, center + Offset(8f, 0f))
                up(0)
            }
            composeRule.mainClock.advanceTimeBy(96)
            composeRule.waitForIdle()

            composeRule.runOnIdle {
                assertEquals(
                    listOf("selectPlayablePad", "triggerPad", "releasePad"),
                    padActions,
                )
            }
            composeRule.mainClock.advanceTimeBy(400)
            composeRule.waitForIdle()
            assertTrue(
                "A remaining secondary pointer must not inherit trim ownership",
                composeRule.onAllNodes(hasText("切り位置", substring = true))
                    .fetchSemanticsNodes().isEmpty(),
            )
            composeRule.onRoot().performTouchInput { cancel() }
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
    fun largeTextChopVerticalSwipeStartingOnWaveformReachesParentScroller() {
        val waveformActions = mutableListOf<String>()
        val audio = PcmAudio(
            id = 100L,
            name = "waveform-scroll-source.wav",
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
            onPadAction = { waveformActions += it },
        )

        val waveform = composeRule.onNode(
            hasContentDescription("音声波形", substring = true),
        ).performScrollTo()
        val pad = composeRule.onNode(
            hasContentDescription("PAD 01 空", substring = true),
        )
        val padTopBeforeSwipe = pad.fetchSemanticsNode().boundsInRoot.top

        waveform.performTouchInput { swipeUp(durationMillis = 600) }
        composeRule.waitForIdle()

        val padTopAfterSwipe = composeRule.onNode(
            hasContentDescription("PAD 01 空", substring = true),
        ).fetchSemanticsNode().boundsInRoot.top
        assertTrue(
            "A vertical single-pointer waveform drag must move the large-text CHOP workspace",
            padTopAfterSwipe < padTopBeforeSwipe - 1f,
        )
        composeRule.runOnIdle {
            assertTrue(
                "A waveform-origin scroll gesture must not also dispatch a waveform tap",
                waveformActions.isEmpty(),
            )
        }
    }

    @Test
    fun normalTextVerticalWaveformDragDoesNotBecomeATap() {
        val waveformActions = mutableListOf<String>()
        val audio = PcmAudio(
            id = 101L,
            name = "waveform-no-tap-source.wav",
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
            fontScale = 1f,
            onPadAction = { waveformActions += it },
        )

        val waveform = composeRule.onNode(
            hasContentDescription("音声波形", substring = true),
        )
        waveform.performTouchInput { swipeUp(durationMillis = 600) }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(
                "A normal-text vertical waveform drag must not seek on pointer-up",
                waveformActions.isEmpty(),
            )
        }

        waveform.performTouchInput {
            down(center)
            up()
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(listOf("playSourceFrom"), waveformActions)
        }
    }

    @Test
    fun twoPointerWaveformRotationCancelsTapWithoutChangingItsCentroidOrScale() {
        val waveformActions = mutableListOf<String>()
        val audio = PcmAudio(
            id = 102L,
            name = "waveform-rotation-source.wav",
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
            fontScale = 1f,
            onPadAction = { waveformActions += it },
        )

        val waveform = composeRule.onNode(
            hasContentDescription("音声波形", substring = true),
        )
        waveform.performTouchInput {
            val radius = minOf(width, height) * 0.22f
            down(0, center - Offset(radius, 0f))
            down(1, center + Offset(radius, 0f))
            updatePointerTo(0, center - Offset(0f, radius))
            updatePointerTo(1, center + Offset(0f, radius))
            move(160)
            up(0)
            up(1)
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertTrue(
                "A rotation-only two-pointer gesture must consume movement and cancel tap",
                waveformActions.isEmpty(),
            )
        }

        waveform.performTouchInput {
            down(center)
            up()
        }
        composeRule.waitForIdle()
        composeRule.runOnIdle {
            assertEquals(listOf("playSourceFrom"), waveformActions)
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
        composeRule.onNode(
            hasContentDescription("PAD 01 割り当て済み。再生モード ONE SHOT", substring = true),
        ).assertIsDisplayed()
        composeRule.runOnIdle {
            val gateIndex = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
            // This in-place mode change must restart the existing pointer-input handler.
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
        onControllerReady: (SamplerDeckController) -> Unit = {},
    ): MutableState<SamplerUiState> = setDeck(
        initialState = BuiltInDrumKits.installStarterKit(SamplerUiState()).copy(
            projectLaunchTarget = ProjectLaunchTarget.CAPTURE,
        ),
        fontScale = fontScale,
        onPadAction = onPadAction,
        onControllerReady = onControllerReady,
    )

    private fun setDeck(
        initialState: SamplerUiState,
        fontScale: Float,
        onPadAction: (String) -> Unit,
        onControllerReady: (SamplerDeckController) -> Unit = {},
    ): MutableState<SamplerUiState> {
        val state = mutableStateOf(initialState)
        val controller = noOpController(
            onEnsurePlayablePadSelected = {
                state.value = ensurePlayablePadSelectedState(state.value)
            },
            onPadAction = onPadAction,
        )
        onControllerReady(controller)
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

    private fun setLargeTextGateDeck(
        onPadAction: (String) -> Unit,
        onControllerReady: (SamplerDeckController) -> Unit = {},
    ) {
        val state = setPristineDeck(
            fontScale = 2f,
            onPadAction = onPadAction,
            onControllerReady = onControllerReady,
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
        }
    }

    private fun gatePad() = composeRule.onNode(
        hasContentDescription("PAD 01 割り当て済み", substring = true),
    )

    private fun noOpController(
        onEnsurePlayablePadSelected: () -> Unit,
        onPadAction: (String) -> Unit,
    ): SamplerDeckController {
        val triggerOwnership = PadTriggerOwnership()
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
                SamplerDeckController::playSourceFrom.name -> {
                    onPadAction(method.name)
                    null
                }
                SamplerDeckController::triggerPad.name -> {
                    triggerOwnership.acquire((requireNotNull(arguments)[0] as Number).toInt())
                    onPadAction("triggerPad")
                    null
                }
                SamplerDeckController::triggerPadWithOwnership.name -> {
                    val ownership = triggerOwnership.acquire(
                        (requireNotNull(arguments)[0] as Number).toInt(),
                    )
                    onPadAction("triggerPad")
                    ownership
                }
                SamplerDeckController::releasePad.name -> {
                    triggerOwnership.invalidate((requireNotNull(arguments)[0] as Number).toInt())
                    onPadAction("releasePad")
                    null
                }
                SamplerDeckController::releasePadIfOwned.name -> {
                    val callArguments = requireNotNull(arguments)
                    val padIndex = (callArguments[0] as Number).toInt()
                    val ownership = (callArguments[1] as Number).toLong()
                    if (triggerOwnership.releaseIfCurrent(padIndex, ownership)) {
                        onPadAction("releasePad")
                    }
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
