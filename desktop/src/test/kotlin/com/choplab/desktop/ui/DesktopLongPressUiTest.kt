@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.choplab.desktop.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.choplab.desktop.DesktopSamplerController
import com.choplab.desktop.audio.DesktopAudioRecorder
import com.choplab.desktop.audio.DesktopPreparedLoopSession
import com.choplab.desktop.audio.DesktopSamplerAudioEngine
import com.choplab.desktop.persistence.DesktopProjectFiles
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.ui.OtohiroiDeck
import com.choplab.sampler.ui.theme.ChopLabTheme
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.io.File
import java.nio.file.Files
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Component evidence on the JVM/Skiko input stack, not OS pointer or physical audio evidence. */
class DesktopLongPressUiTest {
    @Test
    fun liveSourceMouseClickStillCapturesAnEmptyPadThroughTheRealController() = runBlocking {
        withTimeout(15_000) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.mousePress(fixture.nodeWithDescription("チョップ開始"), 40)
                assertTrue(fixture.controller.state.value.sourcePlaying)
                fixture.audio.advanceSourceTo(24_000)
                fixture.mousePress(fixture.nodeWithDescription("PAD 03 空"), 40)
                fixture.capture("live-source-empty-click")

                assertEquals(2, fixture.controller.state.value.selectedPad)
                assertEquals(24_000, fixture.controller.state.value.pads[2].startFrame)
                assertEquals(80_000, fixture.controller.state.value.pads[2].endFrame)
                assertTrue(fixture.controller.state.value.sourcePlaying)
                assertFalse(fixture.hasDescription("全体波形。PAD範囲"))
                assertTrue(fixture.audio.padRequests.isEmpty(), "Live capture must not request a PAD voice")
                fixture.assertOffscreen()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun waveformOrdinaryMouseClickEditsTheBoundaryWithoutPrecisionZoom() = runBlocking {
        withTimeout(15_000) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.mousePress(fixture.nodeWithDescription("PAD 02 割り当て済み"), 700)
                fixture.mousePress(fixture.nodeWithDescription("音声波形。タップで近い境界"), 40, fractionX = 0.75f)
                // Waveform supports double-click; allow its real single-click decision to settle.
                fixture.settle(350)
                fixture.capture("waveform-short-click")

                assertEquals(16_000, fixture.controller.state.value.pads[1].startFrame)
                assertEquals(29_000, fixture.controller.state.value.pads[1].endFrame)
                assertEquals(
                    "拡大表示。14000から33999フレーム。全体80000フレーム",
                    fixture.nodeWithDescription("音声波形。タップで近い境界").stateDescription(),
                )
                fixture.assertOffscreen()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun sourceEndChopKeepsOneSecondFloorAndClampsEndFocus() = runBlocking {
        withTimeout(15_000) {
            val fixture = DeckFixture.create(coroutineContext, targetStart = 76_000, targetEnd = 80_000)
            try {
                fixture.mousePress(fixture.nodeWithDescription("PAD 02 割り当て済み"), 700)
                fixture.capture("source-end-initial")
                assertEquals(
                    "全体波形。PAD範囲 0:09.500 から 0:10.000。編集表示 0:09.000 から 0:10.000",
                    fixture.nodeWithDescription("全体波形。PAD範囲").description(),
                )
                fixture.mousePress(fixture.nodeWithDescription("音声波形。タップで近い境界"), 700, fractionX = 0.8f)
                fixture.capture("source-end-focus")

                assertEquals(76_000, fixture.controller.state.value.pads[1].startFrame)
                assertEquals(78_400, fixture.controller.state.value.pads[1].endFrame)
                val waveform = fixture.nodeWithDescription("音声波形。タップで近い境界")
                assertEquals("拡大表示。72000から79999フレーム。全体80000フレーム", waveform.stateDescription())
                fixture.assertInsideScene(waveform)
                fixture.assertOffscreen()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun sourceStartChopKeepsOneSecondFloorAndClampsStartFocus() = runBlocking {
        withTimeout(15_000) {
            val fixture = DeckFixture.create(coroutineContext, targetStart = 0, targetEnd = 4_000)
            try {
                fixture.mousePress(fixture.nodeWithDescription("PAD 02 割り当て済み"), 700)
                fixture.capture("source-start-initial")
                assertEquals(
                    "全体波形。PAD範囲 0:00.000 から 0:00.500。編集表示 0:00.000 から 0:01.000",
                    fixture.nodeWithDescription("全体波形。PAD範囲").description(),
                )
                fixture.mousePress(fixture.nodeWithDescription("音声波形。タップで近い境界"), 700, fractionX = 0.25f)
                fixture.capture("source-start-focus")

                // At the midpoint of this half-second Chop, the existing tie rule chooses START.
                assertEquals(2_000, fixture.controller.state.value.pads[1].startFrame)
                assertEquals(4_000, fixture.controller.state.value.pads[1].endFrame)
                val waveform = fixture.nodeWithDescription("音声波形。タップで近い境界")
                assertEquals("拡大表示。0から7999フレーム。全体80000フレーム", waveform.stateDescription())
                fixture.assertInsideScene(waveform)
                fixture.assertOffscreen()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun unassignedPadMouseClickAndHoldOnlySelectWithoutTrimOrAudio() = runBlocking {
        withTimeout(15_000) {
            for (holdMillis in listOf(40L, 700L)) {
                val fixture = DeckFixture.create(coroutineContext)
                try {
                    fixture.mousePress(fixture.nodeWithDescription("PAD 03 空"), holdMillis)
                    fixture.capture("unassigned-$holdMillis")

                    assertEquals(2, fixture.controller.state.value.selectedPad)
                    assertFalse(fixture.controller.state.value.pads[2].isAssigned)
                    assertFalse(fixture.hasDescription("全体波形。PAD範囲"))
                    assertTrue(fixture.audio.padRequests.isEmpty())
                    fixture.assertOffscreen()
                } finally {
                    fixture.close()
                }
            }
        }
    }

    @Test
    fun assignedPadOrdinaryMouseClickAuditionsWithoutOpeningTrimOrEditing() = runBlocking {
        withTimeout(15_000) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.mousePress(fixture.nodeWithDescription("PAD 02 割り当て済み"), 40)
                fixture.capture("assigned-short-click")

                assertEquals(1, fixture.controller.state.value.selectedPad)
                assertEquals(16_000, fixture.controller.state.value.pads[1].startFrame)
                assertEquals(32_000, fixture.controller.state.value.pads[1].endFrame)
                assertFalse(fixture.hasDescription("全体波形。PAD範囲"))
                assertEquals(listOf(1), fixture.audio.padRequests)
                fixture.assertOffscreen()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun assignedPadMouseLongPressOpensItsExistingFittedTrim() = runBlocking {
        withTimeout(15_000) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.capture("assigned-before")
                assertEquals(0, fixture.controller.state.value.selectedPad)
                assertEquals(16_000, fixture.controller.state.value.pads[1].startFrame)
                assertEquals(32_000, fixture.controller.state.value.pads[1].endFrame)
                val target = fixture.nodeWithDescription("PAD 02 割り当て済み")
                val shortNegative = java.lang.Boolean.getBoolean("h13.negativeShortPress")
                fixture.mousePress(target, if (shortNegative) 40 else 700)
                fixture.capture(if (shortNegative) "short-negative-after" else "assigned-after")

                val overview = fixture.nodeWithDescription("全体波形。PAD範囲")
                assertEquals(1, fixture.controller.state.value.selectedPad)
                assertEquals(16_000, fixture.controller.state.value.pads[1].startFrame)
                assertEquals(32_000, fixture.controller.state.value.pads[1].endFrame)
                assertEquals(
                    "全体波形。PAD範囲 0:02.000 から 0:04.000。編集表示 0:01.750 から 0:04.250",
                    overview.description(),
                )
                assertEquals(
                    "拡大表示。14000から33999フレーム。全体80000フレーム",
                    fixture.nodeWithDescription("音声波形。タップで近い境界").stateDescription(),
                )
                fixture.assertOffscreen()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun beatPadMouseLongPressPreservesItsRangeAndOpensFittedTrim() = runBlocking {
        withTimeout(15_000) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.mousePress(fixture.nodeWithDescription("工程3 ビート BEAT"), 40)
                fixture.capture("beat-before")
                fixture.mousePress(fixture.nodeWithDescription("PAD 02 割り当て済み"), 700)
                fixture.capture("beat-after")

                assertEquals(1, fixture.controller.state.value.selectedPad)
                assertEquals(16_000, fixture.controller.state.value.pads[1].startFrame)
                assertEquals(32_000, fixture.controller.state.value.pads[1].endFrame)
                val overview = fixture.nodeWithDescription("全体波形。PAD範囲")
                assertEquals(
                    "全体波形。PAD範囲 0:02.000 から 0:04.000。編集表示 0:01.750 から 0:04.250",
                    overview.description(),
                )
                val waveform = fixture.nodeWithDescription("音声波形。タップで近い境界")
                assertEquals(
                    "拡大表示。14000から33999フレーム。全体80000フレーム",
                    waveform.stateDescription(),
                )
                fixture.assertInsideScene(overview)
                fixture.assertInsideScene(waveform)
                fixture.assertOffscreen()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun waveformMouseLongPressMovesTheCloserEndAndFocusesOneSecond() = runBlocking {
        withTimeout(15_000) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.mousePress(fixture.nodeWithDescription("工程3 ビート BEAT"), 40)
                fixture.mousePress(fixture.nodeWithDescription("PAD 02 割り当て済み"), 700)
                fixture.mousePress(fixture.nodeWithDescription("音声波形。タップで近い境界"), 700, fractionX = 0.75f)
                fixture.capture("waveform-end-focus")

                assertEquals(16_000, fixture.controller.state.value.pads[1].startFrame)
                assertEquals(29_000, fixture.controller.state.value.pads[1].endFrame)
                val waveform = fixture.nodeWithDescription("音声波形。タップで近い境界")
                assertEquals("拡大表示。25000から32999フレーム。全体80000フレーム", waveform.stateDescription())
                assertEquals(
                    "全体波形。PAD範囲 0:02.000 から 0:03.625。編集表示 0:03.125 から 0:04.125",
                    fixture.nodeWithDescription("全体波形。PAD範囲").description(),
                )
                fixture.assertInsideScene(waveform)
                fixture.assertOffscreen()
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun assignedPadLongPressStillOpensTrimWhenAuditionStartupFails() = runBlocking {
        withTimeout(15_000) {
            val fixture = DeckFixture.create(coroutineContext)
            try {
                fixture.audio.failNextTrigger = true

                fixture.mousePress(fixture.nodeWithDescription("PAD 02 割り当て済み"), 700)

                assertEquals(16_000, fixture.controller.state.value.pads[1].startFrame)
                assertEquals(32_000, fixture.controller.state.value.pads[1].endFrame)
                assertTrue(fixture.hasDescription("全体波形。PAD範囲"))
                assertTrue(fixture.hasDescription("音声波形。タップで近い境界"))
                fixture.assertOffscreen()
            } finally {
                fixture.close()
            }
        }
    }
}

private class DeckFixture private constructor(
    val controller: DesktopSamplerController,
    val audio: SilentAudioPort,
    private val scene: ImageComposeScene,
    private val directory: File,
    private val project: File,
) : AutoCloseable {
    private val inputTrace = mutableListOf<String>()
    private fun nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) {
            add(node)
            node.children.forEach(::visit)
        }
        scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }

    fun nodeWithDescription(prefix: String): SemanticsNode {
        val matches = nodes().filter { it.description().startsWith(prefix) }
        assertEquals(1, matches.size, "Expected one '$prefix' node; found ${matches.map { it.description() }}")
        return matches.single()
    }

    fun hasDescription(prefix: String): Boolean = nodes().any { it.description().startsWith(prefix) }

    suspend fun mousePress(node: SemanticsNode, holdMillis: Long, fractionX: Float = 0.5f) {
        val bounds = node.boundsInRoot
        val position = Offset(bounds.left + bounds.width * fractionX, bounds.center.y)
        val pressedAt = System.nanoTime()
        inputTrace += "Mouse Move/Press target=${node.description()} bounds=$bounds position=$position holdRequestedMs=$holdMillis"
        scene.sendPointerEvent(PointerEventType.Move, position, type = PointerType.Mouse)
        scene.sendPointerEvent(
            PointerEventType.Press,
            position,
            type = PointerType.Mouse,
            buttons = PointerButtons(isPrimaryPressed = true),
            button = PointerButton.Primary,
        )
        settle(holdMillis)
        inputTrace += "Mouse Release position=$position elapsedMs=${(System.nanoTime() - pressedAt) / 1_000_000}"
        scene.sendPointerEvent(
            PointerEventType.Release,
            position,
            type = PointerType.Mouse,
            buttons = PointerButtons(),
            button = PointerButton.Primary,
        )
        settle(100)
    }

    suspend fun settle(durationMillis: Long = 100) {
        val deadline = System.nanoTime() + durationMillis * 1_000_000
        do {
            scene.render(System.nanoTime()).close()
            delay(10)
        } while (System.nanoTime() < deadline)
        scene.render(System.nanoTime()).close()
    }

    fun capture(label: String) {
        val output = File(requireNotNull(System.getProperty("h13.evidenceDir")))
        output.mkdirs()
        val image = scene.render(System.nanoTime())
        try {
            val data = requireNotNull(image.encodeToData())
            try {
                File(output, "$label.png").writeBytes(data.bytes)
            } finally {
                data.close()
            }
        } finally {
            image.close()
        }
        val state = controller.state.value
        File(output, "$label.txt").writeText(
            buildString {
                appendLine("platform=JVM ImageComposeScene; input=Mouse; headless=${GraphicsEnvironment.isHeadless()}")
                appendLine("os=${System.getProperty("os.name")} ${System.getProperty("os.version")}; java=${System.getProperty("java.version")}; renderer=${System.getProperty("skiko.renderApi")}")
                appendLine("uiRuntime=${ImageComposeScene::class.java.protectionDomain.codeSource.location}")
                appendLine("selectedPad=${state.selectedPad}; targetRange=${state.pads[1].startFrame}..${state.pads[1].endFrame}")
                appendLine("silentPadRequests=${audio.padRequests}; realAudioImplementation=false")
                inputTrace.forEach(::appendLine)
                nodes().forEach { node ->
                    val description = node.description()
                    val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString { it.text }.orEmpty()
                    if (description.isNotEmpty() || text.isNotEmpty()) {
                        appendLine("${node.boundsInRoot}: description=$description; state=${node.stateDescription()}; text=$text")
                    }
                }
            },
            Charsets.UTF_8,
        )
    }

    fun assertOffscreen() {
        assertTrue(GraphicsEnvironment.isHeadless())
        assertFalse(Window.getWindows().any { it.isVisible }, "The H13 fixture must never create a visible window")
    }

    fun assertInsideScene(node: SemanticsNode) {
        val bounds = node.boundsInRoot
        assertTrue(bounds.width > 0f && bounds.height > 0f, "Rendered control must have non-empty bounds: $bounds")
        assertTrue(
            bounds.left >= 0f && bounds.top >= 0f && bounds.right <= 1_100f && bounds.bottom <= 1_000f,
            "Rendered control must fit inside its actual offscreen viewport: $bounds",
        )
    }

    override fun close() {
        try {
            scene.close()
        } finally {
            try {
                controller.close()
            } finally {
                try {
                    Files.deleteIfExists(project.toPath())
                } finally {
                    Files.deleteIfExists(directory.toPath())
                }
            }
        }
    }

    companion object {
        suspend fun create(context: CoroutineContext, targetStart: Int = 16_000, targetEnd: Int = 32_000): DeckFixture {
            check(GraphicsEnvironment.isHeadless()) { "Use :desktop:desktopLongPressUiTest, not an interactive launcher" }
            val temporaryRoot = File(System.getProperty("java.io.tmpdir"))
            val directory = Files.createTempDirectory(temporaryRoot.toPath(), "h13-input-").toFile()
            val audio = PcmAudio(
                name = "H13 synthetic source",
                samples = ShortArray(80_000) { frame -> ((frame % 80 - 40) * 150).toShort() },
                sampleRate = 8_000,
            )
            val input = SamplerUiState(
                currentAudio = audio,
                rangeStartFrame = 0,
                rangeEndFrame = audio.frameCount,
                selectedBank = 0,
                selectedPad = 0,
                activeSteps = emptySet(),
                pads = List(SamplerConfig.PAD_COUNT) { index ->
                    when (index) {
                        0 -> PadModel(0, audio, 8_000, 12_000)
                        1 -> PadModel(1, audio, targetStart, targetEnd)
                        else -> PadModel(index)
                    }
                },
            )
            val project = DesktopProjectFiles.save(File(directory, "fixture.choplab"), input)
            val audioPort = SilentAudioPort()
            val controller = DesktopSamplerController(
                player = audioPort,
                microphone = ForbiddenRecorder(),
                systemAudio = ForbiddenRecorder(),
                autosaveStore = null,
            )
            var scene: ImageComposeScene? = null
            try {
                controller.openProject(project)
                withTimeout(5_000) {
                    while (controller.state.value.isLoading) delay(10)
                }
                check(controller.state.value.pads[1].startFrame == targetStart && controller.state.value.pads[1].endFrame == targetEnd) {
                    "Synthetic project did not load through the public controller"
                }
                val readyScene = ImageComposeScene(width = 1_100, height = 1_000, density = Density(1f), coroutineContext = context) {
                    ChopLabTheme {
                        OtohiroiDeck(
                            state = controller.state.collectAsState().value,
                            onImportAudio = { error("Native file picker is outside H13") },
                            onToggleMicrophoneRecording = { error("Recording is outside H13") },
                            onToggleVocalRecording = { error("Recording is outside H13") },
                            onToggleSystemAudioRecording = { error("Recording is outside H13") },
                            onExportBeat = { error("Export is outside H13") },
                            onOpenProject = { error("Native project picker is outside H13") },
                            onSaveProject = { error("Native project picker is outside H13") },
                            viewModel = controller,
                        )
                    }
                }
                scene = readyScene
                return DeckFixture(controller, audioPort, readyScene, directory, project).also { it.settle() }
            } catch (failure: Throwable) {
                runCatching { scene?.close() }.onFailure(failure::addSuppressed)
                runCatching { controller.close() }.onFailure(failure::addSuppressed)
                runCatching { Files.deleteIfExists(project.toPath()) }.onFailure(failure::addSuppressed)
                runCatching { Files.deleteIfExists(directory.toPath()) }.onFailure(failure::addSuppressed)
                throw failure
            }
        }
    }
}

private fun SemanticsNode.description(): String =
    config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString().orEmpty()

private fun SemanticsNode.stateDescription(): String =
    config.getOrNull(SemanticsProperties.StateDescription).orEmpty()

/** No Java Sound implementation, line, Clip, device query, or provider exists in this port. */
private class SilentAudioPort : DesktopSamplerAudioEngine {
    private var ownership = 0L
    val padRequests = mutableListOf<Int>()
    var failNextTrigger = false
    private var sourceFrames = 0
    @Volatile private var sourcePosition = 0
    @Volatile private var sourcePlaying = false
    override val isSourcePlaying get() = sourcePlaying
    override fun loadPcm(audio: PcmAudio, pitchSemitones: Float) {
        sourceFrames = audio.frameCount
        sourcePosition = 0
        sourcePlaying = false
    }
    override fun playFrom(frame: Int) {
        check(frame in 0 until sourceFrames)
        sourcePosition = frame
        sourcePlaying = true
    }
    fun advanceSourceTo(frame: Int) {
        check(sourcePlaying && frame in 0 until sourceFrames)
        sourcePosition = frame
    }
    override fun seekSource(frame: Int) { sourcePosition = frame }
    override fun sourceFramePosition() = sourcePosition
    override fun padFramePosition(index: Int): Int? = null
    override fun stop() { sourcePlaying = false }
    override fun triggerPad(pad: PadModel, forceLoop: Boolean): Long {
        if (failNextTrigger) {
            failNextTrigger = false
            error("test output unavailable")
        }
        padRequests += pad.globalIndex
        return ++ownership
    }
    override fun prepareExclusiveLoopSession(loopPad: PadModel, companionPads: List<PadModel>): DesktopPreparedLoopSession =
        error("Loop playback is outside H13")
    override fun releasePad(index: Int) = Unit
    override fun releasePadIfOwned(index: Int, ownership: Long) = Unit
    override fun stopPad(index: Int) = Unit
    override fun stopAll() { sourcePlaying = false }
    override fun close() { sourcePlaying = false }
}

private class ForbiddenRecorder : DesktopAudioRecorder {
    override val isRecording = false
    override fun start(file: File): Result<Unit> = error("Recording is forbidden in H13")
    override fun stop(): Result<File> = Result.failure(IllegalStateException("No H13 recording"))
    override fun close() = Unit
}
