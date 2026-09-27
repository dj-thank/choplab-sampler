package com.choplab.sampler.next

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.choplab.core.model.ProjectLimits
import com.choplab.jvm.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** MediaCodec output is streamed at its native rate/channels into bounded private float scratch. */
class AndroidOriginalAudioDecoder(private val directory: Path) : OriginalAudioDecoder {
    private val gate = ReentrantLock()
    private val metadata = object : LinkedHashMap<String, WavInfo>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WavInfo>?) = size > 256
    }
    private var handoff: Pair<String, PcmFrameSource>? = null
    init { Files.createDirectories(directory); require(!Files.isSymbolicLink(directory)) }

    override fun inspect(path: Path, hash: String, cancelled: () -> Boolean): WavInfo = locked(cancelled) {
        metadata[hash] ?: decodeFile(path, cancelled).also {
            handoff?.second?.close(); handoff = hash to it; metadata[hash] = it.info
        }.info
    }
    override fun openPcm(path: Path, hash: String, cancelled: () -> Boolean): PcmFrameSource = locked(cancelled) {
        handoff?.takeIf { it.first == hash }?.second?.also { handoff = null }
            ?: decodeFile(path, cancelled).also { metadata[hash] = it.info }
    }
    override fun decode(path: Path, hash: String, cancelled: () -> Boolean): WavAudio = openPcm(path, hash, cancelled).use { input ->
        require(input.info.frames * maxOf(2, input.info.channels) * 4 <= 16L * 1024 * 1024) { "Long PCM needs paged decoding" }
        val samples = FloatArray((input.info.frames * input.info.channels).toInt())
        var first = 0
        while (first < input.info.frames) {
            val count = minOf(4096L, input.info.frames - first).toInt()
            input.read(first, count, cancelled).copyInto(samples, first * input.info.channels); first += count
        }
        WavAudio(input.info, samples)
    }
    override fun close() { gate.lock(); try { handoff?.second?.close(); handoff = null; metadata.clear() } finally { gate.unlock() } }
    private fun <T> locked(cancelled: () -> Boolean, block: () -> T): T {
        while (!gate.tryLock(25, TimeUnit.MILLISECONDS)) checkCancellation(cancelled)
        try { checkCancellation(cancelled); return block() } finally { gate.unlock() }
    }
    private fun checkCancellation(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw java.util.concurrent.CancellationException("Audio decode cancelled")
    }
    private fun decodeFile(path: Path, cancelled: () -> Boolean): PcmFrameSource {
        require(Files.isRegularFile(path) && Files.size(path) in 1..ProjectLimits.MAX_ASSET_BYTES)
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        var writer: NativeFloatScratch? = null
        try {
            extractor.setDataSource(path.toString())
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("No audio stream")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            require(rate in 8_000..192_000 && channels in 1..2)
            // Request float. If the codec reports integer PCM, preserve every native value when converting.
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
            val decoder = MediaCodec.createDecoderByType(mime).also { codec = it }
            decoder.configure(format, null, null, 0); decoder.start()
            var encoding = NativePcmEncoding.SIGNED16
            var inputEnded = false
            var outputEnded = false
            var outputFormat: Triple<Int, Int, NativePcmEncoding>? = null
            val info = MediaCodec.BufferInfo()
            val began = System.nanoTime()
            var progressed = began
            while (!outputEnded) {
                checkCancellation(cancelled)
                val now = System.nanoTime()
                check(now - began < 120_000_000_000L && now - progressed < 10_000_000_000L) { "Audio decoder timed out" }
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(decoder.getInputBuffer(index)); buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                        progressed = now
                    }
                }
                when (val index = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val actual = decoder.outputFormat
                        rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        require(rate in 8_000..192_000 && channels in 1..2)
                        val rawEncoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) actual.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                        encoding = when (rawEncoding) {
                            AudioFormat.ENCODING_PCM_FLOAT -> NativePcmEncoding.FLOAT32
                            AudioFormat.ENCODING_PCM_32BIT -> NativePcmEncoding.SIGNED32
                            AudioFormat.ENCODING_PCM_24BIT_PACKED -> NativePcmEncoding.SIGNED24
                            AudioFormat.ENCODING_PCM_16BIT -> NativePcmEncoding.SIGNED16
                            AudioFormat.ENCODING_PCM_8BIT -> NativePcmEncoding.UNSIGNED8
                            else -> error("Unsupported PCM encoding")
                        }
                        val updated = Triple(rate, channels, encoding)
                        require(outputFormat == null || outputFormat == updated) { "Decoded PCM format changed" }
                        outputFormat = updated
                        progressed = now
                    }
                    else -> if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val buffer = requireNotNull(decoder.getOutputBuffer(index))
                                buffer.position(info.offset); buffer.limit(info.offset + info.size)
                                val target = writer ?: NativeFloatScratch(directory, rate, channels).also {
                                    writer = it; outputFormat = Triple(rate, channels, encoding)
                                }
                                target.write(buffer, encoding, cancelled)
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            progressed = now
                        } finally { decoder.releaseOutputBuffer(index, false) }
                    }
                }
            }
            checkCancellation(cancelled)
            return requireNotNull(writer) { "Empty audio stream" }.finish()
        } finally {
            writer?.close()
            try { codec?.stop() } catch (_: Exception) { }
            try { codec?.release() } finally { extractor.release() }
        }
    }
}
