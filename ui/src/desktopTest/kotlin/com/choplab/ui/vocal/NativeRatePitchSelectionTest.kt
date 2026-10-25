package com.choplab.ui.vocal

import com.choplab.core.DocumentState
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import com.choplab.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.*

class NativeRatePitchSelectionTest {
    @Test fun savedSelectionPreparesTheOriginalOnceAndCommitsOneUndoWithTheExactCrop() = runBlocking {
        val f = Fixture()
        try {
            val before = f.document.value
            assertTrue(f.controller.dispatch(PitchAction.SelectSaved(true)))
            assertEquals(1, f.originals)
            assertEquals(1, f.session.undoCount)
            assertEquals(before.project.clips.single().copy(assetHash = f.original.hash), f.session.project.clips.single())
            assertEquals(f.original.hash, f.session.project.pitchCorrections.single().normalizedOriginalHash)
            assertTrue(f.controller.dispatch(PitchAction.SelectSaved(false)))
            assertEquals(before.project.clips, f.session.project.clips)
            assertTrue(f.controller.dispatch(PitchAction.SelectSaved(true)))
            assertEquals(1, f.originals, "Saved normalized asset is reused")
            assertEquals(3, f.session.undoCount)
            val undo = requireNotNull(f.session.planUndo())
            f.commit(undo)
            assertEquals(before.project.clips, f.session.project.clips)
        } finally { f.close() }
    }

    @Test fun uncooperativeOriginalCannotChangeTheDocumentAfterStopCloseRevisionRecordingOrFailure() = runBlocking {
        for (reason in listOf("stop", "close", "revision", "recording", "failure", "refuse")) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val f = Fixture {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                if (reason == "failure") error("No disk")
            }
            try {
                val initial = f.session.project
                val pending = async { f.controller.dispatch(PitchAction.SelectSaved(true)) }
                withTimeout(5000) { entered.await() }
                when (reason) {
                    "stop" -> f.controller.dispatch(PitchAction.Stop)
                    "close" -> f.controller.close()
                    "revision" -> f.document.value = f.document.value.copy(revision = 5)
                    "recording" -> f.availability.value = VocalAvailability.RECORDING
                    "refuse" -> f.acceptApply = false
                }
                release.complete(Unit)
                assertFalse(withTimeout(5000) { pending.await() }, reason)
                assertEquals(initial, f.session.project, reason)
                assertEquals(0, f.session.undoCount, reason)
            } finally { release.complete(Unit); f.close() }
        }
    }

    private class Fixture(private val preparing: suspend () -> Unit = {}) {
        private val source = Asset("a".repeat(64), "wav", 352_844, 44_100, 2, 44_100, "Native")
        private val corrected = Asset("b".repeat(64), "wav", 384_044, 48_000, 2, 48_000, "Corrected", AssetRole.RENDERED, derivedFrom = source.hash)
        val original = corrected.copy(hash = "c".repeat(64), name = "Original normalized")
        private val project = Project(assets = frozenListOf(source, corrected), tracks = frozenListOf(Track("voice", "Voice", TrackKind.VOCAL)),
            clips = frozenListOf(Clip("clip", "voice", corrected.hash, FrameRange(137, 47_789), timelineStartFrame = 1237)),
            pitchCorrections = frozenListOf(VocalPitchCorrection("pitch", "clip", source.hash, FrameRange(0, source.frames), corrected.hash, PitchCorrectionSettings())))
        val session = EditSession(project)
        val document = MutableStateFlow(DocumentState(project, 0))
        val availability = MutableStateFlow(VocalAvailability.EDITABLE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var originals = 0
        var acceptApply = true
        val controller = VocalPitchController(document, availability, object : VocalPitchPorts {
            override val previewing = MutableStateFlow(false)
            override suspend fun render(project: Project, draft: VocalPitchDraft, progress: (PitchCorrectionPhase, Int, Int) -> Unit): PreparedVocalPitch = error("No corrected render on saved selection")
            override suspend fun original(project: Project, draft: VocalPitchDraft): Asset {
                originals++; preparing(); assertEquals(FrameRange(0, source.frames), draft.sourceRange); return original
            }
            override suspend fun preview(asset: Asset, range: FrameRange, expectedRevision: Long): Boolean = error("Saved selection does not autoplay")
            override fun cancelPreview() {}
            override suspend fun stopPreview() = true
            override suspend fun apply(intent: Intent, expectedRevision: Long): Boolean {
                if (!acceptApply || document.value.revision != expectedRevision) return false
                commit(session.plan(intent)); return true
            }
        }, scope)
        fun commit(plan: EditPlan) {
            plan.effects.indices.forEach { session.acknowledge(plan, it) }; session.commit(plan)
            document.value = DocumentState(session.project, session.revision)
        }
        fun close() { controller.close(); scope.cancel() }
    }
}
