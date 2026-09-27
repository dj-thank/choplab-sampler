package com.choplab.ui

import com.choplab.core.DocumentState
import com.choplab.core.Studio
import com.choplab.core.edit.Intent
import com.choplab.core.model.Bank
import com.choplab.core.model.Pad
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

enum class BankPadEditField { NAME, COLOR, ROLE, PAN, ATTACK, DECAY, SUSTAIN, RELEASE }
enum class BankPadEditProblem { BUSY, RECORDING, EMPTY_PAD, STALE, INVALID, APPLY_FAILED }

/** Text is a draft, never a partially valid document value. Times are milliseconds at the engine's 48 kHz. */
sealed interface BankPadDraft {
    val id: Int
    data class BankMetadata(override val id: Int, val name: String, val color: String, val role: String) : BankPadDraft
    data class PadSound(override val id: Int, val name: String, val pan: String, val attack: String,
                        val decay: String, val sustain: String, val release: String) : BankPadDraft
}

data class BankPadEditorState(
    val draft: BankPadDraft? = null,
    val invalidFields: Set<BankPadEditField> = emptySet(),
    val problem: BankPadEditProblem? = null,
    val canApply: Boolean = false,
    val applying: Boolean = false,
)

sealed interface BankPadEditAction {
    data object OpenBank : BankPadEditAction
    data object OpenPad : BankPadEditAction
    data class Change(val field: BankPadEditField, val text: String) : BankPadEditAction
    data object Apply : BankPadEditAction
    data object Cancel : BankPadEditAction
}

/**
 * Serialized by the editor alongside selection and recording. [blocked] reads its current recording/work state;
 * [apply] rechecks the revision and uses the existing Studio edit path. Opening and typing send no audio commands.
 * A confirmation belongs to the exact project revision and selected PAD that opened it, including BANK edits.
 */
internal class BankPadEditController(
    private val studio: Studio,
    private val blocked: () -> BankPadEditProblem? = { null },
    private val apply: suspend (Intent, Long) -> Boolean,
) {
    private data class Pending(val document: DocumentState, val selectedPad: Int, val initial: BankPadDraft)
    private var pending: Pending? = null
    private val mutable = MutableStateFlow(BankPadEditorState())
    val view = mutable.asStateFlow()

    suspend fun dispatch(action: BankPadEditAction): Boolean = when (action) {
        BankPadEditAction.OpenBank -> open(bank = true)
        BankPadEditAction.OpenPad -> open(bank = false)
        BankPadEditAction.Cancel -> if (view.value.applying) false else {
            pending = null; mutable.value = BankPadEditorState(); true
        }
        is BankPadEditAction.Change -> change(action)
        BankPadEditAction.Apply -> confirm()
    }

    private fun unavailable(): BankPadEditProblem? = blocked()
        ?: BankPadEditProblem.BUSY.takeIf { studio.work.value.jobId != null || studio.work.value.preparationId != null }

    private fun open(bank: Boolean): Boolean {
        unavailable()?.let { return refuse(it) }
        val document = studio.document.value
        val selected = studio.selection.value.padId
        val draft = if (bank) document.project.banks[selected / 16].let {
            BankPadDraft.BankMetadata(it.id, it.name, "#" + it.color.toString(16).padStart(6, '0').uppercase(), it.role)
        } else document.project.pads[selected].let {
            if (it.assetHash == null) return refuse(BankPadEditProblem.EMPTY_PAD)
            BankPadDraft.PadSound(it.id, it.name, percent(it.pan), milliseconds(it.attackFrames),
                milliseconds(it.decayFrames), percent(it.sustainLevel), milliseconds(it.releaseFrames))
        }
        pending = Pending(document, selected, draft)
        mutable.value = BankPadEditorState(draft)
        return true
    }

    private fun change(action: BankPadEditAction.Change): Boolean {
        val captured = pending ?: return false
        if (view.value.applying || action.text.length > 256) return false
        val draft = when (val old = view.value.draft ?: return false) {
            is BankPadDraft.BankMetadata -> when (action.field) {
                BankPadEditField.NAME -> old.copy(name = action.text)
                BankPadEditField.COLOR -> old.copy(color = action.text)
                BankPadEditField.ROLE -> old.copy(role = action.text)
                else -> return false
            }
            is BankPadDraft.PadSound -> when (action.field) {
                BankPadEditField.PAN -> old.copy(pan = action.text)
                BankPadEditField.ATTACK -> old.copy(attack = action.text)
                BankPadEditField.DECAY -> old.copy(decay = action.text)
                BankPadEditField.SUSTAIN -> old.copy(sustain = action.text)
                BankPadEditField.RELEASE -> old.copy(release = action.text)
                else -> return false
            }
        }
        val candidate = candidate(captured, draft)
        mutable.value = BankPadEditorState(draft, candidate.invalid, canApply = candidate.changed)
        return true
    }

    private suspend fun confirm(): Boolean {
        val captured = pending ?: return false
        if (view.value.applying) return false
        unavailable()?.let { return refuse(it) }
        val now = studio.document.value
        if (now.revision != captured.document.revision || now.project != captured.document.project ||
            studio.selection.value.padId != captured.selectedPad) {
            pending = null
            mutable.value = view.value.copy(problem = BankPadEditProblem.STALE, canApply = false)
            return false
        }
        val candidate = candidate(captured, requireNotNull(view.value.draft))
        if (candidate.invalid.isNotEmpty()) return refuse(BankPadEditProblem.INVALID)
        if (!candidate.changed) { pending = null; mutable.value = BankPadEditorState(); return true }
        mutable.value = view.value.copy(applying = true, problem = null)
        val accepted = try { apply(requireNotNull(candidate.intent), captured.document.revision) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { false }
        finally { mutable.value = view.value.copy(applying = false) }
        return if (accepted) {
            pending = null; mutable.value = BankPadEditorState(); true
        } else if (studio.document.value.let { it.revision != captured.document.revision || it.project != captured.document.project }) {
            pending = null; mutable.value = view.value.copy(problem = BankPadEditProblem.STALE, canApply = false); false
        } else refuse(BankPadEditProblem.APPLY_FAILED)
    }

    private fun refuse(problem: BankPadEditProblem): Boolean {
        mutable.value = view.value.copy(problem = problem)
        return false
    }

    private data class Candidate(val intent: Intent?, val invalid: Set<BankPadEditField>, val changed: Boolean = false)
    private fun candidate(captured: Pending, draft: BankPadDraft): Candidate {
        val invalid = mutableSetOf<BankPadEditField>()
        fun label(value: String, field: BankPadEditField) {
            if (value.isBlank() || value.length > 48 || value.any { it < ' ' || it == '\u007f' }) invalid += field
        }
        fun number(value: String, field: BankPadEditField, range: ClosedFloatingPointRange<Double>): Double? =
            value.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in range }.also { if (it == null) invalid += field }
        return when (draft) {
            is BankPadDraft.BankMetadata -> {
                label(draft.name, BankPadEditField.NAME); label(draft.role, BankPadEditField.ROLE)
                val hex = draft.color.trim().removePrefix("#")
                val color = hex.takeIf { it.length == 6 && it.all { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } }?.toIntOrNull(16)
                if (color == null) invalid += BankPadEditField.COLOR
                if (invalid.isNotEmpty()) Candidate(null, invalid.toSet())
                else {
                    val bank = Bank(draft.id, draft.name, requireNotNull(color), draft.role)
                    Candidate(Intent.SetBank(bank), emptySet(), bank != captured.document.project.banks[draft.id])
                }
            }
            is BankPadDraft.PadSound -> {
                val pan = number(draft.pan, BankPadEditField.PAN, -100.0..100.0)
                val attack = number(draft.attack, BankPadEditField.ATTACK, 0.0..1000.0)
                val decay = number(draft.decay, BankPadEditField.DECAY, 0.0..1000.0)
                val sustain = number(draft.sustain, BankPadEditField.SUSTAIN, 0.0..100.0)
                val release = number(draft.release, BankPadEditField.RELEASE, 0.0..1000.0)
                if (release != null && (release * 48).roundToInt() < 1) invalid += BankPadEditField.RELEASE
                if (invalid.isNotEmpty()) Candidate(null, invalid.toSet())
                else {
                    val initial = captured.initial as BankPadDraft.PadSound
                    val old = captured.document.project.pads[draft.id]
                    // Unedited float fields keep their exact stored bits, including older precise values.
                    val pad: Pad = old.copy(
                        pan = if (draft.pan == initial.pan) old.pan else (requireNotNull(pan) / 100).toFloat(),
                        attackFrames = (requireNotNull(attack) * 48).roundToInt(),
                        decayFrames = (requireNotNull(decay) * 48).roundToInt(),
                        sustainLevel = if (draft.sustain == initial.sustain) old.sustainLevel else (requireNotNull(sustain) / 100).toFloat(),
                        releaseFrames = (requireNotNull(release) * 48).roundToInt(),
                    )
                    Candidate(Intent.SetPad(pad), emptySet(), pad != old)
                }
            }
        }
    }

    companion object {
        private fun percent(value: Float): String = (value * 100f).toString().removeSuffix(".0")
        /** Three decimals retain every allowed 48 kHz frame on a round trip, including the one-frame release. */
        internal fun milliseconds(frames: Int): String {
            val thousandths = (frames.toLong() * 1000 + 24) / 48
            val decimal = (thousandths % 1000).toString().padStart(3, '0').trimEnd('0')
            return (thousandths / 1000).toString() + if (decimal.isEmpty()) "" else ".$decimal"
        }
    }
}
