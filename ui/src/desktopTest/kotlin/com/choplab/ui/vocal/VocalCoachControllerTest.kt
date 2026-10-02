package com.choplab.ui.vocal

import com.choplab.core.DocumentState
import com.choplab.core.ai.*
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.test.*

class VocalCoachControllerTest {
    @Test fun takeHistoryReferenceAndExplicitListeningResponseNeverEditTheDocument() = runBlocking {
        val f = Fixture()
        try {
            val before = f.document.value
            assertEquals(2, f.controller.state.value.takes.size)
            assertTrue(f.controller.dispatch(CoachAction.Take("take")))
            assertTrue(f.controller.dispatch(CoachAction.Reference("reference")))
            assertTrue(f.controller.dispatch(CoachAction.Input(CoachVoiceInput.VOICE_ONLY)))
            assertTrue(f.controller.dispatch(CoachAction.Analyze))
            assertEquals("take", f.request?.takeId); assertEquals("reference", f.request?.referenceTakeId)
            assertEquals("line", f.controller.state.value.line?.lineId)
            assertFalse(f.controller.dispatch(CoachAction.Respond))
            assertTrue(f.controller.dispatch(CoachAction.ListenGuide))
            assertEquals(CoachPhase.LISTENING, f.controller.state.value.phase)
            assertFalse(f.controller.dispatch(CoachAction.Respond))
            assertEquals(listOf("guide"), f.guideProject?.clips?.map { it.trackId })
            assertEquals(VocalPracticeRequest(0, 48_000), f.guideRequest)
            f.preview.finish()
            withTimeout(5000) { f.controller.state.first { it.phase == CoachPhase.READY_RESPONSE } }
            assertTrue(f.controller.dispatch(CoachAction.Respond)); assertEquals(1, f.responses)
            assertTrue(f.controller.dispatch(CoachAction.Practice)); assertEquals(1, f.practices)
            assertEquals(before, f.document.value)
            assertFalse(f.document.value.canUndo)
        } finally { f.close() }
    }

    @Test fun stopAndOutputFailureDoNotPretendTheGuideFinished() = runBlocking {
        val f = Fixture()
        try {
            assertTrue(f.controller.dispatch(CoachAction.Analyze))
            assertTrue(f.controller.dispatch(CoachAction.ListenGuide))
            assertTrue(f.controller.dispatch(CoachAction.Stop))
            assertFalse(f.controller.dispatch(CoachAction.Respond))
            assertNull(f.controller.state.value.heardLine)
            assertTrue(f.controller.dispatch(CoachAction.ListenGuide))
            f.preview.state.value = VocalPreviewState(VocalPreviewPhase.FAILED, failure = TtsFailure(TtsProblem.FAILED))
            withTimeout(5000) { f.controller.state.first { it.problem == CoachProblem.PREVIEW_FAILED } }
            assertFalse(f.controller.dispatch(CoachAction.Respond))
            assertEquals(0, f.responses)
        } finally { f.close() }
    }

    @Test fun absentOrMutedGuideCannotStartAnUnrelatedSilentResponseRound() = runBlocking {
        for (case in listOf("outside", "muted", "solo", "master")) {
            val f = Fixture()
            try {
                val project = when (case) {
                    "outside" -> f.project.copy(clips = f.project.clips.map { if (it.trackId == "guide") it.copy(timelineStartFrame = 48_000) else it }.frozen())
                    "muted" -> f.project.copy(tracks = f.project.tracks.map { if (it.id == "guide") it.copy(mute = true) else it }.frozen())
                    "solo" -> f.project.copy(tracks = f.project.tracks.map { if (it.id == "voice") it.copy(solo = true) else it }.frozen())
                    else -> f.project.copy(mix = f.project.mix.copy(masterGain = 0f))
                }
                f.document.value = DocumentState(project, 1)
                assertTrue(f.controller.dispatch(CoachAction.Reload))
                assertTrue(f.controller.dispatch(CoachAction.Analyze))
                assertFalse(f.controller.dispatch(CoachAction.ListenGuide), case)
                assertEquals(CoachProblem.NO_GUIDE, f.controller.state.value.problem, case)
                assertNull(f.guideProject)
                assertFalse(f.controller.dispatch(CoachAction.Respond))
                assertEquals(project, f.document.value.project)
            } finally { f.close() }
        }
    }

    @Test fun cancelledLateAnalysisAndStaleOrRecordingResultsCannotBecomePracticeEvidence() = runBlocking {
        for (case in listOf("stop", "close", "stale", "recording", "caller")) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val f = Fixture { project, revision, request ->
                entered.complete(Unit); withContext(NonCancellable) { release.await() }
                Fixture.report(project, revision, request)
            }
            try {
                val pending = async { f.controller.dispatch(CoachAction.Analyze) }
                withTimeout(5000) { entered.await() }
                when (case) {
                    "stop" -> f.controller.dispatch(CoachAction.Stop)
                    "close" -> { f.controller.close(); f.controller.close() }
                    "stale" -> f.document.value = f.document.value.copy(revision = 1)
                    "recording" -> f.availability.value = VocalAvailability.RECORDING
                    "caller" -> pending.cancel()
                }
                release.complete(Unit)
                if (case == "caller") assertFailsWith<CancellationException> { pending.await() }
                else assertFalse(withTimeout(5000) { pending.await() }, case)
                assertNull(f.controller.state.value.report, case)
                assertEquals(0, f.practices); assertEquals(0, f.responses)
                assertFalse(f.document.value.canUndo)
                if (case == "stale") {
                    assertFalse(f.controller.dispatch(CoachAction.Analyze))
                    assertTrue(f.controller.dispatch(CoachAction.Reload)); assertEquals(1L, f.controller.state.value.revision)
                }
            } finally { release.complete(Unit); f.close() }
        }
    }

    @Test fun completedEvidenceIsWithdrawnWhenTheSongChangesOrRecordingBegins() = runBlocking {
        for (recording in listOf(false, true)) {
            val f = Fixture()
            try {
                assertTrue(f.controller.dispatch(CoachAction.Analyze))
                assertNotNull(f.controller.state.value.report)
                if (recording) f.availability.value = VocalAvailability.RECORDING else f.document.value = f.document.value.copy(revision = 1)
                withTimeout(5000) { f.controller.state.first { it.report == null } }
                assertFalse(f.controller.dispatch(CoachAction.Practice))
                assertFalse(f.controller.dispatch(CoachAction.ListenGuide))
                assertEquals(0, f.practices)
            } finally { f.close() }
        }
    }

    private class Fixture(private val analyze: suspend (Project, Long, VocalCoachRequest) -> CoachResult<VocalCoachReport> = ::report) {
        val source = Asset("a".repeat(64), "wav", 384_044, 48_000, 2, 48_000, "Voice")
        val guide = Asset("b".repeat(64), "wav", 384_044, 48_000, 2, 48_000, "Guide", AssetRole.RENDERED)
        val project = Project(assets = frozenListOf(source, guide), tracks = frozenListOf(Track("voice", "Voice", TrackKind.VOCAL),
            Track("guide", "Guide", TrackKind.GUIDE)), clips = frozenListOf(Clip("voice-clip", "voice", source.hash, FrameRange(0, source.frames)),
            Clip("guide-clip", "guide", guide.hash, FrameRange(0, guide.frames))),
            takes = frozenListOf(Take("reference", "voice", source.hash, FrameRange(0, source.frames), 0),
                Take("take", "voice", source.hash, FrameRange(0, source.frames), 0)))
        val document = MutableStateFlow(DocumentState(project, 0))
        val availability = MutableStateFlow(VocalAvailability.EDITABLE)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val preview = Preview()
        var request: VocalCoachRequest? = null
        var guideProject: Project? = null
        var guideRequest: VocalPracticeRequest? = null
        var responses = 0; var practices = 0
        val controller = VocalCoachController(document, availability, object : VocalCoachHost {
            override val preview = this@Fixture.preview
            override val analyzer = object : VocalCoachAnalyzer {
                override suspend fun analyze(project: Project, revision: Long, request: VocalCoachRequest): CoachResult<VocalCoachReport> {
                    this@Fixture.request = request
                    return this@Fixture.analyze(project, revision, request)
                }
            }
            override val renderer = object : VocalPracticeRenderer {
                override suspend fun render(project: Project, revision: Long, request: VocalPracticeRequest,
                    progress: (PracticeProgress) -> Unit): PracticeResult<Asset> {
                    guideProject = project; guideRequest = request; return PracticeResult.Success(guide)
                }
            }
        }, object : VocalCoachActions {
            override suspend fun allowed(revision: Long): CoachProblem? = null
            override suspend fun preview(asset: Asset, revision: Long) = this@Fixture.preview.start(asset, revision, false, VocalPreviewOwner.COACH) is TtsResult.Success
            override suspend fun practice(line: VocalCoachLine, revision: Long): Boolean { practices++; assertEquals(0L, line.startFrame); return true }
            override suspend fun respond(line: VocalCoachLine, revision: Long): Boolean { responses++; assertEquals(48_000L, line.endFrame); return true }
        }, scope)
        fun close() { controller.close(); scope.cancel() }
        companion object {
            fun report(project: Project, revision: Long, request: VocalCoachRequest): CoachResult<VocalCoachReport> =
                CoachResult.Success(VocalCoachReport(revision, request, frozenListOf(VocalCoachLine("line", "A line", 0, 48_000,
                    onsetDifferenceMillis = 100, observedPitchHz = 220))))
        }
    }
    private class Preview : VocalPreviewPort {
        override val state = MutableStateFlow(VocalPreviewState())
        override suspend fun start(asset: Asset, expectedRevision: Long) = start(asset, expectedRevision, false, VocalPreviewOwner.COACH)
        override suspend fun start(asset: Asset, expectedRevision: Long, loop: Boolean, owner: VocalPreviewOwner): TtsResult<Unit> {
            state.value = VocalPreviewState(VocalPreviewPhase.PLAYING, true, asset.hash, owner = owner); return TtsResult.Success(Unit)
        }
        override suspend fun stop() = TtsResult.Success(Unit).also { finish() }
        override fun requestStop() { finish() }
        override fun frame() = 0L
        fun finish() { state.value = VocalPreviewState() }
    }
}
