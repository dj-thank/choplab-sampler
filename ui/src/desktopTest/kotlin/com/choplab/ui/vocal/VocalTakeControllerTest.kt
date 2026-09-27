package com.choplab.ui.vocal

import com.choplab.core.DocumentState
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class VocalTakeControllerTest {
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
                    "stop_preview" -> fixture.controller.dispatch(VocalAction.StopPreview)
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

    private class Fixture(val render: suspend (VocalCompDraft) -> Asset = { rendered(it) }) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val asset = Asset("a".repeat(64), "wav", 8044, 48_000, 2, 1000, "A")
        val document = MutableStateFlow(DocumentState(Project(assets = frozenListOf(asset), tracks = frozenListOf(Track("voice", "Voice", TrackKind.VOCAL)),
            takes = frozenListOf(Take("take", "voice", asset.hash, FrameRange(0, 1000), 0))), 0))
        val availability = MutableStateFlow(VocalAvailability.EDITABLE)
        var applies = 0
        var previews = 0
        val controller = VocalTakeController(document, availability, object : VocalTakePorts {
            override suspend fun render(project: Project, draft: VocalCompDraft, name: String) = render(draft)
            override suspend fun apply(intent: Intent, expectedRevision: Long): Boolean {
                if (document.value.revision != expectedRevision || availability.value != VocalAvailability.EDITABLE) return false
                applies++
                document.value = DocumentState(Reducer.reduce(document.value.project, intent).project, expectedRevision + 1, canUndo = true)
                return true
            }
            override suspend fun previewTake(project: Project, takeId: String): Boolean { previews++; return true }
            override suspend fun previewComp(asset: Asset): Boolean { previews++; return true }
            override fun stopPreview() = Unit
        }, scope)
        fun close() { controller.close(); scope.cancel() }
        companion object {
            fun rendered(draft: VocalCompDraft) = Asset("b".repeat(64), "wav", 44 + (draft.endFrame - draft.startFrame) * 8,
                48_000, 2, draft.endFrame - draft.startFrame, "Comp", AssetRole.RENDERED)
        }
    }
}
