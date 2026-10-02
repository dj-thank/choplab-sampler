package com.choplab.ui.stretch

import com.choplab.core.DocumentState
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import com.choplab.ui.vocal.VocalAvailability
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class BeatStretchControllerTest {
    @Test fun lateStopNotificationCannotEraseANewerAuditionButAnActualStopDoes() = runBlocking {
        val f = Fixture(Dispatchers.Unconfined)
        try {
            assertTrue(f.controller.dispatch(StretchAction.Bpm("120")))
            assertTrue(f.controller.dispatch(StretchAction.Prepare))
            assertTrue(f.controller.dispatch(StretchAction.Original))
            assertTrue(f.controller.dispatch(StretchAction.Stretched))
            assertEquals(StretchAudition.STRETCHED, f.controller.state.value.audition)
            f.previewing.value = false // A's old stop arrives after the SOURCE owner has started B.
            assertEquals(StretchAudition.STRETCHED, f.controller.state.value.audition)
            f.previewing.value = true
            f.actuallyPreviewing = false
            f.previewing.value = false
            assertEquals(StretchAudition.NONE, f.controller.state.value.audition)
        } finally { f.close() }
    }
    @Test fun preparationAndABDoNotEditThenOnlyThePreparedResultAppliesOneUndo() = runBlocking {
        val f = Fixture()
        try {
            val before = f.document.value
            assertFalse(f.controller.dispatch(StretchAction.Apply)); assertEquals(0, f.renders)
            assertTrue(f.controller.dispatch(StretchAction.Bpm("120")))
            assertTrue(f.controller.dispatch(StretchAction.Prepare)); assertEquals(before, f.document.value)
            assertTrue(f.controller.dispatch(StretchAction.Original)); assertEquals(before, f.document.value)
            assertTrue(f.controller.dispatch(StretchAction.Stretched)); assertEquals(before, f.document.value)
            assertEquals(1, f.renders)
            assertTrue(f.controller.dispatch(StretchAction.Apply)); assertEquals(1, f.session.undoCount)
            assertEquals(1, f.renders); assertEquals("b".repeat(64), f.session.project.pads[0].assetHash)
            assertEquals(120_000, f.session.project.beatStretches.single().sourceMilliBpm)
            assertTrue(f.controller.state.value.applied)
            val undo = f.session.planUndo()!!; f.commit(undo)
            assertEquals(before.project, f.session.project)
        } finally { f.close() }
    }
    @Test fun invalidBusyRecordingAndUnpreparedApplyNeverReachTheWorker() = runBlocking {
        val f = Fixture()
        try {
            for (text in listOf("", "NaN", "Infinity", "39.9", "240.001")) {
                f.controller.dispatch(StretchAction.Bpm(text)); assertFalse(f.controller.dispatch(StretchAction.Prepare))
            }
            f.controller.dispatch(StretchAction.Bpm("120")); assertFalse(f.controller.dispatch(StretchAction.Apply))
            for (availability in listOf(VocalAvailability.BUSY, VocalAvailability.RECORDING)) {
                f.availability.value = availability; assertFalse(f.controller.dispatch(StretchAction.Prepare))
                assertFalse(f.controller.dispatch(StretchAction.Original))
            }
            assertEquals(0, f.renders); assertEquals(0, f.session.undoCount)
        } finally { f.close() }
    }
    @Test fun lateOrPartialResultsNeverBecomePreparedAfterCancellationReplacementRecordingOrFailure() = runBlocking {
        for (case in listOf("cancel", "close", "revision", "recording", "caller", "failure", "partial")) {
            val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
            val f = Fixture { draft ->
                entered.complete(Unit); withContext(NonCancellable) { released.await() }
                if (case == "failure") error("Render failed")
                Fixture.rendered(draft).let { if (case == "partial") it.copy(frames = it.frames - 1) else it }
            }
            try {
                f.controller.dispatch(StretchAction.Bpm("120"))
                val pending = async { f.controller.dispatch(StretchAction.Prepare) }
                withTimeout(5000) { entered.await() }
                when (case) {
                    "cancel" -> f.controller.dispatch(StretchAction.Cancel)
                    "close" -> f.controller.close()
                    "revision" -> f.document.value = f.document.value.copy(revision = 7)
                    "recording" -> f.availability.value = VocalAvailability.RECORDING
                    "caller" -> pending.cancelAndJoin()
                }
                released.complete(Unit)
                if (case != "caller") assertFalse(withTimeout(5000) { pending.await() }, case)
                assertFalse(f.controller.state.value.prepared, case)
                assertEquals(0, f.previews); assertEquals(0, f.session.undoCount)
                assertFalse(f.controller.dispatch(StretchAction.Apply))
            } finally { released.complete(Unit); f.close() }
        }
    }
    @Test fun tempoFieldChangesDiscardPreparedSoundAndPreviewOrApplyFailureNeverConsumesUndo() = runBlocking {
        val f = Fixture()
        try {
            f.controller.dispatch(StretchAction.Bpm("120")); assertTrue(f.controller.dispatch(StretchAction.Prepare))
            f.controller.dispatch(StretchAction.Bpm("121")); assertFalse(f.controller.state.value.prepared)
            assertFalse(f.controller.dispatch(StretchAction.Stretched)); assertEquals(0, f.previews)
            f.acceptPreview = false; assertFalse(f.controller.dispatch(StretchAction.Original))
            assertEquals(StretchProblem.PREVIEW_FAILED, f.controller.state.value.problem)
            assertTrue(f.controller.dispatch(StretchAction.Prepare))
            f.acceptApply = false; assertFalse(f.controller.dispatch(StretchAction.Apply))
            assertEquals(0, f.session.undoCount); assertEquals(StretchPhase.EDITING, f.controller.state.value.phase)
        } finally { f.close() }
    }
    @Test fun reassignedPadDoesNotInheritTheOldMaterialsTempo() = runBlocking {
        val f = Fixture()
        try {
            f.controller.dispatch(StretchAction.Bpm("120")); assertTrue(f.controller.dispatch(StretchAction.Prepare))
            assertTrue(f.controller.dispatch(StretchAction.Apply))
            f.commit(f.session.plan(Intent.AssignRange(Fixture.source.hash, FrameRange(0, 3000), 0)))
            assertTrue(f.controller.dispatch(StretchAction.Reload))
            assertNull(f.controller.state.value.saved)
            assertEquals("", f.controller.state.value.sourceBpm)
            assertFalse(f.controller.dispatch(StretchAction.Prepare))
            assertEquals(1, f.renders)
        } finally { f.close() }
    }
    private class Fixture(dispatcher: CoroutineDispatcher = Dispatchers.Default,
                          private val rendering: suspend (StretchDraft) -> Asset = { rendered(it) }) {
        val session = EditSession(Project(assets = frozenListOf(source), pads = (0..127).map {
            if (it == 0) Pad(0, source.hash, FrameRange(0, source.frames)) else Pad(it)
        }.frozen(), tempo = Tempo(150_000)))
        val document = MutableStateFlow(DocumentState(session.project, 0))
        val availability = MutableStateFlow(VocalAvailability.EDITABLE)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val previewing = MutableStateFlow(false)
        var actuallyPreviewing = false
        var renders = 0; var previews = 0; var acceptPreview = true; var acceptApply = true
        fun commit(plan: EditPlan) {
            plan.effects.indices.forEach { session.acknowledge(plan, it) }; session.commit(plan)
            document.value = DocumentState(session.project, session.revision, session.canUndo, session.canRedo)
        }
        val controller = BeatStretchController(document, availability, object : BeatStretchPorts {
            override val previewing = this@Fixture.previewing
            override fun isPreviewing() = actuallyPreviewing
            override suspend fun render(project: Project, draft: StretchDraft, progress: (Int, Int) -> Unit): Asset { renders++; return rendering(draft) }
            override suspend fun original(project: Project, draft: StretchDraft) = source
            override suspend fun preview(asset: Asset, revision: Long): Boolean { previews++; actuallyPreviewing = acceptPreview; previewing.value = acceptPreview; return acceptPreview }
            override suspend fun stopPreview(): Boolean { cancelPreview(); return true }
            override fun cancelPreview() { actuallyPreviewing = false; previewing.value = false }
            override suspend fun apply(intent: Intent, revision: Long): Boolean {
                if (!acceptApply || document.value.revision != revision || availability.value != VocalAvailability.EDITABLE) return false
                commit(session.plan(intent)); return true
            }
        }, scope, StretchTarget(StretchKind.PAD, "0"))
        fun close() { controller.close(); scope.cancel() }
        companion object {
            val source = Asset("a".repeat(64), "wav", 48_044, 48_000, 2, 6000, "Original")
            fun rendered(draft: StretchDraft): Asset {
                val frames = stretchFrames(source, draft.sourceRange, draft.sourceMilliBpm, draft.targetMilliBpm)
                return Asset("b".repeat(64), "wav", 44 + frames * 8, 48_000, 2, frames, "Stretched", AssetRole.RENDERED, derivedFrom = source.hash)
            }
        }
    }
}
