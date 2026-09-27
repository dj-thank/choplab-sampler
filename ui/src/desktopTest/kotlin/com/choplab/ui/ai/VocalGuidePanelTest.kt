@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.ui.ai

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
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
import kotlin.test.*

class VocalGuidePanelTest {
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
                val scene = ImageComposeScene(width = width, height = height, density = Density(1f, font), coroutineContext = coroutineContext) {
                    MaterialTheme { if (open) CEVocalGuideDialog(controller) { action -> when (action) {
                        ContinuousEditorAction.StopAll -> { stopped++; controller.cancel() }
                        ContinuousEditorAction.CloseVocalGuide -> { open = false; controller.close() }
                        else -> error("Unexpected dialog action")
                    } } }
                }
                try {
                    scene.until { !controller.state.value.loadingVoices && scene.tag("ce-vocal-guide-close") != null }
                    val fixed = listOf("ce-vocal-guide-stop", "ce-vocal-guide-close").associateWith { scene.tag(it)!!.boundsInRoot }
                    fixed.keys.forEach { scene.hit(it, width, height) }
                    scene.reach("vocal-generate-$lineId", width, height); scene.click("vocal-generate-$lineId")
                    scene.until { controller.state.value.phase == VocalGuidePhase.READY }
                    assertEquals(1, generated); assertEquals(0, applied)
                    scene.reach("vocal-listen-$lineId", width, height); scene.click("vocal-listen-$lineId")
                    scene.until { listened == 1 }
                    scene.reach("vocal-generate-$lineId", width, height); scene.click("vocal-generate-$lineId")
                    scene.until { generated == 2 && controller.state.value.phase == VocalGuidePhase.READY }
                    assertEquals(1, regenerated)
                    scene.reach("vocal-apply", width, height)
                    fixed.forEach { (tag, bounds) -> assertEquals(bounds, scene.tag(tag)!!.boundsInRoot); scene.hit(tag, width, height) }
                    assertFalse(scene.nodes().flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }.any { '%' in it.text })
                    scene.capture("${locale.language}-${width}-font${(font * 100).toInt()}.png")
                    scene.click("vocal-apply"); scene.until { applied == 1 }
                    scene.click("ce-vocal-guide-stop"); assertEquals(1, stopped)
                    scene.click("ce-vocal-guide-close"); scene.until { !open && scene.semanticsOwners.size == 1 }
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
    private suspend fun ImageComposeScene.until(condition: () -> Boolean) { withTimeout(5_000) { do { settle() } while (!condition()) }; settle() }
    private fun ImageComposeScene.hit(value: String, width: Int, height: Int) {
        val node = requireNotNull(tag(value)); val bounds = node.boundsInRoot
        assertTrue(node.size.width >= 48 && node.size.height >= 48, "$value target: ${node.size}")
        assertEquals(node.size.width.toFloat(), bounds.width, .5f, "$value clipped width")
        assertEquals(node.size.height.toFloat(), bounds.height, .5f, "$value clipped height")
        assertTrue(bounds.left >= 0 && bounds.top >= 0 && bounds.right <= width && bounds.bottom <= height, "$value offscreen: $bounds")
    }
    private suspend fun ImageComposeScene.reach(value: String, width: Int, height: Int) {
        repeat(3) {
            val node = requireNotNull(tag(value)); val area = requireNotNull(tag("vocal-guide-panel")).boundsInRoot
            val scroll = nodes().firstNotNullOf { it.config.getOrNull(SemanticsActions.ScrollBy)?.action }
            scroll(0f, node.positionInRoot.y - area.top - area.height / 3f)
            settle()
        }
        hit(value, width, height)
    }
    private suspend fun ImageComposeScene.click(value: String) {
        val point = requireNotNull(tag(value)).boundsInRoot.center
        sendPointerEvent(PointerEventType.Press, point, type = PointerType.Mouse, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary)
        render(System.nanoTime()).close()
        sendPointerEvent(PointerEventType.Release, point, type = PointerType.Mouse, buttons = PointerButtons(), button = PointerButton.Primary)
        settle()
    }
    private fun ImageComposeScene.capture(name: String) {
        val output = File(System.getProperty("choplab.ui.evidenceDir"), "vocal-guide").apply { mkdirs() }
        render(System.nanoTime()).use { bitmap -> bitmap.encodeToData()!!.use { File(output, name).writeBytes(it.bytes) } }
    }
}
