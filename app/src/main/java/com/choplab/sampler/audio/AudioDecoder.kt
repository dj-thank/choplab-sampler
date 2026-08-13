package com.choplab.sampler.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.choplab.sampler.model.PcmAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

class AudioDecoder(private val context: Context) {
    suspend fun decode(uri: Uri): PcmAudio = withContext(Dispatchers.IO) {
        decodeBlocking(uri)
    }

    private fun decodeBlocking(uri: Uri): PcmAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null

        return try {
            extractor.setDataSource(context, uri, null)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: error("このファイルには読み込める音声トラックがありません")

            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("音声形式を判定できません")

            val durationUs = if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            if (durationUs > MAX_DURATION_US) {
                error("音声が長すぎます。MVP版では10分以内の音声を使用してください")
            }

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var outputSampleRate = inputFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 48_000)
            var outputChannels = inputFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            val output = Pcm16ArrayBuilder(
                initialCapacity = estimateInitialCapacity(durationUs, outputSampleRate),
                maximumSize = MAX_MONO_FRAMES,
            )
            val info = MediaCodec.BufferInfo()
            var inputEnded = false
            var outputEnded = false
            var idleCount = 0

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = decoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: error("デコーダー入力バッファを取得できません")
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime.coerceAtLeast(0L),
                                0,
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, CODEC_TIMEOUT_US)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        outputSampleRate = format.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, outputSampleRate)
                        outputChannels = format.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, outputChannels)
                            .coerceAtLeast(1)
                        pcmEncoding = format.intOrDefault(
                            MediaFormat.KEY_PCM_ENCODING,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                        idleCount = 0
                    }

                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        idleCount++
                        if (inputEnded && idleCount > MAX_IDLE_POLLS) {
                            error("音声デコードが完了しませんでした")
                        }
                    }

                    else -> {
                        if (outputIndex >= 0) {
                            idleCount = 0
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val sourceBuffer = decoder.getOutputBuffer(outputIndex)
                                    ?: error("デコーダー出力バッファを取得できません")
                                val frameBuffer = sourceBuffer.duplicate().apply {
                                    position(info.offset)
                                    limit(info.offset + info.size)
                                }.slice().order(ByteOrder.LITTLE_ENDIAN)

                                appendAsMono(
                                    source = frameBuffer,
                                    encoding = pcmEncoding,
                                    channelCount = outputChannels,
                                    destination = output,
                                )
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            val samples = output.toArray()
            if (samples.isEmpty()) error("音声データを展開できませんでした")

            removeTinyDcOffset(samples)
            PcmAudio(
                name = resolveDisplayName(uri),
                samples = samples,
                sampleRate = outputSampleRate.coerceAtLeast(8_000),
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun appendAsMono(
        source: ByteBuffer,
        encoding: Int,
        channelCount: Int,
        destination: Pcm16ArrayBuilder,
    ) {
        val channels = channelCount.coerceAtLeast(1)
        when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val values = source.asFloatBuffer()
                while (values.remaining() >= channels) {
                    var sum = 0f
                    repeat(channels) { sum += values.get() }
                    destination.append((sum / channels).coerceIn(-1f, 1f))
                }
            }

            AudioFormat.ENCODING_PCM_8BIT -> {
                while (source.remaining() >= channels) {
                    var sum = 0f
                    repeat(channels) {
                        val unsigned = source.get().toInt() and 0xFF
                        sum += (unsigned - 128) / 128f
                    }
                    destination.append((sum / channels).coerceIn(-1f, 1f))
                }
            }

            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                while (source.remaining() >= channels * 3) {
                    var sum = 0f
                    repeat(channels) {
                        val b0 = source.get().toInt() and 0xFF
                        val b1 = source.get().toInt() and 0xFF
                        val b2 = source.get().toInt() and 0xFF
                        var value = b0 or (b1 shl 8) or (b2 shl 16)
                        if (value and 0x0080_0000 != 0) value = value or -0x0100_0000
                        sum += value / 8_388_608f
                    }
                    destination.append((sum / channels).coerceIn(-1f, 1f))
                }
            }

            AudioFormat.ENCODING_PCM_32BIT -> {
                while (source.remaining() >= channels * Int.SIZE_BYTES) {
                    var sum = 0f
                    repeat(channels) { sum += source.getInt() / 2_147_483_648f }
                    destination.append((sum / channels).coerceIn(-1f, 1f))
                }
            }

            else -> {
                val values = source.asShortBuffer()
                while (values.remaining() >= channels) {
                    var sum = 0f
                    repeat(channels) { sum += values.get() / 32_768f }
                    destination.append((sum / channels).coerceIn(-1f, 1f))
                }
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "recording.wav"
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "sample"
    }

    private fun removeTinyDcOffset(samples: ShortArray) {
        val checkCount = minOf(samples.size, 240_000)
        if (checkCount <= 0) return
        var sum = 0L
        for (index in 0 until checkCount) sum += samples[index].toLong()
        val mean = sum.toFloat() / checkCount
        if (kotlin.math.abs(mean) in 16f..2_621f) {
            for (index in samples.indices) {
                samples[index] = (samples[index] - mean)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
    }

    private fun estimateInitialCapacity(durationUs: Long, sampleRate: Int): Int {
        if (durationUs <= 0L) return 1 shl 18
        val estimated = durationUs / 1_000_000.0 * max(8_000, sampleRate)
        return estimated.toInt().coerceIn(1 shl 16, 4_000_000)
    }

    private fun MediaFormat.intOrDefault(key: String, defaultValue: Int): Int =
        if (containsKey(key)) getInteger(key) else defaultValue

    private companion object {
        const val CODEC_TIMEOUT_US = 10_000L
        const val MAX_IDLE_POLLS = 500
        const val MAX_DURATION_US = 10L * 60L * 1_000_000L
        const val MAX_MONO_FRAMES = 30_000_000
    }
}

internal class Pcm16ArrayBuilder(
    initialCapacity: Int,
    private val maximumSize: Int,
) {
    init {
        require(maximumSize > 0) { "maximumSize must be positive" }
    }

    private var values = ShortArray(initialCapacity.coerceIn(1, maximumSize))
    var size: Int = 0
        private set

    fun append(value: Float) {
        if (size >= maximumSize) {
            error("展開後の音声が大きすぎます。短い範囲に切った音声を使用してください")
        }
        if (size == values.size) {
            val nextSize = minOf(maximumSize, values.size.toLong().times(2L).toInt())
            values = values.copyOf(nextSize)
        }
        val normalized = value.coerceIn(-1f, 1f)
        values[size++] = when {
            normalized <= -1f -> Short.MIN_VALUE
            normalized >= 1f -> Short.MAX_VALUE
            else -> (normalized * Short.MAX_VALUE).roundToInt().toShort()
        }
    }

    fun toArray(): ShortArray = values.copyOf(size)
}
