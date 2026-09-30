@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.choplab.core.ProgramCompiler
import com.choplab.core.DocumentState
import com.choplab.core.ai.*
import com.choplab.core.model.Project
import com.choplab.engine.Tempo
import com.choplab.ui.ai.*
import java.io.File
import java.util.Locale
import kotlin.test.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow

class ContinuousEditorTest {
    private val output = File(System.getProperty("choplab.ui.evidenceDir")).resolve("linked-ui").apply { mkdirs() }

    @Test fun pcmMissReadoutPreservesTheInstrumentAndExposesReloadStopAndCloseAtLargeText() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) for ((width, height, font) in listOf(
                Triple(1440, 1024, 1f), Triple(390, 844, 2f), Triple(320, 844, 2f))) {
                Locale.setDefault(locale)
                val actions = mutableListOf<ContinuousEditorAction>()
                val state = mutableStateOf(ContinuousEditorFixture.state().copy(capabilities = ContinuousCapability.entries.toSet()))
                val reading = mutableStateOf(ContinuousPcmReadout(com.choplab.engine.PcmReadStatus.PREFETCHING, 192, 2))
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                    ContinuousEditor(state.value, actions::add, { ContinuousEditorFixture.readout().copy(pcm = reading.value) })
                }
                fun full(tag: String) {
                    val node = requireNotNull(scene.tag(tag)) { tag }
                    val bounds = node.boundsInWindow
                    assertEquals(node.size.height.toFloat(), bounds.height, .5f, tag)
                    assertEquals(node.size.width.toFloat(), bounds.width, .5f, tag)
                    assertTrue(bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= height, tag)
                    assertTrue(bounds.width >= 48 && bounds.height >= 48, tag)
                }
                try {
                    scene.settle()
                    val songStop = requireNotNull(scene.tag("ce-song-stop"))
                    println("PCM_CONTROLS locale=${locale.language} width=$width font=$font stop=${songStop.boundsInWindow} size=${songStop.size}")
                    scene.capture("pcm-miss-instrument-${locale.language}-$width-font${(font * 100).toInt()}.png")
                    if (width == 1440) for (id in 0..15) full("ce-pad-$id")
                    for (tag in listOf("ce-stop-all", "ce-song-stop", "ce-pcm-status")) full(tag)
                    scene.click("ce-song-stop")
                    assertEquals(ContinuousEditorAction.StopSong, actions.last())
                    scene.click("ce-stop-all")
                    assertEquals(ContinuousEditorAction.StopAll, actions.last())
                    scene.reach("ce-song-monitor")
                    full("ce-song-stop")
                    scene.click("ce-pcm-status")
                    for (tag in listOf("ce-pcm-reload", "ce-pcm-stop", "ce-pcm-close")) full(tag)
                    scene.capture("pcm-miss-${locale.language}-$width-font${(font * 100).toInt()}.png")
                    scene.click("ce-pcm-stop")
                    assertEquals(ContinuousEditorAction.StopAll, actions.last())
                    scene.click("ce-pcm-close")
                    scene.awaitDialogClosed("ce-pcm-dialog")
                    assertNull(scene.tag("ce-pcm-status"), "Acknowledged past misses do not obscure the instrument")
                    reading.value = ContinuousPcmReadout() // A reattached output has fresh counters.
                    scene.settle()
                    reading.value = ContinuousPcmReadout(com.choplab.engine.PcmReadStatus.FAILED, 1, 0)
                    scene.settle()
                    scene.click("ce-pcm-status")
                    state.value = state.value.copy(capabilities = state.value.capabilities - ContinuousCapability.RELOAD_AUDIO)
                    scene.settle()
                    assertNotNull(requireNotNull(scene.tag("ce-pcm-reload")).config.getOrNull(SemanticsProperties.Disabled))
                    scene.click("ce-pcm-reload")
                    assertEquals(ContinuousEditorAction.StopAll, actions.last())
                    state.value = state.value.copy(capabilities = state.value.capabilities + ContinuousCapability.RELOAD_AUDIO)
                    scene.settle()
                    scene.click("ce-pcm-reload")
                    scene.awaitDialogClosed("ce-pcm-dialog")
                    assertEquals(ContinuousEditorAction.ReloadAudio, actions.last())
                    full("ce-stop-all"); full("ce-song-stop")
                } finally { scene.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun lyricProposalOpensFromVocalAndKeepsStopAndCloseReachableWithoutEnablingUnverifiedSending() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) for ((width, height, font) in listOf(
                Triple(1440, 1024, 1f), Triple(390, 844, 2f), Triple(844, 390, 2f))) {
                Locale.setDefault(locale)
                val actions = mutableListOf<ContinuousEditorAction>()
                val state = mutableStateOf(ContinuousEditorFixture.state())
                val active = mutableStateOf<LyricProposalController?>(null)
                var closes = 0
                val controller = LyricProposalController(MutableStateFlow(DocumentState(Project(), 0)), object : LlmProvider {
                    override suspend fun lyrics(request: LyricRequest, key: SessionApiKey): LyricProviderResult = error("Unverified host cannot send")
                    override fun close() { closes++ }
                }, LyricProposalApply { _, _ -> error("No preview exists") }, this)
                val send: (ContinuousEditorAction) -> Unit = { action ->
                    actions += action
                    when (action) {
                        is ContinuousEditorAction.Lyrics -> state.value = state.value.copy(lyrics = state.value.lyrics.copy(open = action.action == LyricAction.Open))
                        ContinuousEditorAction.OpenLyricProposal -> {
                            state.value = state.value.copy(lyrics = state.value.lyrics.copy(open = false)); active.value = controller
                        }
                        ContinuousEditorAction.CloseLyricProposal -> { controller.close(); active.value = null }
                        else -> Unit
                    }
                }
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                    ContinuousEditor(state.value, send, lyricProposal = active.value)
                }
                try {
                    scene.settle()
                    scene.click("ce-lyrics-open")
                    scene.click("ce-lyrics-ai-open")
                    withTimeout(5_000) { while (scene.tag("ce-lyrics-panel") != null || scene.semanticsOwners.size != 2) scene.settle() }
                    assertSame(controller, active.value)
                    assertNotNull(scene.tag("ai-lyrics-panel"))
                    for (tag in listOf("ce-lyric-proposal-stop", "ce-lyric-proposal-close")) {
                        val node = requireNotNull(scene.tag(tag))
                        assertTrue(node.size.height >= 48)
                        assertTrue(node.boundsInRoot.width >= node.size.width - 1 && node.boundsInRoot.height >= node.size.height - 1)
                        assertTrue(node.boundsInRoot.left >= 0 && node.boundsInRoot.right <= width && node.boundsInRoot.top >= 0 && node.boundsInRoot.bottom <= height)
                    }
                    scene.click("ce-lyric-proposal-stop")
                    assertEquals(ContinuousEditorAction.StopAll, actions.last())
                    assertTrue(requireNotNull(scene.tag("ai-generate")).config.contains(SemanticsProperties.Disabled))
                    scene.capture("lyric-proposal-${locale.language}-${width}x$height-font${(font * 100).toInt()}.png")
                    scene.click("ce-lyric-proposal-close")
                    scene.awaitDialogClosed("ce-lyric-proposal-dialog")
                    assertNull(active.value)
                    assertEquals(1, closes, "Button and panel disposal close their provider only once")
                    assertEquals(LyricProposalPhase.CLOSED, controller.state.value.phase)
                    scene.click("ce-lyrics-open")
                    state.value = state.value.copy(capabilities = state.value.capabilities - ContinuousCapability.LYRIC_PROPOSAL)
                    scene.settle()
                    assertTrue(requireNotNull(scene.tag("ce-lyrics-ai-open")).config.contains(SemanticsProperties.Disabled))
                } finally { scene.close(); controller.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun recordingGuideSettingsAndStopRemainReachableAndEarlyPadPressesAreNotRecorded() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) {
                Locale.setDefault(locale)
                val actions = mutableListOf<ContinuousEditorAction>()
                val state = mutableStateOf(ContinuousEditorFixture.state(ContinuousStage.BEAT).copy(
                    recordingGuide = RecordingGuideState(settingsEnabled = true)))
                var countIn = 0
                val scene = ImageComposeScene(width = 390, height = 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
                    ContinuousEditor(state.value, actions::add, { ContinuousEditorReadout(songFrame = 0, countInBeatsRemaining = countIn) })
                }
                try {
                    scene.settle()
                    scene.click("ce-pad-details")
                    for (bars in 0..2) {
                        scene.click("ce-count-in-$bars")
                        assertEquals(ContinuousEditorAction.RecordingGuide(RecordingGuideAction.CountInBars(bars)), actions.last())
                    }
                    scene.click("ce-metronome")
                    assertEquals(ContinuousEditorAction.RecordingGuide(RecordingGuideAction.Metronome(true)), actions.last())
                    scene.capture("recording-guide-${locale.language}-font200.png")
                    countIn = 4
                    state.value = state.value.copy(recordingVoice = true,
                        recordingGuide = RecordingGuideState(countInBars = 1, beatsRemaining = 4, settingsEnabled = false))
                    scene.settle()
                    assertTrue(requireNotNull(scene.tag("ce-metronome")).config.contains(SemanticsProperties.Disabled))
                    scene.click("ce-record-voice")
                    assertEquals(ContinuousEditorAction.StopVoice, actions.last())
                    assertEquals(LiveRegionMode.Polite, requireNotNull(scene.tag("ce-count-in-progress")).config[SemanticsProperties.LiveRegion])
                    state.value = state.value.copy(recordingVoice = false, recordingHits = true)
                    scene.settle()
                    actions.clear()
                    scene.click("ce-pad-0")
                    assertFalse(actions.any { it is ContinuousEditorAction.BeginHit }, "Read the audio clock at pointer-down, not a stale display tick")
                    countIn = 0
                    actions.clear()
                    scene.click("ce-pad-0")
                    assertEquals(1, actions.count { it is ContinuousEditorAction.BeginHit })
                    assertEquals(1, actions.count { it is ContinuousEditorAction.EndHit })
                } finally { scene.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun separationSelectionIsAnExplicitSourceAction() = runBlocking<Unit> {
        val actions = mutableListOf<ContinuousEditorAction>()
        val fixture = ContinuousEditorFixture.state(ContinuousStage.CAPTURE)
        val scene = ImageComposeScene(width = 1440, height = 1024, coroutineContext = coroutineContext) {
            ContinuousEditor(fixture.copy(capabilities = fixture.capabilities + ContinuousCapability.SEPARATE_SOURCE), actions::add)
        }
        try {
            scene.settle()
            scene.nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }.forEach { it(0f, 10_000f) }
            scene.settle()
            val button = requireNotNull(scene.tag("ce-separate"))
            assertTrue(button.boundsInRoot.height >= 48f && button.boundsInRoot.bottom <= 1024)
            scene.click("ce-separate")
            assertEquals(listOf<ContinuousEditorAction>(ContinuousEditorAction.SeparateSource), actions)
        } finally { scene.close() }
    }

    @Test fun onlineSelectionIsAnExplicitSourceAction() = runBlocking<Unit> {
        val actions = mutableListOf<ContinuousEditorAction>()
        val fixture = ContinuousEditorFixture.state(ContinuousStage.CAPTURE)
        val scene = ImageComposeScene(width = 1440, height = 1024, coroutineContext = coroutineContext) {
            ContinuousEditor(fixture.copy(capabilities = fixture.capabilities + ContinuousCapability.IMPORT_ONLINE), actions::add)
        }
        try {
            scene.settle()
            scene.nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }.forEach { it(0f, 10_000f) }
            scene.settle()
            val button = requireNotNull(scene.tag("ce-online"))
            assertTrue(button.boundsInRoot.height >= 48f && button.boundsInRoot.bottom <= 1024)
            scene.click("ce-online")
            assertEquals(listOf<ContinuousEditorAction>(ContinuousEditorAction.ImportOnline), actions)
        } finally { scene.close() }
    }

    @Test fun librarySelectionIsAnExplicitSourceAction() = runBlocking<Unit> {
        val actions = mutableListOf<ContinuousEditorAction>()
        val fixture = ContinuousEditorFixture.state(ContinuousStage.CAPTURE)
        val scene = ImageComposeScene(width = 1440, height = 1024, coroutineContext = coroutineContext) {
            ContinuousEditor(fixture.copy(capabilities = fixture.capabilities + ContinuousCapability.IMPORT_LIBRARY), actions::add)
        }
        try {
            scene.settle()
            scene.nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }.forEach { it(0f, 10_000f) }
            scene.settle()
            val button = requireNotNull(scene.tag("ce-library"))
            assertTrue(button.boundsInRoot.height >= 48f && button.boundsInRoot.bottom <= 1024)
            scene.click("ce-library")
            assertEquals(listOf<ContinuousEditorAction>(ContinuousEditorAction.ImportLibrary), actions)
        } finally { scene.close() }
    }

    @Test fun spotifyMetadataIsAnExplicitSourceActionAndExplainsUnavailableStates() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPAN, Locale.US)) {
                Locale.setDefault(locale)
                val fixture = ContinuousEditorFixture.state(ContinuousStage.CAPTURE)
                val state = mutableStateOf(fixture.copy(capabilities = fixture.capabilities + ContinuousCapability.SPOTIFY_METADATA))
                val actions = mutableListOf<ContinuousEditorAction>()
                val scene = ImageComposeScene(width = 390, height = 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
                    ContinuousEditor(state.value, actions::add)
                }
                try {
                    scene.settle()
                    scene.nodes().filter { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
                        .mapNotNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }.forEach { it(0f, 10_000f) }
                    repeat(4) { scene.settle() }
                    val button = requireNotNull(scene.tag("ce-spotify-metadata"))
                    assertTrue(button.boundsInRoot.width >= 48 && button.boundsInRoot.height >= 48)
                    assertEquals(button.size.height.toFloat(), button.boundsInRoot.height, .5f)
                    scene.click("ce-spotify-metadata")
                    assertEquals(listOf<ContinuousEditorAction>(ContinuousEditorAction.OpenSpotifyMetadata), actions)
                    for (reason in listOf(ContinuousUnavailable.BUSY, ContinuousUnavailable.RECORDING, ContinuousUnavailable.NOT_CONNECTED)) {
                        state.value = state.value.copy(capabilities = fixture.capabilities - ContinuousCapability.SPOTIFY_METADATA,
                            unavailable = mapOf(ContinuousCapability.SPOTIFY_METADATA to reason))
                        scene.settle()
                        scene.click("ce-spotify-metadata")
                        val disabled = requireNotNull(scene.tag("ce-spotify-metadata"))
                        assertTrue(disabled.config.contains(SemanticsProperties.Disabled))
                        assertFalse(disabled.config.getOrNull(SemanticsProperties.StateDescription).isNullOrBlank(), "$locale $reason")
                        assertEquals(1, actions.size, "A disabled provider button emits no request: $locale $reason")
                    }
                    scene.capture("spotify-source-${locale.language}-font200.png")
                } finally { scene.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun sourceInputsShowRecordingAndReachableStopAndDiscardAtLargeText() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        try {
            for (system in listOf(false, true)) for ((width, height, font) in listOf(Triple(1440, 1024, 1f), Triple(390, 844, 2f))) {
                val state = mutableStateOf(ContinuousEditorFixture.state(ContinuousStage.CAPTURE).copy(recordingSource = true, recordingSystemAudio = system, capabilities = setOf(ContinuousCapability.STOP_ALL)))
                val actions = mutableListOf<ContinuousEditorAction>()
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                    ContinuousEditor(state.value, actions::add, { ContinuousEditorReadout(recordingMillis = 12_345) })
                }
                try {
                    scene.settle()
                    scene.nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }.forEach { it(0f, 10_000f) }
                    scene.settle()
                    val stopTag = if (system) "ce-system-record-stop" else "ce-source-record-stop"
                    val stop = requireNotNull(scene.tag(stopTag))
                    val discard = requireNotNull(scene.tag("ce-source-record-discard"))
                    assertTrue(stop.boundsInRoot.height >= 48f && stop.boundsInRoot.top >= 0 && stop.boundsInRoot.bottom <= height)
                    assertTrue(discard.boundsInRoot.height >= 48f && discard.boundsInRoot.bottom <= height)
                    assertTrue(requireNotNull(scene.tag("ce-source-recording")).config[SemanticsProperties.Text].joinToString().contains("12"))
                    scene.click(stopTag)
                    scene.click("ce-source-record-discard")
                    assertEquals(listOf(ContinuousEditorAction.StopSourceRecording, ContinuousEditorAction.DiscardSourceRecording), actions)
                    scene.capture("source-recording-${system}-${width}-font${(font * 100).toInt()}.png")
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
                        val pad = requireNotNull(scene.tag("ce-pad-0")).boundsInRoot
                        assertTrue(pad.width > 100f && pad.height == pad.width, "The instrument keeps large square PADs within its width and height: $pad")
                        scene.capture("beat-desktop.png")
                        listOf("ce-add-drums", "ce-record-hits", "ce-record-voice", "ce-scratch").forEach { tag ->
                            scene.reach(tag)
                            val bounds = requireNotNull(scene.tag(tag)).boundsInRoot
                            assertTrue(bounds.height >= 48 && bounds.bottom <= 1024f, "Instrument actions scroll into view: $tag $bounds")
                        }
                        assertTrue(requireNotNull(scene.tag("ce-song-seek")).boundsInRoot.width > 600, "Wide song transport must use the available width")
                        assertTrue(requireNotNull(scene.tag("ce-clip-scratch-1")).boundsInRoot.height >= 93.9f, "All four reference tracks must fit without clipping the last clip")
                    }
                    scene.capture(if (stage == ContinuousStage.BEAT) "beat-desktop-controls.png" else "${stage.name.lowercase()}-desktop.png")
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

    @Test fun wideInitialLayoutShowsAndPlaysAllSixteenPadsWithoutScrolling() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        try {
            val sides = mutableListOf<Int>()
            for ((width, height) in listOf(1440 to 1024, 1920 to 1080)) {
                val actions = mutableListOf<ContinuousEditorAction>()
                val state = mutableStateOf(ContinuousEditorFixture.state().copy(canUndo = true,
                    capabilities = ContinuousCapability.entries.toSet()))
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f), coroutineContext = coroutineContext) {
                    ContinuousEditor(state.value, { action ->
                        actions += action
                        if (action is ContinuousEditorAction.SelectPad) state.value = state.value.copy(selectedPadId = action.padId)
                    }, ContinuousEditorFixture::readout)
                }
                try {
                    scene.settle()
                    val scroll = requireNotNull(requireNotNull(scene.tag("ce-pads-pane")).config.getOrNull(SemanticsProperties.VerticalScrollAxisRange))
                    assertEquals(0f, scroll.value(), "The initial instrument is not scrolled")
                    assertTrue(scroll.maxValue() <= 1f, "All initial instrument controls fit the available height")
                    val initial = (0..15).associateWith { id ->
                        val node = requireNotNull(scene.tag("ce-pad-$id"))
                        val bounds = node.boundsInWindow
                        assertEquals(node.size.width.toFloat(), bounds.width, .5f, "PAD $id is fully visible horizontally")
                        assertEquals(node.size.height.toFloat(), bounds.height, .5f, "PAD $id is fully visible vertically")
                        assertTrue(bounds.top >= 0 && bounds.bottom <= height && bounds.left >= 0 && bounds.right <= width)
                        assertEquals(bounds.width, bounds.height, .5f)
                        assertTrue(bounds.width > 100f, "The initial 4×4 instrument keeps large targets: $bounds")
                        bounds
                    }
                    sides += requireNotNull(scene.tag("ce-pad-0")).size.width
                    for (tag in listOf("ce-pad-details", "ce-bank-0", "ce-add-drums", "ce-record-hits", "ce-record-voice", "ce-scratch", "ce-lyrics-open",
                            "ce-original-play", "ce-source-monitor", "ce-undo", "ce-stop-all", "ce-song-stop")) {
                        val node = requireNotNull(scene.tag(tag))
                        assertEquals(node.size.height.toFloat(), node.boundsInWindow.height, .5f, "$tag stays wholly visible")
                        assertTrue(node.boundsInWindow.height >= 48f, "$tag retains its input target")
                    }
                    scene.capture("wide-all-pads-${width}x$height-initial.png")
                    // Intentionally no reach()/scroll helper: every press uses the initial window coordinates.
                    for (id in 0..15) {
                        val before = actions.size
                        val center = requireNotNull(initial[id]).center
                        scene.sendPointerEvent(PointerEventType.Press, center, type = PointerType.Mouse,
                            buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                        scene.render(System.nanoTime()).close()
                        scene.sendPointerEvent(PointerEventType.Release, center, type = PointerType.Mouse,
                            buttons = PointerButtons(), button = PointerButton.Primary)
                        scene.settle()
                        val expected = listOf(ContinuousEditorAction.SelectPad(id)) +
                            if (id < 4) listOf(ContinuousEditorAction.TapPad(id)) else emptyList()
                        assertEquals(expected, actions.drop(before))
                        assertEquals(id, state.value.selectedPadId)
                        assertEquals(0f, scroll.value(), "PAD input must not move the instrument")
                        assertEquals(initial[id], requireNotNull(scene.tag("ce-pad-$id")).boundsInWindow)
                    }
                    scene.capture("wide-all-pads-${width}x$height-pressed.png")
                } finally { scene.close() }
            }
            assertTrue(sides[1] > sides[0], "A taller instrument grows its PADs instead of using a fixed cap: $sides")
        } finally { Locale.setDefault(previous) }
    }

    @Test fun playableRowsAndEditingControlsFitRealWindowsAndLargeText() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        // A slower transition exposes clicks sent while a dismissed dialog still owns the modal layer.
        val motion = object : MotionDurationScale { override val scaleFactor = 2f }
        try {
            for ((width, height, font) in listOf(Triple(1440, 1024, 1f), Triple(1920, 1080, 1f),
                    Triple(390, 844, 1f), Triple(390, 844, 1.3f), Triple(390, 844, 2f), Triple(844, 390, 1f), Triple(844, 390, 2f))) {
                val actions = mutableListOf<ContinuousEditorAction>()
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext + motion) {
                    ContinuousEditor(ContinuousEditorFixture.state().copy(compactPane = ContinuousPane.PADS, canUndo = true,
                        capabilities = ContinuousCapability.entries.toSet()), actions::add, ContinuousEditorFixture::readout)
                }
                try {
                    scene.settle()
                    val name = "playable-${width}x${height}-font${(font * 100).toInt()}"
                    scene.capture("$name-initial.png")
                    for (tag in listOf("ce-stop-all", "ce-song-stop", "ce-nav-BEAT")) {
                        val node = requireNotNull(scene.tag(tag))
                        val bounds = node.boundsInRoot
                        assertTrue(bounds.width >= 48 && bounds.height >= 48 && bounds.bottom <= height, "$tag stays visible: $bounds")
                    }
                    // A phone starts with a whole row ready to play; short landscape scrolls to that row.
                    if (height < 600) scene.reach("ce-pad-0")
                    val first = requireNotNull(scene.tag("ce-pad-0"))
                    val top = first.boundsInRoot.top
                    for (id in 0..3) {
                        val node = requireNotNull(scene.tag("ce-pad-$id"))
                        val bounds = node.boundsInRoot
                        assertTrue(bounds.width >= 64 && bounds.height >= 64 && bounds.top == top && bounds.bottom <= height,
                            "A whole playable row is visible: PAD $id $bounds at $width×$height font $font")
                        assertEquals(node.size.width.toFloat(), bounds.width, .5f, "PAD $id is not clipped sideways")
                        assertEquals(node.size.height.toFloat(), bounds.height, .5f, "PAD $id is not clipped vertically")
                        assertEquals(bounds.width, bounds.height, .5f, "PAD $id stays square")
                    }
                    if (width >= 900) {
                        val grid = requireNotNull(scene.tag("ce-pad-grid")).size.width
                        assertTrue(first.size.width * 4f + 18f <= grid + 4f, "Wide PADs fit the width after the height bound")
                    }
                    scene.capture("$name-pads.png")
                    for (id in 0..15) {
                        val before = actions.size
                        scene.click("ce-pad-$id")
                        val pad = requireNotNull(scene.tag("ce-pad-$id")).boundsInRoot
                        assertTrue(pad.width >= 64 && pad.height >= 64 && pad.top >= 0 && pad.bottom <= height,
                            "Every PAD is fully reachable: $id $pad at $width×$height font $font")
                        assertEquals(pad.width, pad.height, .5f)
                        val expected = listOf(ContinuousEditorAction.SelectPad(id)) +
                            if (id < 4) listOf(ContinuousEditorAction.TapPad(id)) else emptyList()
                        assertEquals(expected, actions.drop(before), "A pointer activates only the reached PAD")
                    }
                    scene.capture("$name-last-row.png")
                    scene.click("ce-bank-7")
                    assertEquals(ContinuousEditorAction.SelectBank(7), actions.last())
                    scene.click("ce-bank-0")
                    assertEquals(ContinuousEditorAction.SelectBank(0), actions.last())
                    scene.click("ce-undo")
                    assertEquals(ContinuousEditorAction.Undo, actions.last())
                    // PAD details are opened explicitly, keeping the initial instrument available for playing.
                    scene.click("ce-pad-details")
                    scene.click("ce-pad-fill")
                    assertNotNull(scene.tag("ce-pad-fill-panel"))
                    val beforeFill = actions.size
                    scene.click("ce-pad-fill-apply")
                    assertEquals(1, actions.size - beforeFill, "$name: one pointer applies Fill once")
                    assertIs<ContinuousEditorAction.FillPad>(actions.last())
                    scene.awaitDialogClosed("ce-pad-fill-panel")
                    val beforePlay = actions.size
                    scene.click("ce-pad-play")
                    assertEquals(listOf(ContinuousEditorAction.OpenPadPlay), actions.drop(beforePlay), "$name: one pointer opens PAD play")
                    for (tag in listOf("ce-add-drums", "ce-record-hits", "ce-record-voice", "ce-scratch")) {
                        scene.reach(tag)
                        val bounds = requireNotNull(scene.tag(tag)).boundsInRoot
                        assertTrue(bounds.height >= 48 && bounds.bottom <= height, "$tag is reachable: $bounds")
                        scene.click(tag)
                    }
                    assertTrue(ContinuousEditorAction.RecordHits in actions && ContinuousEditorAction.RecordVoice in actions)
                    scene.capture("$name-controls.png")
                    scene.click("ce-stop-all")
                    scene.click("ce-song-stop")
                    assertEquals(listOf(ContinuousEditorAction.StopAll, ContinuousEditorAction.StopSong), actions.takeLast(2))
                    scene.reach("ce-source-monitor")
                    val monitor = requireNotNull(scene.tag("ce-source-monitor"))
                    assertTrue(monitor.touchBoundsInRoot.width >= 48 && monitor.touchBoundsInRoot.height >= 48,
                        "Original monitoring keeps its touch target: ${monitor.touchBoundsInRoot}")
                    requireNotNull(monitor.config.getOrNull(SemanticsActions.SetProgress)?.action)(.4f)
                    assertEquals(ContinuousEditorAction.SetOriginalMonitorGain(.4f), actions.last())
                } finally { scene.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun scrollingTheCompactPadGridNeverPlaysOrHoldsAPad() = runBlocking<Unit> {
        for (mode in listOf(ContinuousPadMode.ONE_SHOT, ContinuousPadMode.GATE)) {
            val actions = mutableListOf<ContinuousEditorAction>()
            val state = ContinuousEditorFixture.state().copy(compactPane = ContinuousPane.PADS)
            val scene = ImageComposeScene(width = 390, height = 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
                ContinuousEditor(state.copy(pads = state.pads.map { if (it.id == 0) it.copy(mode = mode) else it }), actions::add)
            }
            try {
                scene.settle()
                val before = requireNotNull(scene.tag("ce-pad-0")).positionInRoot.y
                scene.swipe(requireNotNull(scene.tag("ce-pad-0")).boundsInRoot.center, Offset(0f, -100f))
                assertTrue(requireNotNull(scene.tag("ce-pad-0")).positionInRoot.y < before - 20f, "Dragging a PAD scrolls the compact instrument")
                assertTrue(actions.none { it is ContinuousEditorAction.TapPad || it is ContinuousEditorAction.HoldPad || it is ContinuousEditorAction.PlacePad },
                    "Scroll cannot play, hold or place $mode: $actions")
            } finally { scene.close() }
        }
    }

    @Test fun heldGateIsReleasedOnceWhenItsCompactSurfaceDisappears() = runBlocking<Unit> {
        for (recording in listOf(false, true)) for (change in listOf("stage", "pane", "bank")) {
            val actions = mutableListOf<ContinuousEditorAction>()
            val fixture = ContinuousEditorFixture.state().let { state -> state.copy(compactPane = ContinuousPane.PADS,
                recordingHits = recording, pads = state.pads.map { if (it.id == 0) it.copy(mode = ContinuousPadMode.GATE) else it }) }
            val state = mutableStateOf(fixture)
            val scene = ImageComposeScene(width = 390, height = 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
                ContinuousEditor(state.value, actions::add, ContinuousEditorFixture::readout)
            }
            try {
                scene.settle()
                scene.reach("ce-pad-0")
                val center = requireNotNull(scene.tag("ce-pad-0")).boundsInRoot.center
                scene.sendPointerEvent(PointerEventType.Press, center, type = PointerType.Touch)
                scene.render(System.nanoTime()).close()
                if (!recording) delay(650)
                scene.settle()
                assertEquals(1, actions.count { it == ContinuousEditorAction.HoldPad(0) }, "GATE starts: $recording $change $actions")
                state.value = when (change) {
                    "stage" -> state.value.copy(stage = ContinuousStage.CAPTURE)
                    "pane" -> state.value.copy(compactPane = ContinuousPane.TIMELINE)
                    else -> state.value.copy(selectedBank = 1)
                }
                scene.settle()
                assertEquals(1, actions.count { it == ContinuousEditorAction.ReleasePad(0) }, "Removed GATE releases once: $recording $change $actions")
                scene.sendPointerEvent(PointerEventType.Release, center, type = PointerType.Touch)
                scene.settle()
                assertEquals(1, actions.count { it == ContinuousEditorAction.ReleasePad(0) }, "A late pointer-up cannot release twice")
                assertTrue(actions.none { it is ContinuousEditorAction.TapPad || it is ContinuousEditorAction.PlacePad })
                if (recording) {
                    val gesture = actions.filterIsInstance<ContinuousEditorAction.BeginHit>().single().gesture
                    val end = actions.filterIsInstance<ContinuousEditorAction.EndHit>().single()
                    assertEquals(gesture, end.gesture)
                    assertTrue(end.cancelled, "A removed surface cancels the pending recorded press")
                }
            } finally { scene.close() }
        }
    }

    @Test fun recordingStopIsReachableFromBothPanesAtActualCompactWindowHeights() = runBlocking<Unit> {
        for ((width, height) in listOf(390 to 844, 844 to 390)) for (voice in listOf(false, true)) {
            val actions = mutableListOf<ContinuousEditorAction>()
            val state = mutableStateOf(ContinuousEditorFixture.state().copy(compactPane = ContinuousPane.TIMELINE,
                recordingVoice = voice, recordingHits = !voice,
                capabilities = setOf(ContinuousCapability.STOP_ALL, ContinuousCapability.SONG_PLAYBACK, ContinuousCapability.PAD_AUDITION)))
            val scene = ImageComposeScene(width = width, height = height, density = Density(1f, 2f), coroutineContext = coroutineContext) {
                ContinuousEditor(state.value, { action ->
                    actions += action
                    if (action is ContinuousEditorAction.SelectCompactPane) state.value = state.value.copy(compactPane = action.pane)
                }, ContinuousEditorFixture::readout)
            }
            try {
                scene.settle()
                for (tag in listOf("ce-stop-all", "ce-song-stop")) {
                    val bounds = requireNotNull(scene.tag(tag)).boundsInRoot
                    assertTrue(bounds.width >= 48 && bounds.height >= 48 && bounds.bottom <= height, "$tag remains visible while recording")
                }
                scene.click("ce-pane-pads")
                val stopTag = if (voice) "ce-record-voice" else "ce-record-hits"
                scene.click(stopTag)
                assertEquals(if (voice) ContinuousEditorAction.StopVoice else ContinuousEditorAction.StopHits, actions.last())
                scene.capture("recording-stop-${if (voice) "voice" else "hits"}-${width}x$height-font200.png")
                scene.click("ce-stop-all")
                scene.click("ce-song-stop")
                assertEquals(listOf(ContinuousEditorAction.StopAll, ContinuousEditorAction.StopSong), actions.takeLast(2))
            } finally { scene.close() }
        }
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
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
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
            assertEquals(ContinuousEditorAction.NudgeClip("warm-1", forward = true), actions.last())
            // Screen readers move it a grid line at a time too.
            val moves = requireNotNull(requireNotNull(scene.tag("ce-clip-warm-1")).config.getOrNull(SemanticsActions.CustomActions))
            moves.first { it.label == "配置を前へ移動" }.action()
            assertEquals(ContinuousEditorAction.NudgeClip("warm-1", forward = false), actions.last())
            moves.first { it.label == "配置を後へ移動" }.action()
            assertEquals(ContinuousEditorAction.NudgeClip("warm-1", forward = true), actions.last())
        } finally { scene.close(); Locale.setDefault(previous) }
    }

    @Test fun theBeatGridShowsBarsSnapsAMovedClipAndOffersItsChoices() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        val state = ContinuousEditorFixture.state()
        val actions = mutableListOf<ContinuousEditorAction>()
        val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) { ContinuousEditor(state, actions::add, ContinuousEditorFixture::readout) }
        try {
            scene.settle()
            fun texts() = scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
            // The ruler counts bars: at 92 BPM and 25 px a second a bar is about 65 px, so each is numbered.
            assertTrue("小節" in texts())
            val ruler = requireNotNull(scene.tag("ce-ruler")).boundsInRoot
            val bars = scene.nodes().filter { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.toIntOrNull() != null } &&
                node.boundsInRoot.center.y in ruler.top..ruler.bottom }
            assertEquals(listOf("1", "2", "3"), bars.take(3).map { it.config[SemanticsProperties.Text].single().text })
            assertEquals(65.2f, bars[1].boundsInRoot.left - bars[0].boundsInRoot.left, .5f)
            // The choices: one selected, each read with what it is for.
            for ((grid, label) in listOf(ContinuousGrid.BEAT to "1拍", ContinuousGrid.HALF to "1/2拍", ContinuousGrid.QUARTER to "1/4拍",
                ContinuousGrid.EIGHTH_TRIPLET to "8 分 3 連", ContinuousGrid.SIXTEENTH_TRIPLET to "16 分 3 連", ContinuousGrid.FREE to "自由")) {
                val node = requireNotNull(scene.tag("ce-grid-${grid.name.lowercase()}"))
                assertEquals(grid == ContinuousGrid.BEAT, node.config.getOrNull(SemanticsProperties.Selected))
                assertEquals(listOf("拍に合わせる $label"), node.config.getOrNull(SemanticsProperties.ContentDescription))
                assertTrue(node.boundsInRoot.height >= 48f)
            }
            scene.click("ce-grid-quarter")
            assertEquals(ContinuousEditorAction.SetGrid(ContinuousGrid.QUARTER), actions.last())
            scene.reach("ce-grid-eighth_triplet")
            scene.click("ce-grid-eighth_triplet")
            assertEquals(ContinuousEditorAction.SetGrid(ContinuousGrid.EIGHTH_TRIPLET), actions.last())
            scene.reach("ce-grid-sixteenth_triplet")
            scene.click("ce-grid-sixteenth_triplet")
            assertEquals(ContinuousEditorAction.SetGrid(ContinuousGrid.SIXTEENTH_TRIPLET), actions.last())
            // The zoom buttons say what they do, not "−" and "+".
            assertEquals(listOf("縮小"), requireNotNull(scene.tag("ce-zoom-out")).config.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals(listOf("拡大"), requireNotNull(scene.tag("ce-zoom-in")).config.getOrNull(SemanticsProperties.ContentDescription))
            // While dragged, a clip shows where it will land: the beat nearest where the finger has taken it.
            val before = requireNotNull(scene.tag("ce-clip-warm-1")).boundsInRoot
            val start = before.center
            scene.sendPointerEvent(PointerEventType.Press, start, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
            repeat(5) { index ->
                scene.sendPointerEvent(PointerEventType.Move, start + Offset(10f * (index + 1), 0f), type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true))
                scene.render(System.nanoTime()).close(); delay(15)
            }
            val shown = requireNotNull(scene.tag("ce-clip-warm-1")).boundsInRoot.left - before.left
            scene.sendPointerEvent(PointerEventType.Release, start + Offset(50f, 0f), type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
            scene.settle()
            // The request carries where the finger went; the edit puts it on the nearest beat, where it was shown.
            val moved = actions.filterIsInstance<ContinuousEditorAction.MoveClip>().last()
            val beat = requireNotNull(ContinuousClipEdits.snapTick(moved.timelineStartFrame, Tempo(92_000), ContinuousGrid.BEAT))
            assertEquals(0L, beat % 960)
            val landing = ProgramCompiler.tickToFrame(beat, 92_000)
            assertEquals((landing - 6L * 48_000) / 48_000f * 25f, shown, 1f)
            assertNotEquals(moved.timelineStartFrame, landing, "Shown on the beat, not where the finger stopped")
            scene.capture("beat-grid-desktop.png")
        } finally { scene.close(); Locale.setDefault(previous) }
    }

    @Test fun straightTripletAndFreeGridChoicesRemainReachableAtWideAndCompactTextSizes() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPAN, Locale.US)) for ((width, height, font) in listOf(
                Triple(1920, 1080, 1.3f), Triple(1600, 900, 1.3f), Triple(390, 844, 2f))) {
                Locale.setDefault(locale)
                val actions = mutableListOf<ContinuousEditorAction>()
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                    ContinuousEditor(ContinuousEditorFixture.state().copy(compactPane = ContinuousPane.TIMELINE), actions::add, ContinuousEditorFixture::readout)
                }
                try {
                    scene.settle()
                    for (grid in ContinuousGrid.entries) {
                        val tag = "ce-grid-${grid.name.lowercase()}"
                        scene.reach(tag)
                        val node = requireNotNull(scene.tag(tag))
                        val bounds = node.boundsInWindow
                        assertTrue(bounds.width >= 48 && bounds.height >= 48 && bounds.width >= node.size.width - 1 &&
                            bounds.height >= node.size.height - 1 && bounds.left >= 0 && bounds.right <= width && bounds.top >= 0 && bounds.bottom <= height,
                            "$locale $width font=$font $tag clipped: $bounds, size=${node.size}")
                        scene.click(tag)
                        assertEquals(ContinuousEditorAction.SetGrid(grid), actions.last())
                    }
                } finally { scene.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    @Test fun onAPhoneWithLargeTextTheArrangementScrollsToEveryControl() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        val actions = mutableListOf<ContinuousEditorAction>()
        val scene = ImageComposeScene(width = 390, height = 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
            ContinuousEditor(ContinuousEditorFixture.state().copy(compactPane = ContinuousPane.TIMELINE), actions::add, ContinuousEditorFixture::readout)
        }
        try {
            scene.settle()
            val pane = requireNotNull(scene.tag("ce-arrangement-pane"))
            val scroll = requireNotNull(pane.config.getOrNull(SemanticsActions.ScrollBy)?.action) { "A short arrangement pane scrolls" }
            suspend fun reach(tag: String) {
                // Where it is laid out, visible or not; its bounds are empty until it scrolls into view.
                val top = requireNotNull(scene.tag(tag)) { tag }.positionInRoot.y
                scroll(0f, top - requireNotNull(scene.tag("ce-arrangement-pane")).boundsInRoot.top)
                scene.settle()
                val view = requireNotNull(scene.tag("ce-arrangement-pane")).boundsInRoot
                val bounds = requireNotNull(scene.tag(tag)).boundsInRoot
                assertTrue(bounds.width > 0 && bounds.top >= view.top - 1 && bounds.top < view.bottom, "$tag scrolls into view: $bounds in $view")
            }
            reach("ce-grid-beat")
            // The choices that do not fit across scroll sideways.
            val first = requireNotNull(scene.tag("ce-grid-beat")).boundsInRoot
            val choices = scene.nodes().first { it.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null && it.boundsInRoot.contains(first.center) }
            requireNotNull(choices.config[SemanticsActions.ScrollBy].action)(1_000f, 0f)
            scene.settle()
            val free = requireNotNull(scene.tag("ce-grid-free")).boundsInRoot
            assertTrue(free.width >= 48 && free.right <= 390, "Free scrolls into view: $free")
            scene.click("ce-grid-free")
            assertEquals(ContinuousEditorAction.SetGrid(ContinuousGrid.FREE), actions.last())
            reach("ce-timeline")
            reach("ce-clip-gain")
            scene.capture("beat-phone-timeline-grid-font200.png")
        } finally { scene.close(); Locale.setDefault(previous) }
    }

    @Test fun tappingAlongFillsInTheTempoAndOnlyApplyingChangesIt() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        val actions = mutableListOf<ContinuousEditorAction>()
        val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
            ContinuousEditor(ContinuousEditorFixture.state(), actions::add, ContinuousEditorFixture::readout)
        }
        try {
            scene.settle()
            scene.click("ce-tempo")
            fun value() = requireNotNull(scene.tag("ce-tempo-value")).config.getOrNull(SemanticsProperties.EditableText)?.text
            assertEquals("92", value())
            val tap = requireNotNull(scene.tag("ce-tap-tempo"))
            assertTrue(tap.boundsInRoot.height >= 64f, "A large target to tap in time")
            val press = requireNotNull(tap.config[SemanticsActions.OnClick].action)
            press(); scene.settle()
            assertEquals("92", value(), "One tap has no tempo yet")
            // Taps at once are faster than any song: the fastest tempo it takes.
            press(); press(); scene.settle()
            assertEquals("240", value())
            assertTrue(actions.none { it is ContinuousEditorAction.SetTempo }, "Nothing changes until it is applied")
            scene.capture("tempo-tap-desktop.png")
            scene.click("ce-tempo-apply")
            // With the swing it opened at, unchanged.
            assertEquals(ContinuousEditorAction.SetTempo(240, 500), actions.last())
        } finally { scene.close(); Locale.setDefault(previous) }
    }

    @Test fun theSwingIsChosenWithTheTempoAndShownBesideItWhenTheSongSwings() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        val actions = mutableListOf<ContinuousEditorAction>()
        val song = mutableStateOf(ContinuousEditorFixture.state())
        val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
            ContinuousEditor(song.value, actions::add, ContinuousEditorFixture::readout)
        }
        val choices = listOf(500, 540, 580, 620, 660, 710)
        try {
            scene.settle()
            // The tempo button's label, from the text inside it.
            fun label(): String = buildList { fun visit(node: SemanticsNode) { addAll(node.config.getOrNull(SemanticsProperties.Text).orEmpty()); node.children.forEach(::visit) }
                visit(requireNotNull(scene.tag("ce-tempo"))) }.joinToString { it.text }
            fun value() = requireNotNull(scene.tag("ce-swing-value")).config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
            fun chosen() = choices.filter { requireNotNull(scene.tag("ce-swing-$it")) { "ce-swing-$it" }.config.getOrNull(SemanticsProperties.Selected) == true }
            // A straight song shows its tempo only.
            assertEquals("92 BPM", label())
            scene.click("ce-tempo")
            assertEquals("スウィング 50%", value())
            assertEquals(listOf(500), chosen())
            val top = requireNotNull(scene.tag("ce-swing-500")).boundsInRoot.top
            for (choice in choices) {
                val node = requireNotNull(scene.tag("ce-swing-$choice"))
                assertTrue(node.size.height >= 48, "ce-swing-$choice keeps a 48 dp target")
                assertEquals(top, node.boundsInRoot.top, .5f, "All six in one row where they fit")
                assertEquals(listOf("スウィング ${choice / 10}%"), node.config.getOrNull(SemanticsProperties.ContentDescription))
            }
            scene.click("ce-swing-580")
            assertEquals("スウィング 58%", value())
            assertEquals(listOf(580), chosen())
            assertTrue(actions.none { it is ContinuousEditorAction.SetTempo }, "Nothing changes until it is applied")
            scene.capture("tempo-swing-desktop.png")
            scene.click("ce-tempo-apply")
            assertEquals(ContinuousEditorAction.SetTempo(92, 580), actions.last())
            // A swung song says so beside its tempo, and the panel opens at its swing, even one between the choices.
            song.value = song.value.copy(swingPermille = 670)
            scene.settle()
            assertEquals("92 BPM・スウィング67%", label())
            scene.click("ce-tempo")
            assertEquals("スウィング 67%", value())
            assertEquals(emptyList(), chosen())
            scene.click("ce-tempo-apply")
            assertEquals(ContinuousEditorAction.SetTempo(92, 670), actions.last(), "Applying the tempo keeps that swing")
        } finally { scene.close() }
        // On a phone with large text the six choices are two rows below the tap button, each a 48 dp target.
        val phone = ImageComposeScene(width = 390, height = 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
            ContinuousEditor(ContinuousEditorFixture.state().copy(swingPermille = 580), actions::add, ContinuousEditorFixture::readout)
        }
        try {
            phone.settle()
            requireNotNull(requireNotNull(phone.tag("ce-tempo")).config[SemanticsActions.OnClick].action)()
            phone.settle()
            for (tag in listOf("ce-tempo-value", "ce-tap-tempo", "ce-tempo-apply") + choices.map { "ce-swing-$it" })
                assertTrue(requireNotNull(phone.tag(tag)) { tag }.size.height >= 48, tag)
            assertEquals(2, choices.map { requireNotNull(phone.tag("ce-swing-$it")).positionInRoot.y }.distinct().size)
            phone.capture("tempo-swing-phone-font200.png")
        } finally { phone.close(); Locale.setDefault(previous) }
    }

    @Test fun theFillPanelChoosesSpacingAndLengthAndFillsFromTheSongPositionsBar() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        val actions = mutableListOf<ContinuousEditorAction>()
        val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
            ContinuousEditor(ContinuousEditorFixture.state(), actions::add, ContinuousEditorFixture::readout)
        }
        try {
            scene.settle()
            scene.click("ce-pad-details")
            // Beside placing once, in the one row of PAD actions the reference layout keeps.
            val audition = requireNotNull(scene.tag("ce-pad-audition")).boundsInRoot
            val place = requireNotNull(scene.tag("ce-place-pad")).boundsInRoot
            val fill = requireNotNull(scene.tag("ce-pad-fill")).boundsInRoot
            assertEquals(audition.top, fill.top, .5f)
            assertTrue(fill.height >= 48f && fill.left > place.left)
            scene.click("ce-pad-fill")
            fun text(tag: String) = requireNotNull(scene.tag(tag)) { tag }.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
            fun chosen(tag: String) = requireNotNull(scene.tag(tag)) { tag }.config.getOrNull(SemanticsProperties.Selected)
            // The fixture's song position 0:08 at 92 BPM (a bar is 2.61 s) is in the fourth bar.
            assertEquals("4小節目から", text("ce-pad-fill-from"))
            assertEquals(listOf(true, false, false), listOf("ce-fill-beat", "ce-fill-half", "ce-fill-quarter").map(::chosen))
            assertEquals(listOf(false, false, true, false), listOf(1, 2, 4, 8).map { chosen("ce-fill-bars-$it") })
            (listOf("ce-fill-beat", "ce-fill-half", "ce-fill-quarter", "ce-fill-eighth_triplet", "ce-fill-sixteenth_triplet") + listOf(1, 2, 4, 8).map { "ce-fill-bars-$it" }).forEach { tag ->
                assertTrue(requireNotNull(scene.tag(tag)).boundsInRoot.height >= 48f, tag)
            }
            scene.click("ce-fill-quarter")
            scene.click("ce-fill-bars-2")
            assertEquals(listOf(false, false, true), listOf("ce-fill-beat", "ce-fill-half", "ce-fill-quarter").map(::chosen))
            assertEquals(true, chosen("ce-fill-bars-2"))
            assertTrue(actions.none { it is ContinuousEditorAction.FillPad }, "Choosing places nothing")
            scene.capture("pad-fill-desktop.png")
            scene.click("ce-pad-fill-apply")
            assertEquals(ContinuousEditorAction.FillPad(2, "melody", ContinuousEditorFixture.readout().songFrame, ContinuousGrid.QUARTER, 2), actions.last())
            assertNull(scene.tag("ce-pad-fill-panel"), "Filling closes the panel")
        } finally { scene.close() }
        // On a phone with large text every choice is reachable.
        val phone = ImageComposeScene(width = 390, height = 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
            ContinuousEditor(ContinuousEditorFixture.state().copy(compactPane = ContinuousPane.PADS), actions::add, ContinuousEditorFixture::readout)
        }
        try {
            phone.settle()
            phone.click("ce-pad-details")
            phone.reach("ce-pad-fill")
            val opener = requireNotNull(phone.tag("ce-pad-fill"))
            requireNotNull(opener.config[SemanticsActions.OnClick].action)()
            phone.settle()
            for (tag in listOf("ce-fill-beat", "ce-fill-quarter", "ce-fill-eighth_triplet", "ce-fill-sixteenth_triplet", "ce-fill-bars-1", "ce-fill-bars-8", "ce-pad-fill-apply")) {
                phone.reach(tag)
                val node = requireNotNull(phone.tag(tag)) { tag }
                val bounds = node.boundsInWindow
                assertTrue(bounds.width >= 48 && bounds.height >= 48 && bounds.left >= 0 && bounds.right <= 390 && bounds.top >= 0 && bounds.bottom <= 844,
                    "$tag keeps a reachable 48 dp target: $bounds")
            }
            phone.reach("ce-fill-sixteenth_triplet")
            phone.click("ce-fill-sixteenth_triplet")
            phone.reach("ce-pad-fill-apply")
            phone.capture("pad-fill-phone-font200.png")
            phone.click("ce-pad-fill-apply")
            assertEquals(ContinuousEditorAction.FillPad(2, "melody", ContinuousEditorFixture.readout().songFrame, ContinuousGrid.SIXTEENTH_TRIPLET, 4), actions.last())
        } finally { phone.close(); Locale.setDefault(previous) }
    }

    @Test fun theRepeatPanelSaysWhatItWouldDoAndRepeatsOnlyIntoEmptyBars() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        val actions = mutableListOf<ContinuousEditorAction>()
        try {
            val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
                ContinuousEditor(ContinuousEditorFixture.state(), actions::add, ContinuousEditorFixture::readout)
            }
            try {
                scene.settle()
                assertTrue(requireNotNull(scene.tag("ce-repeat")).boundsInRoot.height >= 48f)
                scene.click("ce-repeat")
                fun text(tag: String) = requireNotNull(scene.tag(tag)) { tag }.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
                fun enabled() = requireNotNull(scene.tag("ce-repeat-apply")).config.getOrNull(SemanticsProperties.Disabled) == null
                // Song position 0:08.3 at 92 BPM (a bar is 2.61 s) is in the fourth bar. Bars 4-7 hold six clips, and
                // bars 8-11 already two (a drum and a voice at 0:20): four bars once would layer over them.
                assertEquals("4小節目から", text("ce-repeat-from"))
                assertEquals("8〜11小節目には、もう配置があります。先に空けるか、長さか回数を変えてください。", text("ce-repeat-plan"))
                assertFalse(enabled())
                // The button says why too, for TalkBack.
                assertEquals(text("ce-repeat-plan"), requireNotNull(scene.tag("ce-repeat-apply")).config.getOrNull(SemanticsProperties.StateDescription))
                (listOf(1, 2, 4, 8).map { "ce-repeat-bars-$it" } + listOf(1, 2, 4, 8).map { "ce-repeat-times-$it" }).forEach { tag ->
                    assertTrue(requireNotNull(scene.tag(tag)).boundsInRoot.height >= 48f, tag)
                }
                // Eight bars once: bars 4-11 hold eight clips and bars 12-19 are empty.
                scene.click("ce-repeat-bars-8")
                assertEquals(true, requireNotNull(scene.tag("ce-repeat-bars-8")).config.getOrNull(SemanticsProperties.Selected))
                assertEquals("この範囲の配置8個を、12〜19小節目にくり返します。", text("ce-repeat-plan"))
                // One bar once goes into one bar, the fifth, which already holds clips.
                scene.click("ce-repeat-bars-1")
                assertEquals("5小節目には、もう配置があります。先に空けるか、長さか回数を変えてください。", text("ce-repeat-plan"))
                scene.click("ce-repeat-bars-8")
                assertTrue(enabled())
                assertTrue(actions.none { it is ContinuousEditorAction.RepeatBars }, "Choosing repeats nothing")
                scene.capture("repeat-bars-desktop.png")
                scene.click("ce-repeat-apply")
                assertEquals(ContinuousEditorAction.RepeatBars(ContinuousEditorFixture.readout().songFrame, 8, 1), actions.last())
                assertNull(scene.tag("ce-repeat-panel"), "Repeating closes the panel")
            } finally { scene.close() }
            // Bars with nothing in them, or only a silent clip an earlier build saved, have nothing to repeat.
            fun seconds(value: Double) = (value * CONTINUOUS_TIMELINE_RATE).toLong()
            val silent = ContinuousClip("silent", "drums", "B01", seconds(8.0), 1, 1, 2, 192_000, sourceRate = 96_000)
            val empty = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
                ContinuousEditor(ContinuousEditorFixture.state().copy(clips = listOf(silent), selectedClipId = null), actions::add, ContinuousEditorFixture::readout)
            }
            try {
                empty.settle()
                empty.click("ce-repeat")
                assertEquals("この範囲には配置がありません。", requireNotNull(empty.tag("ce-repeat-plan")).config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text })
                assertNotNull(requireNotNull(empty.tag("ce-repeat-apply")).config.getOrNull(SemanticsProperties.Disabled))
            } finally { empty.close() }
            // A four-second sound in the fourth bar (a bar is 2.61 s) would play over its own repeat every bar, not every two.
            val long = ContinuousClip("long", "drums", "B01", seconds(8.0), seconds(4.0), 0, seconds(4.0), seconds(6.0))
            val outlasting = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
                ContinuousEditor(ContinuousEditorFixture.state().copy(clips = listOf(long), selectedClipId = null), actions::add, ContinuousEditorFixture::readout)
            }
            try {
                outlasting.settle()
                outlasting.click("ce-repeat")
                fun text() = requireNotNull(outlasting.tag("ce-repeat-plan")).config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
                outlasting.click("ce-repeat-bars-1")
                assertEquals("この範囲に、選んだ長さより長く続く音があるので、くり返すと音が重なります。長さを長くしてください。", text())
                val apply = requireNotNull(outlasting.tag("ce-repeat-apply"))
                assertNotNull(apply.config.getOrNull(SemanticsProperties.Disabled))
                assertEquals(text(), apply.config.getOrNull(SemanticsProperties.StateDescription))
                outlasting.click("ce-repeat-bars-2")
                assertEquals("この範囲の配置1個を、6〜7小節目にくり返します。", text())
                assertNull(requireNotNull(outlasting.tag("ce-repeat-apply")).config.getOrNull(SemanticsProperties.Disabled))
            } finally { outlasting.close() }
            // On a phone with large text every choice keeps a 48 dp target.
            val phone = ImageComposeScene(width = 390, height = 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
                ContinuousEditor(ContinuousEditorFixture.state().copy(compactPane = ContinuousPane.TIMELINE), actions::add, ContinuousEditorFixture::readout)
            }
            try {
                phone.settle()
                requireNotNull(requireNotNull(phone.tag("ce-repeat")).config[SemanticsActions.OnClick].action)()
                phone.settle()
                (listOf(1, 2, 4, 8).map { "ce-repeat-bars-$it" } + listOf(1, 2, 4, 8).map { "ce-repeat-times-$it" } + "ce-repeat-apply").forEach { tag ->
                    assertTrue(requireNotNull(phone.tag(tag)) { tag }.size.height >= 48, tag)
                }
                phone.capture("repeat-bars-phone-font200.png")
            } finally { phone.close() }
        } finally { Locale.setDefault(previous) }
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

    @Test fun padPlayPanelSetsReverseModeAndChokeAndClearsOnlyOnASecondPress() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        try {
            val state = mutableStateOf(ContinuousEditorFixture.state())
            val actions = mutableListOf<ContinuousEditorAction>()
            fun update(pad: (ContinuousPad) -> ContinuousPad) {
                state.value = state.value.copy(pads = state.value.pads.map { if (it.id == state.value.selectedPadId) pad(it) else it })
            }
            val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
                ContinuousEditor(state.value, { action ->
                    actions += action
                    when (action) {
                        ContinuousEditorAction.OpenPadPlay -> state.value = state.value.copy(padPlayOpen = true)
                        ContinuousEditorAction.ClosePadPlay -> state.value = state.value.copy(padPlayOpen = false)
                        is ContinuousEditorAction.SetPadReverse -> update { it.copy(reverse = action.reverse) }
                        is ContinuousEditorAction.SetPadMode -> update { it.copy(mode = action.mode) }
                        is ContinuousEditorAction.SetPadChoke -> update { it.copy(chokeGroup = action.group) }
                        else -> Unit
                    }
                }, ContinuousEditorFixture::readout)
            }
            try {
                scene.settle()
                // The opener sits with the PAD's other settings; the reference bottom actions stay where they were.
                assertTrue(requireNotNull(scene.tag("ce-add-drums")).boundsInRoot.bottom <= requireNotNull(scene.tag("ce-stage-BEAT")).boundsInRoot.bottom)
                scene.click("ce-pad-details")
                scene.click("ce-pad-play")
                assertEquals(ContinuousEditorAction.OpenPadPlay, actions.last())
                assertNotNull(scene.tag("ce-pad-play-panel"))
                for (tag in listOf("ce-reverse-off", "ce-reverse-on", "ce-mode-once", "ce-mode-held", "ce-choke-0", "ce-choke-4", "ce-clear-pad")) {
                    assertTrue(requireNotNull(scene.tag(tag)) { tag }.boundsInRoot.height >= 48, "$tag is a full-size button")
                }
                // Where it starts and ends, a millisecond or ten at a time, shown to the millisecond.
                fun text(tag: String) = requireNotNull(scene.tag(tag)) { tag }.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString { it.text }
                assertEquals("はじめ  0:00.000", text("ce-trim-start-time"))
                assertEquals("おわり  0:06.000", text("ce-trim-end-time"))
                for (tag in listOf("ce-trim-start-earlier-10", "ce-trim-start-later-1", "ce-trim-end-earlier-1", "ce-trim-end-later-10", "ce-trim-audition")) {
                    assertTrue(requireNotNull(scene.tag(tag)) { tag }.boundsInRoot.height >= 48, "$tag is a full-size button")
                }
                assertEquals("はじめを10ミリ秒前へ", requireNotNull(scene.tag("ce-trim-start-earlier-10")).config.getOrNull(SemanticsProperties.ContentDescription)?.single())
                scene.click("ce-trim-start-later-10")
                assertEquals(ContinuousEditorAction.NudgePadBoundary(2, end = false, milliseconds = 10), actions.last())
                scene.click("ce-trim-end-earlier-1")
                assertEquals(ContinuousEditorAction.NudgePadBoundary(2, end = true, milliseconds = -1), actions.last())
                scene.click("ce-trim-audition")
                assertEquals(ContinuousEditorAction.TapPad(2), actions.last())
                scene.click("ce-reverse-on")
                assertEquals(ContinuousEditorAction.SetPadReverse(2, true), actions.last())
                scene.click("ce-mode-held")
                assertEquals(ContinuousEditorAction.SetPadMode(2, ContinuousPadMode.GATE), actions.last())
                scene.click("ce-choke-2")
                assertEquals(ContinuousEditorAction.SetPadChoke(2, 2), actions.last())
                for (tag in listOf("ce-reverse-on", "ce-mode-held", "ce-choke-2")) {
                    assertEquals(true, requireNotNull(scene.tag(tag)).config.getOrNull(SemanticsProperties.Selected), "$tag reads as chosen")
                }
                assertEquals(false, requireNotNull(scene.tag("ce-choke-0")).config.getOrNull(SemanticsProperties.Selected))
                scene.capture("pad-play-desktop.png")
                fun asking() = scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.any { it.text == "もう一度押すと空にします" }
                val count = actions.size
                scene.click("ce-clear-pad")
                assertEquals(count, actions.size, "The first press only asks")
                assertTrue(asking())
                // A change to the PAD withdraws the question: a press asked about what it was before does not count.
                scene.click("ce-reverse-off")
                assertEquals(ContinuousEditorAction.SetPadReverse(2, false), actions.last())
                assertFalse(asking())
                val again = actions.size
                scene.click("ce-clear-pad")
                assertEquals(again, actions.size, "Asked again after the change")
                scene.click("ce-clear-pad")
                assertEquals(ContinuousEditorAction.ClearPad(2), actions.last())
                scene.click("ce-pad-play-close")
                assertEquals(ContinuousEditorAction.ClosePadPlay, actions.last())
                assertNull(scene.tag("ce-pad-play-panel"))
            } finally { scene.close() }
            val phone = ImageComposeScene(width = 390, height = 844, density = Density(1f, 2f), coroutineContext = coroutineContext) {
                ContinuousEditor(state.value.copy(padPlayOpen = true), {}, ContinuousEditorFixture::readout)
            }
            try {
                phone.settle()
                assertNotNull(phone.tag("ce-pad-play-panel"))
                // The panel scrolls on a phone: every button keeps its full size, also those below the first screen.
                for (tag in listOf("ce-trim-start-earlier-10", "ce-trim-end-later-10", "ce-trim-audition", "ce-reverse-on", "ce-mode-held", "ce-choke-4", "ce-clear-pad")) {
                    assertTrue(requireNotNull(phone.tag(tag)) { tag }.size.height >= 48, "$tag is a full-size button")
                }
                phone.capture("pad-play-phone-font200.png")
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

    @Test fun recordingThePadsTimesEachPressAsItGoesDownAndTheButtonStopsThePass() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        Locale.setDefault(Locale.JAPAN)
        try {
            // PAD A04 sounds while held and A01 loops; the fixture's others are one-shots.
            val state = mutableStateOf(ContinuousEditorFixture.state(ContinuousStage.BEAT).let { s ->
                s.copy(pads = s.pads.map { when (it.id) { 3 -> it.copy(mode = ContinuousPadMode.GATE); 0 -> it.copy(mode = ContinuousPadMode.LOOP); else -> it } }) })
            val actions = mutableListOf<ContinuousEditorAction>()
            // The song moves on while a PAD is down: the press, not the release, is when it was played.
            var songFrame = 100_000L
            val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
                ContinuousEditor(state.value, { actions += it }, { ContinuousEditorFixture.readout().copy(songFrame = songFrame) })
            }
            suspend fun press(tag: String, move: Offset = Offset.Zero) {
                scene.reach(tag)
                val center = requireNotNull(scene.tag(tag)) { tag }.boundsInRoot.center
                songFrame = 100_000L
                scene.sendPointerEvent(PointerEventType.Press, center, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                scene.render(System.nanoTime()).close()
                songFrame = 112_000L
                if (move != Offset.Zero) {
                    scene.sendPointerEvent(PointerEventType.Move, center + move, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true))
                    scene.render(System.nanoTime()).close()
                }
                scene.sendPointerEvent(PointerEventType.Release, center + move, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
                scene.settle()
            }
            try {
                scene.settle()
                // Beside adding drums, in the one row of actions under the PADs.
                scene.reach("ce-record-hits")
                val drums = requireNotNull(scene.tag("ce-add-drums")).boundsInRoot
                val record = requireNotNull(scene.tag("ce-record-hits")).boundsInRoot
                assertEquals(drums.top, record.top, .5f)
                assertTrue(record.height >= 48f && record.left > drums.left)
                assertNull(scene.tag("ce-hits-hint"))
                scene.click("ce-record-hits")
                assertEquals(ContinuousEditorAction.RecordHits, actions.last())
                // While recording, a PAD sounds as it goes down and is timed there; the same button stops the pass.
                state.value = state.value.copy(recordingHits = true, capabilities = setOf(ContinuousCapability.STOP_ALL,
                    ContinuousCapability.SONG_PLAYBACK, ContinuousCapability.PAD_AUDITION),
                    unavailable = ContinuousCapability.entries.associateWith { ContinuousUnavailable.RECORDING })
                scene.settle()
                val hint = requireNotNull(scene.tag("ce-hits-hint"))
                assertEquals(LiveRegionMode.Polite, hint.config.getOrNull(SemanticsProperties.LiveRegion), "Screen readers hear that recording began")
                // A one-shot plays out as a tap does, even when let go at once.
                actions.clear()
                press("ce-pad-2")
                val oneShot = (actions.first() as ContinuousEditorAction.BeginHit).gesture
                assertEquals(2, oneShot.padId); assertEquals(100_000L, oneShot.songFrame)
                assertEquals(listOf(ContinuousEditorAction.BeginHit(oneShot), ContinuousEditorAction.TapPad(2), ContinuousEditorAction.EndHit(oneShot, false, 112_000)), actions)
                // A PAD that sounds while held stops when let go.
                actions.clear()
                press("ce-pad-3")
                val gate = (actions.first() as ContinuousEditorAction.BeginHit).gesture
                assertEquals(3, gate.padId); assertEquals(100_000L, gate.songFrame)
                assertEquals(listOf(ContinuousEditorAction.BeginHit(gate), ContinuousEditorAction.HoldPad(3), ContinuousEditorAction.EndHit(gate, false, 112_000), ContinuousEditorAction.ReleasePad(3)), actions)
                // A loop keeps sounding on pointer-up; only the pass/explicit stop releases its voice.
                actions.clear()
                press("ce-pad-0")
                val loop = (actions.first() as ContinuousEditorAction.BeginHit).gesture
                assertEquals(listOf(ContinuousEditorAction.BeginHit(loop), ContinuousEditorAction.TapPad(0), ContinuousEditorAction.EndHit(loop, false, 112_000)), actions)
                // A touch that slides off the PAD sounded, but records nothing.
                actions.clear()
                press("ce-pad-2", Offset(0f, 160f))
                val cancelled = (actions.first() as ContinuousEditorAction.BeginHit).gesture
                assertEquals(listOf(ContinuousEditorAction.BeginHit(cancelled), ContinuousEditorAction.TapPad(2), ContinuousEditorAction.EndHit(cancelled, true, 112_000)), actions)
                actions.clear()
                press("ce-pad-9")
                assertEquals(emptyList(), actions, "An empty PAD plays and records nothing")
                // A screen reader plays and records a PAD as it is activated.
                val play = requireNotNull(requireNotNull(scene.tag("ce-pad-1")).config.getOrNull(SemanticsActions.OnClick))
                assertEquals("たたいて録る", play.label)
                requireNotNull(play.action).invoke()
                val accessiblePress = (actions.first() as ContinuousEditorAction.BeginHit).gesture
                assertEquals(1, accessiblePress.padId); assertEquals(112_000L, accessiblePress.songFrame)
                assertEquals(listOf(ContinuousEditorAction.BeginHit(accessiblePress), ContinuousEditorAction.TapPad(1)), actions)
                scene.capture("beat-recording-hits-desktop.png")
                scene.click("ce-record-hits")
                assertEquals(ContinuousEditorAction.StopHits, actions.last())
            } finally { scene.close() }
            // On a phone at double text size the four actions take two rows, each a 48 dp target, the hint below them,
            // and the PAD pane's button says it is recording.
            val phone = ImageComposeScene(width = 390, height = 2200, density = Density(1f, 2f), coroutineContext = coroutineContext) {
                ContinuousEditor(ContinuousEditorFixture.state().copy(recordingHits = true), {}, ContinuousEditorFixture::readout)
            }
            try {
                phone.settle()
                val tags = listOf("ce-add-drums", "ce-record-hits", "ce-record-voice", "ce-scratch")
                tags.forEach { tag -> assertTrue(requireNotNull(phone.tag(tag)) { tag }.size.height >= 48, tag) }
                assertEquals(2, tags.map { requireNotNull(phone.tag(it)).positionInRoot.y }.distinct().size)
                assertTrue(requireNotNull(phone.tag("ce-hits-hint")).positionInRoot.y > requireNotNull(phone.tag("ce-scratch")).positionInRoot.y)
                fun ImageComposeScene.texts() = nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.map { it.text }
                assertTrue(phone.texts().any { it == "PAD（録音中）" })
                phone.capture("beat-recording-hits-phone-font200.png")
            } finally { phone.close() }
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
                val sourceFace = requireNotNull(scene.tag("ce-scratch-source-platter")).boundsInRoot
                val handFace = requireNotNull(scene.tag("ce-scratch-platter")).boundsInRoot
                assertTrue(sourceFace.width >= 260f && handFace.width >= 260f)
                assertTrue(sourceFace.right < handFace.left, "SOURCE and HAND are separate, large left and right surfaces")
                scene.capture("source-hand-desktop-initial.png")
                scene.click("ce-scratch-source-play")
                assertEquals(ContinuousEditorAction.PlayOriginal, actions.last())
                scene.click("ce-scratch-source-stop")
                assertEquals(ContinuousEditorAction.StopOriginal, actions.last())
                scene.click("ce-scratch-source-start")
                assertEquals(ContinuousEditorAction.SeekOriginal(0), actions.last())
                scene.tag("ce-scratch-source-gain")!!.config[SemanticsActions.SetProgress].action!!.invoke(.2f)
                assertEquals(ContinuousEditorAction.SetOriginalMonitorGain(.2f), actions.last())
                scene.tag("ce-scratch-hand-gain")!!.config[SemanticsActions.SetProgress].action!!.invoke(.6f)
                assertEquals(ContinuousEditorAction.SetHandMonitorGain(.6f), actions.last())
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
                ContinuousEditor(state.value, { actions += it }, { ContinuousEditorReadout(scratchFraction = .6f) })
            }
            try {
                phone.settle()
                assertNotNull(phone.tag("ce-scratch-platter"))
                phone.click("ce-scratch-source-stop")
                assertEquals(ContinuousEditorAction.StopOriginal, actions.last())
                phone.nodes().mapNotNull { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }.forEach { it(0f, 10_000f) }
                phone.settle()
                val close = requireNotNull(phone.tag("ce-scratch-close")).boundsInRoot
                assertTrue(close.height >= 48f && close.bottom <= 844f, "Close stays reachable: $close")
                for (tag in listOf("ce-scratch-hand-stop", "ce-scratch-stop-all")) {
                    val stop = requireNotNull(phone.tag(tag)).boundsInRoot
                    assertTrue(stop.height >= 48f && stop.top >= 0f && stop.bottom <= 844f, "$tag stays visible while the panels scroll")
                }
                val cut = requireNotNull(phone.tag("ce-scratch-cut")).boundsInRoot
                assertTrue(cut.width >= 150f, "The cut fader keeps a usable width at large text: $cut")
                phone.capture("beat-scratch-phone-font200.png")
                phone.reach("ce-scratch-platter")
                actions.clear()
                val point = requireNotNull(phone.tag("ce-scratch-platter")).boundsInRoot.center
                phone.sendPointerEvent(PointerEventType.Press, point, type = PointerType.Mouse,
                    buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
                phone.settle()
                state.value = state.value.copy(scratch = null)
                phone.settle()
                phone.sendPointerEvent(PointerEventType.Release, point, type = PointerType.Mouse,
                    buttons = PointerButtons(), button = PointerButton.Primary)
                phone.settle()
                assertEquals(1, actions.count { it == ContinuousEditorAction.ScratchHold })
                assertEquals(1, actions.count { it == ContinuousEditorAction.ScratchLetGo }, "Disposing a held panel releases it once")
            } finally { phone.close() }
        } finally { Locale.setDefault(previous) }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(value: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }
    private suspend fun ImageComposeScene.settle() { repeat(8) { render(System.nanoTime()).close(); delay(12) } }
    private suspend fun ImageComposeScene.awaitDialogClosed(value: String) {
        // Compose removes the dialog's semantics before its exit animation releases the input-blocking layer.
        // Wait for the editor to be the only owner; a missing dialog tag alone is not readiness for a click.
        repeat(80) {
            render(System.nanoTime()).close()
            delay(12)
            if (tag(value) == null && semanticsOwners.size == 1) return
        }
        fail("$value did not release its modal layer: ${semanticsOwners.size} semantics owners remain")
    }
    /** Scroll real ancestor containers until the target has its complete hit rectangle, then use pointer input. */
    private suspend fun ImageComposeScene.reach(value: String) {
        fun SemanticsNode.contains(tag: String): Boolean = config.getOrNull(SemanticsProperties.TestTag) == tag || children.any { it.contains(tag) }
        repeat(3) {
            val ancestors = nodes().filter { it.contains(value) && it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null }
            for (ancestor in ancestors) {
                val node = requireNotNull(tag(value)) { value }
                val area = ancestor.boundsInRoot
                if (area.width <= 0 || area.height <= 0) continue
                val x = node.positionInRoot.x
                val y = node.positionInRoot.y
                val dx = if (ancestor.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null &&
                    (x < area.left || x + node.size.width > area.right)) x - area.left else 0f
                val dy = if (ancestor.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null &&
                    (y < area.top || y + node.size.height > area.bottom)) y - area.top else 0f
                if (dx != 0f || dy != 0f) {
                    requireNotNull(ancestor.config.getOrNull(SemanticsActions.ScrollBy)?.action)(dx, dy)
                    // Semantics ScrollBy animates. Measure the hit rectangle after the scroll stops, not mid-flight.
                    val horizontal = ancestor.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)
                    val vertical = ancestor.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange)
                    var previous: Pair<Float?, Float?>? = null
                    for (frame in 0..12) {
                        settle()
                        val position = horizontal?.value?.invoke() to vertical?.value?.invoke()
                        if (position == previous) break
                        previous = position
                    }
                }
            }
        }
        val node = requireNotNull(tag(value)) { value }
        assertTrue(node.boundsInRoot.width >= node.size.width - 1f && node.boundsInRoot.height >= node.size.height - 1f,
            "$value must have its full hit rectangle after scrolling: ${node.boundsInRoot} / ${node.size}")
    }
    private suspend fun ImageComposeScene.click(tag: String) {
        reach(tag)
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
    private suspend fun ImageComposeScene.swipe(start: Offset, distance: Offset) {
        sendPointerEvent(PointerEventType.Press, start, type = PointerType.Touch)
        repeat(5) { index ->
            sendPointerEvent(PointerEventType.Move, start + distance * ((index + 1) / 5f), type = PointerType.Touch)
            render(System.nanoTime()).close(); delay(15)
        }
        sendPointerEvent(PointerEventType.Release, start + distance, type = PointerType.Touch)
        settle()
    }
    private fun ImageComposeScene.capture(name: String) { render(System.nanoTime()).use { image -> requireNotNull(image.encodeToData()).use { File(output, name).writeBytes(it.bytes) } } }
}
