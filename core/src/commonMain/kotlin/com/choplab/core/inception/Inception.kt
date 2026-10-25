package com.choplab.core.inception

import com.choplab.core.model.*

/** Independent of the music Project schema. Nothing here is implicitly sent to a provider. */
object InceptionLimits {
    const val VERSION = 1
    const val MAX_BYTES = 262_144
    const val MAX_MATERIALS = 128
    const val MAX_TEXT = 8_192
    const val MAX_CONCEPTS = 3
}
internal fun inceptionText(value: String, maximum: Int, blank: Boolean = false) {
    require(value.length <= maximum && (blank || value.isNotBlank()))
    require(value.none { it == '\u0000' || it == '\r' || (it.code < 32 && it != '\n' && it != '\t') })
}
enum class MaterialKind { TEXT, TRANSCRIPT }
enum class MaterialClassification { NOTE, ASIDE, UNKNOWN }
enum class ConceptOrigin { MANUAL, PROVIDER }
enum class DecisionKind { ADOPT, HOLD, REJECT, REOPEN }
enum class RelationKind { SIMILAR, CONTRAST, CHANGE, COMPLEMENT }
data class InceptionMaterial(val id: String, val original: String, val correction: String? = null,
    val kind: MaterialKind = MaterialKind.TEXT, val audioRef: String? = null,
    val classification: MaterialClassification = MaterialClassification.UNKNOWN,
    val correctionHistory: FrozenList<String> = frozenListOf()) {
    init { requireId(id); inceptionText(original, InceptionLimits.MAX_TEXT); correction?.let { inceptionText(it, InceptionLimits.MAX_TEXT) }
        audioRef?.let(::requireId); require(kind == MaterialKind.TRANSCRIPT || audioRef == null) }
    init { require(correctionHistory.size <= 32); correctionHistory.forEach { inceptionText(it, InceptionLimits.MAX_TEXT) } }
    val currentText get() = correction ?: original
    override fun toString() = "InceptionMaterial(id=$id, [private text])"
}
data class InceptionConcept(val id: String, val title: String, val statement: String,
    val evidence: FrozenList<String>, val origin: ConceptOrigin = ConceptOrigin.MANUAL) {
    init { requireId(id); inceptionText(title, 160); require(!title.contains('\n')); inceptionText(statement, 4_096)
        require(evidence.isNotEmpty() && evidence.size <= 32 && evidence.distinct().size == evidence.size); evidence.forEach(::requireId) }
    override fun toString() = "InceptionConcept(id=$id, origin=$origin, [private text])"
}
data class InceptionDecision(val conceptId: String, val kind: DecisionKind, val atRevision: Long, val snapshot: InceptionConcept, val reason: String = "") {
    init { require(conceptId == snapshot.id && atRevision >= 0); inceptionText(reason, 1024, blank = kind != DecisionKind.REJECT) }
    override fun toString() = "InceptionDecision(conceptId=$conceptId, kind=$kind, atRevision=$atRevision)"
}
data class InceptionFocus(val center: String = "", val feeling: String = "", val direction: String = "", val unresolved: String = "") {
    init { listOf(center, feeling, direction, unresolved).forEach { inceptionText(it, 2048, blank = true) } }
    override fun toString() = "InceptionFocus([private text])"
}
data class InceptionRelation(val id: String, val from: String, val to: String, val kind: RelationKind, val explanation: String) {
    init { requireId(id); requireId(from); requireId(to); require(from != to); inceptionText(explanation, 1_024) }
    override fun toString() = "InceptionRelation(id=$id, kind=$kind, [private text])"
}
data class InceptionHypothesis(val id: String, val statement: String, val evidence: FrozenList<String>, val confirmed: Boolean = false) {
    init { requireId(id); inceptionText(statement, 1_024); require(evidence.isNotEmpty() && evidence.size <= 32 && evidence.distinct().size == evidence.size) }
    override fun toString() = "InceptionHypothesis(id=$id, confirmed=$confirmed, [private text])"
}
data class InceptionWorkspace(val id: String, val title: String, val revision: Long = 0,
    val materials: FrozenList<InceptionMaterial> = frozenListOf(), val concepts: FrozenList<InceptionConcept> = frozenListOf(),
    val decisions: FrozenList<InceptionDecision> = frozenListOf(), val relations: FrozenList<InceptionRelation> = frozenListOf(),
    val hypotheses: FrozenList<InceptionHypothesis> = frozenListOf(), val version: Int = InceptionLimits.VERSION,
    val focus: InceptionFocus = InceptionFocus()) {
    init {
        require(version == InceptionLimits.VERSION); requireId(id); inceptionText(title, 160); require(revision in 0 until Long.MAX_VALUE)
        require(materials.size <= InceptionLimits.MAX_MATERIALS && concepts.size <= 3 && decisions.size <= 256 && relations.size <= 256 && hypotheses.size <= 64)
        val ids = materials.map { it.id }.toSet()
        require(ids.size == materials.size && concepts.map { it.id }.distinct().size == concepts.size)
        require(relations.map { it.id }.distinct().size == relations.size && hypotheses.map { it.id }.distinct().size == hypotheses.size)
        require(concepts.all { ids.containsAll(it.evidence) })
        require(decisions.all { d -> concepts.any { it.id == d.conceptId } && ids.containsAll(d.snapshot.evidence) && d.atRevision <= revision })
        require(relations.all { it.from in ids && it.to in ids })
        require(hypotheses.all { ids.containsAll(it.evidence) })
    }
    fun adopted(concept: InceptionConcept) = decisions.lastOrNull { it.conceptId == concept.id }?.let {
        it.kind == DecisionKind.ADOPT && it.snapshot == concept
    } == true
    override fun toString() = "InceptionWorkspace(id=$id, revision=$revision, materials=${materials.size}, concepts=${concepts.size})"
}

/** One-operation Undo; privacy deletion deliberately drops every old snapshot. */
class InceptionEdits(initial: InceptionWorkspace) {
    var document = initial; private set
    private var previous: InceptionWorkspace? = null
    val canUndo get() = previous != null
    fun change(transform: (InceptionWorkspace) -> InceptionWorkspace): InceptionWorkspace {
        val before = document
        val value = transform(before)
        require(value.id == before.id)
        if (value == before) return before
        val next = value.copy(revision = before.revision + 1)
        previous = before; document = next; return next
    }
    fun undo(): InceptionWorkspace {
        val old = previous ?: return document
        document = old.copy(revision = document.revision + 1); previous = null; return document
    }
    fun deleteMaterial(id: String): InceptionWorkspace {
        val affected = document.concepts.filter { id in it.evidence }.map { it.id }.toSet()
        change { it.copy(materials = it.materials.filterNot { m -> m.id == id }.frozen(),
            concepts = it.concepts.filterNot { c -> c.id in affected }.frozen(),
            decisions = it.decisions.filterNot { d -> d.conceptId in affected || id in d.snapshot.evidence }.frozen(),
            relations = it.relations.filterNot { r -> r.from == id || r.to == id }.frozen(),
            hypotheses = it.hypotheses.filterNot { h -> id in h.evidence }.frozen()) }
        previous = null
        return document
    }
    fun clearHistory() { previous = null }
}
enum class InceptionProblem { INVALID, STORAGE, CORRUPT, UNKNOWN_VERSION, CAPACITY, STALE, BUSY, CLOSED,
    CONSENT, UNVERIFIED, EXPIRED, COST, CANCELLED, TIMEOUT, RATE_LIMITED, PROVIDER, NO_SPEECH, VOICE_UNAVAILABLE }
class InceptionException(val problem: InceptionProblem) : Exception(problem.name)
interface InceptionStore {
    suspend fun list(): List<InceptionWorkspace>
    suspend fun load(id: String): InceptionWorkspace?
    /** CAS prevents stale windows/autosaves from resurrecting deleted material. null means create only. */
    suspend fun save(document: InceptionWorkspace, expectedRevision: Long?)
    suspend fun delete(id: String, expectedRevision: Long)
}
data class InceptionHit(val workspaceId: String, val materialId: String, val revision: Long, val excerpt: String,
    val relation: RelationKind, val matchedTerm: String) {
    override fun toString() = "InceptionHit(workspaceId=$workspaceId, materialId=$materialId, revision=$revision)"
}
/** Literal retrieval. Relation is a user-selected lens, never an inferred personality trait. */
object InceptionSearch {
    fun find(workspaces: List<InceptionWorkspace>, permittedIds: Set<String>, query: String, relation: RelationKind): List<InceptionHit> {
        inceptionText(query, 128)
        val term = query.trim()
        return workspaces.filter { it.id in permittedIds }.flatMap { workspace ->
            workspace.materials.filter { it.currentText.contains(term, ignoreCase = true) }.map { material ->
                val at = material.currentText.indexOf(term, ignoreCase = true)
                InceptionHit(workspace.id, material.id, workspace.revision,
                    material.currentText.substring((at - 40).coerceAtLeast(0), (at + term.length + 80).coerceAtMost(material.currentText.length)), relation, term)
            }
        }.take(32)
    }
}
