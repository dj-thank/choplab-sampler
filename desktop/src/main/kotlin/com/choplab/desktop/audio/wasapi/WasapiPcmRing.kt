package com.choplab.desktop.audio.wasapi

/** Fixed stereo SPSC queue. Neither producer/consumer operation allocates, waits, or calls native I/O. */
internal class WasapiPcmRing(val capacityFrames: Int) {
    private val samples = FloatArray(capacityFrames * 2)
    @Volatile private var written = 0L
    @Volatile private var read = 0L

    init { require(capacityFrames in 1..WASAPI_MAX_BUFFER_FRAMES) }

    val availableFrames: Int get() = (written - read).toInt().coerceIn(0, capacityFrames)
    val freeFrames: Int get() = capacityFrames - availableFrames

    fun offerBytes(source: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= source.size - length && length % 8 == 0)
        val count = minOf(length / 8, freeFrames)
        // Refuse the entire accepted prefix before publishing any non-finite input.
        var at = offset
        repeat(count * 2) {
            if (!floatAt(source, at).isFinite()) wasapiReject(WasapiStage.OUTPUT, WasapiFault.NON_FINITE_PCM)
            at += 4
        }
        val first = written
        at = offset
        for (frame in 0 until count) {
            val target = ((first + frame) % capacityFrames).toInt() * 2
            samples[target] = floatAt(source, at)
            samples[target + 1] = floatAt(source, at + 4)
            at += 8
        }
        written = first + count
        return count * 8
    }

    /** Whole capture packet or nothing; overwriting unread samples would silently shorten a recording. */
    fun offerPacket(source: FloatArray, frames: Int): Boolean {
        require(frames >= 0 && frames <= source.size / 2)
        if (frames > freeFrames) return false
        val first = written
        copyIntoRing(source, first, frames)
        written = first + frames
        return true
    }

    fun readInto(target: FloatArray, requestedFrames: Int): Int {
        require(requestedFrames >= 0 && requestedFrames <= target.size / 2)
        val count = minOf(requestedFrames, availableFrames)
        val first = read
        val from = (first % capacityFrames).toInt()
        val beforeWrap = minOf(count, capacityFrames - from)
        samples.copyInto(target, 0, from * 2, (from + beforeWrap) * 2)
        if (count > beforeWrap) samples.copyInto(target, beforeWrap * 2, 0, (count - beforeWrap) * 2)
        read = first + count
        return count
    }

    private fun copyIntoRing(source: FloatArray, first: Long, frames: Int) {
        val at = (first % capacityFrames).toInt()
        val beforeWrap = minOf(frames, capacityFrames - at)
        source.copyInto(samples, at * 2, 0, beforeWrap * 2)
        if (frames > beforeWrap) source.copyInto(samples, 0, beforeWrap * 2, frames * 2)
    }

    private fun floatAt(source: ByteArray, at: Int): Float = Float.fromBits(
        (source[at].toInt() and 255) or ((source[at + 1].toInt() and 255) shl 8) or
            ((source[at + 2].toInt() and 255) shl 16) or (source[at + 3].toInt() shl 24),
    )
}
