package com.choplab.jvm.ai

import com.choplab.core.ai.*
import java.io.ByteArrayOutputStream

enum class DevicePcmEncoding(val bytes: Int) { UNSIGNED_8(1), SIGNED_16(2), FLOAT_32(4) }

/** Native TTS callbacks provide a format plus raw PCM chunks, not a WAV file with an assumed header. */
class DeviceSpeechPcm {
    private var rate = 0
    private var channels = 0
    private var encoding: DevicePcmEncoding? = null
    private val bytes = ByteArrayOutputStream()
    private var failed = false
    @Synchronized fun begin(sampleRate: Int, channelCount: Int, format: DevicePcmEncoding) {
        if (rate != 0 || sampleRate !in 8_000..192_000 || channelCount !in 1..2) { failed = true; return }
        rate = sampleRate; channels = channelCount; encoding = format
    }
    @Synchronized fun append(chunk: ByteArray) {
        val format = encoding
        if (failed || format == null || bytes.size().toLong() + chunk.size > rate.toLong() * TtsLimits.MAX_SECONDS * channels * format.bytes) { failed = true; return }
        bytes.write(chunk)
    }
    @Synchronized fun finish(): TtsResult<TtsAudio> {
        val format = encoding ?: return ttsFailure(TtsProblem.INVALID_AUDIO)
        if (failed || bytes.size() == 0 || bytes.size() % (channels * format.bytes) != 0) return ttsFailure(TtsProblem.INVALID_AUDIO)
        val raw = bytes.toByteArray()
        val samples = FloatArray(raw.size / format.bytes)
        for (i in samples.indices) {
            val offset = i * format.bytes
            samples[i] = when (format) {
                DevicePcmEncoding.UNSIGNED_8 -> ((raw[offset].toInt() and 255) - 128) / 128f
                DevicePcmEncoding.SIGNED_16 -> ((raw[offset].toInt() and 255) or (raw[offset + 1].toInt() shl 8)).toShort() / 32768f
                DevicePcmEncoding.FLOAT_32 -> Float.fromBits((raw[offset].toInt() and 255) or ((raw[offset + 1].toInt() and 255) shl 8) or
                    ((raw[offset + 2].toInt() and 255) shl 16) or (raw[offset + 3].toInt() shl 24))
            }
            if (!samples[i].isFinite()) return ttsFailure(TtsProblem.INVALID_AUDIO)
        }
        return TtsResult.Success(TtsAudio.fromPcm(samples, rate, channels))
    }
    @Synchronized fun clear() { failed = true; bytes.reset() }
}
