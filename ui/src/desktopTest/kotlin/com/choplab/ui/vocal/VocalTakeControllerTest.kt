package com.choplab.ui.vocal

import com.choplab.ui.CreationRevisionNotifications
import com.choplab.core.ai.*
import com.choplab.core.DocumentState
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.collect
import kotlin.test.*

class VocalTakeControllerTest {
    @OptIn(InternalCoroutinesApi::class)
    @Test fun previewTracksCurrentOwnerNaturalEndAndFailureWithoutOldNotificationsStoppingANewPreview() = runBlocking {
        val captured = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val delivered = CompletableDeferred<Unit>()
        val f = Fixture(observePreview = { current -> object : StateFlow<VocalPreviewState> by current {
            override suspend fun collect(collector: FlowCollector<VocalPreviewState>): Nothing = current.collect { value ->
                if (!captured.isCompleted) { captured.complete(Unit); release.await(); collector.emit(value); delivered.complete(Unit) }
                else collector.emit(value)
            }
        } })
        suspend fun waitFor(test: () -> Boolean) = withTimeout(5000) { while (!test()) delay(5) }
        try {
            withTimeout(5000) { captured.await() }
            assertTrue(f.controller.dispatch(VocalAction.PreviewTake))
            release.complete(Unit); withTimeout(5000) { delivered.await() }
            assertTrue(f.controller.state.value.previewing)
            f.preview.value = VocalPreviewState()
            waitFor { !f.controller.state.value.previewing }
            assertNull(f.controller.state.value.problem)
            assertTrue(f.controller.dispatch(VocalAction.PreviewTake))
            f.preview.value = VocalPreviewState(VocalPreviewPhase.RESTORING, true, owner = VocalPreviewOwner.TAKE)
            waitFor { !f.controller.state.value.previewing }
            f.preview.value = VocalPreviewState(VocalPreviewPhase.FAILED, failure = TtsFailure(TtsProblem.INVALID_AUDIO))
            waitFor { f.controller.state.value.problem == VocalProblem.PREVIEW_FAILED }
            assertFalse(f.controller.state.value.previewing)
            assertTrue(f.controller.dispatch(VocalAction.PreviewTake))
            f.preview.value = VocalPreviewState(VocalPreviewPhase.PLAYING, true, owner = VocalPreviewOwner.GUIDE)
            waitFor { !f.controller.state.value.previewing }
            assertNull(f.controller.state.value.problem)
            f.controller.close()
            assertEquals(VocalPreviewOwner.GUIDE, f.preview.value.owner)
        } finally { release.complete(Unit); f.close() }
    }
    @Test fun stopRetainsCompAndReplacementChoicesWhereExplicitDiscardClearsThem() = runBlocking {
        val f = Fixture()
        try {
            assertTrue(f.controller.dispatch(VocalAction.WholeTake))
            assertTrue(f.controller.dispatch(VocalAction.ReplaceClip("placed", true)))
            val draft = f.controller.state.value.draft
            for (listen in listOf(false, true)) {
                if (listen) assertTrue(f.controller.dispatch(VocalAction.PreviewComp("Comp")))
                assertTrue(f.controller.dispatch(VocalAction.StopPreview))
                assertEquals(draft, f.controller.state.value.draft)
                assertEquals(setOf("placed"), f.controller.state.value.replaceClipIds)
                assertFalse(f.controller.state.value.previewing)
            }
            assertEquals(0, f.applies)
            assertTrue(f.controller.dispatch(VocalAction.Cancel))
            assertNull(f.controller.state.value.draft); assertTrue(f.controller.state.value.replaceClipIds.isEmpty())
        } finally { f.close() }
    }

    @Test fun oldDocumentNotificationAfterReloadCannotInvalidateNewPreparedWork() = runBlocking {
        val notifications = CreationRevisionNotifications()
        val f = Fixture(observeDocument = notifications::observe)
        try {
            withTimeout(5000) { notifications.captured.await() }
            f.document.value = f.document.value.copy(revision = 1)
            assertTrue(f.controller.dispatch(VocalAction.Reload))
            assertTrue(f.controller.dispatch(VocalAction.WholeTake))
            assertTrue(f.controller.dispatch(VocalAction.PreviewComp("Comp")))
            notifications.release.complete(Unit)
            withTimeout(5000) { notifications.delivered.await() }
            assertTrue(f.controller.state.value.previewing)
            assertNotNull(f.controller.state.value.draft)
            assertNull(f.controller.state.value.problem)
            assertEquals(1L, f.controller.state.value.revision)
        } finally { notifications.release.complete(Unit); f.close() }
    }

    @Test fun cancelBusyRecordingAndStaleCannotModifyTheDocumentOrConsumeUndo() = runBlocking<Unit> {
        val fixture = Fixture()
        try {
            assertTrue(fixture.controller.dispatch(VocalAction.WholeTake))
            assertTrue(fixture.controller.dispatch(VocalAction.Cancel))
            assertNull(fixture.controller.state.value.draft)
            assertEquals(0, fixture.document.value.revision)
            for (block in listOf(VocalAvailability.BUSY, VocalAvailability.RECORDING)) {
                fixture.availability.value = block
                assertFalse(fixture.controller.dispatch(VocalAction.WholeTake))
                assertFalse(fixture.controller.dispatch(VocalAction.Apply("Comp")))
            }
            fixture.availability.value = VocalAvailability.EDITABLE
            fixture.document.value = fixture.document.value.copy(revision = 1)
            assertFalse(fixture.controller.dispatch(VocalAction.WholeTake))
            assertEquals(VocalProblem.STALE, fixture.controller.state.value.problem)
            assertTrue(fixture.controller.dispatch(VocalAction.Reload))
            assertTrue(fixture.controller.dispatch(VocalAction.WholeTake))
            assertEquals(0, fixture.applies)
        } finally { fixture.close() }
    }

    @Test fun closeCancelRevisionRecordingAndRenderFailureRejectLateAssets() = runBlocking<Unit> {
        for (case in listOf("close", "cancel", "stop_preview", "revision", "recording", "failure", "caller_cancel")) {
            val reached = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = Fixture { draft ->
                reached.complete(Unit)
                withContext(NonCancellable) { release.await() }
                if (case == "failure") error("Synthetic read failure")
                Fixture.rendered(draft)
            }
            try {
                fixture.controller.dispatch(VocalAction.WholeTake)
                val applying = async { fixture.controller.dispatch(VocalAction.Apply("Comp")) }
                withTimeout(5000) { reached.await() }
                when (case) {
                    "close" -> { fixture.controller.close(); fixture.controller.close() }
                    "cancel" -> fixture.controller.dispatch(VocalAction.Cancel)
                    "stop_preview" -> {
                        val draft = fixture.controller.state.value.draft
                        assertTrue(fixture.controller.dispatch(VocalAction.StopPreview))
                        assertEquals(draft, fixture.controller.state.value.draft)
                    }
                    "revision" -> fixture.document.value = fixture.document.value.copy(revision = 1)
                    "recording" -> fixture.availability.value = VocalAvailability.RECORDING
                    "caller_cancel" -> applying.cancelAndJoin()
                }
                release.complete(Unit)
                if (case == "caller_cancel") assertTrue(applying.isCancelled) else assertFalse(applying.await(), case)
                assertEquals(0, fixture.applies, case)
                assertTrue(fixture.document.value.project.vocalComps.isEmpty(), case)
                assertEquals(0, fixture.previews, case)
            } finally { release.complete(Unit); fixture.close() }
        }
    }

    @Test fun previewIsUncommittedAndApplyUsesExactlyOneGuardedIntentThenStopsRepeating() = runBlocking<Unit> {
        val fixture = Fixture()
        try {
            val before = fixture.document.value.project
            fixture.controller.dispatch(VocalAction.WholeTake)
            assertTrue(fixture.controller.dispatch(VocalAction.PreviewComp("Comp")))
            assertEquals(1, fixture.previews)
            assertEquals(before, fixture.document.value.project)
            assertTrue(fixture.controller.dispatch(VocalAction.Apply("Comp")))
            assertEquals(1, fixture.applies)
            assertFalse(fixture.controller.dispatch(VocalAction.Apply("Comp")))
            assertEquals(before.takes, fixture.document.value.project.takes)
            assertEquals(1, fixture.document.value.project.vocalComps.size)
        } finally { fixture.close() }
    }

    private class Fixture(val observeDocument: (StateFlow<DocumentState>) -> StateFlow<DocumentState> = { it },
                          val observePreview: (StateFlow<VocalPreviewState>) -> StateFlow<VocalPreviewState> = { it }, val render: suspend (VocalCompDraft) -> Asset = { rendered(it) }) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val asset = Asset("a".repeat(64), "wav", 8044, 48_000, 2, 1000, "A")
        val document = MutableStateFlow(DocumentState(Project(assets = frozenListOf(asset), tracks = frozenListOf(Track("voice", "Voice", TrackKind.VOCAL)),
            takes = frozenListOf(Take("take", "voice", asset.hash, FrameRange(0, 1000), 0)),
            clips = frozenListOf(Clip("placed", "voice", asset.hash, FrameRange(0, 1000)))), 0))
        val availability = MutableStateFlow(VocalAvailability.EDITABLE)
        val preview = MutableStateFlow(VocalPreviewState())
        var applies = 0
        var previews = 0
        val controller = VocalTakeController(observeDocument(document), availability, object : VocalTakePorts {
            override val previewState = observePreview(preview)
            override suspend fun render(project: Project, draft: VocalCompDraft, name: String) = render(draft)
            override suspend fun apply(intent: Intent, expectedRevision: Long): Boolean {
                if (document.value.revision != expectedRevision || availability.value != VocalAvailability.EDITABLE) return false
                applies++
                document.value = DocumentState(Reducer.reduce(document.value.project, intent).project, expectedRevision + 1, canUndo = true)
                return true
            }
            override suspend fun previewTake(project: Project, takeId: String): Boolean { previews++; preview.value = VocalPreviewState(VocalPreviewPhase.PLAYING, true, owner = VocalPreviewOwner.TAKE); return true }
            override suspend fun previewComp(asset: Asset): Boolean { previews++; preview.value = VocalPreviewState(VocalPreviewPhase.PLAYING, true, assetHash = asset.hash, owner = VocalPreviewOwner.TAKE); return true }
            override fun stopPreview() { if (preview.value.owner == VocalPreviewOwner.TAKE) preview.value = VocalPreviewState() }
        }, scope)
        fun close() { controller.close(); scope.cancel() }
        companion object {
            fun rendered(draft: VocalCompDraft) = Asset("b".repeat(64), "wav", 44 + (draft.endFrame - draft.startFrame) * 8,
                48_000, 2, draft.endFrame - draft.startFrame, "Comp", AssetRole.RENDERED)
        }
    }
}
