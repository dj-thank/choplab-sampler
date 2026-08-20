package com.choplab.desktop.audio.wasapi

import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Guid

internal const val WAVE_FORMAT_PCM = 0x0001
internal const val WAVE_FORMAT_IEEE_FLOAT = 0x0003
internal const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

data class WaveFormat(
    val formatTag: Int,
    val channels: Int,
    val sampleRate: Int,
    val averageBytesPerSecond: Int,
    val blockAlign: Int,
    val bitsPerSample: Int,
    val extraSize: Int,
    val validBitsPerSample: Int? = null,
    val channelMask: Long? = null,
    val subFormat: String? = null,
) {
    init {
        require(channels in 1..32) { "WASAPI channel count is invalid: $channels" }
        require(sampleRate in 8_000..384_000) { "WASAPI sample rate is invalid: $sampleRate" }
        require(blockAlign > 0) { "WASAPI block alignment is invalid" }
        require(bitsPerSample in 8..64) { "WASAPI bit depth is invalid: $bitsPerSample" }
        require(averageBytesPerSecond > 0) { "WASAPI byte rate is invalid" }
        require(extraSize in 0..4_096) { "WASAPI extra format data is invalid" }
    }

    val encoding: WaveEncoding
        get() = when {
            formatTag == WAVE_FORMAT_PCM -> WaveEncoding.PCM_INTEGER
            formatTag == WAVE_FORMAT_IEEE_FLOAT -> WaveEncoding.IEEE_FLOAT
            formatTag == WAVE_FORMAT_EXTENSIBLE && subFormat.equals(KSDATAFORMAT_SUBTYPE_PCM, ignoreCase = true) ->
                WaveEncoding.PCM_INTEGER
            formatTag == WAVE_FORMAT_EXTENSIBLE && subFormat.equals(KSDATAFORMAT_SUBTYPE_IEEE_FLOAT, ignoreCase = true) ->
                WaveEncoding.IEEE_FLOAT
            else -> WaveEncoding.UNKNOWN
        }

    companion object {
        fun read(pointer: Pointer): WaveFormat {
            require(Pointer.nativeValue(pointer) != 0L) { "WASAPI mix format pointer is null" }
            val tag = pointer.getShort(0).toInt() and 0xFFFF
            val channels = pointer.getShort(2).toInt() and 0xFFFF
            val sampleRate = pointer.getInt(4)
            val averageBytes = pointer.getInt(8)
            val blockAlign = pointer.getShort(12).toInt() and 0xFFFF
            val bits = pointer.getShort(14).toInt() and 0xFFFF
            val extra = pointer.getShort(16).toInt() and 0xFFFF
            val extensible = tag == WAVE_FORMAT_EXTENSIBLE && extra >= 22
            val subFormat = if (extensible) Guid.GUID(pointer.share(24)).toGuidString() else null
            return WaveFormat(
                formatTag = tag,
                channels = channels,
                sampleRate = sampleRate,
                averageBytesPerSecond = averageBytes,
                blockAlign = blockAlign,
                bitsPerSample = bits,
                extraSize = extra,
                validBitsPerSample = if (extensible) pointer.getShort(18).toInt() and 0xFFFF else null,
                channelMask = if (extensible) pointer.getInt(20).toLong() and 0xFFFF_FFFFL else null,
                subFormat = subFormat,
            )
        }
    }
}

enum class WaveEncoding {
    PCM_INTEGER,
    IEEE_FLOAT,
    UNKNOWN,
}

internal const val KSDATAFORMAT_SUBTYPE_PCM = "{00000001-0000-0010-8000-00A0C9223196}"
internal const val KSDATAFORMAT_SUBTYPE_IEEE_FLOAT = "{00000003-0000-0010-8000-00A0C9223196}"
