package com.choplab.desktop.audio

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.abs
import kotlin.math.max

/** Streaming envelope extraction for the original deck's source waveform region. */
object WavWaveform {
    fun read(file: File, bucketCount: Int = 360): FloatArray {
        require(bucketCount > 0) { "Waveform bucket count must be positive" }
        return runCatching {
            AudioSystem.getAudioInputStream(file).use { stream ->
                val format = stream.format
                val frameSize = format.frameSize
                val frameLength = stream.frameLength
                if (frameSize <= 0 || frameLength <= 0 || format.sampleSizeInBits <= 0) {
                    return@use FloatArray(0)
                }
                val framesPerBucket = max(1L, (frameLength + bucketCount - 1) / bucketCount)
                val peaks = FloatArray(bucketCount)
                val buffer = ByteArray(frameSize * 1024)
                var frameIndex = 0L
                while (true) {
                    val bytesRead = stream.read(buffer)
                    if (bytesRead <= 0) break
                    val completeBytes = bytesRead - (bytesRead % frameSize)
                    var offset = 0
                    while (offset < completeBytes) {
                        val bucket = (frameIndex / framesPerBucket).toInt().coerceAtMost(bucketCount - 1)
                        peaks[bucket] = max(peaks[bucket], frameAmplitude(buffer, offset, format))
                        frameIndex++
                        offset += frameSize
                    }
                }
                val strongest = peaks.maxOrNull()?.coerceAtLeast(0.08f) ?: return@use FloatArray(0)
                FloatArray(bucketCount) { index ->
                    (peaks[index] / strongest).coerceIn(0.08f, 1f)
                }
            }
        }.getOrDefault(FloatArray(0))
    }

    private fun frameAmplitude(bytes: ByteArray, frameOffset: Int, format: AudioFormat): Float {
        val sampleBytes = (format.sampleSizeInBits + 7) / 8
        if (sampleBytes !in 1..4 || format.channels <= 0) return 0f
        var strongest = 0f
        repeat(format.channels) { channel ->
            val offset = frameOffset + channel * sampleBytes
            if (offset + sampleBytes > bytes.size) return@repeat
            var raw = 0L
            if (format.isBigEndian) {
                repeat(sampleBytes) { index -> raw = (raw shl 8) or (bytes[offset + index].toLong() and 0xFF) }
            } else {
                repeat(sampleBytes) { index -> raw = raw or ((bytes[offset + index].toLong() and 0xFF) shl (index * 8)) }
            }
            val bits = sampleBytes * 8
            val signed = if (format.encoding == AudioFormat.Encoding.PCM_UNSIGNED) {
                raw - (1L shl (bits - 1))
            } else if (raw and (1L shl (bits - 1)) != 0L) {
                raw - (1L shl bits)
            } else {
                raw
            }
            val divisor = (1L shl (bits - 1)).toFloat().coerceAtLeast(1f)
            strongest = max(strongest, abs(signed.toFloat() / divisor).coerceIn(0f, 1f))
        }
        return strongest
    }
}
