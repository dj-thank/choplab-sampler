package com.choplab.ui.inception

import com.choplab.core.*
import com.choplab.core.ai.*
import com.choplab.core.edit.*
import com.choplab.core.inception.*
import com.choplab.core.model.*
import kotlinx.coroutines.*
import kotlin.test.*

class InceptionControllerTest {
    @Test fun manualTextEvidenceAdoptionReopenAndExplicitProductionApplyHaveOneUndo() = runBlocking<Unit> {
        val f = InceptionFixture(this)
        f.prepare()
        val c = f.controller.state.value.workspace!!.concepts.single()
        assertTrue(f.controller.preview(c.id, InceptionDestination.LYRICS))
        assertTrue(f.production.project.lyrics.isEmpty()); assertEquals(0, f.production.undoCount)
        assertTrue(f.controller.apply()); assertEquals(c.statement, f.production.project.lyrics.single().text)
        assertEquals(1, f.production.undoCount)
        assertFalse(f.controller.apply())
        f.commit(f.production.planUndo()!!); assertTrue(f.production.project.lyrics.isEmpty())
        assertTrue(f.controller.dismiss()); assertTrue(f.controller.open())
        assertTrue(f.controller.state.value.workspace!!.adopted(c))
        f.controller.close()
    }
    @Test fun originalIsImmutableCorrectionAndDecisionSnapshotsSurviveEdits() = runBlocking<Unit> {
        val f = InceptionFixture(this); f.prepare()
        val w = f.controller.state.value.workspace!!; val m = w.materials.single(); val c = w.concepts.single()
        assertTrue(f.controller.correctMaterial(m.id, "corrected"))
        assertEquals("original", f.controller.state.value.workspace!!.materials.single().original)
        assertTrue(f.controller.putConcept(c.id, c.title, "edited statement"))
        val after = f.controller.state.value.workspace!!
        assertEquals(c, after.decisions.single().snapshot); assertFalse(after.adopted(after.concepts.single()))
        assertTrue(f.controller.undo()); assertEquals(c, f.controller.state.value.workspace!!.concepts.single())
        f.controller.close()
    }
    @Test fun deletionPurgesDependentConceptHistoryHypothesisSearchAndUndo() = runBlocking<Unit> {
        val f = InceptionFixture(this); f.prepare()
        val w = f.controller.state.value.workspace!!
        assertTrue(f.controller.hypothesis(statement = "maybe")); assertTrue(f.controller.permitSearch(w.id))
        assertTrue(f.controller.search("original", RelationKind.SIMILAR)); assertEquals(1, f.controller.state.value.hits.size)
        assertTrue(f.controller.deleteMaterial(w.materials.single().id))
        val after = f.controller.state.value
        assertFalse(after.canUndo); assertTrue(after.hits.isEmpty()); assertTrue(after.workspace!!.concepts.isEmpty())
        assertTrue(after.workspace!!.decisions.isEmpty()); assertTrue(after.workspace!!.hypotheses.isEmpty())
        f.controller.close()
    }
    @Test fun failedStoreCommitDoesNotPublishUnsavedTextOrDiscardUndo() = runBlocking<Unit> {
        val f = InceptionFixture(this); f.prepare(); val before = f.controller.state.value.workspace
        f.store.fail = true
        assertFalse(f.controller.addMaterial("not saved")); assertEquals(before, f.controller.state.value.workspace)
        assertTrue(f.controller.state.value.canUndo); assertEquals(InceptionProblem.CAPACITY, f.controller.state.value.problem)
        f.controller.close()
    }
    @Test fun oldProductionSnapshotAndBusyHostRefuseApply() = runBlocking<Unit> {
        val f = InceptionFixture(this); f.prepare(); val c = f.controller.state.value.workspace!!.concepts.single()
        f.controller.preview(c.id, InceptionDestination.PRODUCTION_TITLE)
        f.commit(f.production.plan(Intent.Rename("changed elsewhere")))
        assertFalse(f.controller.apply()); assertEquals(InceptionProblem.STALE, f.controller.state.value.problem)
        f.allowApply = false; f.controller.preview(c.id, InceptionDestination.PRODUCTION_TITLE)
        assertFalse(f.controller.apply()); assertEquals("changed elsewhere", f.production.project.title)
        f.controller.close()
    }
    @Test fun noReviewOrConsentSendsNothingAndEditingInvalidatesConsent() = runBlocking<Unit> {
        val f = InceptionFixture(this); f.prepare(); f.controller.bindProvider("gemini-test", "test-key")
        assertFalse(f.controller.generate()); assertEquals(0, f.calls)
        f.review(); assertFalse(f.controller.generate()); assertEquals(0, f.calls)
        f.controller.consent(true); f.controller.addMaterial("new")
        assertNull(f.controller.state.value.consentVersion); assertFalse(f.controller.generate()); assertEquals(0, f.calls)
        f.controller.close()
    }
    @Test fun lateResponseIsRejectedAfterNewInputStopCloseAndWorkspaceSwitch() = runBlocking<Unit> {
        for (operation in 0..3) {
            val response = CompletableDeferred<InceptionProviderResult>()
            val entered = CompletableDeferred<Unit>()
            val f = InceptionFixture(this) { request -> entered.complete(Unit); withContext(NonCancellable) { response.await() } }
            f.prepare(); f.controller.bindProvider("gemini-test", "test-key"); f.review(); f.controller.consent(true); f.controller.generate(); entered.await()
            val request = f.request!!
            when (operation) { 0 -> f.controller.addMaterial("later"); 1 -> f.controller.stop(); 2 -> f.controller.dismiss(); else -> f.controller.create("another") }
            response.complete(InceptionProviderResult.Success(InceptionResponse(request.id, request.revision, frozenListOf())))
            delay(30); assertNull(f.controller.state.value.candidates); assertFalse(f.controller.state.value.generating)
            f.controller.close()
        }
    }
    @Test fun strictSuccessfulResponseIsOnlyACandidateUntilKeptAndAdopted() = runBlocking<Unit> {
        val f = InceptionFixture(this) { r -> InceptionProviderResult.Success(InceptionResponse(r.id, r.revision,
            frozenListOf(InceptionConcept("provider", "angle", "idea", frozenListOf(r.materials.single().id), ConceptOrigin.PROVIDER)))) }
        f.prepare(); f.controller.bindProvider("gemini-test", "test-key"); f.review(); f.controller.consent(true)
        assertTrue(f.controller.generate()); f.awaitResponse()
        assertEquals(1, f.calls); assertEquals(1, f.controller.state.value.workspace!!.concepts.size)
        assertTrue(f.controller.keepCandidate("provider"))
        val w = f.controller.state.value.workspace!!; assertEquals(2, w.concepts.size); assertFalse(w.adopted(w.concepts.last()))
        assertFalse(f.controller.generate()); assertEquals(1, f.calls)
        f.controller.close()
    }
    @Test fun rateLimitDoesNotRetryAndRequiresANewReview() = runBlocking<Unit> {
        val f = InceptionFixture(this) { InceptionProviderResult.Failure(InceptionProblem.RATE_LIMITED, true) }
        f.prepare(); f.controller.bindProvider("gemini-test", "test-key"); f.review(); f.controller.consent(true); f.controller.generate(); f.awaitResponse()
        assertEquals(1, f.calls); assertTrue(f.controller.state.value.costUnknown)
        assertEquals(InceptionProblem.RATE_LIMITED, f.controller.state.value.problem)
        assertFalse(f.controller.generate()); delay(20); assertEquals(1, f.calls)
        f.controller.close()
    }
    @Test fun confirmedHypothesisCanBeEditedUnconfirmedAndDeletedWithoutChangingConcepts() = runBlocking<Unit> {
        val f = InceptionFixture(this); f.prepare(); f.controller.hypothesis(statement = "hypothesis")
        val h = f.controller.state.value.workspace!!.hypotheses.single()
        assertFalse(h.confirmed); f.controller.hypothesis(h.id, h.statement, true)
        assertTrue(f.controller.state.value.workspace!!.hypotheses.single().confirmed)
        f.controller.hypothesis(h.id, "changed"); assertFalse(f.controller.state.value.workspace!!.hypotheses.single().confirmed)
        f.controller.removeHypothesis(h.id); assertTrue(f.controller.state.value.workspace!!.hypotheses.isEmpty())
        assertEquals(1, f.controller.state.value.workspace!!.concepts.size)
        f.controller.close()
    }
    @Test fun unsavedDraftCancelsOldProposalAndPreviewWithoutSavingOrHidingUnknownCost() = runBlocking<Unit> {
        val response = CompletableDeferred<InceptionProviderResult>()
        val entered = CompletableDeferred<Unit>()
        val f = InceptionFixture(this) { entered.complete(Unit); withContext(NonCancellable) { response.await() } }
        f.prepare(); val before = f.controller.state.value.workspace!!
        f.controller.bindProvider("gemini-test", "test-key"); f.review(); f.controller.consent(true)
        f.controller.generate(); entered.await(); val request = f.request!!
        assertTrue(f.controller.draftChanged()); assertEquals(before, f.controller.state.value.workspace)
        assertNull(f.controller.state.value.consentVersion); assertTrue(f.controller.state.value.costUnknown)
        response.complete(InceptionProviderResult.Success(InceptionResponse(request.id, request.revision, frozenListOf())))
        delay(30); assertNull(f.controller.state.value.candidates)
        assertTrue(f.controller.preview(before.concepts.single().id, InceptionDestination.LYRICS))
        f.controller.draftChanged(); assertNull(f.controller.state.value.preview); assertFalse(f.controller.apply())
        f.controller.close()
    }
    @Test fun deletingAndRecreatingNotebookCannotReuseTheOldIdentity() = runBlocking<Unit> {
        val f = InceptionFixture(this); f.prepare(); val old = f.controller.state.value.workspace!!
        assertTrue(f.controller.deleteWorkspace()); assertTrue(f.controller.create("same title"))
        assertNotEquals(old.id, f.controller.state.value.workspace!!.id)
        assertFailsWith<InceptionException> { f.store.save(old.copy(revision = old.revision + 1), old.revision) }
        f.controller.close()
    }
    @Test fun quietAndJamSaveOnlyExplicitUtterancesAndStopDropsLateTranscripts() = runBlocking<Unit> {
        class Voice : InceptionVoice {
            override val available = true
            var result = CompletableDeferred<InceptionMaterial>()
            var entered = CompletableDeferred<Unit>()
            var stops = 0
            override suspend fun utterance(): InceptionMaterial { entered.complete(Unit); return withContext(NonCancellable) { result.await() } }
            override fun stop() { stops++ }
            fun next() { result = CompletableDeferred(); entered = CompletableDeferred() }
        }
        val voice = Voice(); val f = InceptionFixture(this, voice = voice); f.prepare()
        for (mode in InceptionVoiceMode.entries) {
            f.controller.mode(mode); assertTrue(f.controller.listen()); voice.entered.await()
            voice.result.complete(InceptionMaterial("raw", "explicit utterance", kind = MaterialKind.TRANSCRIPT))
            withTimeout(3000) { while (f.controller.state.value.listening) delay(5) }
            assertEquals(0, f.calls); assertEquals(MaterialKind.TRANSCRIPT, f.controller.state.value.workspace!!.materials.last().kind)
            assertNull(f.controller.state.value.workspace!!.materials.last().audioRef); voice.next()
        }
        val saved = f.controller.state.value.workspace!!
        f.controller.listen(); voice.entered.await(); f.controller.stop()
        voice.result.complete(InceptionMaterial("late", "not accepted", kind = MaterialKind.TRANSCRIPT))
        delay(30); assertEquals(saved, f.controller.state.value.workspace); assertTrue(voice.stops > 0)
        f.controller.close()
    }
    @Test fun invalidFocusInputIsReportedWithoutEscapingTheController() = runBlocking<Unit> {
        val f = InceptionFixture(this); f.prepare(); val before = f.controller.state.value.workspace
        assertFalse(f.controller.focusSummary("bad\rtext", "", "", ""))
        assertEquals(InceptionProblem.INVALID, f.controller.state.value.problem)
        assertEquals(before, f.controller.state.value.workspace); f.controller.close()
    }
    @Test fun unavailableVoiceNeverCreatesMaterial() = runBlocking<Unit> {
        val f = InceptionFixture(this); f.prepare(); assertFalse(f.controller.listen())
        assertEquals(InceptionProblem.VOICE_UNAVAILABLE, f.controller.state.value.problem)
        assertEquals(1, f.controller.state.value.workspace!!.materials.size); f.controller.close()
    }
}

internal class InceptionFixture(scope: CoroutineScope, voice: InceptionVoice = UnavailableInceptionVoice, private val respond: suspend (InceptionRequest) -> InceptionProviderResult = { r ->
    InceptionProviderResult.Success(InceptionResponse(r.id, r.revision, frozenListOf()))
}) {
    val store = MemoryInceptionStore()
    val production = EditSession()
    var current = DocumentState(production.project, 0)
    var allowApply = true
    var calls = 0
    var request: InceptionRequest? = null
    val controller = InceptionController(store, scope, { current }, { intent, revision ->
        if (revision != current.revision) false else { commit(production.plan(intent)); true }
    }, GoogleLyricSession { 100L }, { object : InceptionProvider {
        override suspend fun propose(request: InceptionRequest, model: String, key: SessionApiKey, attempt: GoogleLyricAttempt): InceptionProviderResult {
            calls++; this@InceptionFixture.request = request
            return respond(request)
        }
        override fun close() {}
    } }, voice = voice, canApply = { allowApply })
    fun commit(plan: EditPlan) { plan.effects.indices.forEach { production.acknowledge(plan, it) }; production.commit(plan); current = DocumentState(production.project, production.revision) }
    suspend fun prepare() {
        assertTrue(controller.open()); assertTrue(controller.create("notebook")); assertTrue(controller.addMaterial("original"))
        assertTrue(controller.select(controller.state.value.workspace!!.materials.single().id))
        assertTrue(controller.putConcept(null, "concept", "statement"))
        assertTrue(controller.decide(controller.state.value.workspace!!.concepts.single().id, DecisionKind.ADOPT))
    }
    fun review() { assertNull(controller.google.install(controller.google.pendingReview()!!,
        ReviewedGoogleUse("gemini-test", GoogleAccountTier.PAID, GoogleUseEligibility.REVIEWED_FOR_THIS_SESSION,
            GoogleTokenPrice("gemini-test", GoogleAccountTier.PAID, "USD", 1000, 1, 1, 10000, 0, 1000),
            GoogleTokenBounds(5000, 1000, 1000), GoogleMoney("USD", 1000), 0, 1000))) }
    suspend fun awaitResponse() { withTimeout(3000) { while (controller.state.value.generating) delay(5) } }
}
internal class MemoryInceptionStore : InceptionStore {
    val values = mutableMapOf<String, InceptionWorkspace>()
    var fail = false
    override suspend fun list() = values.values.toList()
    override suspend fun load(id: String) = values[id]
    override suspend fun save(document: InceptionWorkspace, expectedRevision: Long?) {
        if (fail) throw InceptionException(InceptionProblem.CAPACITY)
        if (values[document.id]?.revision != expectedRevision) throw InceptionException(InceptionProblem.STALE)
        values[document.id] = document
    }
    override suspend fun delete(id: String, expectedRevision: Long) {
        if (values[id]?.revision != expectedRevision) throw InceptionException(InceptionProblem.STALE)
        values.remove(id)
    }
}
