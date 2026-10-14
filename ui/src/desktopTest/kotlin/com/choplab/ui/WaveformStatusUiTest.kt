@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)
package com.choplab.ui

import androidx.compose.runtime.*
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.test.*

class WaveformStatusUiTest {
    @Test fun namedFailureCanRetryWithoutTreatingReadySilenceAsFailure() = runBlocking<Unit> {
        val old = Locale.getDefault()
        try { for (locale in listOf(Locale.JAPANESE, Locale.ENGLISH)) {
            Locale.setDefault(locale)
            var state by mutableStateOf(ContinuousEditorState(
                assetWaveforms = mapOf("failed" to WaveformLoadState.FAILED, "silent" to WaveformLoadState.READY),
                assetWaveformNames = mapOf("failed" to "Recorded voice", "silent" to "Silence")))
            val actions = mutableListOf<ContinuousEditorAction>()
            val scene = ImageComposeScene(390, 844, Density(1f, 2f), coroutineContext = coroutineContext) {
                CETheme { CEWaveformStatus(state, actions::add) }
            }
            try {
                scene.settle(); scene.click("ce-waveforms-failed")
                val name = scene.node("ce-waveform-name-failed").config[SemanticsProperties.Text].joinToString { it.text }
                assertEquals("Recorded voice", name)
                assertNull(scene.nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == "ce-waveform-retry-silent" })
                scene.click("ce-waveform-retry-failed")
                assertEquals(listOf<ContinuousEditorAction>(ContinuousEditorAction.RetryWaveforms("failed")), actions)
                state = state.copy(assetWaveforms = state.assetWaveforms + ("failed" to WaveformLoadState.LOADING))
                scene.settle(); assertTrue(scene.node("ce-waveform-retry-failed").config.contains(SemanticsProperties.Disabled))
                state = state.copy(assetWaveforms = state.assetWaveforms + ("failed" to WaveformLoadState.READY))
                scene.settle(); scene.node("ce-waveforms-ready")
                scene.click("ce-waveforms-stop"); assertEquals(ContinuousEditorAction.StopAll, actions.last())
                scene.node("ce-waveforms-ready")
                scene.click("ce-waveforms-close")
                assertTrue(scene.nodes().none { it.config.getOrNull(SemanticsProperties.TestTag) == "ce-waveforms-failed" })
                assertEquals(0L, state.documentRevision)
            } finally { scene.close() }
        } } finally { Locale.setDefault(old) }
    }
    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.node(tag: String) = nodes().first { it.config.getOrNull(SemanticsProperties.TestTag) == tag }
    private suspend fun ImageComposeScene.settle() { repeat(8) { render(System.nanoTime()).close(); delay(12) } }
    private suspend fun ImageComposeScene.click(tag: String) { assertTrue(node(tag).config[SemanticsActions.OnClick].action!!.invoke()); settle() }
}
