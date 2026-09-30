package com.choplab.core.edit

import com.choplab.core.kits.DrumKits
import com.choplab.core.model.*
import com.choplab.engine.Tempo

/** One durable user action. Selection and asynchronous work live in Studio, outside Project. */
sealed interface Intent {
    data class Rename(val title: String) : Intent
    data class SetTempo(val tempo: Tempo, val gesture: String? = null) : Intent
    data class SetBank(val bank: Bank) : Intent
    data class SetTrackMix(val track: Track) : Intent
    /** First BANK mix creates its explicit route and track together; later edits retain that identity. */
    data class SetBankMix(val bankId: Int, val track: Track) : Intent
    data class SetMasterMix(val settings: com.choplab.engine.MixSettings) : Intent
    data class ImportAsset(val asset: Asset) : Intent
    data class SetSourceRange(val range: FrameRange, val gesture: String? = null) : Intent
    data class SetSourcePitch(val semitones: Double, val gesture: String? = null) : Intent
    data class AddMarker(val frame: Long) : Intent
    data class MoveMarker(val index: Int, val frame: Long, val gesture: String? = null) : Intent
    data class EqualChop(val count: Int) : Intent
    data class AssignSlice(val slice: Int, val padId: Int) : Intent
    data class AssignRange(val assetHash: String, val range: FrameRange, val padId: Int) : Intent
    /**
     * Live chop, as in the earlier app: while the original plays, the tapped PAD takes the source from [frame] (before
     * the range: its start) to the range end, and the PADs chopped earlier in this pass ([session], same bank and
     * source) each end where the next later one starts. A marker is added at [frame] inside the range. The PADs' other
     * settings stay; each tap is one Undo. A [frame] at or after the range end is refused.
     */
    data class LiveChop(val padId: Int, val frame: Long, val session: FrozenList<Int> = frozenListOf()) : Intent
    /**
     * A voice take recorded while the song played, as in the earlier app's voice layer: the take's [asset] joins the
     * document, optionally goes to [pad], is placed as [clip], or is retained as a library [take] on [track].
     * At least one destination is required. One Undo.
     */
    data class AddVoiceTake(val asset: Asset, val pad: Pad?, val clip: Clip?, val track: Track? = null,
                            val take: Take? = null) : Intent
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
    /**
     * Puts one built-in kit on all 16 PADs of one BANK, slot order. Placed clips of any built-in kit sound
     * take this kit's sound in the same slot, so a placed beat keeps its rhythm. Other clips stay.
     */
    data class InstallKit(val assets: FrozenList<Asset>, val pads: FrozenList<Pad>) : Intent
    /** The song's tracks, clips and takes; [assets] are new sounds its clips bring in, such as a rendered PAD. */
    data class SetArrangement(val tracks: FrozenList<Track>, val clips: FrozenList<Clip>, val takes: FrozenList<Take>,
                              val assets: FrozenList<Asset> = frozenListOf(),
                              val vocalComps: FrozenList<VocalComp>? = null,
                              /** A placement can establish its BANK routes atomically with its clips. */
                              val banks: FrozenList<Bank>? = null) : Intent
    data class SetLyrics(val lines: FrozenList<LyricLine>) : Intent
    data class SetStructuredLyrics(val lines: FrozenList<LyricLine>, val structure: LyricStructure) : Intent
    /** A confirmed guide proposal joins its rendered sounds and lyric alignment in one Undo. */
    data class ApplyVocalGuide(val tracks: FrozenList<Track>, val clips: FrozenList<Clip>, val assets: FrozenList<Asset>,
                              val lines: FrozenList<LyricLine>, val structure: LyricStructure) : Intent
}

sealed interface Effect {
    data class StopPads(val ids: FrozenList<Int>) : Effect
    data object PublishProject : Effect
}
enum class Mutation { NONE, PROJECT }

data class Reduction(val project: Project, val mutation: Mutation, val effects: FrozenList<Effect>, val mergeKey: String? = null)

object Reducer {
    fun reduce(before: Project, intent: Intent): Reduction {
        val edited = when (intent) {
            is Intent.Rename -> before.copy(title = intent.title)
            is Intent.SetTempo -> before.copy(tempo = intent.tempo)
            is Intent.SetBank -> before.copy(banks = before.banks.map { if (it.id == intent.bank.id) intent.bank else it }.frozen())
            is Intent.SetTrackMix -> {
                val current = requireNotNull(before.tracks.firstOrNull { it.id == intent.track.id })
                require(current.kind == intent.track.kind && current.name == intent.track.name) { "Mixing cannot change track identity" }
                before.copy(tracks = before.tracks.map { if (it.id == current.id) intent.track else it }.frozen())
            }
            is Intent.SetBankMix -> {
                require(intent.bankId in before.banks.indices)
                val bank = before.banks[intent.bankId]
                require(intent.track.kind == TrackKind.BANK)
                val tracks = if (bank.trackId == null) {
                    require(before.tracks.none { it.id == intent.track.id }) { "A new BANK route cannot replace another track" }
                    before.tracks + intent.track
                } else {
                    val current = before.tracks.first { it.id == bank.trackId }
                    require(current.id == intent.track.id && current.name == intent.track.name) { "Mixing cannot change BANK identity" }
                    before.tracks.map { if (it.id == current.id) intent.track else it }
                }
                before.copy(banks = before.banks.map { if (it.id == bank.id) it.copy(trackId = intent.track.id) else it }.frozen(),
                    tracks = tracks.frozen())
            }
            is Intent.SetMasterMix -> before.copy(mix = intent.settings)
            is Intent.ImportAsset -> before.copy(
                assets = mergeAssets(before.assets, listOf(intent.asset)),
                source = Source(intent.asset.hash, FrameRange(0, intent.asset.frames)),
            )
            is Intent.SetSourceRange -> {
                val source = requireNotNull(before.source) { "No source" }
                before.copy(source = source.copy(range = intent.range, markers = source.markers.filter { it > intent.range.start && it < intent.range.end }.frozen()))
            }
            is Intent.SetSourcePitch -> before.copy(source = requireNotNull(before.source) { "No source" }.copy(pitchSemitones = intent.semitones))
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
            is Intent.LiveChop -> liveChop(before, intent)
            is Intent.AddVoiceTake -> {
                val pad = intent.pad
                val clip = intent.clip
                val take = intent.take
                require(pad != null || clip != null || take != null) { "A recording must be retained" }
                require(pad == null || pad.assetHash == intent.asset.hash)
                require(clip == null || (clip.assetHash == intent.asset.hash && before.clips.none { it.id == clip.id }))
                require(take == null || (take.assetHash == intent.asset.hash && before.takes.none { it.id == take.id }))
                require(intent.track == null || ((clip != null || take != null) && before.tracks.none { it.id == intent.track.id }))
                val tracks = intent.track?.let { before.tracks + it } ?: before.tracks
                require(clip == null || tracks.any { it.id == clip.trackId }) { "No track for the take" }
                require(take == null || tracks.any { it.id == take.trackId }) { "No track for the candidate" }
                before.copy(assets = mergeAssets(before.assets, listOf(intent.asset)),
                    pads = if (pad == null) before.pads else before.pads.map { if (it.id == pad.id) pad else it }.frozen(),
                    tracks = tracks.frozen(), clips = if (clip == null) before.clips else (before.clips + clip).frozen(),
                    takes = if (take == null) before.takes else (before.takes + take).frozen())
            }
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
            is Intent.InstallKit -> {
                require(intent.pads.size == DrumKits.SOUNDS)
                val first = intent.pads.first().id
                require(first % 16 == 0)
                val assets = mergeAssets(before.assets, intent.assets)
                val byHash = assets.associateBy { it.hash }
                val kitAssets = intent.pads.mapIndexed { slot, pad ->
                    require(pad.id == first + slot)
                    val asset = requireNotNull(byHash[requireNotNull(pad.assetHash)]) { "Missing kit asset" }
                    require(DrumKits.identify(asset)?.slot == slot) { "Not this slot's kit sound" }
                    asset
                }
                require(kitAssets.map { DrumKits.identify(it)!!.kit }.distinct().size == 1) { "Sounds from more than one kit" }
                val incoming = intent.pads.associateBy { it.id }
                before.copy(assets = assets, pads = before.pads.map { incoming[it.id] ?: it }.frozen(),
                    clips = before.clips.map { clip ->
                        val slot = DrumKits.identify(byHash.getValue(clip.assetHash))?.slot
                        val next = slot?.let { kitAssets[it] }
                        if (next == null || clip.range.end > next.frames) clip else clip.copy(assetHash = next.hash)
                    }.frozen())
            }
            is Intent.SetArrangement -> {
                intent.banks?.let { banks ->
                    require(banks.size == before.banks.size && banks.indices.all { index ->
                        banks[index].copy(trackId = before.banks[index].trackId) == before.banks[index]
                    }) { "Arrangement placement can only establish BANK routes" }
                }
                before.copy(assets = if (intent.assets.isEmpty()) before.assets else mergeAssets(before.assets, intent.assets),
                    tracks = intent.tracks, clips = intent.clips, takes = intent.takes,
                    vocalComps = intent.vocalComps ?: before.vocalComps, banks = intent.banks ?: before.banks)
            }
            is Intent.SetLyrics -> before.copy(lyrics = intent.lines, lyricStructure = before.lyricStructure?.retainFor(intent.lines))
            is Intent.SetStructuredLyrics -> before.copy(lyrics = intent.lines, lyricStructure = intent.structure)
            is Intent.ApplyVocalGuide -> before.copy(assets = mergeAssets(before.assets, intent.assets), tracks = intent.tracks,
                clips = intent.clips, lyrics = intent.lines, lyricStructure = intent.structure)
        }
        val after = withoutUnusedRenderedSounds(edited)
        val key = when (intent) {
            is Intent.SetTempo -> intent.gesture?.let { "tempo:$it" }
            is Intent.SetSourceRange -> intent.gesture?.let { "range:$it" }
            is Intent.SetSourcePitch -> intent.gesture?.let { "source-pitch:$it" }
            is Intent.MoveMarker -> intent.gesture?.let { "marker:${intent.index}:$it" }
            is Intent.SetPad -> intent.gesture?.let { "pad:${intent.pad.id}:$it" }
            else -> null
        }
        require(key == null || key.length <= 128)
        return reduction(before, after, key)
    }

    internal fun reduction(before: Project, after: Project, key: String? = null): Reduction {
        if (before == after) return Reduction(before, Mutation.NONE, frozenListOf())
        // Tapping lyric timing during playback edits the document without interrupting its voices.
        if (before.copy(lyrics = after.lyrics, lyricStructure = after.lyricStructure) == after) return Reduction(after, Mutation.PROJECT, frozenListOf(), key)
        val mixChanged = before.mix != after.mix || before.tracks != after.tracks ||
            before.banks.map { it.trackId } != after.banks.map { it.trackId }
        val stopped = before.pads.indices.filter {
            before.pads[it].assetHash != null && (before.pads[it] != after.pads[it] ||
                (mixChanged && (before.banks[it / 16].trackId != null || after.banks[it / 16].trackId != null)))
        }.frozen()
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
    private fun liveChop(before: Project, intent: Intent.LiveChop): Project {
        val source = requireNotNull(before.source) { "No source" }
        require(intent.padId in 0..127 && intent.session.all { it in 0..127 })
        val range = source.range
        // A tap after the range cuts nothing; one just before it (the latency correction) starts at the range start.
        require(intent.frame < range.end) { "Past the range" }
        val start = intent.frame.coerceAtLeast(range.start)
        val chopped = assign(before, source.assetHash, FrameRange(start, range.end), intent.padId)
        val bank = intent.padId / 16
        // This pass's chops in time order: each lasts until the next later one starts, the last until the range end.
        // PADs cut at the same moment (two fingers in one audio block) share one chop instead of one going silent.
        val starts = (intent.session + intent.padId).distinct().map { chopped.pads[it] }
            .filter { pad -> pad.id / 16 == bank && pad.assetHash == source.assetHash && pad.range?.start?.let { it in range.start until range.end } == true }
            .associate { it.id to requireNotNull(it.range).start }
        val pads = chopped.pads.map { pad ->
            val from = starts[pad.id] ?: return@map pad
            pad.copy(range = FrameRange(from, starts.values.filter { it > from }.minOrNull() ?: range.end))
        }
        val markers = if (start > range.start && start !in source.markers && source.markers.size < 127) (source.markers + start).sorted() else source.markers
        return chopped.copy(pads = pads.frozen(), source = source.copy(markers = markers.frozen()))
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
    /**
     * App-rendered sounds (built-in kit sounds, transformed PADs placed on the song) stay in the document only while
     * something uses them; choosing the kit or placing the PAD again renders them again. Without this every kit or
     * placement tried would stay in each save and count against the asset limit.
     */
    private fun withoutUnusedRenderedSounds(project: Project): Project {
        if (project.assets.none { it.role == AssetRole.RENDERED }) return project
        val used = HashSet<String>()
        project.source?.let { used += it.assetHash }
        project.pads.forEach { pad -> pad.assetHash?.let { used += it } }
        project.clips.forEach { used += it.assetHash }
        project.takes.forEach { used += it.assetHash }
        project.vocalComps.forEach { used += it.renderedAssetHash }
        project.assets.forEach { asset -> asset.derivedFrom?.let { used += it } }
        val kept = project.assets.filter { it.hash in used || it.role != AssetRole.RENDERED }
        return if (kept.size == project.assets.size) project else project.copy(assets = kept.frozen())
    }
}
