package com.choplab.ui.inception

import com.choplab.core.*
import com.choplab.core.ai.*
import com.choplab.core.edit.Intent
import com.choplab.core.inception.*
import com.choplab.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random

enum class InceptionDestination { LYRICS, PRODUCTION_TITLE }
data class InceptionPreview(val workspaceId: String, val workspaceRevision: Long, val concept: InceptionConcept,
    val materials: FrozenList<InceptionMaterial>, val destination: InceptionDestination, val before: DocumentState,
    val intent: Intent) {
    override fun toString() = "InceptionPreview(destination=$destination, revision=$workspaceRevision)"
}
data class InceptionState(val open: Boolean = false, val workspace: InceptionWorkspace? = null,
    val workspaces: List<Pair<String, String>> = emptyList(), val selected: Set<String> = emptySet(),
    val focusedMaterial: String? = null, val canUndo: Boolean = false, val busy: Boolean = false,
    val generating: Boolean = false, val listening: Boolean = false, val problem: InceptionProblem? = null,
    val preview: InceptionPreview? = null, val candidates: List<InceptionConcept>? = null,
    val suggestedRelations: List<InceptionRelation> = emptyList(), val questions: List<String> = emptyList(),
    val permitted: Set<String> = emptySet(), val hits: List<InceptionHit> = emptyList(),
    val consentVersion: Long? = null, val costUnknown: Boolean = false, val applied: Boolean = false,
    val mode: InceptionVoiceMode = InceptionVoiceMode.QUIET)

/** A serialized local workspace. Saved state is published only after a successful atomic store commit. */
class InceptionController(
    private val store: InceptionStore,
    private val scope: CoroutineScope,
    private val currentDocument: () -> DocumentState,
    private val applyIntent: suspend (Intent, Long) -> Boolean,
    val google: GoogleLyricSession,
    private val providerFactory: (() -> InceptionProvider)? = null,
    private val voice: InceptionVoice = UnavailableInceptionVoice,
    private val canApply: () -> Boolean = { true },
    private val canListen: () -> Boolean = { true },
) {
    constructor(store: InceptionStore, scope: CoroutineScope, studio: Studio, google: GoogleLyricSession,
        providerFactory: (() -> InceptionProvider)? = null, voice: InceptionVoice = UnavailableInceptionVoice,
        canApply: () -> Boolean = { true }, canListen: () -> Boolean = { true }) :
        this(store, scope, { studio.document.value }, { intent, revision -> studio.dispatch(Action.Edit(intent, revision)).accepted }, google, providerFactory, voice, canApply, canListen)
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(InceptionState())
    val state = mutable.asStateFlow()
    val admission get() = google.state
    val providerAvailable get() = providerFactory != null
    val voiceAvailable get() = voice.available
    private var previous: InceptionWorkspace? = null
    private var fence = 0L
    private var sequence = 0L
    private val sessionId = List(2) { Random.nextLong().toULong().toString(16).padStart(16, '0') }.joinToString("")
    private var job: Job? = null
    private var provider: InceptionProvider? = null
    private var dialog = google.openDialog()
    private var key: SessionApiKey? = null
    private var model = ""
    private var closed = false
    private fun nextId(prefix: String) = "$prefix-$sessionId-${(++sequence).toString(36)}"
    private fun update(block: (InceptionState) -> InceptionState) { mutable.value = block(mutable.value) }
    private fun invalidate() {
        val attempted = state.value.generating
        fence++; job?.cancel(); job = null; provider?.close(); provider = null; voice.stop()
        dialog.close(); dialog = google.openDialog(); dialog.bindInputs(model, key)
        update { it.copy(generating = false, listening = false, preview = null, candidates = null, suggestedRelations = emptyList(), questions = emptyList(), consentVersion = null, applied = false, costUnknown = it.costUnknown || attempted) }
    }
    private suspend fun guarded(block: suspend () -> Unit): Boolean = mutex.withLock {
        if (closed) return@withLock false
        try { block(); true }
        catch (cancel: CancellationException) { throw cancel }
        catch (failure: InceptionException) { update { it.copy(problem = failure.problem, busy = false) }; false }
        catch (_: IllegalArgumentException) { update { it.copy(problem = InceptionProblem.INVALID, busy = false) }; false }
        catch (_: Exception) { update { it.copy(problem = InceptionProblem.STORAGE, busy = false) }; false }
    }
    suspend fun open() = guarded {
        invalidate(); previous = null; update { it.copy(open = true, busy = true, problem = null, canUndo = false) }
        val docs = store.list()
        val active = docs.firstOrNull { it.id == state.value.workspace?.id } ?: docs.firstOrNull()
        update { it.copy(workspaces = docs.map { d -> d.id to d.title }, workspace = active, busy = false, selected = emptySet(), focusedMaterial = null) }
    }
    suspend fun create(title: String) = guarded {
        invalidate()
        if (store.list().size >= 32) throw InceptionException(InceptionProblem.CAPACITY)
        val id = nextId("workspace")
        val document = InceptionWorkspace(id, title)
        withContext(NonCancellable) { store.save(document, null); previous = null
            update { it.copy(open = true, workspace = document, workspaces = it.workspaces + (id to title), selected = emptySet(), canUndo = false, problem = null) } }
    }
    suspend fun switch(id: String) = guarded {
        invalidate(); val value = store.load(id) ?: throw InceptionException(InceptionProblem.STALE)
        previous = null; update { it.copy(workspace = value, selected = emptySet(), focusedMaterial = null, canUndo = false, problem = null, hits = emptyList()) }
    }
    private suspend fun change(purge: Boolean = false, transform: (InceptionWorkspace) -> InceptionWorkspace) {
        if (!state.value.open) throw InceptionException(InceptionProblem.CLOSED)
        val before = state.value.workspace ?: throw InceptionException(InceptionProblem.INVALID)
        val next = transform(before.copy(revision = before.revision + 1))
        require(next.id == before.id && next.revision == before.revision + 1)
        invalidate()
        withContext(NonCancellable) {
            store.save(next, before.revision)
            previous = if (purge) null else before
            update { it.copy(workspace = next, canUndo = !purge, problem = null, hits = emptyList(),
                selected = it.selected.intersect(next.materials.map { m -> m.id }.toSet()),
                focusedMaterial = it.focusedMaterial?.takeIf { id -> next.materials.any { m -> m.id == id } }) }
        }
    }
    suspend fun addMaterial(text: String) = guarded { change { it.copy(materials = (it.materials + InceptionMaterial(nextId("material"), text)).frozen()) } }
    suspend fun correctMaterial(id: String, text: String) = guarded { change { w ->
        require(w.materials.any { it.id == id }); w.copy(materials = w.materials.map { if (it.id == id) it.copy(correction = text,
            correctionHistory = (it.correctionHistory + listOfNotNull(it.correction)).frozen()) else it }.frozen())
    } }
    suspend fun classify(id: String, classification: MaterialClassification) = guarded { change { w ->
        require(w.materials.any { it.id == id }); w.copy(materials = w.materials.map { if (it.id == id) it.copy(classification = classification) else it }.frozen())
    } }
    suspend fun focusSummary(center: String, feeling: String, direction: String, unresolved: String) = guarded {
        change { it.copy(focus = InceptionFocus(center, feeling, direction, unresolved)) }
    }
    suspend fun deleteMaterial(id: String) = guarded {
        change(purge = true) { w ->
            require(w.materials.any { it.id == id })
            val removed = w.concepts.filter { id in it.evidence }.map { it.id }.toSet()
            w.copy(materials = w.materials.filterNot { it.id == id }.frozen(), concepts = w.concepts.filterNot { it.id in removed }.frozen(),
                decisions = w.decisions.filterNot { it.conceptId in removed || id in it.snapshot.evidence }.frozen(),
                relations = w.relations.filterNot { it.from == id || it.to == id }.frozen(), hypotheses = w.hypotheses.filterNot { id in it.evidence }.frozen())
        }
    }
    suspend fun select(id: String) = guarded {
        require(state.value.workspace?.materials?.any { it.id == id } == true); invalidate()
        update { it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id) }
    }
    suspend fun focus(id: String) = guarded { require(state.value.workspace?.materials?.any { it.id == id } == true); update { it.copy(focusedMaterial = id) } }
    suspend fun putConcept(id: String?, title: String, statement: String) = guarded { change { w ->
        val concept = InceptionConcept(id ?: nextId("concept"), title, statement, state.value.selected.toList().frozen(), ConceptOrigin.MANUAL)
        require(id == null || w.concepts.any { it.id == id })
        w.copy(concepts = (w.concepts.filterNot { it.id == id } + concept).frozen())
    } }
    suspend fun decide(id: String, kind: DecisionKind, reason: String = "") = guarded { change { w ->
        val concept = w.concepts.first { it.id == id }
        w.copy(decisions = (w.decisions + InceptionDecision(id, kind, w.revision, concept, reason)).frozen())
    } }
    suspend fun removeConcept(id: String) = guarded { change { w -> w.copy(concepts = w.concepts.filterNot { it.id == id }.frozen(), decisions = w.decisions.filterNot { it.conceptId == id }.frozen()) } }
    suspend fun undo() = guarded {
        val before = state.value.workspace ?: return@guarded
        val old = previous ?: return@guarded
        invalidate(); val next = old.copy(revision = before.revision + 1)
        withContext(NonCancellable) { store.save(next, before.revision); previous = null
            update { it.copy(workspace = next, selected = emptySet(), canUndo = false, problem = null, hits = emptyList()) } }
    }
    suspend fun deleteWorkspace() = guarded {
        val before = state.value.workspace ?: return@guarded
        invalidate()
        withContext(NonCancellable) { store.delete(before.id, before.revision); previous = null
            update { InceptionState(open = it.open, workspaces = it.workspaces.filterNot { p -> p.first == before.id }) } }
    }
    suspend fun relation(kind: RelationKind, explanation: String) = guarded { change { w ->
        val ids = state.value.selected.toList(); require(ids.size == 2)
        w.copy(relations = (w.relations + InceptionRelation(nextId("relation"), ids[0], ids[1], kind, explanation)).frozen())
    } }
    suspend fun removeRelation(id: String) = guarded { change { it.copy(relations = it.relations.filterNot { r -> r.id == id }.frozen()) } }
    suspend fun hypothesis(id: String? = null, statement: String, confirmed: Boolean = false) = guarded { change { w ->
        val old = w.hypotheses.firstOrNull { it.id == id }
        val h = InceptionHypothesis(id ?: nextId("hypothesis"), statement, old?.evidence ?: state.value.selected.toList().frozen(), confirmed)
        w.copy(hypotheses = (w.hypotheses.filterNot { it.id == id } + h).frozen())
    } }
    suspend fun removeHypothesis(id: String) = guarded { change { it.copy(hypotheses = it.hypotheses.filterNot { h -> h.id == id }.frozen()) } }
    suspend fun applyHypothesis(id: String) = guarded { change { w ->
        val h = w.hypotheses.first { it.id == id }; require(h.confirmed)
        val c = InceptionConcept(nextId("concept"), h.statement.lineSequence().first { it.isNotBlank() }.take(160), h.statement, h.evidence)
        w.copy(concepts = (w.concepts + c).frozen())
    } }
    suspend fun permitSearch(id: String) = guarded { update { it.copy(permitted = if (id in it.permitted) it.permitted - id else it.permitted + id, hits = emptyList()) } }
    suspend fun search(text: String, kind: RelationKind) = guarded {
        val results = InceptionSearch.find(store.list(), state.value.permitted, text, kind)
        update { it.copy(hits = results, problem = null) }
    }
    suspend fun openHit(hit: InceptionHit) = guarded {
        require(hit.workspaceId in state.value.permitted)
        val workspace = store.load(hit.workspaceId) ?: throw InceptionException(InceptionProblem.STALE)
        if (workspace.revision != hit.revision || workspace.materials.none { it.id == hit.materialId }) throw InceptionException(InceptionProblem.STALE)
        invalidate(); previous = null
        update { it.copy(workspace = workspace, focusedMaterial = hit.materialId, selected = emptySet(), canUndo = false, problem = null) }
    }
    suspend fun preview(id: String, destination: InceptionDestination) = guarded {
        invalidate()
        val w = requireNotNull(state.value.workspace); val c = w.concepts.first { it.id == id }; require(w.adopted(c))
        val before = currentDocument()
        val intent = when (destination) {
            InceptionDestination.PRODUCTION_TITLE -> Intent.Rename(c.title)
            InceptionDestination.LYRICS -> {
                val lines = c.statement.lines().filter { it.isNotBlank() }; require(lines.size in 1..64)
                val start = before.project.lyrics.maxOfOrNull { it.endTick } ?: 0L
                val prefix = nextId("inception")
                val added = lines.mapIndexed { index, value -> LyricLine("$prefix-$index", value, start + index * 3840L, start + (index + 1) * 3840L) }
                Intent.SetLyrics((before.project.lyrics + added).frozen())
            }
        }
        update { it.copy(preview = InceptionPreview(w.id, w.revision, c, w.materials.filter { m -> m.id in c.evidence }.frozen(), destination, before, intent), problem = null) }
    }
    suspend fun apply() = guarded {
        if (!canApply()) throw InceptionException(InceptionProblem.BUSY)
        val p = state.value.preview ?: throw InceptionException(InceptionProblem.INVALID)
        val w = state.value.workspace
        if (w?.id != p.workspaceId || w.revision != p.workspaceRevision || currentDocument() !== p.before) throw InceptionException(InceptionProblem.STALE)
        if (!applyIntent(p.intent, p.before.revision)) throw InceptionException(InceptionProblem.BUSY)
        update { it.copy(preview = null, applied = true, problem = null) }
    }
    suspend fun bindProvider(model: String, keyText: String) = guarded {
        invalidate(); key?.close(); key = if (keyText.isEmpty()) null else SessionApiKey(keyText)
        this.model = model; dialog.bindInputs(model, key); update { it.copy(problem = null) }
    }
    suspend fun consent(enabled: Boolean) = guarded {
        update { it.copy(consentVersion = if (enabled && admission.value.summary != null) admission.value.version else null) }
    }
    suspend fun generate(different: Boolean = false) = guarded {
        if (state.value.generating || state.value.listening) throw InceptionException(InceptionProblem.BUSY)
        val w = requireNotNull(state.value.workspace)
        val request = InceptionRequest(nextId("request"), w.revision, w.materials.filter { it.id in state.value.selected }.frozen(), different)
        val secret = key ?: throw InceptionException(InceptionProblem.UNVERIFIED)
        val consent = state.value.consentVersion ?: throw InceptionException(InceptionProblem.CONSENT)
        val factory = providerFactory ?: throw InceptionException(InceptionProblem.UNVERIFIED)
        val admissionRequest = LyricRequest(model, "Selected inception material", "", LyricLanguage.JAPANESE, LyricStyle.SONG, "", "", "")
        val permit = dialog.reserve(admissionRequest, secret, consent)
        val attempt = (permit as? GoogleAttemptDecision.Allowed)?.attempt ?: throw InceptionException(InceptionProblem.UNVERIFIED)
        val token = ++fence; val active = factory(); provider = active; val requestModel = model
        update { it.copy(generating = true, consentVersion = null, candidates = null, preview = null, problem = null, costUnknown = false) }
        job = scope.launch {
            val result = try { withTimeout(35_000) { active.propose(request, requestModel, secret, attempt) } }
                catch (_: TimeoutCancellationException) { InceptionProviderResult.Failure(InceptionProblem.TIMEOUT, true) }
                catch (_: CancellationException) { return@launch }
                catch (_: Exception) { InceptionProviderResult.Failure(InceptionProblem.PROVIDER, true) }
            finally { active.close() }
            mutex.withLock {
                if (closed || token != fence || state.value.workspace?.id != w.id || state.value.workspace?.revision != request.revision || !state.value.open) return@withLock
                provider = null; job = null
                when (result) {
                    is InceptionProviderResult.Success -> try { result.response.validate(request)
                        update { it.copy(generating = false, candidates = result.response.concepts, suggestedRelations = result.response.relations, questions = result.response.questions, problem = null) }
                    } catch (_: Exception) { update { it.copy(generating = false, problem = InceptionProblem.PROVIDER) } }
                    is InceptionProviderResult.Failure -> update { it.copy(generating = false, problem = result.problem, costUnknown = result.costUnknown) }
                }
            }
        }
    }
    suspend fun keepCandidate(id: String) = guarded {
        val candidate = state.value.candidates?.first { it.id == id } ?: throw InceptionException(InceptionProblem.STALE)
        change { w -> require(w.concepts.size < 3); w.copy(concepts = (w.concepts + candidate.copy(id = nextId("concept"))).frozen()) }
    }
    suspend fun keepRelation(id: String) = guarded {
        val relation = state.value.suggestedRelations.firstOrNull { it.id == id } ?: throw InceptionException(InceptionProblem.STALE)
        change { it.copy(relations = (it.relations + relation.copy(id = nextId("relation"))).frozen()) }
    }
    suspend fun mode(mode: InceptionVoiceMode) = guarded { invalidate(); update { it.copy(mode = mode) } }
    suspend fun listen() = guarded {
        if (!canListen()) throw InceptionException(InceptionProblem.BUSY)
        if (!voice.available) throw InceptionException(InceptionProblem.VOICE_UNAVAILABLE)
        require(state.value.workspace != null); invalidate(); val token = fence
        update { it.copy(listening = true, problem = null) }
        job = scope.launch {
            val material = try { withTimeout(60_000) { voice.utterance() } }
                catch (e: Exception) { mutex.withLock { if (token == fence) update { it.copy(listening = false, problem = (e as? InceptionException)?.problem ?: InceptionProblem.NO_SPEECH) } }; return@launch }
            guarded { if (token == fence && state.value.open) change { w ->
                w.copy(materials = (w.materials + material.copy(id = nextId("speech"))).frozen())
            } }
            // QUIET never answers; JAM also requires a separate explicit Generate press with reviewed consent.
        }
    }
    /** Typing is a context change even before the user saves the draft. */
    suspend fun draftChanged() = guarded { invalidate() }
    suspend fun stop() = guarded { val attempted = state.value.generating; invalidate(); update { it.copy(problem = InceptionProblem.CANCELLED, costUnknown = it.costUnknown || attempted) } }
    /** Host document/stage/recording changes invalidate a preview or asynchronous result. */
    suspend fun hostChanged() = guarded {
        if (state.value.preview != null || state.value.generating || state.value.listening) {
            val attempted = state.value.generating; invalidate(); update { it.copy(problem = InceptionProblem.STALE, costUnknown = it.costUnknown || attempted) }
        }
    }
    suspend fun dismiss() = guarded { invalidate(); key?.close(); key = null; dialog.bindInputs(model, null); update { it.copy(open = false) } }
    suspend fun close() = mutex.withLock { if (!closed) { invalidate(); key?.close(); key = null; google.close(); previous = null; closed = true; mutable.value = InceptionState() } }
}
