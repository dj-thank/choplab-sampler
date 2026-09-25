package com.choplab.core.edit

import com.choplab.core.model.*
import com.choplab.engine.Tempo

/** One durable user action. Selection and asynchronous work live in Studio, outside Project. */
sealed interface Intent {
    data class Rename(val title: String) : Intent
    data class SetTempo(val tempo: Tempo, val gesture: String? = null) : Intent
    data class SetBank(val bank: Bank) : Intent
    data class ImportAsset(val asset: Asset) : Intent
    data class SetSourceRange(val range: FrameRange, val gesture: String? = null) : Intent
    data class AddMarker(val frame: Long) : Intent
    data class MoveMarker(val index: Int, val frame: Long, val gesture: String? = null) : Intent
    data class EqualChop(val count: Int) : Intent
    data class AssignSlice(val slice: Int, val padId: Int) : Intent
    data class AssignRange(val assetHash: String, val range: FrameRange, val padId: Int) : Intent
    data class SetPad(val pad: Pad, val gesture: String? = null) : Intent
    data class ClearPad(val padId: Int) : Intent
    data class PutPattern(val pattern: Pattern) : Intent
    data class SetNote(val patternId: String, val note: Note, val enabled: Boolean) : Intent
    data class FillPadPattern(val patternId: String, val padId: Int, val spacingTicks: Int) : Intent
    data class ClearPadPattern(val patternId: String, val padId: Int) : Intent
    data class ShiftPadPattern(val patternId: String, val padId: Int, val ticks: Int) : Intent
    data class SetSong(val sections: FrozenList<SongSection>) : Intent
    /** Replaces only explicitly assigned PADs; other music, source and pattern placement survives. */
    data class ApplyKit(val assets: FrozenList<Asset>, val pads: FrozenList<Pad>) : Intent
    data class SetArrangement(val tracks: FrozenList<Track>, val clips: FrozenList<Clip>, val takes: FrozenList<Take>) : Intent
    data class SetLyrics(val lines: FrozenList<LyricLine>) : Intent
}

sealed interface Effect {
    data class StopPads(val ids: FrozenList<Int>) : Effect
    data object PublishProject : Effect
}
enum class Mutation { NONE, PROJECT }

data class Reduction(val project: Project, val mutation: Mutation, val effects: FrozenList<Effect>, val mergeKey: String? = null)

object Reducer {
    fun reduce(before: Project, intent: Intent): Reduction {
        val after = when (intent) {
            is Intent.Rename -> before.copy(title = intent.title)
            is Intent.SetTempo -> before.copy(tempo = intent.tempo)
            is Intent.SetBank -> before.copy(banks = before.banks.map { if (it.id == intent.bank.id) intent.bank else it }.frozen())
            is Intent.ImportAsset -> before.copy(
                assets = mergeAssets(before.assets, listOf(intent.asset)),
                source = Source(intent.asset.hash, FrameRange(0, intent.asset.frames)),
            )
            is Intent.SetSourceRange -> {
                val source = requireNotNull(before.source) { "No source" }
                before.copy(source = source.copy(range = intent.range, markers = source.markers.filter { it > intent.range.start && it < intent.range.end }.frozen()))
            }
            is Intent.AddMarker -> {
                val source = requireNotNull(before.source) { "No source" }
                before.copy(source = source.copy(markers = (source.markers + intent.frame).distinct().sorted().frozen()))
            }
            is Intent.MoveMarker -> {
                val source = requireNotNull(before.source) { "No source" }
                require(intent.index in source.markers.indices)
                before.copy(source = source.copy(markers = source.markers.mapIndexed { i, frame -> if (i == intent.index) intent.frame else frame }.frozen()))
            }
            is Intent.EqualChop -> {
                val source = requireNotNull(before.source) { "No source" }
                require(intent.count in 1..128 && source.range.length >= intent.count)
                val points = (1 until intent.count).map { source.range.start + source.range.length * it / intent.count }.frozen()
                before.copy(source = source.copy(markers = points))
            }
            is Intent.AssignSlice -> {
                val source = requireNotNull(before.source) { "No source" }
                val slices = source.slices()
                require(intent.slice in slices.indices)
                assign(before, source.assetHash, slices[intent.slice], intent.padId)
            }
            is Intent.AssignRange -> assign(before, intent.assetHash, intent.range, intent.padId)
            is Intent.SetPad -> before.copy(pads = before.pads.map { if (it.id == intent.pad.id) intent.pad else it }.frozen())
            is Intent.ClearPad -> {
                require(intent.padId in 0..127)
                before.copy(
                    pads = before.pads.map { if (it.id == intent.padId) Pad(it.id) else it }.frozen(),
                    patterns = before.patterns.map { p -> p.copy(notes = p.notes.filter { it.padId != intent.padId }.frozen()) }.frozen(),
                )
            }
            is Intent.PutPattern -> before.copy(patterns = if (before.patterns.any { it.id == intent.pattern.id })
                before.patterns.map { if (it.id == intent.pattern.id) intent.pattern else it }.frozen()
                else (before.patterns + intent.pattern).frozen())
            is Intent.SetNote -> editPattern(before, intent.patternId) { p ->
                val other = p.notes.filterNot { it.tick == intent.note.tick && it.padId == intent.note.padId }
                p.copy(notes = (if (intent.enabled) other + intent.note else other).sortedWith(compareBy(Note::tick, Note::padId)).frozen())
            }
            is Intent.FillPadPattern -> editPattern(before, intent.patternId) { p ->
                require(intent.padId in 0..127 && before.pads[intent.padId].assetHash != null)
                require(intent.spacingTicks in 1..p.lengthTicks)
                val notes = (0 until p.lengthTicks step intent.spacingTicks).map { Note(it, intent.padId) }
                p.copy(notes = (p.notes.filterNot { it.padId == intent.padId } + notes).sortedWith(compareBy(Note::tick, Note::padId)).frozen())
            }
            is Intent.ClearPadPattern -> editPattern(before, intent.patternId) { p ->
                require(intent.padId in 0..127)
                p.copy(notes = p.notes.filterNot { it.padId == intent.padId }.frozen())
            }
            is Intent.ShiftPadPattern -> editPattern(before, intent.patternId) { p ->
                require(intent.padId in 0..127)
                p.copy(notes = p.notes.map { n -> if (n.padId == intent.padId) n.copy(tick = ((n.tick.toLong() + intent.ticks).mod(p.lengthTicks.toLong())).toInt()) else n }
                    .sortedWith(compareBy(Note::tick, Note::padId)).frozen())
            }
            is Intent.SetSong -> before.copy(song = intent.sections)
            is Intent.ApplyKit -> {
                require(intent.pads.size in 1..128 && intent.pads.map { it.id }.distinct().size == intent.pads.size)
                require(intent.pads.all { it.assetHash != null })
                val incoming = intent.pads.associateBy { it.id }
                before.copy(assets = mergeAssets(before.assets, intent.assets), pads = before.pads.map { incoming[it.id] ?: it }.frozen())
            }
            is Intent.SetArrangement -> before.copy(tracks = intent.tracks, clips = intent.clips, takes = intent.takes)
            is Intent.SetLyrics -> before.copy(lyrics = intent.lines)
        }
        val key = when (intent) {
            is Intent.SetTempo -> intent.gesture?.let { "tempo:$it" }
            is Intent.SetSourceRange -> intent.gesture?.let { "range:$it" }
            is Intent.MoveMarker -> intent.gesture?.let { "marker:${intent.index}:$it" }
            is Intent.SetPad -> intent.gesture?.let { "pad:${intent.pad.id}:$it" }
            else -> null
        }
        require(key == null || key.length <= 128)
        return reduction(before, after, key)
    }

    internal fun reduction(before: Project, after: Project, key: String? = null): Reduction {
        if (before == after) return Reduction(before, Mutation.NONE, frozenListOf())
        val stopped = before.pads.indices.filter { before.pads[it] != after.pads[it] && before.pads[it].assetHash != null }.frozen()
        val effects = buildList {
            if (stopped.isNotEmpty()) add(Effect.StopPads(stopped))
            add(Effect.PublishProject)
        }.frozen()
        return Reduction(after, Mutation.PROJECT, effects, key)
    }

    private fun assign(before: Project, hash: String, range: FrameRange, padId: Int): Project {
        require(padId in 0..127)
        val asset = before.asset(hash)
        return before.copy(pads = before.pads.map { if (it.id == padId) it.copy(assetHash = hash, range = range, name = asset.name.take(80)) else it }.frozen())
    }
    private fun editPattern(before: Project, id: String, edit: (Pattern) -> Pattern): Project {
        require(before.patterns.any { it.id == id })
        return before.copy(patterns = before.patterns.map { if (it.id == id) edit(it) else it }.frozen())
    }
    private fun mergeAssets(old: List<Asset>, incoming: List<Asset>): FrozenList<Asset> {
        val merged = old.associateBy { it.hash }.toMutableMap()
        incoming.forEach { asset ->
            val existing = merged[asset.hash]
            require(existing == null || existing == asset) { "Conflicting asset metadata" }
            merged[asset.hash] = asset
        }
        return merged.values.sortedBy { it.hash }.frozen()
    }
}
