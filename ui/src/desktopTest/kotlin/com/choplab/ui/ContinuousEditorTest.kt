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

    @Test fun sourceMicrophoneShowsRecordingAndReachableStopAndDiscardAtLargeText() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        try {
            for ((width, height, font) in listOf(Triple(1440, 1024, 1f), Triple(390, 844, 2f))) {
                val state = mutableStateOf(ContinuousEditorFixture.state(ContinuousStage.CAPTURE).copy(recordingSource = true, capabilities = setOf(ContinuousCapability.STOP_ALL)))
                val actions = mutableListOf<ContinuousEditorAction>()
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                    ContinuousEditor(state.value, actions::add, { ContinuousEditorReadout(recordingMillis = 12_345) })
                }
                try {
                    scene.settle()
                    scene.nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }.forEach { it(0f, 10_000f) }
                    scene.settle()
                    val stop = requireNotNull(scene.tag("ce-source-record-stop"))
                    val discard = requireNotNull(scene.tag("ce-source-record-discard"))
                    assertTrue(stop.boundsInRoot.height >= 48f && stop.boundsInRoot.top >= 0 && stop.boundsInRoot.bottom <= height)
                    assertTrue(discard.boundsInRoot.height >= 48f && discard.boundsInRoot.bottom <= height)
                    assertTrue(requireNotNull(scene.tag("ce-source-recording")).config[SemanticsProperties.Text].joinToString().contains("12"))
                    scene.click("ce-source-record-stop")
                    scene.click("ce-source-record-discard")
                    assertEquals(listOf(ContinuousEditorAction.StopSourceRecording, ContinuousEditorAction.DiscardSourceRecording), actions)
                    scene.capture("source-recording-${width}-font${(font * 100).toInt()}.png")
                } finally { scene.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

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

    @Test fun drumKitChooserAndReplaceQuestionSendTypedRequests() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        try {
            val base = ContinuousEditorFixture.state()
            val state = mutableStateOf(base.copy(drumKits = com.choplab.core.kits.DrumKits.catalog.map { ContinuousDrumKit(it.id, it.name) }))
            val actions = mutableListOf<ContinuousEditorAction>()
            val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
                ContinuousEditor(state.value, { action ->
                    actions += action
                    when (action) {
                        ContinuousEditorAction.AddDrum -> state.value = state.value.copy(drumKitChooserOpen = true)
                        is ContinuousEditorAction.ChooseDrumKit -> state.value = state.value.copy(drumKitChooserOpen = false,
                            drumKitQuestion = ContinuousKitQuestion(action.kitId, 3))
                        ContinuousEditorAction.DismissDrumKit -> state.value = state.value.copy(drumKitChooserOpen = false, drumKitQuestion = null)
                        else -> Unit
                    }
                }, ContinuousEditorFixture::readout)
            }
            try {
                scene.settle()
                scene.click("ce-add-drums")
                assertEquals(ContinuousEditorAction.AddDrum, actions.last())
                assertNotNull(scene.tag("ce-kit-chooser"))
                for (kit in state.value.drumKits) {
                    val button = requireNotNull(scene.tag("ce-kit-${kit.id}")) { kit.id }
                    assertTrue(button.boundsInRoot.height >= 48, "${kit.id} is a full-size button")
                }
                scene.capture("kit-chooser-desktop.png")
                scene.click("ce-kit-boom-bap")
                assertEquals(ContinuousEditorAction.ChooseDrumKit("boom-bap"), actions.last())
                assertNull(scene.tag("ce-kit-chooser"))
                assertTrue(scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.any { "自分の音3個" in it.text })
                scene.capture("kit-question-desktop.png")
                scene.click("ce-kit-keep")
                assertEquals(ContinuousEditorAction.DismissDrumKit, actions.last())
                assertNull(scene.tag("ce-kit-question"))
                state.value = state.value.copy(drumKitQuestion = ContinuousKitQuestion("boom-bap", 3))
                scene.settle()
                scene.click("ce-kit-replace")
                assertEquals(ContinuousEditorAction.ConfirmDrumKit, actions.last())
                state.value = state.value.copy(drumKitQuestion = null, installedDrumKit = "boom-bap")
                scene.settle()
                assertTrue(requireNotNull(scene.tag("ce-add-drums")).config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == "ドラムを変える" } ||
                    scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.any { it.text == "ドラムを変える" })
            } finally { scene.close() }
            val phone = ImageComposeScene(width = 390, height = 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
                ContinuousEditor(state.value.copy(drumKitChooserOpen = true, installedDrumKit = "dusty-jazz"), {}, ContinuousEditorFixture::readout)
            }
            try {
                phone.settle()
                assertNotNull(phone.tag("ce-kit-chooser"))
                assertTrue(phone.nodes().any { it.config.getOrNull(SemanticsProperties.TestTag) == "ce-kit-dusty-jazz" && it.config.getOrNull(SemanticsProperties.Selected) == true })
                phone.capture("kit-chooser-phone-font200.png")
            } finally { phone.close() }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun liveChopCutsWhereThePadWentDown() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        try {
            var frame = 1_000L
            val base = ContinuousEditorFixture.state(ContinuousStage.CHOP)
            val state = mutableStateOf(base.copy(capabilities = base.capabilities + ContinuousCapability.LIVE_CHOP))
            val actions = mutableListOf<ContinuousEditorAction>()
            val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
                ContinuousEditor(state.value, { action ->
                    actions += action
                    when (action) {
                        ContinuousEditorAction.BeginLiveChop -> state.value = state.value.copy(liveChopping = true, originalPlaying = true)
                        ContinuousEditorAction.EndLiveChop -> state.value = state.value.copy(liveChopping = false, originalPlaying = false)
                        else -> Unit
                    }
                }, { ContinuousEditorReadout(originalFrame = frame) })
            }
            fun texts() = scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            try {
                scene.settle()
                scene.click("ce-live-chop")
                assertEquals(ContinuousEditorAction.BeginLiveChop, actions.last())
                assertTrue(texts().any { "音に合わせてPADを叩く" in it })
                actions.clear()

                // The position is read as the PAD goes down; the original plays on before the release.
                val pad = requireNotNull(scene.tag("ce-pad-5")).boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, pad, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.render(System.nanoTime()).close()
                frame = 9_000
                scene.sendPointerEvent(PointerEventType.Release, pad, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
                scene.settle()
                assertEquals(listOf<ContinuousEditorAction>(ContinuousEditorAction.CapturePad(5, 1_000)), actions,
                    "During a pass a PAD cuts; it neither plays nor selects by itself")

                // A touch that turns into a drag cuts nothing.
                scene.drag(requireNotNull(scene.tag("ce-pad-2")).boundsInRoot.center, Offset(0f, 120f))
                assertEquals(1, actions.size, "$actions")

                // A screen reader activates the PAD and cuts at that moment.
                frame = 12_000
                val node = requireNotNull(scene.tag("ce-pad-6"))
                val click = requireNotNull(node.config.getOrNull(SemanticsActions.OnClick))
                assertEquals("今の位置で切る", click.label)
                assertTrue(requireNotNull(click.action).invoke())
                assertEquals(ContinuousEditorAction.CapturePad(6, 12_000), actions.last())
                scene.capture("chop-live-desktop.png")

                scene.click("ce-live-chop")
                assertEquals(ContinuousEditorAction.EndLiveChop, actions.last())
                assertTrue(texts().none { "音に合わせてPADを叩く" in it })
                scene.click("ce-pad-2")
                assertTrue(actions.takeLast(2).any { it == ContinuousEditorAction.SelectPad(2) }, "After the pass a PAD selects and plays again")
                assertTrue(actions.none { it is ContinuousEditorAction.CapturePad && it.padId == 2 })
            } finally { scene.close() }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun diagnosticsCardShowsOutputHealthAndCopiesIt() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        try {
            val health = ContinuousDiagnostics(outputAttached = true, floatOutput = true, blockFrames = 256, bufferFrames = 1920,
                pendingFrames = 1440, underruns = 2, outputLosses = 1, measuredBlocks = 4096, renderP99 = .184, renderMax = .52,
                drawnFrames = 1200, slowFrames = 3)
            val actions = mutableListOf<ContinuousEditorAction>()
            val scene = ImageComposeScene(width = 1440, height = 1600, density = Density(1f), coroutineContext = coroutineContext) {
                ContinuousEditor(ContinuousEditorFixture.state(ContinuousStage.SAVE), actions::add, ContinuousEditorFixture::readout,
                    diagnostics = { health })
            }
            try {
                scene.settle()
                assertNotNull(scene.tag("ce-diagnostics"))
                val shown = scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                for (expected in listOf("音の診断", "つながっている", "48 kHz・ステレオ・32bit 浮動小数点", "256 フレーム（5.3 ms）",
                    "1920 フレーム（40.0 ms）", "30.0 ms", "99%値 18%・最大 52%", "2 回", "1 回", "開いてから 1200 フレーム中 3 回")) {
                    assertTrue(shown.any { expected in it }, "Missing $expected in $shown")
                }
                // A screen reader hears each row as one item: its label with its value.
                val rows = scene.semanticsOwners.flatMap { owner ->
                    buildList { fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }; visit(owner.rootSemanticsNode) }
                }.map { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }
                assertTrue(rows.any { it == listOf("出力の遅れ（推定）", "30.0 ms") }, "No merged row: $rows")
                scene.capture("save-diagnostics-desktop.png")
                scene.click("ce-diag-copy")
                val copied = assertIs<ContinuousEditorAction.CopyDiagnostics>(actions.last()).text
                assertTrue(copied.startsWith("音の診断\n") && "音声出力: つながっている" in copied && "出力の遅れ（推定）: 30.0 ms" in copied, copied)
            } finally { scene.close() }

            // Nothing measured and nothing reported: the card says so instead of showing zeros.
            val quiet = ImageComposeScene(width = 390, height = 2200, density = Density(1f, 2f), coroutineContext = coroutineContext) {
                ContinuousEditor(ContinuousEditorFixture.state(ContinuousStage.SAVE), {}, ContinuousEditorFixture::readout,
                    diagnostics = { ContinuousDiagnostics(outputAttached = false) })
            }
            try {
                quiet.settle()
                val shown = quiet.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                for (expected in listOf("なし（編集と保存はできます）", "まだ測っていません（音を出すと測り始めます）", "—（出力なし）", "この環境では数えません")) {
                    assertTrue(shown.any { expected in it }, "Missing $expected in $shown")
                }
                assertTrue(shown.none { it == "分かりません" }, "Without output nothing reads as unreported by the device")
                // Scroll the SAVE page down so the card itself is in the picture.
                quiet.nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }.forEach { it(0f, 10_000f) }
                quiet.settle()
                val card = requireNotNull(quiet.tag("ce-diag-copy")).boundsInRoot
                assertTrue(card.height >= 48 && card.bottom <= 2200f, "The copy button is reachable and full size: $card")
                quiet.capture("save-diagnostics-phone-font200.png")
            } finally { quiet.close() }

            // Connected, but the device does not tell: that reads as unreported, not as no output.
            val silent = ImageComposeScene(width = 1440, height = 1600, density = Density(1f), coroutineContext = coroutineContext) {
                ContinuousEditor(ContinuousEditorFixture.state(ContinuousStage.SAVE), {}, ContinuousEditorFixture::readout,
                    diagnostics = { ContinuousDiagnostics(outputAttached = true, floatOutput = false) })
            }
            try {
                silent.settle()
                val shown = silent.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                assertTrue(shown.any { it == "48 kHz・ステレオ・16bit" } && shown.any { it == "分かりません" } && shown.none { "出力なし" in it }, "$shown")
            } finally { silent.close() }

            val hosts = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
                ContinuousEditor(ContinuousEditorFixture.state(ContinuousStage.SAVE), {}, ContinuousEditorFixture::readout)
            }
            try { hosts.settle(); assertNull(hosts.tag("ce-diagnostics"), "A host that measures nothing shows no card") } finally { hosts.close() }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun recordingTurnsTheVoiceButtonIntoStopAndExplainsItOnEveryPane() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        try {
            val state = mutableStateOf(ContinuousEditorFixture.state(ContinuousStage.BEAT).let { it.copy(capabilities = it.capabilities + ContinuousCapability.RECORD_VOICE) })
            val actions = mutableListOf<ContinuousEditorAction>()
            val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
                ContinuousEditor(state.value, { actions += it }, ContinuousEditorFixture::readout)
            }
            fun ImageComposeScene.texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            try {
                scene.settle()
                assertNull(scene.tag("ce-voice-hint"))
                scene.click("ce-record-voice")
                assertEquals(ContinuousEditorAction.RecordVoice, actions.last())
                // While recording, the same button stops the take, and the hint says how.
                state.value = state.value.copy(recordingVoice = true, capabilities = setOf(ContinuousCapability.STOP_ALL, ContinuousCapability.SONG_PLAYBACK,
                    ContinuousCapability.PAD_AUDITION), unavailable = ContinuousCapability.entries.associateWith { ContinuousUnavailable.RECORDING })
                scene.settle()
                assertTrue(scene.texts().any { it == "録音を止める" })
                val hint = requireNotNull(scene.tag("ce-voice-hint"))
                assertEquals(LiveRegionMode.Polite, hint.config.getOrNull(SemanticsProperties.LiveRegion), "Screen readers hear that recording began")
                scene.click("ce-record-voice")
                assertEquals(ContinuousEditorAction.StopVoice, actions.last())
                scene.capture("beat-recording-desktop.png")
            } finally { scene.close() }

            // On a phone at double text size (a tall one, as the PAD pane is short there) the hint sits under the stop
            // button in the PAD pane; the status line explains refusals.
            val phone = ImageComposeScene(width = 390, height = 2200, density = Density(1f, 2f), coroutineContext = coroutineContext) {
                ContinuousEditor(ContinuousEditorFixture.state().copy(recordingVoice = true, status = ContinuousStatus.RECORDING_BUSY),
                    {}, ContinuousEditorFixture::readout)
            }
            try {
                phone.settle()
                assertTrue(phone.texts().any { it == "録音中はできません。先に「録音を止める」を押してください。" })
                assertTrue(phone.texts().any { it == "PAD（録音中）" }, "The pane switch shows where the take's stop button is")
                phone.nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }.forEach { it(0f, 10_000f) }
                phone.settle()
                val stop = requireNotNull(phone.tag("ce-record-voice")).boundsInRoot
                val hint = requireNotNull(phone.tag("ce-voice-hint")).boundsInRoot
                assertTrue(stop.height >= 48f && hint.top >= stop.bottom, "The hint follows the stop button: $stop $hint")
                phone.capture("beat-recording-phone-font200.png")
            } finally { phone.close() }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun theScratchPanelHoldsDragsAndLetsGoAndOffersScreenReaderActions() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        try {
            val state = mutableStateOf(ContinuousEditorFixture.state(ContinuousStage.BEAT).let {
                it.copy(capabilities = it.capabilities + ContinuousCapability.SCRATCH,
                    scratch = ContinuousScratch(ContinuousScratchTarget.PAD, padAvailable = true, originalAvailable = true))
            })
            val actions = mutableListOf<ContinuousEditorAction>()
            val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
                ContinuousEditor(state.value, { actions += it }, { ContinuousEditorReadout(scratchFraction = .25f) })
            }
            fun ImageComposeScene.texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            try {
                scene.settle()
                assertNotNull(scene.tag("ce-scratch-panel"))
                assertTrue(scene.texts().any { it.startsWith("選んだPAD") } && scene.texts().any { it.startsWith("原曲の範囲") })
                scene.click("ce-scratch-target-original")
                assertEquals(ContinuousEditorAction.SetScratchTarget(ContinuousScratchTarget.ORIGINAL), actions.last())
                scene.click("ce-scratch-fine")
                assertEquals(ContinuousEditorAction.SetScratchSensitivity(ContinuousScratchSensitivity.FINE), actions.last())
                // A drag on the platter: hold, moves in screen pixels, let go.
                val platter = requireNotNull(scene.tag("ce-scratch-platter"))
                assertTrue(platter.boundsInRoot.width >= 190f)
                actions.clear()
                scene.drag(platter.boundsInRoot.center, Offset(60f, 0f))
                assertEquals(ContinuousEditorAction.ScratchHold, actions.first())
                assertEquals(ContinuousEditorAction.ScratchLetGo, actions.last())
                assertEquals(60f, actions.filterIsInstance<ContinuousEditorAction.ScratchDrag>().sumOf { it.distancePx.toDouble() }.toFloat(), .01f)
                // Screen readers scratch back or forward with actions.
                val custom = platter.config.getOrNull(SemanticsActions.CustomActions).orEmpty()
                assertEquals(listOf("左へこする", "右へこする"), custom.map { it.label })
                actions.clear()
                custom[1].action()
                assertEquals(ContinuousEditorAction.ScratchNudge(true), actions.single())
                requireNotNull(scene.tag("ce-scratch-cut")!!.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(.3f)
                assertEquals(ContinuousEditorAction.SetScratchCut(.3f), actions.last())
                scene.click("ce-scratch-close")
                assertEquals(ContinuousEditorAction.CloseScratch, actions.last())
                state.value = state.value.copy(scratch = state.value.scratch!!.copy(holding = true))
                scene.settle()
                assertEquals("こすっています", scene.tag("ce-scratch-platter")!!.config.getOrNull(SemanticsProperties.StateDescription))
                scene.capture("beat-scratch-desktop.png")
            } finally { scene.close() }

            // Counted in screen pixels as in the earlier app, also on a dense screen.
            val dense = ImageComposeScene(width = 1440, height = 2048, density = Density(2f), coroutineContext = coroutineContext) {
                ContinuousEditor(state.value, { actions += it }, { ContinuousEditorReadout() })
            }
            try {
                dense.settle()
                val platter = requireNotNull(dense.tag("ce-scratch-platter"))
                actions.clear()
                dense.drag(platter.boundsInRoot.center, Offset(60f, 0f))
                assertEquals(60f, actions.filterIsInstance<ContinuousEditorAction.ScratchDrag>().sumOf { it.distancePx.toDouble() }.toFloat(), .01f)
            } finally { dense.close() }

            // A phone at double text size reaches the platter, the fader and the close button by scrolling.
            val phone = ImageComposeScene(width = 390, height = 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
                ContinuousEditor(state.value, {}, { ContinuousEditorReadout(scratchFraction = .6f) })
            }
            try {
                phone.settle()
                assertNotNull(phone.tag("ce-scratch-platter"))
                phone.nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }.forEach { it(0f, 10_000f) }
                phone.settle()
                val close = requireNotNull(phone.tag("ce-scratch-close")).boundsInRoot
                assertTrue(close.height >= 48f && close.bottom <= 844f, "Close stays reachable: $close")
                val cut = requireNotNull(phone.tag("ce-scratch-cut")).boundsInRoot
                assertTrue(cut.width >= 150f, "The cut fader keeps a usable width at large text: $cut")
                phone.capture("beat-scratch-phone-font200.png")
            } finally { phone.close() }
        } finally { Locale.setDefault(previous) }
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
