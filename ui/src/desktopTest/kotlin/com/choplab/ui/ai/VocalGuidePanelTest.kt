@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.ui.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.choplab.core.DocumentState
import com.choplab.core.ai.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import com.choplab.ui.CEVocalGuideDialog
import com.choplab.ui.ContinuousEditorAction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.test.*

class VocalGuidePanelTest {
    private var observation: () -> String = { "" }
    @Test fun jaAndEnCompactDialogsKeepStopAndCloseFixedAndReachLinePreviewRegenerateAndApply() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.JAPAN, Locale.US)) for ((width, height, font) in listOf(Triple(900, 900, 1f), Triple(390, 844, 2f))) {
                Locale.setDefault(locale)
                val placement = LyricProposal("川", LyricLanguage.JAPANESE, frozenListOf(ProposalSection("A", LyricSectionKind.VERSE, 1,
                    frozenListOf(ProposalLine.create("川の歌", "かわのうた", LyricLanguage.JAPANESE))))).placeStructured(0, 4, "line")
                val project = Project(lyrics = placement.lines, lyricStructure = placement.structure)
                val lineId = project.lyrics.single().id
                var generated = 0; var regenerated = 0; var listened = 0; var applied = 0; var stopped = 0; var closes = 0
                val preview = object : VocalPreviewPort {
                    override val state = MutableStateFlow(VocalPreviewState())
                    override suspend fun start(asset: Asset, expectedRevision: Long): TtsResult<Unit> {
                        listened++; state.value = VocalPreviewState(VocalPreviewPhase.PLAYING, true, asset.hash); return TtsResult.Success(Unit)
                    }
                    override suspend fun stop(): TtsResult<Unit> { state.value = VocalPreviewState(); return TtsResult.Success(Unit) }
                    override fun requestStop() { state.value = VocalPreviewState() }
                    override fun frame() = 12_000L
                }
                val voice = TtsVoice(TtsEngine("device", "1", "system", "1"), "voice", "Installed voice", "ja-JP", "1", LyricLanguage.JAPANESE)
                val controller = VocalGuideController(MutableStateFlow(DocumentState(project, 1)), MutableStateFlow(VocalGuideAvailability.EDITABLE),
                    object : VocalSynthesisPort {
                        override suspend fun voices() = TtsResult.Success(frozenListOf(voice))
                        override suspend fun prepare(row: FlowRow, tempo: Tempo, voice: TtsVoice, settings: TtsSettings, regenerate: Boolean): TtsResult<PreparedVocalLine> {
                            generated++; if (regenerate) regenerated++
                            return TtsResult.Success(PreparedVocalLine(row.line.copy(words = frozenListOf(
                                LyricWord(row.line.text, row.line.startTick, row.line.endTick, WordTimingOrigin.ESTIMATED))),
                                Asset("a".repeat(64), "wav", 100, 48_000, 2, 96_000, "Guide", AssetRole.RENDERED), 1.0, 0, false, false))
                        }
                        override fun close() { closes++ }
                    }, preview, object : VocalGuideActions {
                        override suspend fun prepareAllowed(expectedRevision: Long) = TtsResult.Success(Unit)
                        override suspend fun preview(asset: Asset, expectedRevision: Long) = preview.start(asset, expectedRevision)
                        override suspend fun apply(intent: Intent.ApplyVocalGuide, expectedRevision: Long): Boolean { applied++; return true }
                    }, this)
                var open by mutableStateOf(true)
                observation = { "${locale.language}/$width/font$font phase=${controller.state.value.phase} voicesLoading=${controller.state.value.loadingVoices} generated=$generated regenerated=$regenerated listened=$listened applied=$applied closed=$closes open=$open" }
                // A slower semantic scroll reproduces the CI race: a visible button can still move under the pointer.
                val motion = object : MotionDurationScale { override val scaleFactor = 2f }
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext + motion) {
                    MaterialTheme { if (open) CEVocalGuideDialog(controller) { action -> when (action) {
                        ContinuousEditorAction.StopAll -> { stopped++; controller.cancel() }
                        ContinuousEditorAction.CloseVocalGuide -> { open = false; controller.close() }
                        else -> error("Unexpected dialog action")
                    } } }
                }
                try {
                    scene.until("open") { !controller.state.value.loadingVoices && scene.tag("ce-vocal-guide-close") != null }
                    val fixed = listOf("ce-vocal-guide-stop", "ce-vocal-guide-close").associateWith { scene.tag(it)!!.boundsInRoot }
                    fixed.keys.forEach { scene.hit(it, width, height) }
                    scene.reach("vocal-generate-$lineId", width, height); scene.click("vocal-generate-$lineId")
                    scene.until("first generation") { controller.state.value.phase == VocalGuidePhase.READY }
                    assertEquals(1, generated); assertEquals(0, applied)
                    scene.reach("vocal-listen-$lineId", width, height); scene.click("vocal-listen-$lineId")
                    scene.until("preview") { listened == 1 }
                    scene.reach("vocal-generate-$lineId", width, height); scene.click("vocal-generate-$lineId")
                    scene.until("regeneration") { generated == 2 && controller.state.value.phase == VocalGuidePhase.READY }
                    assertEquals(1, regenerated)
                    scene.reach("vocal-apply", width, height)
                    fixed.forEach { (tag, bounds) -> assertEquals(bounds, scene.tag(tag)!!.boundsInRoot); scene.hit(tag, width, height) }
                    assertFalse(scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.any { '%' in it.text })
                    scene.capture("${locale.language}-${width}-font${(font * 100).toInt()}.png")
                    scene.click("vocal-apply"); scene.until("apply") { applied == 1 && controller.state.value.phase == VocalGuidePhase.APPLIED }
                    scene.click("ce-vocal-guide-stop"); assertEquals(1, stopped)
                    scene.click("ce-vocal-guide-close"); scene.until("close") { !open && scene.semanticsOwners.size == 1 }
                    assertEquals(1, closes)
                } finally { scene.close(); controller.close() }
            }
        } finally { Locale.setDefault(previous) }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(value: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }
    private suspend fun ImageComposeScene.settle() { repeat(4) { render(System.nanoTime()).close(); delay(12) } }
    private suspend fun ImageComposeScene.until(label: String, condition: () -> Boolean) {
        try { withTimeout(5_000) { do { settle() } while (!condition()) }; settle() }
        catch (failure: TimeoutCancellationException) { fail("Timed out at $label: ${observation()} owners=${semanticsOwners.size}", failure) }
    }
    private fun ImageComposeScene.hit(value: String, width: Int, height: Int) {
        val node = requireNotNull(tag(value)); val bounds = node.boundsInRoot
        assertTrue(node.size.width >= 48 && node.size.height >= 48, "$value target: ${node.size}")
        assertEquals(node.size.width.toFloat(), bounds.width, .5f, "$value clipped width")
        assertEquals(node.size.height.toFloat(), bounds.height, .5f, "$value clipped height")
        assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height, "$value offscreen: $bounds")
    }
    private suspend fun ImageComposeScene.reach(value: String, width: Int, height: Int) {
        repeat(3) {
            val node = requireNotNull(tag(value))
            val scroll = nodes().first { it.config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null }
            val area = scroll.boundsInRoot
            if (node.positionInRoot.y < area.top || node.positionInRoot.y + node.size.height > area.bottom) {
                val axis = scroll.config[SemanticsProperties.VerticalScrollAxisRange]
                val distance = node.positionInRoot.y - area.top - area.height / 3f
                // ScrollState rounds its pixel position. A one-pixel tolerance can accept the
                // penultimate animation frame while the target still moves under the pointer.
                val destination = (axis.value() + distance).coerceIn(0f, axis.maxValue()).roundToInt().toFloat()
                assertTrue(requireNotNull(scroll.config[SemanticsActions.ScrollBy].action)(0f, distance))
                // ScrollBy starts an animation. Wait for its requested destination before measuring or pressing.
                until("scroll to $value") { axis.value() == destination }
            }
        }
        hit(value, width, height)
    }
    private suspend fun ImageComposeScene.click(value: String) {
        val point = requireNotNull(tag(value)).boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, point, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close()
        assertEquals(point, requireNotNull(tag(value)).boundsInRoot.center, "$value moved during the pointer press")
        sendPointerEvent(PointerEventType.Release, point, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
    }
    private fun ImageComposeScene.capture(name: String) {
        val output = File(System.getProperty("choplab.ui.evidenceDir"), "vocal-guide").apply { mkdirs() }
        render(System.nanoTime()).use { bitmap -> bitmap.encodeToData()!!.use { File(output, name).writeBytes(it.bytes) } }
    }
}
