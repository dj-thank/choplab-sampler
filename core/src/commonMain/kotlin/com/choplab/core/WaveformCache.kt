package com.choplab.core

import com.choplab.core.model.requireHash
import com.choplab.engine.PcmAsset

/** Bounded LRU of display peaks. Audio channels are never replaced by this projection. */
class WaveformCache(val maxBytes: Long = 8L * 1024 * 1024) {
    data class Key(val hash: String, val framesPerBucket: Int) {
        init { requireHash(hash); require(framesPerBucket in 1..1_048_576) }
    }
    class Peaks internal constructor(private val values: FloatArray) {
        val buckets: Int get() = values.size / 4
        val bytes: Long get() = values.size.toLong() * 4
        fun minimum(bucket: Int, channel: Int): Float { require(channel in 0..1 && bucket in 0 until buckets); return values[bucket * 4 + channel * 2] }
        fun maximum(bucket: Int, channel: Int): Float { require(channel in 0..1 && bucket in 0 until buckets); return values[bucket * 4 + channel * 2 + 1] }
    }
    private val entries = mutableMapOf<Key, Peaks>()
    private val order = mutableListOf<Key>()
    var residentBytes: Long = 0
        private set
    init { require(maxBytes in 16..(128L * 1024 * 1024)) }
    fun get(key: Key): Peaks? = entries[key]?.also { order.remove(key); order.add(key) }
    fun build(key: Key, pcm: PcmAsset): Peaks {
        get(key)?.let { return it }
        val buckets = (pcm.frameCount.toLong() + key.framesPerBucket - 1) / key.framesPerBucket
        require(buckets * 16 <= maxBytes) { "Waveform resolution exceeds cache budget" }
        val values = FloatArray(buckets.toInt() * 4)
        repeat(buckets.toInt()) { bucket ->
            val start = bucket * key.framesPerBucket
            val end = minOf(pcm.frameCount, start.toLong().plus(key.framesPerBucket).toInt())
            repeat(2) { channel ->
                var low = Float.POSITIVE_INFINITY
                var high = Float.NEGATIVE_INFINITY
                for (frame in start until end) { val v = pcm.sample(frame, channel); low = minOf(low, v); high = maxOf(high, v) }
                values[bucket * 4 + channel * 2] = low
                values[bucket * 4 + channel * 2 + 1] = high
            }
        }
        val peaks = Peaks(values)
        while (residentBytes + peaks.bytes > maxBytes && order.isNotEmpty()) {
            val expired = order.removeAt(0)
            residentBytes -= requireNotNull(entries.remove(expired)).bytes
        }
        entries[key] = peaks; order.add(key); residentBytes += peaks.bytes
        return peaks
    }
    fun clear() { entries.clear(); order.clear(); residentBytes = 0 }
}
