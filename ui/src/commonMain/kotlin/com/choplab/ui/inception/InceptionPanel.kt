@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.choplab.ui.inception

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.core.inception.*
import com.choplab.ui.resources.*
import com.choplab.ui.CEButton
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Supplemental workspace: the original four-stage instrument remains the host underneath. */
@Composable fun InceptionEntry(controller: InceptionController, enabled: Boolean = true) {
    val scope = rememberCoroutineScope()
    CEButton(stringResource(Res.string.inception_entry), { scope.launch { controller.open() } }, enabled = enabled, tag = "inception-open")
}
@Composable fun InceptionPanel(controller: InceptionController) {
    val state by controller.state.collectAsState()
    val admission by controller.admission.collectAsState()
    val scope = rememberCoroutineScope()
    if (!state.open) return
    val workspace = state.workspace
    var newTitle by remember { mutableStateOf("") }
    var materialText by remember(workspace?.id) { mutableStateOf("") }
    var conceptTitle by remember(workspace?.id) { mutableStateOf("") }
    var conceptText by remember(workspace?.id) { mutableStateOf("") }
    var conceptId by remember(workspace?.id) { mutableStateOf<String?>(null) }
    var correction by remember(workspace?.id, state.focusedMaterial) { mutableStateOf("") }
    var center by remember(workspace?.id, workspace?.focus) { mutableStateOf(workspace?.focus?.center ?: "") }
    var feeling by remember(workspace?.id, workspace?.focus) { mutableStateOf(workspace?.focus?.feeling ?: "") }
    var direction by remember(workspace?.id, workspace?.focus) { mutableStateOf(workspace?.focus?.direction ?: "") }
    var unresolved by remember(workspace?.id, workspace?.focus) { mutableStateOf(workspace?.focus?.unresolved ?: "") }
    var query by remember { mutableStateOf("") }
    var note by remember(workspace?.id) { mutableStateOf("") }
    var hypothesisId by remember(workspace?.id) { mutableStateOf<String?>(null) }
    var relation by remember { mutableStateOf(RelationKind.SIMILAR) }
    var model by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var closeConfirm by remember { mutableStateOf(false) }
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    var deleteMaterial by remember(workspace?.id, workspace?.revision) { mutableStateOf<String?>(null) }
    var deleteWorkspace by remember(workspace?.id, workspace?.revision) { mutableStateOf(false) }
    fun changed() { scope.launch { controller.draftChanged() } }
    fun leave(action: () -> Unit) {
        if (materialText.isNotBlank() || conceptText.isNotBlank() || conceptTitle.isNotBlank() || correction.isNotBlank() || note.isNotBlank() ||
            (workspace != null && listOf(center, feeling, direction, unresolved) != workspace.focus.let { listOf(it.center, it.feeling, it.direction, it.unresolved) })) {
            pendingLeave = action; closeConfirm = true
        } else action()
    }
    fun close() = leave { scope.launch { controller.dismiss() } }
    Dialog(onDismissRequest = ::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxWidth().widthIn(max = 1100.dp).fillMaxHeight(0.96f).testTag("inception-panel"), shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.inception_title), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(Res.string.inception_intro))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { scope.launch { controller.stop() } }, modifier = Modifier.testTag("inception-stop")) { Text(stringResource(Res.string.inception_stop)) }
                    OutlinedButton(onClick = { scope.launch { controller.undo() } }, enabled = state.canUndo, modifier = Modifier.testTag("inception-undo")) { Text(stringResource(Res.string.inception_undo)) }
                    OutlinedButton(onClick = ::close, modifier = Modifier.testTag("inception-close")) { Text(stringResource(Res.string.inception_close)) }
                }
                state.problem?.let { Text(stringResource(when (it) {
                    InceptionProblem.CONSENT, InceptionProblem.UNVERIFIED, InceptionProblem.COST, InceptionProblem.EXPIRED -> Res.string.inception_error_provider
                    InceptionProblem.STALE -> Res.string.inception_error_stale
                    InceptionProblem.CANCELLED -> Res.string.inception_cancelled
                    InceptionProblem.VOICE_UNAVAILABLE, InceptionProblem.NO_SPEECH -> Res.string.inception_voice_unavailable
                    InceptionProblem.TIMEOUT, InceptionProblem.RATE_LIMITED, InceptionProblem.PROVIDER -> Res.string.inception_error_request
                    InceptionProblem.CORRUPT, InceptionProblem.UNKNOWN_VERSION, InceptionProblem.CAPACITY, InceptionProblem.STORAGE -> Res.string.inception_error_storage
                    else -> Res.string.inception_error_input
                }), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("inception-problem")) }
                if (state.costUnknown) Text(stringResource(Res.string.inception_cost_unknown))
                if (state.applied) Text(stringResource(Res.string.inception_applied), modifier = Modifier.testTag("inception-applied"))
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).testTag("inception-scroll"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.workspaces.forEach { (id, title) -> OutlinedButton(onClick = { leave { scope.launch { controller.switch(id) } } }, enabled = id != workspace?.id) { Text(title) } }
                    }
                    OutlinedTextField(newTitle, { if (it.length <= 160) { newTitle = it; changed() } }, label = { Text(stringResource(Res.string.inception_workspace_name)) }, modifier = Modifier.fillMaxWidth().testTag("inception-workspace-name"))
                    Button(onClick = { leave { scope.launch { if (controller.create(newTitle)) newTitle = "" } } }, enabled = newTitle.isNotBlank(), modifier = Modifier.testTag("inception-create")) { Text(stringResource(Res.string.inception_create)) }
                    if (workspace != null) {
                        Text(workspace.title, style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(Res.string.inception_saved), modifier = Modifier.testTag("inception-saved"))
                        OutlinedTextField(center, { if (it.length <= 2048) { center = it; changed() } }, label = { Text(stringResource(Res.string.inception_center)) }, modifier = Modifier.fillMaxWidth().testTag("inception-center"))
                        OutlinedTextField(feeling, { if (it.length <= 2048) { feeling = it; changed() } }, label = { Text(stringResource(Res.string.inception_feeling)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(direction, { if (it.length <= 2048) { direction = it; changed() } }, label = { Text(stringResource(Res.string.inception_direction)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(unresolved, { if (it.length <= 2048) { unresolved = it; changed() } }, label = { Text(stringResource(Res.string.inception_unresolved)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { scope.launch { controller.focusSummary(center, feeling, direction, unresolved) } }, modifier = Modifier.testTag("inception-save-focus")) { Text(stringResource(Res.string.inception_save_focus)) }
                        OutlinedTextField(materialText, { if (it.length <= InceptionLimits.MAX_TEXT) { materialText = it; changed() } }, label = { Text(stringResource(Res.string.inception_material_input)) }, minLines = 3, maxLines = 8,
                            modifier = Modifier.fillMaxWidth().testTag("inception-material-input"))
                        Button(onClick = { scope.launch { if (controller.addMaterial(materialText)) materialText = "" } }, enabled = materialText.isNotBlank(), modifier = Modifier.testTag("inception-add-material")) { Text(stringResource(Res.string.inception_add_material)) }
                        Text(stringResource(Res.string.inception_select_hint))
                        workspace.materials.forEach { m ->
                            val evidenceView = remember(m.id) { BringIntoViewRequester() }
                            LaunchedEffect(state.focusedMaterial) {
                                if (state.focusedMaterial == m.id) { withFrameNanos { }; evidenceView.bringIntoView() }
                            }
                            OutlinedCard(Modifier.fillMaxWidth().bringIntoViewRequester(evidenceView).testTag("inception-material-${m.id}")) {
                                Column(Modifier.padding(12.dp)) {
                                    Row { Checkbox(checked = m.id in state.selected, onCheckedChange = { scope.launch { controller.select(m.id) } }, modifier = Modifier.testTag("inception-select-${m.id}")); Text(m.id) }
                                    Text(m.currentText)
                                    FlowRow { MaterialClassification.entries.forEach { value -> FilterChip(m.classification == value,
                                        { scope.launch { controller.classify(m.id, value) } }, label = { Text(stringResource(when (value) {
                                            MaterialClassification.NOTE -> Res.string.inception_note_class
                                            MaterialClassification.ASIDE -> Res.string.inception_aside_class
                                            MaterialClassification.UNKNOWN -> Res.string.inception_unknown_class
                                        })) }) } }
                                    FlowRow {
                                        TextButton(onClick = { leave { scope.launch { controller.focus(m.id) } } }, modifier = Modifier.testTag("inception-evidence-${m.id}")) { Text(stringResource(Res.string.inception_original)) }
                                        TextButton(onClick = { deleteMaterial = m.id }) { Text(stringResource(Res.string.inception_delete)) }
                                    }
                                    if (state.focusedMaterial == m.id) {
                                        Text(stringResource(Res.string.inception_original)); Text(m.original, modifier = Modifier.testTag("inception-original-text"))
                                        m.correctionHistory.forEach { Text(it) }
                                        OutlinedTextField(correction, { if (it.length <= InceptionLimits.MAX_TEXT) { correction = it; changed() } }, label = { Text(stringResource(Res.string.inception_correction)) }, modifier = Modifier.fillMaxWidth().testTag("inception-correction"))
                                        Button(onClick = { scope.launch { if (controller.correctMaterial(m.id, correction)) correction = "" } }, enabled = correction.isNotBlank(), modifier = Modifier.testTag("inception-correct")) { Text(stringResource(Res.string.inception_save_correction)) }
                                    }
                                }
                            }
                        }
                        HorizontalDivider()
                        Text(stringResource(Res.string.inception_concepts), style = MaterialTheme.typography.titleLarge)
                        if (workspace.concepts.isEmpty()) Text(stringResource(Res.string.inception_no_concepts))
                        OutlinedTextField(conceptTitle, { if (it.length <= 160) { conceptTitle = it; changed() } }, label = { Text(stringResource(Res.string.inception_concept_title)) }, modifier = Modifier.fillMaxWidth().testTag("inception-concept-title"))
                        OutlinedTextField(conceptText, { if (it.length <= 4096) { conceptText = it; changed() } }, label = { Text(stringResource(Res.string.inception_concept_text)) }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth().testTag("inception-concept-text"))
                        Button(onClick = { scope.launch { if (controller.putConcept(conceptId, conceptTitle, conceptText)) { conceptId = null; conceptTitle = ""; conceptText = "" } } },
                            enabled = state.selected.isNotEmpty() && conceptTitle.isNotBlank() && conceptText.isNotBlank() && (conceptId != null || workspace.concepts.size < 3), modifier = Modifier.testTag("inception-save-concept")) { Text(stringResource(Res.string.inception_save_concept)) }
                        workspace.concepts.forEach { c ->
                            var reason by remember(c.id) { mutableStateOf("") }
                            OutlinedCard(Modifier.fillMaxWidth().testTag("inception-concept-${c.id}")) { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(c.title, style = MaterialTheme.typography.titleMedium); Text(c.statement)
                                Text(stringResource(if (workspace.adopted(c)) Res.string.inception_adopted else if (c.origin == ConceptOrigin.PROVIDER) Res.string.inception_ai_hypothesis else Res.string.inception_provisional))
                                FlowRow { c.evidence.forEach { id -> TextButton(onClick = { leave { scope.launch { controller.focus(id) } } }) { Text(id) } } }
                                OutlinedTextField(reason, { if (it.length <= 1024) { reason = it; changed() } }, label = { Text(stringResource(Res.string.inception_reason)) }, modifier = Modifier.fillMaxWidth())
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { changed(); conceptId = c.id; conceptTitle = c.title; conceptText = c.statement; scope.launch { state.selected.filterNot { it in c.evidence }.forEach { controller.select(it) }; c.evidence.filterNot { it in state.selected }.forEach { controller.select(it) } } }) { Text(stringResource(Res.string.inception_edit)) }
                                    TextButton(onClick = { scope.launch { controller.decide(c.id, DecisionKind.ADOPT) } }, modifier = Modifier.testTag("inception-adopt-${c.id}")) { Text(stringResource(Res.string.inception_adopt)) }
                                    TextButton(onClick = { scope.launch { controller.decide(c.id, DecisionKind.HOLD, reason) } }) { Text(stringResource(Res.string.inception_hold)) }
                                    TextButton(onClick = { scope.launch { controller.decide(c.id, DecisionKind.REJECT, reason) } }, enabled = reason.isNotBlank()) { Text(stringResource(Res.string.inception_reject)) }
                                    TextButton(onClick = { scope.launch { controller.removeConcept(c.id) } }) { Text(stringResource(Res.string.inception_delete)) }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(onClick = { scope.launch { controller.preview(c.id, InceptionDestination.LYRICS) } }, enabled = workspace.adopted(c), modifier = Modifier.testTag("inception-preview-lyrics-${c.id}")) { Text(stringResource(Res.string.inception_preview_lyrics)) }
                                    OutlinedButton(onClick = { scope.launch { controller.preview(c.id, InceptionDestination.PRODUCTION_TITLE) } }, enabled = workspace.adopted(c), modifier = Modifier.testTag("inception-preview-title-${c.id}")) { Text(stringResource(Res.string.inception_preview_title)) }
                                }
                                workspace.decisions.filter { it.conceptId == c.id }.takeLast(5).forEach { d ->
                                    Text("${d.atRevision}: " + stringResource(when (d.kind) { DecisionKind.ADOPT -> Res.string.inception_adopt; DecisionKind.HOLD -> Res.string.inception_hold; DecisionKind.REJECT -> Res.string.inception_reject; else -> Res.string.inception_provisional }) + " — " + d.snapshot.statement + "\n" + d.reason)
                                }
                            } }
                        }
                        state.preview?.let { p ->
                            OutlinedCard(Modifier.fillMaxWidth().testTag("inception-preview")) { Column(Modifier.padding(12.dp)) {
                                Text(stringResource(Res.string.inception_preview_hint)); Text(p.concept.title); Text(p.concept.statement)
                                p.materials.forEach { Text("${it.id}: ${it.currentText}") }
                                Text(stringResource(if (p.destination == InceptionDestination.LYRICS) Res.string.inception_append_lyrics else Res.string.inception_replace_title))
                                Button(onClick = { scope.launch { controller.apply() } }, modifier = Modifier.testTag("inception-apply")) { Text(stringResource(Res.string.inception_apply)) }
                            } }
                        }
                        HorizontalDivider()
                        Text(stringResource(Res.string.inception_relationships), style = MaterialTheme.typography.titleLarge)
                        FlowRow { RelationKind.entries.forEach { kind -> FilterChip(selected = relation == kind, onClick = { relation = kind }, label = { Text(relationName(kind)) }) } }
                        OutlinedTextField(note, { if (it.length <= 1024) { note = it; changed() } }, label = { Text(stringResource(Res.string.inception_note)) }, modifier = Modifier.fillMaxWidth().testTag("inception-note"))
                        FlowRow {
                            OutlinedButton(onClick = { scope.launch { if (controller.relation(relation, note)) note = "" } }, enabled = state.selected.size == 2 && note.isNotBlank()) { Text(stringResource(Res.string.inception_link)) }
                            OutlinedButton(onClick = { scope.launch { if (controller.hypothesis(id = hypothesisId, statement = note)) { note = ""; hypothesisId = null } } }, enabled = (state.selected.isNotEmpty() || hypothesisId != null) && note.isNotBlank()) { Text(stringResource(Res.string.inception_add_hypothesis)) }
                        }
                        workspace.relations.forEach { r -> Text("${r.from} → ${r.to}: ${relationName(r.kind)}\n${r.explanation}"); TextButton(onClick = { scope.launch { controller.removeRelation(r.id) } }) { Text(stringResource(Res.string.inception_delete)) } }
                        workspace.hypotheses.forEach { h ->
                            Text(h.statement); Text(stringResource(if (h.confirmed) Res.string.inception_confirmed else Res.string.inception_hypothesis))
                            FlowRow {
                                TextButton(onClick = { scope.launch { controller.hypothesis(h.id, h.statement, !h.confirmed) } }) { Text(stringResource(if (h.confirmed) Res.string.inception_unconfirm else Res.string.inception_confirm)) }
                                TextButton(onClick = { changed(); hypothesisId = h.id; note = h.statement }) { Text(stringResource(Res.string.inception_edit)) }
                                TextButton(onClick = { scope.launch { controller.applyHypothesis(h.id) } }, enabled = h.confirmed && workspace.concepts.size < 3) { Text(stringResource(Res.string.inception_hypothesis_to_concept)) }
                                TextButton(onClick = { scope.launch { controller.removeHypothesis(h.id) } }) { Text(stringResource(Res.string.inception_delete)) }
                            }
                        }
                        Text(stringResource(Res.string.inception_search_hint))
                        state.workspaces.forEach { (id, title) -> Row { Checkbox(id in state.permitted, { scope.launch { controller.permitSearch(id) } }); Text(title) } }
                        OutlinedTextField(query, { if (it.length <= 128) query = it }, label = { Text(stringResource(Res.string.inception_search)) }, modifier = Modifier.fillMaxWidth().testTag("inception-query"))
                        Button(onClick = { scope.launch { controller.search(query, relation) } }, enabled = query.isNotBlank() && state.permitted.isNotEmpty(), modifier = Modifier.testTag("inception-search")) { Text(stringResource(Res.string.inception_search)) }
                        state.hits.forEach { hit -> OutlinedButton(onClick = { leave { scope.launch { controller.openHit(hit) } } }) { Text("${hit.workspaceId}/${hit.materialId}: ${hit.excerpt}") } }
                        HorizontalDivider()
                        Text(stringResource(Res.string.inception_provider), style = MaterialTheme.typography.titleLarge)
                        if (!controller.providerAvailable) {
                            Text(stringResource(Res.string.inception_provider_unavailable), modifier = Modifier.testTag("inception-provider-unavailable"))
                        } else {
                        Text(stringResource(Res.string.inception_provider_hint))
                        Text(state.selected.joinToString())
                        OutlinedTextField(model, { model = it; scope.launch { controller.bindProvider(model, key) } }, label = { Text(stringResource(Res.string.inception_model)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(key, { key = it; scope.launch { controller.bindProvider(model, key) } }, label = { Text(stringResource(Res.string.inception_key)) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                        admission.summary?.let { Text("${it.maximumCost.nanoUnits} / 1,000,000,000 ${it.maximumCost.currency}") } ?: Text(stringResource(Res.string.inception_unverified))
                        Row { Checkbox(state.consentVersion == admission.version, { scope.launch { controller.consent(it) } }, enabled = admission.summary != null); Text(stringResource(Res.string.inception_consent)) }
                        FlowRow {
                            Button(onClick = { scope.launch { controller.generate() } }, enabled = state.consentVersion != null && !state.generating, modifier = Modifier.testTag("inception-generate")) { Text(stringResource(Res.string.inception_generate)) }
                            OutlinedButton(onClick = { scope.launch { controller.generate(true) } }, enabled = state.consentVersion != null && !state.generating) { Text(stringResource(Res.string.inception_different)) }
                        }
                        if (state.generating) Text(stringResource(Res.string.inception_waiting))
                        state.candidates?.let { candidates ->
                            if (candidates.isEmpty()) Text(stringResource(Res.string.inception_no_concepts))
                            candidates.forEach { c -> Text(c.title); Text(c.statement); Text(c.evidence.joinToString())
                                OutlinedButton(onClick = { scope.launch { controller.keepCandidate(c.id) } }, enabled = workspace.concepts.size < 3) { Text(stringResource(Res.string.inception_keep_candidate)) } }
                        }
                        state.suggestedRelations.forEach { r -> Text("${r.from} → ${r.to}: ${r.explanation}")
                            OutlinedButton(onClick = { scope.launch { controller.keepRelation(r.id) } }) { Text(stringResource(Res.string.inception_keep_relation)) } }
                        state.questions.forEach { Text(it) }
                        }
                        Text(stringResource(Res.string.inception_voice_hint))
                        FlowRow { InceptionVoiceMode.entries.forEach { mode -> FilterChip(state.mode == mode, { scope.launch { controller.mode(mode) } }, label = { Text(mode.name) }) } }
                        Button(onClick = { scope.launch { controller.listen() } }, enabled = controller.voiceAvailable && !state.listening && !state.generating, modifier = Modifier.testTag("inception-listen")) { Text(stringResource(Res.string.inception_listen)) }
                        if (!controller.voiceAvailable) Text(stringResource(Res.string.inception_voice_unavailable))
                        if (state.listening) Text(stringResource(Res.string.inception_listening))
                        Text(stringResource(Res.string.inception_delete_hint))
                        OutlinedButton(onClick = { deleteWorkspace = true }, modifier = Modifier.testTag("inception-delete-workspace")) { Text(stringResource(Res.string.inception_delete_workspace)) }
                    }
                }
            }
        }
    }
    if (closeConfirm || deleteMaterial != null || deleteWorkspace) {
        AlertDialog(onDismissRequest = { closeConfirm = false; deleteMaterial = null; deleteWorkspace = false },
            title = { Text(stringResource(Res.string.inception_confirm_action)) }, text = { Text(stringResource(if (closeConfirm) Res.string.inception_discard_hint else Res.string.inception_delete_hint)) },
            confirmButton = { TextButton(onClick = { scope.launch {
                when { closeConfirm -> { closeConfirm = false; val action = pendingLeave; pendingLeave = null; materialText = ""; conceptText = ""; conceptTitle = ""; correction = ""; note = ""; conceptId = null; hypothesisId = null; center = workspace?.focus?.center ?: ""; feeling = workspace?.focus?.feeling ?: ""; direction = workspace?.focus?.direction ?: ""; unresolved = workspace?.focus?.unresolved ?: ""; action?.invoke() }; deleteWorkspace -> { deleteWorkspace = false; controller.deleteWorkspace() }; else -> { val id = deleteMaterial; deleteMaterial = null; if (id != null) controller.deleteMaterial(id) } }
            } }) { Text(stringResource(Res.string.inception_confirm)) } },
            dismissButton = { TextButton(onClick = { closeConfirm = false; deleteMaterial = null; deleteWorkspace = false }) { Text(stringResource(Res.string.inception_close_confirm)) } })
    }
}
@Composable private fun relationName(kind: RelationKind): String = stringResource(when (kind) {
    RelationKind.SIMILAR -> Res.string.inception_similar
    RelationKind.CONTRAST -> Res.string.inception_contrast
    RelationKind.CHANGE -> Res.string.inception_change
    RelationKind.COMPLEMENT -> Res.string.inception_complement
})
