@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.ui.inception

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import com.choplab.core.inception.*
import com.choplab.ui.*
import kotlinx.coroutines.*
import java.io.File
import java.util.Locale
import kotlin.test.*

class InceptionPanelTest {
    @Test fun actualPanelTextEvidenceAdoptionSaveAndProductionApply() = runBlocking<Unit> {
        val f = InceptionFixture(this)
        f.controller.open()
        val scene = ImageComposeScene(width = 1280, height = 1000, coroutineContext = coroutineContext) { MaterialTheme { InceptionPanel(f.controller) } }
        try {
            scene.settle(); scene.type("inception-workspace-name", "未定の音"); scene.click("inception-create"); scene.settle()
            scene.type("inception-material-input", "雨の帰り道。🙂\n言い切れない気持ち")
            scene.click("inception-add-material"); scene.settle()
            val material = f.controller.state.value.workspace!!.materials.single()
            scene.click("inception-evidence-${material.id}"); scene.settle()
            assertEquals(material.original, scene.tag("inception-original-text")!!.config[SemanticsProperties.Text].single().text)
            scene.click("inception-select-${material.id}"); scene.settle()
            scene.type("inception-concept-title", "帰り道"); scene.type("inception-concept-text", "少し立ち止まってみたい")
            scene.click("inception-save-concept"); scene.settle()
            val concept = f.controller.state.value.workspace!!.concepts.single()
            assertFalse(f.controller.state.value.workspace!!.adopted(concept))
            scene.click("inception-adopt-${concept.id}"); scene.settle()
            scene.click("inception-preview-lyrics-${concept.id}"); scene.settle()
            assertTrue(f.production.project.lyrics.isEmpty()); assertNotNull(scene.tag("inception-preview"))
            scene.click("inception-apply"); scene.settle()
            assertEquals(concept.statement, f.production.project.lyrics.single().text); assertEquals(1, f.production.undoCount)
            assertNotNull(scene.tag("inception-applied"))
            f.commit(f.production.planUndo()!!); assertTrue(f.production.project.lyrics.isEmpty())
            scene.click("inception-close"); scene.settle(); assertNull(scene.tag("inception-panel"))
            f.controller.open(); scene.settle(); assertTrue(f.controller.state.value.workspace!!.adopted(concept))
        } finally { scene.close(); f.controller.close() }
    }
    @Test fun narrowLargeJapaneseAndEnglishLongTextKeepStopAndCloseReachable() = runBlocking<Unit> {
        val original = Locale.getDefault()
        try {
            for (language in listOf("ja", "en")) {
                Locale.setDefault(Locale.forLanguageTag(language))
                val f = InceptionFixture(this); f.prepare()
                val scene = ImageComposeScene(width = 390, height = 1000, density = Density(1f, 2f), coroutineContext = coroutineContext) { MaterialTheme { InceptionPanel(f.controller) } }
                try {
                    scene.settle(); scene.type("inception-material-input", "長い文章 abc🙂 ".repeat(400)); scene.settle()
                    assertTrue(scene.tag("inception-material-input")!!.config[SemanticsProperties.EditableText].text.length > 4000)
                    for (tag in listOf("inception-stop", "inception-close")) {
                        val bounds = scene.tag(tag)!!.boundsInRoot
                        assertTrue(bounds.width >= 48 && bounds.height >= 48); assertTrue(bounds.top >= 0 && bounds.bottom <= 1000)
                    }
                    val output = File(System.getProperty("choplab.ui.evidenceDir"), "inception").apply { mkdirs() }
                    scene.render(System.nanoTime()).use { image -> image.encodeToData()!!.use { File(output, "inception-$language-390-font200.png").writeBytes(it.bytes) } }
                    scene.click("inception-close"); scene.settle()
                    assertTrue(f.controller.state.value.open, "Unsaved text needs explicit discard")
                } finally { scene.close(); f.controller.close() }
            }
        } finally { Locale.setDefault(original) }
    }
    @Test fun fourStagesExposeTheSameExplicitEntry() = runBlocking<Unit> {
        val f = InceptionFixture(this)
        for (stage in ContinuousStage.entries) {
            val scene = ImageComposeScene(width = 1280, height = 1000, coroutineContext = coroutineContext) {
                ContinuousEditor(ContinuousEditorState(stage = stage, capabilities = ContinuousCapability.entries.toSet()), {}, inception = f.controller)
            }
            try {
                scene.settle(); assertNotNull(scene.tag("inception-open")); scene.click("inception-open"); scene.settle()
                assertTrue(f.controller.state.value.open); f.controller.dismiss(); scene.settle()
            } finally { scene.close() }
        }
        f.controller.close()
    }
    @Test fun compactInstrumentKeepsEntryAndGlobalStopVisibleAtLargeText() = runBlocking<Unit> {
        val f = InceptionFixture(this)
        val scene = ImageComposeScene(width = 390, height = 820, density = Density(1f, 2f), coroutineContext = coroutineContext) {
            ContinuousEditor(ContinuousEditorState(stage = ContinuousStage.BEAT, capabilities = ContinuousCapability.entries.toSet()), {}, inception = f.controller)
        }
        try {
            scene.settle()
            for (tag in listOf("inception-open", "ce-stop-all")) {
                val bounds = scene.tag(tag)!!.boundsInRoot
                assertTrue(bounds.width >= 48 && bounds.height >= 48)
                assertTrue(bounds.left >= 0 && bounds.right <= 390 && bounds.top >= 0 && bounds.bottom <= 820)
            }
            assertTrue(scene.tag("ce-beat-viewport")!!.boundsInRoot.height > 0)
        } finally { scene.close(); f.controller.close() }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(id: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == id }
    private fun ImageComposeScene.click(id: String) { val node = requireNotNull(tag(id)) { id }; assertFalse(node.config.contains(SemanticsProperties.Disabled), id); assertTrue(node.config[SemanticsActions.OnClick].action!!.invoke()) }
    private suspend fun ImageComposeScene.type(id: String, value: String) { assertTrue(tag(id)!!.config[SemanticsActions.SetText].action!!.invoke(AnnotatedString(value))); settle() }
    private suspend fun ImageComposeScene.settle() { repeat(6) { render(System.nanoTime()).close(); delay(16) } }
}
