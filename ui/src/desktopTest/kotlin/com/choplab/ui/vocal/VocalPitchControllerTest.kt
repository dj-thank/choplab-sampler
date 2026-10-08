package com.choplab.ui.vocal

import com.choplab.ui.CreationRevisionNotifications
import com.choplab.core.DocumentState
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.core.vocal.VocalPitchDraft
import com.choplab.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.*

class VocalPitchControllerTest {
    @Test fun oldDocumentNotificationAfterReloadCannotInvalidateNewPreparedWork() = runBlocking {
        val notifications = CreationRevisionNotifications()
        val f = Fixture(observeDocument = notifications::observe)
        try {
            withTimeout(5000) { notifications.captured.await() }
            f.document.value = f.document.value.copy(revision = 1)
            assertTrue(f.controller.dispatch(PitchAction.Reload))
            assertTrue(f.controller.dispatch(PitchAction.Prepare))
            notifications.release.complete(Unit)
            withTimeout(5000) { notifications.delivered.await() }
            assertTrue(f.controller.state.value.prepared)
            assertNull(f.controller.state.value.problem)
            assertEquals(1L, f.controller.state.value.revision)
        } finally { notifications.release.complete(Unit); f.close() }
    }

    @Test fun lateStopNotificationCannotEraseANewerAuditionButAnActualStopDoes() = runBlocking {
        val f = Fixture(Dispatchers.Unconfined)
        try {
            assertTrue(f.controller.dispatch(PitchAction.PreviewOriginal))
            assertTrue(f.controller.dispatch(PitchAction.PreviewCorrected))
            assertEquals(PitchAudition.CORRECTED, f.controller.state.value.audition)
            // The projected StateFlow can deliver A's stop after the SOURCE port has already started B.
            f.previewing.value = false
            assertEquals(PitchAudition.CORRECTED, f.controller.state.value.audition)
            f.previewing.value = true
            f.actuallyPreviewing = false
            f.previewing.value = false
            assertEquals(PitchAudition.NONE, f.controller.state.value.audition)
        } finally { f.close() }
    }

    @Test fun settingsAndABAreSilentThenApplyIsOneUndoAndSavedABDoesNotLoseTheOriginal() = runBlocking {
        val f = Fixture()
        try {
            val before = f.document.value
            assertTrue(f.controller.dispatch(PitchAction.Field(PitchField.AMOUNT, "72")))
            assertTrue(f.controller.dispatch(PitchAction.Prepare)); assertEquals(before, f.document.value)
            assertTrue(f.controller.dispatch(PitchAction.PreviewOriginal)); assertEquals(before, f.document.value)
            assertEquals(PitchAudition.ORIGINAL, f.controller.state.value.audition)
            assertTrue(f.controller.dispatch(PitchAction.PreviewCorrected)); assertEquals(before, f.document.value)
            assertEquals(PitchAudition.CORRECTED, f.controller.state.value.audition)
            assertEquals(1, f.renders)
            assertTrue(f.controller.dispatch(PitchAction.Apply))
            assertEquals(1, f.session.undoCount); assertEquals(1L, f.document.value.revision)
            val corrected = f.document.value.project
            assertEquals(.72f, corrected.pitchCorrections.single().settings.amount)
            assertEquals(before.project.takes, corrected.takes); assertEquals(before.project.assets.single(), corrected.asset(Fixture.source.hash))
            assertEquals(1, f.renders, "The confirmed preview is the exact asset that Apply saves")
            assertTrue(f.controller.dispatch(PitchAction.SelectSaved(true)))
            assertEquals(before.project.clips, f.document.value.project.clips); assertEquals(2, f.session.undoCount)
            assertTrue(f.controller.dispatch(PitchAction.SelectSaved(false)))
            assertEquals(corrected, f.document.value.project); assertEquals(3, f.session.undoCount)
        } finally { f.close() }
    }

    @Test fun invalidBusyAndRecordingInputsNeverReachWorkerOrChangeUndo() = runBlocking {
        val f = Fixture()
        try {
            f.controller.dispatch(PitchAction.Field(PitchField.RETUNE, "NaN"))
            assertFalse(f.controller.dispatch(PitchAction.Apply)); assertEquals(0, f.renders)
            f.controller.dispatch(PitchAction.Field(PitchField.RETUNE, "50"))
            for (availability in listOf(VocalAvailability.BUSY, VocalAvailability.RECORDING)) {
                f.availability.value = availability
                assertFalse(f.controller.dispatch(PitchAction.Prepare)); assertFalse(f.controller.dispatch(PitchAction.PreviewOriginal))
            }
            f.availability.value = VocalAvailability.EDITABLE
            assertTrue(f.controller.dispatch(PitchAction.Cancel)); assertEquals(0, f.session.undoCount)
            assertEquals(0, f.renders)
        } finally { f.close() }
    }

    @Test fun uncooperativeRenderCannotApplyOrPreviewAfterCancelCloseRevisionRecordingOrCallerCancellation() = runBlocking {
        for (case in listOf("cancel", "close", "revision", "recording", "caller", "failure")) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val f = Fixture { draft ->
                entered.complete(Unit); withContext(NonCancellable) { release.await() }
                if (case == "failure") error("Decoder failure")
                Fixture.prepared(draft)
            }
            try {
                val pending = async { f.controller.dispatch(PitchAction.Apply) }
                withTimeout(5000) { entered.await() }
                when (case) {
                    "cancel" -> f.controller.dispatch(PitchAction.Cancel)
                    "close" -> { f.controller.close(); f.controller.close() }
                    "revision" -> f.document.value = f.document.value.copy(revision = 1)
                    "recording" -> f.availability.value = VocalAvailability.RECORDING
                    "caller" -> pending.cancelAndJoin()
                }
                release.complete(Unit)
                if (case != "caller") assertFalse(withTimeout(5000) { pending.await() }, case)
                assertEquals(0, f.session.undoCount, case); assertEquals(0, f.previews, case)
                assertTrue(f.document.value.project.pitchCorrections.isEmpty())
                if (case == "failure") assertEquals(PitchEditorProblem.FAILED, f.controller.state.value.problem)
                if (case == "cancel") assertEquals(PitchEditorPhase.EDITING, f.controller.state.value.phase)
                if (case == "revision") {
                    assertFalse(f.controller.dispatch(PitchAction.Apply)); assertTrue(f.controller.dispatch(PitchAction.Reload))
                    assertEquals(1L, f.controller.state.value.revision)
                }
            } finally { release.complete(Unit); f.close() }
        }
    }

    @Test fun previewFailureAndApplyRefusalAreVisibleAndDoNotConsumeAnUndo() = runBlocking {
        val f = Fixture()
        try {
            f.acceptPreview = false
            assertFalse(f.controller.dispatch(PitchAction.PreviewOriginal))
            assertEquals(PitchEditorProblem.PREVIEW_FAILED, f.controller.state.value.problem)
            f.acceptApply = false
            assertFalse(f.controller.dispatch(PitchAction.Apply))
            assertEquals(0, f.session.undoCount)
            assertEquals(PitchEditorPhase.EDITING, f.controller.state.value.phase)
        } finally { f.close() }
    }

    private class Fixture(dispatcher: CoroutineDispatcher = Dispatchers.Default,
                          val observeDocument: (StateFlow<DocumentState>) -> StateFlow<DocumentState> = { it },
                          private val rendering: suspend (VocalPitchDraft) -> PreparedVocalPitch = { prepared(it) }) {
        val session = EditSession(Project(assets = frozenListOf(source), tracks = frozenListOf(Track("voice", "Voice", TrackKind.VOCAL)),
            clips = frozenListOf(Clip("clip", "voice", source.hash, FrameRange(0, source.frames))),
            takes = frozenListOf(Take("raw", "voice", source.hash, FrameRange(0, source.frames), 0))))
        val document = MutableStateFlow(DocumentState(session.project, 0))
        val availability = MutableStateFlow(VocalAvailability.EDITABLE)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val previewing = MutableStateFlow(false)
        var actuallyPreviewing = false
        var renders = 0; var previews = 0; var acceptPreview = true; var acceptApply = true
        val controller = VocalPitchController(observeDocument(document), availability, object : VocalPitchPorts {
            override val previewing = this@Fixture.previewing
            override fun isPreviewing() = actuallyPreviewing
            override suspend fun render(project: Project, draft: VocalPitchDraft, progress: (PitchCorrectionPhase, Int, Int) -> Unit): PreparedVocalPitch {
                renders++; return rendering(draft)
            }
            override suspend fun original(project: Project, draft: VocalPitchDraft) = source
            override suspend fun preview(asset: Asset, expectedRevision: Long): Boolean {
                previews++; actuallyPreviewing = acceptPreview; previewing.value = acceptPreview; return acceptPreview
            }
            override fun cancelPreview() { actuallyPreviewing = false; previewing.value = false }
            override suspend fun stopPreview(): Boolean { cancelPreview(); return true }
            override suspend fun apply(intent: Intent, expectedRevision: Long): Boolean {
                if (!acceptApply || document.value.revision != expectedRevision || availability.value != VocalAvailability.EDITABLE) return false
                val plan = session.plan(intent); plan.effects.indices.forEach { session.acknowledge(plan, it) }; session.commit(plan)
                document.value = DocumentState(session.project, session.revision, session.canUndo, session.canRedo); return true
            }
        }, scope)
        fun close() { controller.close(); scope.cancel() }
        companion object {
            val source = Asset("a".repeat(64), "wav", 48_044, 48_000, 2, 6000, "Original")
            fun prepared(draft: VocalPitchDraft) = PreparedVocalPitch(Asset("b".repeat(64), "wav", 48_044, 48_000, 2, 6000,
                "Correction", AssetRole.RENDERED, derivedFrom = draft.sourceAssetHash), PitchCorrectionReport(6000, 1000, 2, emptyList(), 4096))
        }
    }
}
