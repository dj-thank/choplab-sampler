package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.FrameRange
import com.choplab.jvm.PcmMemoryBudget
import com.choplab.ui.*
import com.choplab.ui.analysis.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

/** Invokes the exact packaged entry, not an alternative in-test arrangement/export implementation. */
class WholeCreationHostTest {
    @Test fun wholeProductionCreationKeepsUndoBytesGraphAndFreshResumeInOneProcess() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("whole-creation-host-")
        try {
            val receipt = NextWholeCreationSelfTest.run(directory)
            assertTrue(receipt.json.contains("\"status\":\"LOCAL_PASS\""))
            for (boundary in listOf("nativeAudio", "gui", "provider", "humanAcceptance"))
                assertTrue(receipt.json.contains("\"$boundary\":false"))
            assertEquals(15, receipt.undoChecks)
            assertEquals(listOf(
                "source-analysis-explicit-tempo-undo-attack-chop",
                "triplet-step-repeat-route-overdub-undo",
                "wsola-preview-apply-undo",
                "lyrics-two-takes-punch-comp-guide-undo",
                "practice-pitch-coach-source-ownership",
                "four-stem-transaction-fixture-model",
                "mixer-fx-master-wav24-stems-lrc",
                "archive-autosave-fresh-backend-all-asset-bytes-and-export",
                "shared-128mib-owners-released"
            ), receipt.checks)
            assertEquals(0L, receipt.pcmAfterCloseBytes)
            assertTrue(receipt.pcmPeakBytes <= 128L * 1024 * 1024)
            assertEquals(receipt.json, Files.readString(receipt.directory.resolve("receipt.json")).trim())
            println(receipt.json)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun sharedBudgetRefusalAndLateAnalysisCannotStealTheLoopRecordingSlotOrEditTheSong() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("whole-creation-fences-")
        val h = NextWholeCreationSelfTest.Workbench(directory)
        try {
            h.prepare()
            val source = h.project.asset(requireNotNull(h.project.source).assetHash)
            h.edit(Intent.AssignRange(source.hash, FrameRange(0, 8_000), 0)); h.editable()
            val before = h.backend.studio.document.value
            val memory = PcmMemoryBudget.shared
            val used = memory.statistics().usedBytes
            memory.reserve(memory.limitBytes - used).use {
                assertFalse(h.presenter.dispatch(ContinuousEditorAction.RecordLoopOverdub(8)))
                NextWholeCreationSelfTest.until { h.presenter.state.value.status == ContinuousStatus.PLACE_NO_ROOM }
                assertEquals(before, h.backend.studio.document.value)
                assertNull(h.lastCapture, "The complete loop must be reserved before any engine capture is allocated")
            }
            h.analysisEntered = CompletableDeferred(); h.analysisRelease = CompletableDeferred(); h.analysisReturned = CompletableDeferred()
            h.act(ContinuousEditorAction.OpenSourceAnalysis)
            val analysis = requireNotNull(h.presenter.sourceAnalysis.value)
            NextWholeCreationSelfTest.until { analysis.state.value.editable }
            val late = async { analysis.dispatch(SourceAnalysisAction.Analyse) }
            withTimeout(30_000) { h.analysisEntered!!.await() }
            h.act(ContinuousEditorAction.RecordLoopOverdub(1))
            assertNull(h.presenter.sourceAnalysis.value)
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.RecordVoice))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.OpenVocalGuide))
            assertFalse(h.presenter.dispatch(ContinuousEditorAction.SaveProject))
            h.analysisRelease!!.complete(Unit)
            withTimeout(5_000) { h.analysisReturned!!.await() }
            assertFalse(late.await())
            assertEquals(SourceAnalysisPhase.CLOSED, analysis.state.value.phase)
            assertFalse(analysis.dispatch(SourceAnalysisAction.Apply))
            assertEquals(before, h.backend.studio.document.value)
            h.act(ContinuousEditorAction.HoldPad(0)); h.act(ContinuousEditorAction.CancelLoopOverdub)
            assertEquals(before, h.backend.studio.document.value)
            assertTrue(requireNotNull(h.lastCapture).take.completed)
        } finally { h.close(); directory.toFile().deleteRecursively() }
        assertEquals(0L, PcmMemoryBudget.shared.statistics().usedBytes)
    }
}
