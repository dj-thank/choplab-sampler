package com.choplab.ui.chop

import com.choplab.core.DocumentState
import com.choplab.core.chop.*
import com.choplab.core.edit.Intent
import com.choplab.core.edit.Reducer
import com.choplab.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class AutoChopControllerTest {
    private val asset = Asset("a".repeat(64), "wav", 100, 48_000, 2, 48_000, "source")
    private val original = DocumentState(Project(assets = frozenListOf(asset), source = Source(asset.hash, FrameRange(0, 48_000))), 7)
    private class Actions(val document: MutableStateFlow<DocumentState>) : AutoChopActions {
        var edits = 0; var previews = 0; var stopped = 0; var range: FrameRange? = null
        override suspend fun preview(asset: Asset, range: FrameRange, revision: Long): AutoChopProblem? { previews++; this.range = range; return null }
        override suspend fun stopPreview(): Boolean { stopped++; return true }
        override fun requestStopPreview() { stopped++ }
        override suspend fun apply(source: Source, markers: FrozenList<Long>, revision: Long): AutoChopProblem? {
            if (document.value.revision != revision) return AutoChopProblem.STALE
            edits++; document.value = DocumentState(Reducer.reduce(document.value.project, Intent.ApplyAutoChop(source, markers)).project, revision + 1)
            return null
        }
    }
    @Test fun settingsAndSlicePreviewAreEphemeralAndOnlyApplyEditsTheCapturedRevision() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val document = MutableStateFlow(original); val actions = Actions(document)
        val controller = AutoChopController(document, MutableStateFlow(AutoChopAvailability.EDITABLE), null, actions, scope)
        try {
            assertTrue(controller.dispatch(AutoChopAction.Settings(AutoChopSettings(slices = 4))))
            assertTrue(controller.dispatch(AutoChopAction.Prepare))
            withTimeout(2_000) { controller.state.first { it.canApply } }
            assertEquals(original, document.value)
            assertTrue(controller.dispatch(AutoChopAction.SelectSlice(2)))
            assertTrue(controller.dispatch(AutoChopAction.Preview)); assertEquals(FrameRange(24_000, 36_000), actions.range)
            assertEquals(original, document.value)
            assertTrue(controller.dispatch(AutoChopAction.Apply))
            assertEquals(1, actions.edits); assertEquals(8, document.value.revision)
            assertEquals(listOf(12_000L, 24_000L, 36_000L), document.value.project.source!!.markers)
            assertFalse(controller.dispatch(AutoChopAction.Apply)); assertEquals(1, actions.edits)
        } finally { scope.cancel() }
    }

    @Test fun cancellationFencesLateWorkersAndEditingOrRecordingRejectsReadyResults() = runBlocking {
        for (scenario in 0..3) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val document = MutableStateFlow(original); val actions = Actions(document)
            val available = MutableStateFlow(AutoChopAvailability.EDITABLE)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>()
            val controller = AutoChopController(document, available, AutoChopPort { _, _, _ -> withContext(NonCancellable) {
                entered.complete(Unit); release.await(); returned.complete(Unit); AutoChopResult.Ready(frozenListOf(12_000))
            } }, actions, scope)
            try {
                assertTrue(controller.dispatch(AutoChopAction.Settings(AutoChopSettings(AutoChopMode.ATTACK))))
                assertTrue(controller.dispatch(AutoChopAction.Prepare)); withTimeout(2_000) { entered.await() }
                when (scenario) {
                    0 -> assertTrue(controller.dispatch(AutoChopAction.Cancel))
                    1 -> document.value = original.copy(project = original.project.copy(title = "Changed"), revision = 8)
                    2 -> available.value = AutoChopAvailability.RECORDING
                    3 -> available.value = AutoChopAvailability.BUSY
                }
                if (scenario != 0) withTimeout(2_000) { controller.state.first { it.problem != null } }
                release.complete(Unit); returned.await()
                withTimeout(2_000) { controller.state.first { !it.working } }
                assertNull(controller.state.value.markers)
                assertFalse(controller.dispatch(AutoChopAction.Apply)); assertEquals(0, actions.edits)
                assertFalse(controller.dispatch(AutoChopAction.Preview)); assertEquals(0, actions.previews)
            } finally { release.complete(Unit); scope.cancel() }
        }
    }
}
