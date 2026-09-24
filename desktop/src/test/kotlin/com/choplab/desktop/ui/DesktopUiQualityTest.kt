@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.choplab.desktop.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.choplab.sampler.audio.BuiltInDrumKits
import com.choplab.sampler.model.*
import com.choplab.sampler.ui.OtohiroiDeck
import com.choplab.sampler.ui.SamplerDeckController
import com.choplab.sampler.ui.theme.ChopLabTheme
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.io.File
import java.lang.reflect.Proxy
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Real Compose layout/semantics with synthetic state and a strict silent controller boundary. */
class DesktopUiQualityTest {
    @Test fun captureChopBeatAndFinishRenderWithoutNavigationStartingAudio() = runBlocking {
        withTimeout(40_000) {
            UiFixture(coroutineContext, 412, 820, 1f).use { fixture ->
                fixture.settle()
                for ((number, label) in listOf(1 to "入れる", 2 to "チョップ", 3 to "ビート", 4 to "保存")) {
                    fixture.click("工程$number $label")
                    fixture.assertStages(number)
                    fixture.capture("phone-$number")
                }
                assertTrue(fixture.calls.none { it.startsWith("trigger") || it.startsWith("restart") || it.startsWith("play") })
                assertEquals(128, fixture.state.value.pads.size)
                fixture.assertOffscreen()
            }
        }
    }

    @Test fun narrowLargeTextSaveActionsKeepTheirSizeAndRemainScrollable() = runBlocking {
        withTimeout(40_000) {
            for (scale in listOf(1f, 1.3f, 2f)) {
                UiFixture(coroutineContext, 320, 640, scale).use { fixture ->
                    fixture.settle()
                    fixture.click("工程4 保存")
                    val labels = listOf("ビートを確認", "WAVを書き出す", "制作を保存", "制作を開く", "1つ戻す", "やり直す")
                    for (label in labels) {
                        val node = fixture.node(label)
                        assertTrue(node.size.height >= 48, "$label is only ${node.size.height}px at fontScale=$scale")
                        assertTrue(node.size.width >= 48, "$label is only ${node.size.width}px wide")
                    }
                    fixture.capture("save-320-font-$scale-top")
                    fixture.scrollBody()
                    fixture.capture("save-320-font-$scale-bottom")
                    // The 2026-09-05 SAVE screen has four rows; REDO is the bottom action.
                    val redo = fixture.node("やり直す")
                    assertTrue(redo.boundsInRoot.height >= 48f, "Bottom save actions must become visible")
                    fixture.assertStages(4)
                    assertTrue(fixture.calls.isEmpty(), "Read-only navigation must not mutate the production")
                }
            }
        }
    }

    @Test fun desktopControlsExposeParameterNamesAndCheckedStepState() = runBlocking {
        withTimeout(40_000) {
            UiFixture(coroutineContext, 1280, 800, 1f).use { fixture ->
                fixture.settle()
                fixture.click("工程3 ビート")
                fixture.capture("desktop-beat")
                fixture.click("並べる詳細")
                fixture.capture("desktop-steps")
                val steps = fixture.nodes().filter { it.description().startsWith("ステップ ") }
                assertEquals(16, steps.size)
                steps.forEach { node ->
                    assertEquals(Role.Checkbox, node.config.getOrNull(SemanticsProperties.Role))
                    assertTrue(node.config.getOrNull(SemanticsProperties.ToggleableState) != null)
                    assertTrue(node.size.width >= 48 && node.size.height >= 48)
                }
                fixture.click("BPM・音色")
                fixture.capture("desktop-controls")
                assertTrue(fixture.nodes().any { it.description().startsWith("テンポ BPMを下げる。現在") })
                assertTrue(fixture.nodes().any { it.description().startsWith("テンポ BPMを上げる。現在") })
            }
        }
    }

    @Test fun freshAndLoadingScreensKeepTheirRealStateAndHeaderAction() = runBlocking {
        withTimeout(40_000) {
            UiFixture(coroutineContext, 360, 720, 1f, fresh = true).use { fixture ->
                fixture.settle()
                fixture.capture("fresh-capture")
                val header = fixture.node("おとひろい、現在")
                assertTrue(header.size.height >= 48)
                assertTrue(header.config.getOrNull(SemanticsActions.OnClick) != null)
                fixture.state.value = fixture.state.value.copy(isLoading = true, statusMessage = "合成データを準備中です。読み込み完了まで制作を変更しません")
                fixture.settle()
                fixture.capture("loading-capture")
                val tabs = fixture.nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
                assertEquals(4, tabs.size)
                assertTrue(fixture.calls.isEmpty())
            }
        }
    }
}

private class UiFixture(
    context: CoroutineContext,
    private val width: Int,
    private val height: Int,
    private val scale: Float,
    fresh: Boolean = false,
) : AutoCloseable {
    val calls = mutableListOf<String>()
    val state = MutableStateFlow(if (fresh) BuiltInDrumKits.installStarterKit(SamplerUiState()) else syntheticProject())
    private val controller = Proxy.newProxyInstance(
        SamplerDeckController::class.java.classLoader,
        arrayOf(SamplerDeckController::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "toString" -> "SilentUiController"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            "ensurePlayablePadSelected" -> {
                state.value = ensurePlayablePadSelected(state.value)
                null
            }
            "stopSourceForWorkspaceChange" -> {
                state.value = state.value.copy(sourcePlaying = false, pendingSourceCommand = PendingSourceCommand.NONE)
                null
            }
            "stopAllSounds" -> { calls += method.name; state.value = stopAllPlaybackState(state.value); null }
            "selectPad", "selectPlayablePad" -> {
                val index = args!![0] as Int
                state.value = state.value.copy(selectedPad = index, selectedBank = index / SamplerConfig.PADS_PER_BANK)
                null
            }
            else -> error("Unexpected controller effect during UI review: ${method.name}")
        }
    } as SamplerDeckController
    private val scene = ImageComposeScene(width = width, height = height, density = Density(1f, scale), coroutineContext = context) {
        ChopLabTheme {
            OtohiroiDeck(
                state = state.collectAsState().value,
                onImportAudio = { error("No real files may be selected by UI review") },
                onToggleMicrophoneRecording = { error("No recording in UI review") },
                onToggleVocalRecording = { error("No recording in UI review") },
                onToggleSystemAudioRecording = { error("No recording in UI review") },
                onExportBeat = { error("No export in UI review") },
                onOpenProject = { error("No project dialogs in UI review") },
                onSaveProject = { error("No saves in UI review") },
                viewModel = controller,
            )
        }
    }

    fun nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        scene.semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    fun node(prefix: String): SemanticsNode = nodes().filter { it.description().startsWith(prefix) }
        .also { assertEquals(1, it.size, "Expected exactly one $prefix") }.single()

    suspend fun click(prefix: String) {
        val action = requireNotNull(node(prefix).config.getOrNull(SemanticsActions.OnClick)?.action)
        assertTrue(action())
        settle()
    }
    suspend fun settle() {
        repeat(12) { scene.render(System.nanoTime()).close(); delay(10) }
    }
    suspend fun scrollBody() {
        val scroll = nodes().first { it.config.getOrNull(SemanticsActions.ScrollBy)?.action != null }
        assertTrue(requireNotNull(scroll.config.getOrNull(SemanticsActions.ScrollBy)?.action).invoke(0f, 5000f))
        repeat(4) { settle() }
    }
    fun assertStages(selectedNumber: Int) {
        val tabs = nodes().filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
        assertEquals(4, tabs.size)
        val selected = tabs.filter { it.config.getOrNull(SemanticsProperties.Selected) == true }
        assertEquals(1, selected.size)
        assertTrue(selected.single().description().startsWith("工程$selectedNumber "))
    }
    fun capture(label: String) {
        val folder = File(requireNotNull(System.getProperty("uiReview.evidenceDir"))).apply { mkdirs() }
        scene.render(System.nanoTime()).use { image ->
            requireNotNull(image.encodeToData()).use { data -> File(folder, "$label.png").writeBytes(data.bytes) }
        }
        File(folder, "$label.txt").writeText(buildString {
            appendLine("Actual shared Compose UI; synthetic in-memory state; silent strict controller; no physical device/audio")
            appendLine("viewport=${width}x$height density=1 fontScale=$scale os=${System.getProperty("os.name")} java=${System.getProperty("java.version")}")
            nodes().forEach { node ->
                val description = node.description()
                if (description.isNotEmpty()) appendLine("${node.size} ${node.boundsInRoot}: $description")
            }
        })
    }
    fun assertOffscreen() {
        assertTrue(GraphicsEnvironment.isHeadless())
        assertFalse(Window.getWindows().any { it.isVisible })
    }
    override fun close() = scene.close()
}

private fun SemanticsNode.description(): String = config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()

private fun syntheticProject(): SamplerUiState {
    val audio = PcmAudio(name = "合成テスト音源・長い素材名の読みやすさ確認", samples = ShortArray(80_000) { ((it % 120 - 60) * 320).toShort() }, sampleRate = 8_000)
    val starter = BuiltInDrumKits.installStarterKit(SamplerUiState())
    val pads = starter.pads.toMutableList()
    pads[0] = PadModel(0, audio, 0, 16_000)
    pads[1] = PadModel(1, audio, 16_000, 32_000)
    return starter.copy(currentAudio = audio, rangeEndFrame = audio.frameCount, pads = pads, selectedPad = 0, selectedBank = 0, projectLaunchTarget = ProjectLaunchTarget.CAPTURE)
}
