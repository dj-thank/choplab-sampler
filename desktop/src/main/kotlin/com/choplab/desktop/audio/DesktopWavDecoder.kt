package com.choplab.desktop.audio

import com.choplab.sampler.audio.AudioResourceLimits
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.ProjectLimits
import com.choplab.sampler.model.persistableAudioDisplayName
import com.choplab.sampler.model.retainedChannelCountForImport
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/** JVM adapter for bounded mono/stereo PCM consumed by the shared deck. */
object DesktopWavDecoder {
    fun decode(file: File): PcmAudio {
        require(file.isFile) { "Audio file does not exist: ${file.path}" }
        AudioResourceLimits.requireImportFileSize(file.length())
        AudioSystem.getAudioInputStream(file).use { source ->
            val sourceFormat = source.format
            validateSampleRate(sourceFormat.sampleRate)
            require(sourceFormat.channels in 1..8) { "音声のチャンネル数に対応していません" }
            val targetFormat = AudioFormat(
                AudioFormat.Encoding.PCM_SIGNED,
                sourceFormat.sampleRate,
                16,
                sourceFormat.channels,
                sourceFormat.channels * Short.SIZE_BYTES,
                sourceFormat.sampleRate,
                false,
            )
            val pcmStream = if (sourceFormat.matches(targetFormat)) {
                source
            } else {
                require(AudioSystem.isConversionSupported(targetFormat, sourceFormat)) {
                    "この音声形式をPCM-16へ変換できません"
                }
                AudioSystem.getAudioInputStream(targetFormat, source)
            }
            pcmStream.use { pcm ->
                return readPcm(file.name, pcm)
            }
        }
    }

    internal fun readPcm(
        name: String,
        stream: AudioInputStream,
        maximumFrames: Int = AudioResourceLimits.MAX_DECODED_FRAMES,
    ): PcmAudio {
        require(maximumFrames > 0) { "maximumFrames must be positive" }
        val channels = stream.format.channels
        require(channels in 1..8) { "音声のチャンネル数に対応していません" }
        val storedChannelCount = retainedChannelCountForImport(channels)
        require(stream.format.sampleSizeInBits == 16 && !stream.format.isBigEndian) {
            "PCM-16 little-endian形式が必要です"
        }
        val sampleRate = validateSampleRate(stream.format.sampleRate)
        val effectiveMaximumFrames = minOf(
            maximumFrames,
            AudioResourceLimits.maxDecodedMonoFrames(sampleRate),
        )

        val knownFrames = stream.frameLength
        if (knownFrames != AudioSystem.NOT_SPECIFIED.toLong()) {
            require(knownFrames in 1..effectiveMaximumFrames.toLong()) {
                "展開後の音声が大きすぎます。10分以内の音声を使用してください"
            }
        }

        val builder = BoundedPcmBuilder(
            initialFrameCapacity = knownFrames
                .takeIf { it in 1..effectiveMaximumFrames.toLong() }
                ?.toInt()
                ?.coerceAtMost(INITIAL_CAPACITY_LIMIT)
                ?: DEFAULT_INITIAL_CAPACITY,
            maximumFrames = effectiveMaximumFrames,
            channelCount = storedChannelCount,
        )
        val frameBytes = channels * Short.SIZE_BYTES
        val bufferSize = (STREAM_BUFFER_BYTES / frameBytes).coerceAtLeast(1) * frameBytes
        val buffer = ByteArray(bufferSize)

        while (true) {
            val read = stream.read(buffer, 0, buffer.size)
            if (read < 0) break
            require(read > 0) { "音声ストリームからデータを読み込めません" }
            require(read % frameBytes == 0) { "音声データに不完全なPCMフレームがあります" }

            var offset = 0
            while (offset < read) {
                if (storedChannelCount == 2) {
                    repeat(storedChannelCount) { channel ->
                        builder.append(buffer.readPcm16(offset + channel * Short.SIZE_BYTES))
                    }
                } else {
                    var sum = 0
                    repeat(channels) { channel ->
                        sum += buffer.readPcm16(offset + channel * Short.SIZE_BYTES).toInt()
                    }
                    builder.append(
                        (sum / channels)
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            .toShort(),
                    )
                }
                offset += frameBytes
            }
        }

        val samples = builder.toArray()
        require(samples.isNotEmpty()) { "音声データを展開できませんでした" }
        val frameCount = samples.size / storedChannelCount
        AudioResourceLimits.requireDecodedMonoFrameCount(frameCount.toLong(), sampleRate)
        return PcmAudio(
            name = persistableAudioDisplayName(name, null),
            samples = samples,
            sampleRate = sampleRate,
            channelCount = storedChannelCount,
        )
    }

    internal fun validateSampleRate(sampleRate: Float): Int {
        require(
            sampleRate.isFinite() &&
                sampleRate >= 8_000f &&
                sampleRate <= ProjectLimits.MAX_SAMPLE_RATE.toFloat(),
        ) {
            "音声のサンプルレートが不正です"
        }
        return sampleRate.toInt()
    }

    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private const val DEFAULT_INITIAL_CAPACITY = 256 * 1024
    private const val INITIAL_CAPACITY_LIMIT = 4_000_000
}

private fun ByteArray.readPcm16(offset: Int): Short {
    val value = (this[offset].toInt() and 0xFF) or (this[offset + 1].toInt() shl 8)
    return value.toShort()
}

internal class BoundedPcmBuilder(
    initialFrameCapacity: Int,
    maximumFrames: Int,
    val channelCount: Int,
) {
    private val maximumSize: Int

    init {
        require(maximumFrames > 0) { "maximumFrames must be positive" }
        require(channelCount in 1..2) { "channelCount must be mono or stereo" }
        maximumSize = Math.multiplyExact(maximumFrames, channelCount)
    }

    private var values = ShortArray(
        Math.multiplyExact(initialFrameCapacity.coerceAtLeast(1), channelCount)
            .coerceAtMost(maximumSize),
    )
    private var size = 0

    fun append(value: Short) {
        if (size >= maximumSize) {
            error("展開後の音声が大きすぎます。短い範囲に切った音声を使用してください")
        }
        if (size == values.size) {
            val doubled = values.size.toLong() * 2L
            val nextSize = minOf(maximumSize.toLong(), doubled).toInt()
            values = values.copyOf(nextSize)
        }
        values[size++] = value
    }

    fun toArray(): ShortArray = values.copyOf(size)
}
