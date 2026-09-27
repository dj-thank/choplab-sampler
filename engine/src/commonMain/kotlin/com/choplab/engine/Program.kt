package com.choplab.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** All counts and positions are stereo frames, never interleaved sample indices. */
object EngineFormat {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val PPQ = 960
    const val PAD_COUNT = 128
    const val MAX_RESIDENT_BYTES = 128L * 1024 * 1024
}

/** Per reader, preallocated: another engine sharing the asset cannot create a false miss. */
internal class PcmReadCursor {
    var missing = false
    var cache: PagedPcm? = null
    var page = -1
    var samples: FloatArray? = null
    fun reset() { missing = false; clear() }
    fun clear() { cache = null; page = -1; samples = null }
}

/** Owns defensive resident samples or a bounded immutable-page cache. Published samples never mutate. */
@OptIn(ExperimentalAtomicApi::class)
class PcmAsset private constructor(private var pcm: FloatArray?, val pages: PagedPcm? = null) {
    // The atomic lease fence permits this worker-only mutation only after all readers have finished.
    // Sample reads keep a stable plain array; there is no atomic/volatile operation per resident tap.
    val frameCount: Int = pcm?.size?.div(2) ?: pages!!.frameCount
    val residentBytes: Long = pcm?.size?.toLong()?.times(4) ?: pages!!.capacityBytes
    private val leases = AtomicInt(0)
    val evicted: Boolean get() = leases.load() < 0
    val leaseCount: Int get() = leases.load().coerceAtLeast(0)
    fun acquire(): PcmLease = checkNotNull(tryAcquire()) { "PCM was evicted" }
    fun tryAcquire(): PcmLease? = if (retainPcm()) PcmLease(this) else null
    /** No allocation, callbacks or destruction: safe for the producer and the render retention table. */
    internal fun retainPcm(): Boolean {
        while (true) {
            val before = leases.load()
            if (before < 0) return false
            check(before < Int.MAX_VALUE)
            if (leases.compareAndSet(before, before + 1)) return true
        }
    }
    internal fun releasePcm() { check(leases.fetchAndAdd(-1) > 0) }
    /** Worker only. A published/queued/being-prepared asset cannot lose its samples. */
    fun evictIfUnleased(): Boolean {
        if (!leases.compareAndSet(0, -1)) return false
        pcm = null
        pages?.close()
        return true
    }
    val cacheMisses: Long get() = pages?.misses ?: 0
    fun sample(frame: Int, channel: Int): Float {
        require(frame in 0 until frameCount && channel in 0..1)
        return at(frame, channel)
    }
    internal fun at(frame: Int, channel: Int, cursor: PcmReadCursor? = null): Float = pcm?.get(frame * 2 + channel) ?: pages!!.sample(frame, channel, cursor)

    companion object {
        fun paged(pages: PagedPcm): PcmAsset = PcmAsset(null, pages)
        fun fromInterleaved(samples: FloatArray, maxBytes: Long = EngineFormat.MAX_RESIDENT_BYTES): PcmAsset {
            require(samples.isNotEmpty() && samples.size % 2 == 0)
            require(maxBytes in 8..EngineFormat.MAX_RESIDENT_BYTES && samples.size.toLong() * 4 <= maxBytes)
            require(samples.all { it.isFinite() }) { "PCM must contain only finite values" }
            return PcmAsset(samples.copyOf())
        }

        fun fromMono(samples: FloatArray, maxBytes: Long = EngineFormat.MAX_RESIDENT_BYTES): PcmAsset {
            require(samples.isNotEmpty() && samples.size.toLong() * 8 <= maxBytes)
            require(maxBytes in 8..EngineFormat.MAX_RESIDENT_BYTES && samples.all { it.isFinite() })
            return PcmAsset(FloatArray(samples.size * 2) { samples[it / 2] })
        }
    }
}

enum class PlayMode { ONE_SHOT, GATE, LOOP }

/** Immutable, prepared on the control/worker thread. Ranges are [startFrame, endFrame). */
class Pad(
    val id: Int,
    val asset: PcmAsset,
    val startFrame: Int = 0,
    val endFrame: Int = asset.frameCount,
    val mode: PlayMode = PlayMode.ONE_SHOT,
    val pitchSemitones: Double = 0.0,
    val gain: Float = 1f,
    val pan: Float = 0f,
    val reverse: Boolean = false,
    val chokeGroup: Int = 0,
    val attackFrames: Int = 96,
    val releaseFrames: Int = 96,
    /** Endpoint crossfade on each side of the seam; constant level and loop period are preserved. */
    val loopCrossfadeFrames: Int = 48,
    val decayFrames: Int = 0,
    val sustainLevel: Float = 1f,
    /** 1 leaves the sound untouched; lower values darken it with a one-pole low-pass, down to 80 Hz at 0. */
    val tone: Float = 1f,
    val mixBus: Int = MixerProgram.UNROUTED_BUS,
    /** Track fader follows the raw PAD/bake, so its pan is never baked or combined twice. */
    val mixGain: Float = 1f,
    val mixPan: Float = 0f,
) {
    init {
        require(id in 0 until EngineFormat.PAD_COUNT)
        require(startFrame >= 0 && endFrame <= asset.frameCount && endFrame > startFrame)
        require(pitchSemitones.isFinite() && pitchSemitones in -24.0..24.0)
        require(gain.isFinite() && gain in 0f..8f && pan.isFinite() && pan in -1f..1f)
        require(chokeGroup in 0..128)
        require(attackFrames in 0..48_000 && releaseFrames in 1..48_000 && loopCrossfadeFrames in 0..24_000)
        require(decayFrames in 0..48_000 && sustainLevel.isFinite() && sustainLevel in 0f..1f)
        require(tone.isFinite() && tone in 0f..1f)
        require(mixBus in 0 until MixerProgram.MAX_BUSES)
        require(mixGain.isFinite() && mixGain in 0f..8f && mixPan.isFinite() && mixPan in -1f..1f)
    }
    /** The earlier app's curve: corner 80 Hz x 225^tone, so 1 would be 18 kHz; the top step bypasses exactly. */
    internal val toneAlpha: Double = if (tone >= TONE_BYPASS) 1.0 else 1.0 - exp(-2.0 * PI * 80.0 * 225.0.pow(tone.toDouble()) / EngineFormat.SAMPLE_RATE)
    internal val mixLeftGain = mixGain * if (mixPan > 0f) cos(mixPan * PI / 2).toFloat() else 1f
    internal val mixRightGain = mixGain * if (mixPan < 0f) cos(-mixPan * PI / 2).toFloat() else 1f
    internal val step = 2.0.pow(pitchSemitones / 12.0) * if (reverse) -1.0 else 1.0
    internal val leftGain = gain * if (pan > 0f) cos(pan * PI / 2).toFloat() else 1f
    internal val rightGain = gain * if (pan < 0f) cos(-pan * PI / 2).toFloat() else 1f

    companion object {
        /** At or above this, tone is bypassed and the PAD renders bit for bit as before tone existed. */
        const val TONE_BYPASS = 0.995f
    }
}

class SequenceNote(val tick: Int, val padId: Int, val velocity: Float = 1f) {
    init {
        require(tick >= 0 && padId in 0 until EngineFormat.PAD_COUNT)
        require(velocity.isFinite() && velocity in 0f..1f)
    }
}

/** Fixed 960 PPQ; notes may use arbitrary ticks, including triplets. */
class Pattern(val lengthTicks: Int, notes: List<SequenceNote>) {
    init {
        require(lengthTicks in 1..(8 * 4 * EngineFormat.PPQ))
        require(notes.size <= 4096 && notes.all { it.tick < lengthTicks })
    }
    private val entries = notes.sortedBy { it.tick }.toTypedArray()
    val noteCount: Int get() = entries.size
    fun note(index: Int): SequenceNote = entries[index]
}

/** Integer milli-BPM makes decimal tempos exact; swing 500 = straight, 750 = 75%. */
data class Tempo(val milliBpm: Int = 120_000, val swingPermille: Int = 500) {
    init {
        require(milliBpm in 40_000..240_000)
        require(swingPermille in 500..750)
    }
}

class EngineProgram(
    pads: List<Pad> = emptyList(),
    val pattern: Pattern? = null,
    val tempo: Tempo = Tempo(),
    val revision: Long = 0,
    val arrangement: Arrangement? = null,
    private val preparedPcm: PcmLeaseGroup? = null,
    val mixer: MixerProgram = MixerProgram.BYPASS,
) {
    private val slots: Array<Pad?> = arrayOfNulls(EngineFormat.PAD_COUNT)
    private val assets: Array<PcmAsset>
    internal val assetCount: Int get() = assets.size
    internal fun asset(index: Int): PcmAsset = assets[index]
    val residentBytes: Long
    init {
        require(revision >= 0 && pads.size <= EngineFormat.PAD_COUNT)
        var bytes = 0L
        val ownedAssets = mutableSetOf<PcmAsset>()
        for (pad in pads) {
            require(slots[pad.id] == null) { "Duplicate pad ID" }
            slots[pad.id] = pad
            if (ownedAssets.add(pad.asset)) bytes += pad.asset.residentBytes
        }
        if (arrangement != null) for (i in 0 until arrangement.clipCount) {
            val asset = arrangement.clip(i).asset
            if (ownedAssets.add(asset)) bytes += asset.residentBytes
        }
        require(bytes <= EngineFormat.MAX_RESIDENT_BYTES) { "Program resident PCM exceeds 128 MiB" }
        if (pattern != null) for (i in 0 until pattern.noteCount) {
            require(slots[pattern.note(i).padId] != null) { "Pattern references an empty pad" }
        }
        residentBytes = bytes
        assets = ownedAssets.toTypedArray()
    }
    fun pad(id: Int): Pad? = if (id in slots.indices) slots[id] else null

    /** The receiver releases this handoff on success, rejection, cancellation and stale completion. */
    fun releasePreparation() { preparedPcm?.close() }
    /** A driver keeps its confirmed program alive across output loss; call only outside render. */
    fun retainPcm(): Boolean {
        for (index in assets.indices) if (!assets[index].retainPcm()) {
            for (previous in 0 until index) assets[previous].releasePcm()
            return false
        }
        return true
    }
    fun releasePcm() { for (asset in assets) asset.releasePcm() }

    companion object { val EMPTY = EngineProgram() }
}

data class EngineConfig(
    val controlCapacity: Int = 1024,
    val eventCapacity: Int = 1024,
    val residentByteLimit: Long = EngineFormat.MAX_RESIDENT_BYTES,
    val masterGain: Float = 1f,
    val outputMode: EngineOutputMode = EngineOutputMode.MONITOR,
) {
    init {
        require(controlCapacity in 2..8192 && controlCapacity.countOneBits() == 1)
        require(eventCapacity in 2..8192 && eventCapacity.countOneBits() == 1)
        require(residentByteLimit in 8..EngineFormat.MAX_RESIDENT_BYTES)
        require(masterGain.isFinite() && masterGain in 0f..8f)
    }
}
