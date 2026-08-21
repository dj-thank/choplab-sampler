package com.choplab.desktop.audio

import com.choplab.sampler.audio.AudioResourceLimits
import com.choplab.sampler.model.PcmAudio
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem

/** JVM adapter for the same bounded mono PCM shape consumed by the shared deck. */
object DesktopWavDecoder {
    fun decode(file: File): PcmAudio {
        require(file.isFile) { "Audio file does not exist: ${file.path}" }
        require(file.length() <= AudioResourceLimits.MAX_IMPORT_FILE_BYTES) {
            "音声ファイルが大きすぎます。256 MiB以下のファイルを使用してください"
        }
        AudioSystem.getAudioInputStream(file).use { source ->
            val sourceFormat = source.format
            require(sourceFormat.sampleRate.isFinite() && sourceFormat.sampleRate >= 8_000f) {
                "音声のサンプルレートが不正です"
            }
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
                return readMono(file.name, pcm)
            }
        }
    }

    internal fun readMono(
        name: String,
        stream: AudioInputStream,
        maximumFrames: Int = AudioResourceLimits.MAX_DECODED_MONO_FRAMES,
    ): PcmAudio {
        require(maximumFrames > 0) { "maximumFrames must be positive" }
        val channels = stream.format.channels
        require(channels in 1..8) { "音声のチャンネル数に対応していません" }
        require(stream.format.sampleSizeInBits == 16 && !stream.format.isBigEndian) {
            "PCM-16 little-endian形式が必要です"
        }
        val sampleRate = stream.format.sampleRate.toInt()
        require(sampleRate >= 8_000) { "音声のサンプルレートが不正です" }

        val knownFrames = stream.frameLength
        if (knownFrames != AudioSystem.NOT_SPECIFIED.toLong()) {
            require(knownFrames in 1..maximumFrames.toLong()) {
                "展開後の音声が大きすぎます。10分以内の音声を使用してください"
            }
            val durationSeconds = knownFrames.toDouble() / sampleRate.toDouble()
            require(durationSeconds <= AudioResourceLimits.MAX_IMPORT_DURATION_SECONDS) {
                "音声が長すぎます。10分以内の音声を使用してください"
            }
        }

        val builder = BoundedMonoPcmBuilder(
            initialCapacity = knownFrames
                .takeIf { it in 1..maximumFrames.toLong() }
                ?.toInt()
                ?.coerceAtMost(INITIAL_CAPACITY_LIMIT)
                ?: DEFAULT_INITIAL_CAPACITY,
            maximumSize = maximumFrames,
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
                var sum = 0
                repeat(channels) { channel ->
                    val sampleOffset = offset + channel * Short.SIZE_BYTES
                    val value = (buffer[sampleOffset].toInt() and 0xFF) or
                        (buffer[sampleOffset + 1].toInt() shl 8)
                    sum += value.toShort().toInt()
                }
                builder.append(
                    (sum / channels)
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        .toShort(),
                )
                offset += frameBytes
            }
        }

        val samples = builder.toArray()
        require(samples.isNotEmpty()) { "音声データを展開できませんでした" }
        require(samples.size.toDouble() / sampleRate <= AudioResourceLimits.MAX_IMPORT_DURATION_SECONDS) {
            "音声が長すぎます。10分以内の音声を使用してください"
        }
        return PcmAudio(name = name, samples = samples, sampleRate = sampleRate)
    }

    private const val STREAM_BUFFER_BYTES = 64 * 1024
    private const val DEFAULT_INITIAL_CAPACITY = 256 * 1024
    private const val INITIAL_CAPACITY_LIMIT = 4_000_000
}

internal class BoundedMonoPcmBuilder(
    initialCapacity: Int,
    private val maximumSize: Int,
) {
    init {
        require(maximumSize > 0) { "maximumSize must be positive" }
    }

    private var values = ShortArray(initialCapacity.coerceIn(1, maximumSize))
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
