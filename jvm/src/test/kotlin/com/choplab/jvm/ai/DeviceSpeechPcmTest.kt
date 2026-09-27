package com.choplab.jvm.ai

import com.choplab.core.ai.*
import kotlin.test.*

class DeviceSpeechPcmTest {
    @Test fun unsigned8Signed16AndFloatChunksRetainNativeRateAndChannelIdentity() {
        val eight = DeviceSpeechPcm().apply { begin(22_050, 2, DevicePcmEncoding.UNSIGNED_8); append(byteArrayOf(0, 255.toByte(), 128.toByte(), 64)) }
        val pcm8 = assertIs<TtsResult.Success<TtsAudio>>(eight.finish()).value
        assertEquals(22_050, pcm8.sampleRate); assertEquals(2, pcm8.channels)
        assertContentEquals(floatArrayOf(-1f, 127 / 128f, 0f, -.5f), pcm8.copySamples())
        val sixteen = DeviceSpeechPcm().apply {
            begin(44_100, 1, DevicePcmEncoding.SIGNED_16)
            append(byteArrayOf(0, 128.toByte(), 255.toByte())); append(byteArrayOf(127))
        }
        assertContentEquals(floatArrayOf(-1f, 32767 / 32768f), assertIs<TtsResult.Success<TtsAudio>>(sixteen.finish()).value.copySamples())
        val samples = floatArrayOf(.125f, -.375f, .7f, -.9f)
        val bytes = ByteArray(samples.size * 4)
        samples.forEachIndexed { index, sample -> repeat(4) { n -> bytes[index * 4 + n] = (sample.toRawBits() ushr (n * 8)).toByte() } }
        val floating = DeviceSpeechPcm().apply { begin(48_000, 2, DevicePcmEncoding.FLOAT_32); append(bytes) }
        assertContentEquals(samples, assertIs<TtsResult.Success<TtsAudio>>(floating.finish()).value.copySamples())
    }

    @Test fun missingDuplicatePartialNonfiniteAndLateChunksCannotBecomeAnAudioResult() {
        val missing = DeviceSpeechPcm().apply { append(byteArrayOf(1)) }
        val duplicate = DeviceSpeechPcm().apply { begin(48_000, 1, DevicePcmEncoding.SIGNED_16); begin(48_000, 1, DevicePcmEncoding.SIGNED_16); append(byteArrayOf(1, 0)) }
        val partial = DeviceSpeechPcm().apply { begin(48_000, 2, DevicePcmEncoding.SIGNED_16); append(byteArrayOf(1, 0)) }
        val invalid = DeviceSpeechPcm().apply { begin(48_000, 1, DevicePcmEncoding.FLOAT_32); append(byteArrayOf(0, 0, 128.toByte(), 127)) }
        val late = DeviceSpeechPcm().apply { begin(48_000, 1, DevicePcmEncoding.SIGNED_16); append(byteArrayOf(1, 0)); clear(); append(byteArrayOf(1, 0)) }
        val tooLarge = DeviceSpeechPcm().apply { begin(8_000, 1, DevicePcmEncoding.UNSIGNED_8); append(ByteArray(8_000 * 30 + 1)) }
        for (value in listOf(missing, duplicate, partial, invalid, late, tooLarge)) assertIs<TtsResult.Failure>(value.finish())
    }
}
