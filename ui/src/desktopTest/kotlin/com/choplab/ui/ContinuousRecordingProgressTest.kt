@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package com.choplab.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Density
import com.choplab.core.DocumentState
import com.choplab.core.model.Project
import com.choplab.core.vocal.*
import com.choplab.ui.vocal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.Locale
import kotlin.test.*

class ContinuousRecordingProgressTest {
    @Test fun estimatesAreExplicitAndActualLimitsReplaceThemAfterOpening() = runBlocking<Unit> {
        val state = mutableStateOf(ContinuousEditorFixture.state(ContinuousStage.CAPTURE).copy(
            voiceRecordingEstimateMillis = 71_000, systemRecordingEstimateMillis = 25_000,
            capabilities = ContinuousCapability.entries.toSet()))
        val scene = ImageComposeScene(width = 1000, height = 500, density = Density(1f), coroutineContext = coroutineContext) {
            CETheme { Column {
                CERecordingEstimates(state.value)
                CERecordingStatus(state.value, {}, { ContinuousEditorReadout(input = RecordingInputReadout(recordedMillis = 5000, limitMillis = 37_000)) }, 0)
            } }
        }
        try {
            scene.settle()
            assertTrue(scene.text("ce-mic-estimate").contains("1:11"))
            assertTrue(scene.text("ce-system-estimate").contains("0:25"))
            assertNull(scene.tag("ce-recording-remaining"))
            state.value = state.value.copy(recordingSource = true)
            scene.settle()
            assertNull(scene.tag("ce-mic-estimate"))
            assertTrue(scene.text("ce-recording-remaining").contains("0:32"), "Only the actual capture limit is used during recording")
        } finally { scene.close() }
    }

    @Test fun voiceEstimateFitsTheExistingInstrumentWithoutDisplacingItsPads() = runBlocking<Unit> {
        val scene = ImageComposeScene(width = 1440, height = 1024, density = Density(1f), coroutineContext = coroutineContext) {
            ContinuousEditor(ContinuousEditorFixture.state().copy(voiceRecordingEstimateMillis = 71_000), {}, ContinuousEditorFixture::readout)
        }
        try {
            scene.settle()
            assertTrue(scene.text("ce-record-voice").contains("1:11"))
            for (pad in 0..15) {
                val bounds = requireNotNull(scene.tag("ce-pad-$pad")).boundsInRoot
                assertTrue(bounds.width >= 64f && bounds.height >= 64f && bounds.top >= 0 && bounds.bottom <= 1024, "PAD $pad remains a visible playable target: $bounds")
            }
        } finally { scene.close() }
    }

    @Test fun punchModalReportsOpeningPrerollCaptureSavingAndExplicitClosing() = runBlocking<Unit> {
        val previous = Locale.getDefault()
        try { for (locale in listOf(Locale.US, Locale.JAPAN)) {
            Locale.setDefault(locale)
            val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
            val progress = MutableStateFlow(VocalPunchProgress())
            val controller = VocalPunchController(MutableStateFlow(DocumentState(Project(), 0)), progress, object : VocalPunchActions {
                override suspend fun record(request: VocalPunchRequest, expectedRevision: Long): PunchCompletion {
                    entered.complete(Unit); finish.await()
                    return PunchCompletion(VocalPunchResult(problem = PunchProblem.CANCELLED), false)
                }
                override fun stop() = Unit
            }, this, 0, 48_000)
            val scene = ImageComposeScene(width = 640, height = 900, density = Density(1f), coroutineContext = coroutineContext) {
                CETheme { VocalPunchPanel(controller, {}, {}) }
            }
            try {
                val recording = async { controller.record() }
                entered.await()
                val ja = locale == Locale.JAPAN
                for ((phase, word) in listOf(
                    PunchPhase.OPENING to if (ja) "入力を準備中" else "opening input",
                    PunchPhase.PRE_ROLL to if (ja) "前の区間を再生中" else "pre-roll",
                    PunchPhase.CAPTURING to if (ja) "選んだ範囲を録音中" else "recording selected range",
                    PunchPhase.SAVING to if (ja) "保存中" else "saving take")) {
                    progress.value = VocalPunchProgress(phase, 1, 1)
                    scene.settle()
                    assertTrue(scene.text("punch-phase").contains(word), scene.text("punch-phase"))
                    val bounds = requireNotNull(scene.tag("punch-phase")).boundsInRoot
                    assertTrue(bounds.top >= 0 && bounds.bottom < 900, "The phase stays visible without scrolling configuration: $bounds")
                    assertEquals(LiveRegionMode.Polite, requireNotNull(scene.tag("punch-phase")).config[SemanticsProperties.LiveRegion])
                }
                progress.value = VocalPunchProgress(PunchPhase.IDLE)
                controller.beginSaving(); scene.settle()
                assertTrue(scene.text("punch-phase").contains(if (ja) "保存中" else "saving take"))
                assertTrue(requireNotNull(scene.tag("punch-stop")).config.contains(SemanticsProperties.Disabled))
                controller.requestClose(); scene.settle()
                assertTrue(scene.text("punch-phase").contains(if (ja) "閉じています" else "before closing"))
                assertTrue(requireNotNull(scene.tag("punch-close")).config.contains(SemanticsProperties.Disabled))
                finish.complete(Unit); recording.await()
            } finally { finish.complete(Unit); controller.closeAndJoin(); scene.close() }
        } } finally { Locale.setDefault(previous) }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> = buildList {
        fun visit(node: SemanticsNode) { add(node); node.children.forEach(::visit) }
        semanticsOwners.forEach { visit(it.unmergedRootSemanticsNode) }
    }
    private fun ImageComposeScene.tag(value: String) = nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.TestTag) == value }
    private fun ImageComposeScene.text(value: String): String {
        fun all(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::all)
        return all(requireNotNull(tag(value)) { value }).joinToString(" ")
    }
    private suspend fun ImageComposeScene.settle() { repeat(15) { render(); delay(10) } }
}
