package com.choplab.ui.mixer

import com.choplab.core.DocumentState
import com.choplab.core.Studio
import com.choplab.core.edit.Intent
import com.choplab.core.model.Track
import com.choplab.core.model.TrackKind
import com.choplab.engine.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

sealed interface MixerTarget {
    data class Bank(val id: Int) : MixerTarget
    data class Track(val id: String) : MixerTarget
    data object Master : MixerTarget
}

data class MixerChannel(val target: MixerTarget, val name: String, val busId: String?)
enum class MixerProblem { BUSY, RECORDING, STALE, INVALID, APPLY_FAILED, UNAPPLIED, MISSING }
enum class MixerSwitch { MUTE, SOLO, COMPRESSOR, DELAY, REVERB }
enum class MixerField(val minimum: Double, val maximum: Double, val scale: Float = 1f) {
    GAIN(0.0, 800.0, 100f), PAN(-100.0, 100.0, 100f),
    LOW_DB(-18.0, 18.0), MID_DB(-18.0, 18.0), HIGH_DB(-18.0, 18.0), CUTOFF(20.0, 20_000.0),
    THRESHOLD(-60.0, 0.0), RATIO(1.0, 20.0), ATTACK(.1, 200.0), RELEASE(5.0, 2000.0), MAKEUP(0.0, 18.0),
    DELAY_SEND(0.0, 100.0, 100f), REVERB_SEND(0.0, 100.0, 100f),
    DELAY_TIME(0.0, 2000.0), FEEDBACK(0.0, 60.0, 100f), DELAY_RETURN(0.0, 200.0, 100f),
    REVERB_DECAY(.1, 3.0), DAMPING(0.0, 95.0, 100f), REVERB_RETURN(0.0, 200.0, 100f),
}

data class MixerDraft(
    val channel: MixerChannel,
    val fields: Map<MixerField, String>,
    val switches: Set<MixerSwitch>,
    val filter: MixFilterMode,
)
data class MixerEditorState(
    val draft: MixerDraft? = null,
    val channels: List<MixerChannel> = emptyList(),
    val invalidFields: Set<MixerField> = emptySet(),
    val problem: MixerProblem? = null,
    val canApply: Boolean = false,
    val applying: Boolean = false,
)
sealed interface MixerAction {
    data class Open(val target: MixerTarget? = null) : MixerAction
    data class Select(val target: MixerTarget) : MixerAction
    data class Change(val field: MixerField, val text: String) : MixerAction
    data class Switch(val control: MixerSwitch, val enabled: Boolean) : MixerAction
    data class Filter(val mode: MixFilterMode) : MixerAction
    data object Apply : MixerAction
    data object Cancel : MixerAction
}

/** Drafts have no audio side effects. The editor serializes this controller with recording and document edits. */
internal class MixerEditorController(
    private val studio: Studio,
    private val blocked: () -> MixerProblem? = { null },
    private val apply: suspend (Intent, Long) -> Boolean,
) {
    private data class Pending(val document: DocumentState, val track: Track?, val initial: MixerDraft)
    private var pending: Pending? = null
    private val mutable = MutableStateFlow(MixerEditorState())
    val view = mutable.asStateFlow()

    suspend fun dispatch(action: MixerAction): Boolean = when (action) {
        is MixerAction.Open -> if (view.value.applying || view.value.draft != null) false
            else open(action.target ?: MixerTarget.Bank(studio.selection.value.padId / 16))
        is MixerAction.Select -> if (view.value.applying || pending == null) false else if (
            view.value.draft != pending?.initial) refuse(MixerProblem.UNAPPLIED) else open(action.target)
        is MixerAction.Change -> change { draft ->
            if (action.text.length > 64 || action.field !in draft.fields) null
            else draft.copy(fields = draft.fields + (action.field to action.text))
        }
        is MixerAction.Switch -> change { draft ->
            val allowed = if (draft.channel.target == MixerTarget.Master)
                setOf(MixerSwitch.COMPRESSOR, MixerSwitch.DELAY, MixerSwitch.REVERB)
            else setOf(MixerSwitch.MUTE, MixerSwitch.SOLO, MixerSwitch.COMPRESSOR)
            if (action.control !in allowed) null else draft.copy(switches = if (action.enabled)
                draft.switches + action.control else draft.switches - action.control)
        }
        is MixerAction.Filter -> change { it.copy(filter = action.mode) }
        MixerAction.Apply -> confirm()
        MixerAction.Cancel -> if (view.value.applying) false else { pending = null; mutable.value = MixerEditorState(); true }
    }

    private fun unavailable(): MixerProblem? = blocked() ?: MixerProblem.BUSY.takeIf {
        studio.work.value.jobId != null || studio.work.value.preparationId != null
    }

    private fun open(target: MixerTarget): Boolean {
        unavailable()?.let { return refuse(it) }
        val document = studio.document.value
        val project = document.project
        val channels = project.banks.map { MixerChannel(MixerTarget.Bank(it.id), it.name, it.trackId) } +
            project.tracks.filter { track -> project.banks.none { it.trackId == track.id } }
                .map { MixerChannel(MixerTarget.Track(it.id), it.name, it.id) } +
            MixerChannel(MixerTarget.Master, "", null)
        val channel = channels.firstOrNull { it.target == target } ?: return refuse(MixerProblem.MISSING)
        val track = when (target) {
            is MixerTarget.Bank -> channel.busId?.let { id -> project.tracks.first { it.id == id } } ?: run {
                val prefix = "mix-bank-${target.id}"
                val id = (0..64).map { if (it == 0) prefix else "$prefix-$it" }.first { id -> project.tracks.none { it.id == id } }
                Track(id, project.banks[target.id].name, TrackKind.BANK)
            }
            is MixerTarget.Track -> project.tracks.first { it.id == target.id }
            MixerTarget.Master -> null
        }
        val insert = track?.fx?.insert ?: project.mix.master
        val values = linkedMapOf<MixerField, String>()
        fun value(field: MixerField, number: Float) { values[field] = (number * field.scale).toString().removeSuffix(".0") }
        value(MixerField.GAIN, track?.gain ?: project.mix.masterGain)
        if (track != null) value(MixerField.PAN, track.pan)
        value(MixerField.LOW_DB, insert.eq.lowDb); value(MixerField.MID_DB, insert.eq.midDb); value(MixerField.HIGH_DB, insert.eq.highDb)
        value(MixerField.CUTOFF, insert.filter.cutoffHz)
        value(MixerField.THRESHOLD, insert.compressor.thresholdDb); value(MixerField.RATIO, insert.compressor.ratio)
        value(MixerField.ATTACK, insert.compressor.attackMs); value(MixerField.RELEASE, insert.compressor.releaseMs)
        value(MixerField.MAKEUP, insert.compressor.makeupDb)
        if (track != null) {
            value(MixerField.DELAY_SEND, track.fx.delaySend); value(MixerField.REVERB_SEND, track.fx.reverbSend)
        } else {
            values[MixerField.DELAY_TIME] = (project.mix.delay.frames / 48.0).toString()
            value(MixerField.FEEDBACK, project.mix.delay.feedback); value(MixerField.DELAY_RETURN, project.mix.delay.returnGain)
            value(MixerField.REVERB_DECAY, project.mix.reverb.decaySeconds); value(MixerField.DAMPING, project.mix.reverb.damping)
            value(MixerField.REVERB_RETURN, project.mix.reverb.returnGain)
        }
        val switches = buildSet {
            if (track?.mute == true) add(MixerSwitch.MUTE)
            if (track?.solo == true) add(MixerSwitch.SOLO)
            if (insert.compressor.enabled) add(MixerSwitch.COMPRESSOR)
            if (track == null && project.mix.delay.enabled) add(MixerSwitch.DELAY)
            if (track == null && project.mix.reverb.enabled) add(MixerSwitch.REVERB)
        }
        val draft = MixerDraft(channel, values, switches, insert.filter.mode)
        pending = Pending(document, track, draft)
        mutable.value = MixerEditorState(draft, channels)
        return true
    }

    /** Call from the serialized document observer, or rely on the identical check before Apply. */
    fun documentChanged() {
        val captured = pending ?: return
        if (!view.value.applying && studio.document.value.let { it.revision != captured.document.revision || it.project != captured.document.project }) {
            mutable.value = view.value.copy(problem = MixerProblem.STALE, canApply = false)
        }
    }

    private fun change(transform: (MixerDraft) -> MixerDraft?): Boolean {
        val captured = pending ?: return false
        if (view.value.applying || view.value.problem == MixerProblem.STALE) return false
        val draft = transform(view.value.draft ?: return false) ?: return false
        val candidate = candidate(captured, draft)
        mutable.value = view.value.copy(draft = draft, invalidFields = candidate.invalid,
            canApply = candidate.changed, problem = null)
        return true
    }

    private suspend fun confirm(): Boolean {
        val captured = pending ?: return false
        if (view.value.applying) return false
        unavailable()?.let { return refuse(it) }
        documentChanged()
        if (view.value.problem == MixerProblem.STALE) return false
        val candidate = candidate(captured, requireNotNull(view.value.draft))
        if (candidate.invalid.isNotEmpty()) return refuse(MixerProblem.INVALID)
        if (!candidate.changed) { pending = null; mutable.value = MixerEditorState(); return true }
        mutable.value = view.value.copy(applying = true, problem = null)
        val accepted = try { apply(requireNotNull(candidate.intent), captured.document.revision) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { false }
        finally { mutable.value = view.value.copy(applying = false) }
        if (accepted) { pending = null; mutable.value = MixerEditorState(); return true }
        documentChanged()
        return if (view.value.problem == MixerProblem.STALE) false else refuse(MixerProblem.APPLY_FAILED)
    }

    private fun refuse(problem: MixerProblem): Boolean { mutable.value = view.value.copy(problem = problem); return false }
    private data class Candidate(val intent: Intent?, val invalid: Set<MixerField>, val changed: Boolean = false)
    private fun candidate(captured: Pending, draft: MixerDraft): Candidate {
        val invalid = mutableSetOf<MixerField>()
        val numbers = draft.fields.mapValues { (field, text) ->
            text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it in field.minimum..field.maximum }
                .also { if (it == null) invalid += field }
        }
        if (numbers[MixerField.DELAY_TIME]?.let { (it * 48).roundToInt() < 1 } == true) invalid += MixerField.DELAY_TIME
        if (invalid.isNotEmpty()) return Candidate(null, invalid)
        fun number(field: MixerField, original: Float): Float = if (draft.fields[field] == captured.initial.fields[field]) original
            else (requireNotNull(numbers[field]) / field.scale).toFloat()
        fun enabled(control: MixerSwitch) = control in draft.switches
        val oldInsert = captured.track?.fx?.insert ?: captured.document.project.mix.master
        val insert = MixInsert(
            MixEq(number(MixerField.LOW_DB, oldInsert.eq.lowDb), number(MixerField.MID_DB, oldInsert.eq.midDb), number(MixerField.HIGH_DB, oldInsert.eq.highDb)),
            MixFilter(draft.filter, number(MixerField.CUTOFF, oldInsert.filter.cutoffHz)),
            oldInsert.compressor.let { old -> MixCompressor(enabled(MixerSwitch.COMPRESSOR), number(MixerField.THRESHOLD, old.thresholdDb),
                number(MixerField.RATIO, old.ratio), number(MixerField.ATTACK, old.attackMs), number(MixerField.RELEASE, old.releaseMs), number(MixerField.MAKEUP, old.makeupDb)) },
        )
        val track = captured.track
        if (track != null) {
            val changed = track.copy(gain = number(MixerField.GAIN, track.gain), pan = number(MixerField.PAN, track.pan),
                mute = enabled(MixerSwitch.MUTE), solo = enabled(MixerSwitch.SOLO),
                fx = TrackFx(insert, number(MixerField.DELAY_SEND, track.fx.delaySend), number(MixerField.REVERB_SEND, track.fx.reverbSend)))
            val intent = when (val target = draft.channel.target) {
                is MixerTarget.Bank -> Intent.SetBankMix(target.id, changed)
                else -> Intent.SetTrackMix(changed)
            }
            return Candidate(intent, emptySet(), changed != track)
        }
        val old = captured.document.project.mix
        val delayFrames = if (draft.fields[MixerField.DELAY_TIME] == captured.initial.fields[MixerField.DELAY_TIME]) old.delay.frames
            else (requireNotNull(numbers[MixerField.DELAY_TIME]) * 48).roundToInt()
        val settings = MixSettings(
            MixDelay(enabled(MixerSwitch.DELAY), delayFrames, number(MixerField.FEEDBACK, old.delay.feedback), number(MixerField.DELAY_RETURN, old.delay.returnGain)),
            MixReverb(enabled(MixerSwitch.REVERB), number(MixerField.REVERB_DECAY, old.reverb.decaySeconds), number(MixerField.DAMPING, old.reverb.damping),
                number(MixerField.REVERB_RETURN, old.reverb.returnGain)), insert, number(MixerField.GAIN, old.masterGain),
        )
        return Candidate(Intent.SetMasterMix(settings), emptySet(), settings != old)
    }
}
