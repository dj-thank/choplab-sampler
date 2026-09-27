package com.choplab.jvm.ai

import com.choplab.core.ai.*
import com.choplab.jvm.PcmMemoryBudget
import com.choplab.jvm.PcmMemoryLimit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

enum class DevicePcmEncoding(val bytes: Int) { UNSIGNED_8(1), SIGNED_16(2), FLOAT_32(4) }

/** Native TTS callback worker, never render. Each received chunk is admitted before its decoded allocation. */
class DeviceSpeechPcm(private val memory: PcmMemoryBudget = PcmMemoryBudget.shared) {
    private class Chunk(val samples: FloatArray, val reservation: PcmMemoryBudget.Reservation)
    private var rate = 0
    private var channels = 0
    private var encoding: DevicePcmEncoding? = null
    private val chunks = ArrayList<Chunk>()
    private var sampleCount = 0
    private var partialBytes = 0
    private var partialBits = 0
    private var failure: TtsProblem? = null
    @Synchronized fun begin(sampleRate: Int, channelCount: Int, format: DevicePcmEncoding) {
        if (rate != 0 || sampleRate !in 8_000..192_000 || channelCount !in 1..2) { fail(TtsProblem.INVALID_AUDIO); return }
        rate = sampleRate; channels = channelCount; encoding = format
    }
    @Synchronized fun append(chunk: ByteArray) {
        val format = encoding
        if (failure != null) return
        if (format == null) { fail(TtsProblem.INVALID_AUDIO); return }
        val decodedCount = (partialBytes.toLong() + chunk.size) / format.bytes
        if (sampleCount + decodedCount > rate.toLong() * TtsLimits.MAX_SECONDS * channels) { fail(TtsProblem.TOO_LARGE); return }
        // Bound object overhead even if a malformed provider emits one sample per callback.
        if (decodedCount > 0 && chunks.size >= 65_536) { fail(TtsProblem.TOO_LARGE); return }
        var reservation: PcmMemoryBudget.Reservation? = null
        try {
            if (decodedCount > 0) reservation = runBlocking(Dispatchers.IO) { memory.reserve(decodedCount * 4) }
            val decoded = FloatArray(decodedCount.toInt())
            var offset = 0
            for (byte in chunk) {
                partialBits = partialBits or ((byte.toInt() and 255) shl (partialBytes * 8)); partialBytes++
                if (partialBytes == format.bytes) {
                    val value = when (format) {
                        DevicePcmEncoding.UNSIGNED_8 -> (partialBits - 128) / 128f
                        DevicePcmEncoding.SIGNED_16 -> partialBits.toShort() / 32768f
                        DevicePcmEncoding.FLOAT_32 -> Float.fromBits(partialBits)
                    }
                    require(value.isFinite())
                    decoded[offset++] = value; partialBytes = 0; partialBits = 0
                }
            }
            if (decoded.isNotEmpty()) { chunks += Chunk(decoded, requireNotNull(reservation)); reservation = null; sampleCount += decoded.size }
        } catch (_: PcmMemoryLimit) { fail(TtsProblem.MEMORY_LIMIT) }
        catch (_: IllegalArgumentException) { fail(TtsProblem.INVALID_AUDIO) }
        finally { reservation?.close() }
    }
    @Synchronized fun finish(): TtsResult<TtsAudio> {
        failure?.let { return ttsFailure(it) }
        if (encoding == null || sampleCount == 0 || partialBytes != 0 || sampleCount % channels != 0) {
            fail(TtsProblem.INVALID_AUDIO); return ttsFailure(TtsProblem.INVALID_AUDIO)
        }
        var reservation: PcmMemoryBudget.Reservation? = null
        return try {
            val charge = runBlocking(Dispatchers.IO) { memory.reserve(sampleCount * 4L) }
            reservation = charge
            val audio = TtsAudio.takeOwnership(combine(), rate, channels, reservationOwner = memory, release = charge::close)
            clearChunks(); failure = TtsProblem.CLOSED
            reservation = null
            TtsResult.Success(audio)
        } catch (_: PcmMemoryLimit) { fail(TtsProblem.MEMORY_LIMIT); ttsFailure(TtsProblem.MEMORY_LIMIT) }
        finally { reservation?.close() }
    }
    private fun combine(): FloatArray {
        val samples = FloatArray(sampleCount)
        var at = 0
        for (chunk in chunks) { chunk.samples.copyInto(samples, at); at += chunk.samples.size }
        return samples
    }
    private fun clearChunks() {
        val charges = chunks.map { it.reservation }
        chunks.clear(); sampleCount = 0; partialBytes = 0; partialBits = 0
        charges.forEach { it.close() }
    }
    private fun fail(problem: TtsProblem) { failure = problem; clearChunks() }
    @Synchronized fun clear() { fail(TtsProblem.CLOSED) }
}
