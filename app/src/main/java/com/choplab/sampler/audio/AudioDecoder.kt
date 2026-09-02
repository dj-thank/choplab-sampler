package com.choplab.sampler.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.provider.OpenableColumns
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.persistableAudioDisplayName
import com.choplab.sampler.model.retainedChannelCountForImport
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

internal data class DecodedAudioFormat(
    val sampleRate: Int,
    val channelCount: Int,
)

internal fun validateDecodedAudioFormat(sampleRate: Int, channelCount: Int): DecodedAudioFormat {
    require(sampleRate in 8_000..192_000) {
        "対応できないサンプルレートです: $sampleRate Hz"
    }
    require(channelCount in 1..8) {
        "対応できないチャンネル数です: $channelCount"
    }
    return DecodedAudioFormat(sampleRate, channelCount)
}

internal fun validateStableDecodedPcmFormat(
    storedChannelCount: Int?,
    storedSampleRate: Int?,
    decodedFormat: DecodedAudioFormat,
) {
    check((storedChannelCount == null) == (storedSampleRate == null)) {
        "Decoded PCM format initialization is incomplete"
    }
    if (storedChannelCount == null) return
    check(storedChannelCount == retainedChannelCountForImport(decodedFormat.channelCount)) {
        "デコード中に音声チャンネル構成が変わりました"
    }
    check(storedSampleRate == decodedFormat.sampleRate) {
        "デコード中にサンプルレートが変わりました"
    }
}

internal fun validateDecodedPcmEncoding(decodedEncoding: Int): Int {
    require(
        decodedEncoding == AudioFormat.ENCODING_PCM_8BIT ||
            decodedEncoding == AudioFormat.ENCODING_PCM_16BIT ||
            decodedEncoding == AudioFormat.ENCODING_PCM_24BIT_PACKED ||
            decodedEncoding == AudioFormat.ENCODING_PCM_32BIT ||
            decodedEncoding == AudioFormat.ENCODING_PCM_FLOAT,
    ) {
        "対応できないPCM形式です: $decodedEncoding"
    }
    return decodedEncoding
}

internal fun validateStableDecodedPcmEncoding(storedEncoding: Int?, decodedEncoding: Int): Int {
    val validatedEncoding = validateDecodedPcmEncoding(decodedEncoding)
    check(storedEncoding == null || storedEncoding == decodedEncoding) {
        "デコード中にPCM形式が変わりました"
    }
    return validatedEncoding
}

internal fun appendDecodedPcm(
    source: ByteBuffer,
    encoding: Int,
    sourceChannelCount: Int,
    destination: Pcm16ArrayBuilder,
) {
    val channels = sourceChannelCount
    validateDecodedPcmEncoding(encoding)
    check(channels in 1..8) { "Decoded channel count was not validated" }
    check(destination.channelCount == retainedChannelCountForImport(channels)) {
        "Decoded channel layout changed after PCM output started"
    }

    fun appendFrame(readSample: () -> Float) {
        if (destination.channelCount == 2) {
            destination.append(readSample())
            destination.append(readSample())
        } else {
            var sum = 0f
            repeat(channels) { sum += readSample() }
            destination.append((sum / channels).coerceIn(-1f, 1f))
        }
    }

    val bytesPerSample = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> Int.SIZE_BYTES
        AudioFormat.ENCODING_PCM_8BIT -> Byte.SIZE_BYTES
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        else -> Short.SIZE_BYTES
    }
    val frameBytes = Math.multiplyExact(channels, bytesPerSample)
    require(source.remaining() % frameBytes == 0) { "音声データに不完全なPCMフレームがあります" }

    when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> {
            val values = source.asFloatBuffer()
            while (values.remaining() >= channels) {
                appendFrame { values.get().coerceIn(-1f, 1f) }
            }
        }

        AudioFormat.ENCODING_PCM_8BIT -> {
            while (source.remaining() >= channels) {
                appendFrame {
                    val unsigned = source.get().toInt() and 0xFF
                    ((unsigned - 128) / 128f).coerceIn(-1f, 1f)
                }
            }
        }

        AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
            while (source.remaining() >= frameBytes) {
                appendFrame {
                    val b0 = source.get().toInt() and 0xFF
                    val b1 = source.get().toInt() and 0xFF
                    val b2 = source.get().toInt() and 0xFF
                    var value = b0 or (b1 shl 8) or (b2 shl 16)
                    if (value and 0x0080_0000 != 0) value = value or -0x0100_0000
                    (value / 8_388_608f).coerceIn(-1f, 1f)
                }
            }
        }

        AudioFormat.ENCODING_PCM_32BIT -> {
            while (source.remaining() >= frameBytes) {
                appendFrame { (source.getInt() / 2_147_483_648f).coerceIn(-1f, 1f) }
            }
        }

        else -> {
            val values = source.asShortBuffer()
            while (values.remaining() >= channels) {
                appendFrame { (values.get() / 32_768f).coerceIn(-1f, 1f) }
            }
        }
    }
}

class AudioDecoder(private val context: Context) {
    suspend fun decode(uri: Uri): PcmAudio = withContext(Dispatchers.IO) {
        decodeBlocking(uri)
    }

    private fun decodeBlocking(uri: Uri): PcmAudio {
        validateInputSize(uri)
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
            if (durationUs > AudioResourceLimits.MAX_IMPORT_DURATION_SECONDS * 1_000_000L) {
                error("音声が長すぎます。MVP版では10分以内の音声を使用してください")
            }

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            var outputSampleRate = inputFormat.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, 48_000)
            var outputChannels = inputFormat.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, 1)
            validateDecodedAudioFormat(outputSampleRate, outputChannels).also { format ->
                outputSampleRate = format.sampleRate
                outputChannels = format.channelCount
            }
            var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
            var output: Pcm16ArrayBuilder? = null
            var pcmSampleRate: Int? = null
            var outputPcmEncoding: Int? = null
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
                        val decodedFormat = validateDecodedAudioFormat(
                            sampleRate = format.intOrDefault(MediaFormat.KEY_SAMPLE_RATE, outputSampleRate),
                            channelCount = format.intOrDefault(MediaFormat.KEY_CHANNEL_COUNT, outputChannels),
                        )
                        validateStableDecodedPcmFormat(
                            storedChannelCount = output?.channelCount,
                            storedSampleRate = pcmSampleRate,
                            decodedFormat = decodedFormat,
                        )
                        outputSampleRate = decodedFormat.sampleRate
                        outputChannels = decodedFormat.channelCount
                        pcmEncoding = validateStableDecodedPcmEncoding(
                            storedEncoding = outputPcmEncoding,
                            decodedEncoding = format.intOrDefault(
                                MediaFormat.KEY_PCM_ENCODING,
                                AudioFormat.ENCODING_PCM_16BIT,
                            ),
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

                                val storedChannelCount = retainedChannelCountForImport(outputChannels)
                                val destination = output ?: Pcm16ArrayBuilder(
                                    initialFrameCapacity = estimateInitialCapacity(durationUs, outputSampleRate),
                                    maximumFrames = AudioResourceLimits.maxDecodedMonoFrames(outputSampleRate),
                                    channelCount = storedChannelCount,
                                ).also {
                                    output = it
                                    pcmSampleRate = outputSampleRate
                                    outputPcmEncoding = pcmEncoding
                                }
                                validateStableDecodedPcmFormat(
                                    storedChannelCount = destination.channelCount,
                                    storedSampleRate = pcmSampleRate,
                                    decodedFormat = DecodedAudioFormat(outputSampleRate, outputChannels),
                                )
                                appendDecodedPcm(
                                    source = frameBuffer,
                                    encoding = pcmEncoding,
                                    sourceChannelCount = outputChannels,
                                    destination = destination,
                                )
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            val destination = output ?: error("音声データを展開できませんでした")
            val samples = destination.toArray()
            if (samples.isEmpty()) error("音声データを展開できませんでした")
            AudioResourceLimits.requireDecodedMonoFrameCount(
                frameCount = destination.frameCount.toLong(),
                sampleRate = outputSampleRate,
            )

            removeTinyDcOffsetWithoutClipping(samples, destination.channelCount)
            PcmAudio(
                name = resolveDisplayName(uri),
                samples = samples,
                sampleRate = outputSampleRate,
                channelCount = destination.channelCount,
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun validateInputSize(uri: Uri) {
        val sizeBytes = when (uri.scheme) {
            "file" -> uri.path?.let(::File)?.takeIf(File::isFile)?.length()
            else -> runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else {
                        null
                    }
                }
            }.getOrNull()
        }
        AudioResourceLimits.requireImportFileSize(sizeBytes)
    }

    private fun resolveDisplayName(uri: Uri): String {
        val fallbackName = uri.lastPathSegment
        if (uri.scheme == "file") {
            return persistableAudioDisplayName(fallbackName, "recording.wav")
        }
        val providerName = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return persistableAudioDisplayName(providerName, fallbackName)
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
    }
}

internal fun removeTinyDcOffsetWithoutClipping(samples: ShortArray, channelCount: Int) {
    require(channelCount in 1..2 && samples.size % channelCount == 0) {
        "PCM must contain complete mono or stereo frames"
    }
    val frameCount = samples.size / channelCount
    if (frameCount <= 0) return
    repeat(channelCount) { channel ->
        val probeFrames = minOf(frameCount, 240_000)
        var probeSum = 0L
        for (frame in 0 until probeFrames) {
            probeSum += samples[frame * channelCount + channel].toLong()
        }
        val probeOffset = (probeSum.toDouble() / probeFrames).roundToInt()
        if (kotlin.math.abs(probeOffset) !in 16..2_621) return@repeat

        var sum = 0L
        var minimum = Short.MAX_VALUE.toInt()
        var maximum = Short.MIN_VALUE.toInt()
        for (frame in 0 until frameCount) {
            val sample = samples[frame * channelCount + channel].toInt()
            sum += sample.toLong()
            if (sample < minimum) minimum = sample
            if (sample > maximum) maximum = sample
        }
        val requestedOffset = (sum.toDouble() / frameCount).roundToInt()
        if (kotlin.math.abs(requestedOffset) !in 16..2_621) return@repeat

        val safeOffset = requestedOffset.coerceIn(
            minimumValue = maximum - Short.MAX_VALUE.toInt(),
            maximumValue = minimum - Short.MIN_VALUE.toInt(),
        )
        if (kotlin.math.abs(safeOffset) !in 16..2_621) return@repeat
        for (frame in 0 until frameCount) {
            val index = frame * channelCount + channel
            samples[index] = (samples[index].toInt() - safeOffset).toShort()
        }
    }
}

internal class Pcm16ArrayBuilder(
    initialFrameCapacity: Int,
    maximumFrames: Int,
    val channelCount: Int,
) {
    private var maximumSize: Int

    constructor(initialCapacity: Int, maximumSize: Int) : this(
        initialFrameCapacity = initialCapacity,
        maximumFrames = maximumSize,
        channelCount = 1,
    )

    init {
        require(maximumFrames > 0) { "maximumFrames must be positive" }
        require(channelCount in 1..2) { "channelCount must be mono or stereo" }
        maximumSize = Math.multiplyExact(maximumFrames, channelCount)
    }

    private var values = ShortArray(
        Math.multiplyExact(initialFrameCapacity.coerceAtLeast(1), channelCount)
            .coerceAtMost(maximumSize),
    )
    var size: Int = 0
        private set
    val frameCount: Int
        get() = size / channelCount

    fun updateMaximumSize(maximumSize: Int) {
        require(maximumSize > 0) { "maximumSize must be positive" }
        check(size <= maximumSize) {
            "展開後の音声が大きすぎます。10分以内の音声を使用してください"
        }
        this.maximumSize = maximumSize
    }

    fun append(value: Float) {
        if (size >= maximumSize) {
            error("展開後の音声が大きすぎます。短い範囲に切った音声を使用してください")
        }
        if (size == values.size) {
            val nextSize = minOf(maximumSize.toLong(), values.size.toLong() * 2L).toInt()
            values = values.copyOf(nextSize)
        }
        require(value.isFinite()) { "デコード音声に非有限PCMサンプルが含まれています" }
        val normalized = value.coerceIn(-1f, 1f)
        values[size++] = when {
            normalized <= -1f -> Short.MIN_VALUE
            normalized >= 1f -> Short.MAX_VALUE
            else -> (normalized * Short.MAX_VALUE).roundToInt().toShort()
        }
    }

    fun toArray(): ShortArray {
        check(size % channelCount == 0) { "音声データに不完全なPCMフレームがあります" }
        return values.copyOf(size)
    }
}
