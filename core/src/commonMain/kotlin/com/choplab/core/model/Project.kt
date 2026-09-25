package com.choplab.core.model

import com.choplab.engine.PlayMode
import com.choplab.engine.Tempo

/** A defensive, read-only snapshot; cannot be cast back to a caller-owned MutableList. */
class FrozenList<out T> private constructor(private val values: List<T>) : AbstractList<T>() {
    override val size: Int get() = values.size
    override fun get(index: Int): T = values[index]
    companion object {
        fun <T> from(values: Iterable<T>): FrozenList<T> = FrozenList(values.toList())
    }
}
fun <T> Iterable<T>.frozen(): FrozenList<T> = FrozenList.from(this)
fun <T> frozenListOf(vararg values: T): FrozenList<T> = values.asList().frozen()

object ProjectLimits {
    const val SCHEMA = 10
    const val PPQ = 960
    const val PAD_COUNT = 128
    const val BANK_COUNT = 8
    const val MAX_ASSETS = 256
    const val MAX_PATTERNS = 128
    const val MAX_NOTES = 4096
    const val MAX_FRAMES = 30_000_000L
    const val MAX_ASSET_BYTES = 256L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 1024L * 1024 * 1024
    const val MAX_TIMELINE_TICKS = 100_000_000L
    const val MAX_TIMELINE_FRAMES = 48_000L * 60 * 30
}

private val idPattern = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
private val hashPattern = Regex("[0-9a-f]{64}")
fun requireId(id: String) { require(idPattern.matches(id)) { "Invalid document ID" } }
fun requireHash(hash: String) { require(hashPattern.matches(hash)) { "Invalid SHA-256" } }
fun requireLabel(label: String, maximum: Int = 256) {
    require(label.isNotBlank() && label.length <= maximum && label.none { it < ' ' || it == '\u007f' })
}

enum class AssetRole { ORIGINAL, RENDERED, PCM_CACHE }
data class Asset(
    val hash: String,
    val extension: String,
    val byteCount: Long,
    val sampleRate: Int,
    val channels: Int,
    val frames: Long,
    val name: String,
    val role: AssetRole = AssetRole.ORIGINAL,
    val required: Boolean = true,
    val derivedFrom: String? = null,
) {
    init {
        requireHash(hash)
        require(extension in setOf("wav", "mp3", "flac", "ogg", "opus", "m4a", "aac"))
        require(byteCount in 1..ProjectLimits.MAX_ASSET_BYTES)
        require(sampleRate in 8_000..192_000 && channels in 1..2 && frames in 1..ProjectLimits.MAX_FRAMES)
        requireLabel(name)
        derivedFrom?.let(::requireHash)
        require(derivedFrom != hash)
        require(required || (role == AssetRole.PCM_CACHE && derivedFrom != null))
        require(role != AssetRole.PCM_CACHE || (extension == "wav" && derivedFrom != null))
        require(role != AssetRole.RENDERED || extension == "wav")
    }
    val entryName: String get() = "assets/$hash.$extension"
}

data class FrameRange(val start: Long, val end: Long) {
    init { require(start >= 0 && end > start && end <= ProjectLimits.MAX_FRAMES) }
    val length: Long get() = end - start
}

data class Source(val assetHash: String, val range: FrameRange, val markers: FrozenList<Long> = frozenListOf()) {
    init {
        requireHash(assetHash)
        require(markers.size <= 127 && markers.zipWithNext().all { (a, b) -> a < b })
        require(markers.all { it > range.start && it < range.end })
    }
    fun slices(): FrozenList<FrameRange> = (listOf(range.start) + markers + range.end)
        .zipWithNext { a, b -> FrameRange(a, b) }.frozen()
}

data class Bank(val id: Int, val name: String = ('A' + id).toString(), val color: Int = 0x4477aa, val role: String = "samples") {
    init { require(id in 0..7 && color in 0..0xffffff); requireLabel(name, 48); requireLabel(role, 48) }
}

data class Pad(
    val id: Int,
    val assetHash: String? = null,
    val range: FrameRange? = null,
    val name: String = "PAD ${id + 1}",
    val mode: PlayMode = PlayMode.ONE_SHOT,
    val pitchSemitones: Double = 0.0,
    val gain: Float = 1f,
    val pan: Float = 0f,
    val reverse: Boolean = false,
    val chokeGroup: Int = 0,
    val attackFrames: Int = 96,
    val releaseFrames: Int = 96,
    val loopCrossfadeFrames: Int = 48,
    val decayFrames: Int = 0,
    val sustainLevel: Float = 1f,
) {
    init {
        require(id in 0 until ProjectLimits.PAD_COUNT)
        require((assetHash == null) == (range == null))
        assetHash?.let(::requireHash)
        requireLabel(name, 80)
        require(pitchSemitones.isFinite() && pitchSemitones in -24.0..24.0)
        require(gain.isFinite() && gain in 0f..8f && pan.isFinite() && pan in -1f..1f)
        require(chokeGroup in 0..128 && attackFrames in 0..48_000 && releaseFrames in 1..48_000 && loopCrossfadeFrames in 0..24_000)
        require(decayFrames in 0..48_000 && sustainLevel.isFinite() && sustainLevel in 0f..1f)
    }
}

data class Note(val tick: Int, val padId: Int, val velocity: Float = 1f) {
    init {
        require(tick >= 0 && padId in 0 until ProjectLimits.PAD_COUNT)
        require(velocity.isFinite() && velocity in 0f..1f)
    }
}
data class Pattern(val id: String, val name: String = "Pattern", val bars: Int = 1, val notes: FrozenList<Note> = frozenListOf()) {
    init {
        requireId(id); requireLabel(name, 80)
        require(bars in 1..8 && notes.size <= ProjectLimits.MAX_NOTES)
        require(notes.all { it.tick < lengthTicks })
        require(notes.map { it.tick to it.padId }.distinct().size == notes.size) { "Duplicate pattern note" }
    }
    val lengthTicks: Int get() = bars * 4 * ProjectLimits.PPQ
}
data class SongSection(val patternId: String, val repeats: Int = 1) {
    init { requireId(patternId); require(repeats in 1..128) }
}
enum class TrackKind { BANK, SOURCE, STEM, VOCAL, GUIDE, CLICK }
data class Track(val id: String, val name: String, val kind: TrackKind, val gain: Float = 1f, val pan: Float = 0f, val mute: Boolean = false, val solo: Boolean = false) {
    init {
        requireId(id); requireLabel(name, 80)
        require(gain.isFinite() && gain in 0f..8f && pan.isFinite() && pan in -1f..1f)
    }
}
data class Clip(val id: String, val trackId: String, val assetHash: String, val range: FrameRange, val startTick: Long = 0,
                /** Absolute 48 kHz placement takes priority; null retains the musical startTick anchor. */
                val timelineStartFrame: Long? = null,
                /** Multiplied by track gain; audible combined gain above the current engine limit 8 is rejected. */
                val gain: Float = 1f,
                /** Added to track pan and clamped to [-1,1]. */
                val pan: Float = 0f) {
    init {
        requireId(id); requireId(trackId); requireHash(assetHash); require(startTick in 0..ProjectLimits.MAX_TIMELINE_TICKS)
        require(timelineStartFrame == null || timelineStartFrame in 0..ProjectLimits.MAX_TIMELINE_FRAMES)
        require(gain.isFinite() && gain in 0f..8f && pan.isFinite() && pan in -1f..1f)
    }
}
data class LyricWord(val text: String, val startTick: Long, val endTick: Long) {
    init { require(text.isNotEmpty() && text.length <= 256 && text.none { it == '\u0000' }); require(startTick >= 0 && endTick > startTick && endTick <= ProjectLimits.MAX_TIMELINE_TICKS) }
}
data class LyricLine(val id: String, val text: String, val startTick: Long, val endTick: Long, val words: FrozenList<LyricWord> = frozenListOf()) {
    init {
        requireId(id); require(text.length <= 4096 && text.none { it == '\u0000' })
        require(startTick >= 0 && endTick > startTick && endTick <= ProjectLimits.MAX_TIMELINE_TICKS)
        require(words.size <= 256 && words.all { it.startTick >= startTick && it.endTick <= endTick })
        require(words.zipWithNext().all { (a, b) -> a.endTick <= b.startTick })
    }
}
/**
 * Both placement and compensation are 48 kHz timeline frames. Positive compensation removes input
 * latency (moves earlier); negative compensation delays. If corrected start is negative, compilation
 * trims that exact number of normalized 48 kHz source frames. A take entirely before zero is omitted.
 * Stored takes are candidates and play only when their IDs are explicitly selected.
 */
data class Take(val id: String, val trackId: String, val assetHash: String, val range: FrameRange, val timelineStartFrame: Long, val compensationFrames: Int = 0) {
    init { requireId(id); requireId(trackId); requireHash(assetHash); require(timelineStartFrame in 0..(48_000L * 60 * 60 * 24)); require(compensationFrames in -480_000..480_000) }
}

/** Durable schema 10 document only. No selection, progress, OS paths, handles or PCM arrays. */
data class Project(
    val id: String = "untitled",
    val title: String = "Untitled",
    val assets: FrozenList<Asset> = frozenListOf(),
    val banks: FrozenList<Bank> = (0..7).map(::Bank).frozen(),
    val pads: FrozenList<Pad> = (0..127).map(::Pad).frozen(),
    val patterns: FrozenList<Pattern> = frozenListOf(Pattern("pattern-1")),
    val song: FrozenList<SongSection> = frozenListOf(),
    val tracks: FrozenList<Track> = frozenListOf(),
    val clips: FrozenList<Clip> = frozenListOf(),
    val lyrics: FrozenList<LyricLine> = frozenListOf(),
    val takes: FrozenList<Take> = frozenListOf(),
    val source: Source? = null,
    val tempo: Tempo = Tempo(),
    val schemaVersion: Int = ProjectLimits.SCHEMA,
) {
    init {
        require(schemaVersion == ProjectLimits.SCHEMA); requireId(id); requireLabel(title)
        require(assets.size <= ProjectLimits.MAX_ASSETS && assets.sumOf { it.byteCount } <= ProjectLimits.MAX_TOTAL_BYTES)
        require(assets.map { it.hash }.distinct().size == assets.size) { "Duplicate asset hash" }
        require(banks.size == 8 && banks.map { it.id } == (0..7).toList())
        require(pads.size == 128 && pads.map { it.id } == (0..127).toList())
        require(patterns.size in 1..ProjectLimits.MAX_PATTERNS && patterns.map { it.id }.distinct().size == patterns.size)
        require(song.size <= 1024 && song.all { section -> patterns.any { it.id == section.patternId } })
        require(tracks.size <= 64 && tracks.map { it.id }.distinct().size == tracks.size)
        require(clips.size <= 4096 && clips.map { it.id }.distinct().size == clips.size)
        require(lyrics.size <= 4096 && lyrics.map { it.id }.distinct().size == lyrics.size)
        require(takes.size <= 1024 && takes.map { it.id }.distinct().size == takes.size)
        val byHash = assets.associateBy { it.hash }
        fun checkRange(hash: String, range: FrameRange) {
            val asset = requireNotNull(byHash[hash]) { "Missing referenced asset" }
            require(range.end <= asset.frames)
            // Derived optional caches must never be the only source for durable music.
            require(asset.required) { "Durable references must use required assets" }
        }
        assets.forEach { asset -> asset.derivedFrom?.let { require(byHash.containsKey(it)) { "Missing original asset" } } }
        source?.let { checkRange(it.assetHash, it.range) }
        pads.forEach { pad -> pad.assetHash?.let { checkRange(it, requireNotNull(pad.range)) } }
        patterns.forEach { p -> require(p.notes.all { pads[it.padId].assetHash != null }) { "Note references empty PAD" } }
        clips.forEach { c -> checkRange(c.assetHash, c.range); require(tracks.any { it.id == c.trackId }) }
        takes.forEach { t -> checkRange(t.assetHash, t.range); require(tracks.any { it.id == t.trackId }) }
    }
    fun asset(hash: String): Asset = requireNotNull(assets.firstOrNull { it.hash == hash }) { "Unknown asset" }
}
