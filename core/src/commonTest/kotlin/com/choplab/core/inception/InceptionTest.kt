package com.choplab.core.inception

import com.choplab.core.model.*
import kotlin.test.*

class InceptionTest {
    private val material = InceptionMaterial("m1", "川の音を聴いた", "川の音に立ち止まった")
    private val concept = InceptionConcept("c1", "立ち止まる", "急ぐ日々のなかに静かな場所を残したい", frozenListOf("m1"))
    private fun workspace() = InceptionWorkspace("w1", "未定", materials = frozenListOf(material), concepts = frozenListOf(concept))

    @Test fun emptyWorkspaceAndMaximumThreeConceptsAreSupported() {
        assertEquals(0, InceptionJson.decode(InceptionJson.encode(InceptionWorkspace("empty", "未定"))).concepts.size)
        assertEquals(3, workspace().copy(concepts = (1..3).map { concept.copy(id = "c$it") }.frozen()).concepts.size)
        assertFailsWith<IllegalArgumentException> { workspace().copy(concepts = (1..4).map { concept.copy(id = "c$it") }.frozen()) }
    }
    @Test fun originalCorrectionDecisionAndRelationshipRoundTripIndependently() {
        val w = workspace().copy(revision = 2, materials = frozenListOf(material, InceptionMaterial("m2", "駅の音")),
            decisions = frozenListOf(InceptionDecision("c1", DecisionKind.ADOPT, 2, concept)),
            relations = frozenListOf(InceptionRelation("r", "m1", "m2", RelationKind.CONTRAST, "静けさと人の流れ")),
            hypotheses = frozenListOf(InceptionHypothesis("h", "今回は静けさが大事かもしれない", frozenListOf("m1"))))
        assertEquals(w, InceptionJson.decode(InceptionJson.encode(w)))
        assertTrue(w.adopted(concept)); assertFalse(w.adopted(concept.copy(statement = "変更")))
        assertFalse(w.hypotheses.single().confirmed)
    }
    @Test fun unknownDuplicateEscapedDuplicateAndWrongTypesAreRejected() {
        val json = InceptionJson.encode(workspace())
        listOf(json.replace("\"version\":1", "\"version\":1,\"extra\":0"),
            json.replace("\"version\":1", "\"version\":1,\"version\":1"),
            json.replace("\"version\":1", "\"version\":1,\"vers\\u0069on\":1"),
            json.replace("\"revision\":0", "\"revision\":\"0\""),
            json.replace("\"original\":", "\"bad\":"), json + "junk").forEach { value -> assertFails { InceptionJson.decode(value) } }
    }
    @Test fun unknownVersionHasItsOwnFailureAndDepthAndSizeAreBounded() {
        val failure = assertFailsWith<InceptionException> { InceptionJson.decode(InceptionJson.encode(workspace()).replace("\"version\":1", "\"version\":2")) }
        assertEquals(InceptionProblem.UNKNOWN_VERSION, failure.problem)
        assertFails { InceptionJson.parse("[".repeat(50) + "0" + "]".repeat(50)) }
        assertFails { InceptionJson.parse(" ".repeat(InceptionLimits.MAX_BYTES + 1)) }
        assertFails { InceptionMaterial("m", "a".repeat(InceptionLimits.MAX_TEXT + 1)) }
    }
    @Test fun invalidReferencesAndDuplicateIdsNeverEnterDocument() {
        assertFails { workspace().copy(materials = frozenListOf(material, material)) }
        assertFails { workspace().copy(concepts = frozenListOf(concept.copy(evidence = frozenListOf("absent")))) }
        assertFails { workspace().copy(hypotheses = frozenListOf(InceptionHypothesis("h", "h", frozenListOf("absent")))) }
    }
    @Test fun oneEditOneUndoAndDeletionPurgesEveryDependentSnapshot() {
        val edits = InceptionEdits(workspace().copy(revision = 1, decisions = frozenListOf(InceptionDecision("c1", DecisionKind.ADOPT, 1, concept)),
            hypotheses = frozenListOf(InceptionHypothesis("h", "h", frozenListOf("m1")))))
        val before = edits.document
        edits.change { it.copy(title = "変更") }; assertTrue(edits.canUndo)
        assertEquals(before.copy(revision = 3), edits.undo()); assertFalse(edits.canUndo)
        edits.deleteMaterial("m1")
        assertTrue(edits.document.materials.isEmpty() && edits.document.concepts.isEmpty() && edits.document.decisions.isEmpty() && edits.document.hypotheses.isEmpty())
        assertFalse(edits.canUndo)
    }
    @Test fun crossNotebookSearchRequiresPermissionAndReturnsExactEvidence() {
        val other = workspace().copy(id = "w2")
        assertTrue(InceptionSearch.find(listOf(workspace(), other), emptySet(), "川", RelationKind.SIMILAR).isEmpty())
        val hit = InceptionSearch.find(listOf(workspace(), other), setOf("w2"), "川", RelationKind.CONTRAST).single()
        assertEquals("w2", hit.workspaceId); assertEquals("m1", hit.materialId); assertEquals(material.currentText, hit.excerpt)
        assertEquals(RelationKind.CONTRAST, hit.relation)
    }
    @Test fun responseAllowsZeroButRejectsUnknownStaleDuplicateAndUnselectedEvidence() {
        val request = InceptionRequest("req", 4, frozenListOf(material))
        assertTrue(InceptionWire.decode("""{"requestId":"req","revision":4,"concepts":[],"relations":[],"questions":[]}""", request).concepts.isEmpty())
        val valid = """{"requestId":"req","revision":4,"concepts":[{"id":"c","title":"案","statement":"意味","evidence":["m1"]}],"relations":[],"questions":[]}"""
        assertEquals(ConceptOrigin.PROVIDER, InceptionWire.decode(valid, request).concepts.single().origin)
        listOf(valid.replace("\"revision\":4", "\"revision\":3"), valid.replace("\"m1\"", "\"absent\""),
            valid.replace("\"title\":", "\"adopted\":true,\"title\":"), valid.replace("\"req\"", "\"old\""),
            valid.replace("\"revision\":4", "\"revision\":4,\"revision\":4")).forEach { assertFails { InceptionWire.decode(it, request) } }
    }
    @Test fun requestContainsOnlySelectedCurrentTextAndNoAudioOrOriginal() {
        val request = InceptionRequest("req", 0, frozenListOf(material))
        val json = InceptionWire.input(request).toString()
        assertTrue(json.contains(material.currentText)); assertFalse(json.contains(material.original)); assertFalse(json.contains("audioRef"))
        assertFails { InceptionRequest("req", 0, frozenListOf()) }
    }
    @Test fun focusClassificationCorrectionHistoryAndRejectionReasonAreDurable() {
        val w = workspace().copy(revision = 1, focus = InceptionFocus("中心", "感覚", "方向", "未決"),
            materials = frozenListOf(material.copy(classification = MaterialClassification.ASIDE, correctionHistory = frozenListOf("以前の訂正"))),
            decisions = frozenListOf(InceptionDecision("c1", DecisionKind.REJECT, 1, concept, "今回は違う")))
        assertEquals(w, InceptionJson.decode(InceptionJson.encode(w)))
        assertFalse(w.adopted(concept))
        assertFails { InceptionDecision("c1", DecisionKind.REJECT, 1, concept) }
    }
    @Test fun responseRelationsNeedSelectedEvidenceAndOnlyOneQuestionIsAllowed() {
        val request = InceptionRequest("r", 0, frozenListOf(material, InceptionMaterial("m2", "駅")))
        val r = InceptionRelation("r1", "m1", "m2", RelationKind.CONTRAST, "静けさと人の流れ")
        InceptionResponse("r", 0, frozenListOf(), frozenListOf(r), frozenListOf("どちらを中心にしますか？")).validate(request)
        assertFails { InceptionResponse("r", 0, frozenListOf(), frozenListOf(r.copy(to = "unselected"))).validate(request) }
        assertFails { InceptionResponse("r", 0, frozenListOf(), questions = frozenListOf("一つ目？", "二つ目？")).validate(request) }
    }
}
