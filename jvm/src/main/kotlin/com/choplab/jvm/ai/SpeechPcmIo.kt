package com.choplab.jvm.ai

import com.choplab.core.ai.*
import com.choplab.jvm.*
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

/** Fixed-window file work. The caller owns its reservation for the entire call. */
internal object SpeechPcmIo {
    const val READ_BYTES = 16_384L
    const val WRITE_BYTES = 16_384L + 4096 * 2 * 4

    suspend fun read(path: Path, memory: PcmMemoryBudget, check: () -> Unit): TtsAudio {
        val info = memory.reserve(READ_BYTES).use {
            Files.newInputStream(path).use { WavCodec.inspect(checked(it, check), TtsLimits.MAX_WAV_BYTES) }
        }
        require(info.frames <= info.sampleRate.toLong() * TtsLimits.MAX_SECONDS)
        val bytes = info.frames * info.channels * 4
        val reserved = memory.reserve(bytes + READ_BYTES)
        try {
            val audio = Files.newInputStream(path).use { WavCodec.read(checked(it, check), TtsLimits.MAX_WAV_BYTES, bytes) }
            require(audio.info == info)
            check()
            val value = TtsAudio.takeOwnership(audio.samples, info.sampleRate, info.channels, reservationOwner = memory, release = reserved::close)
            reserved.shrinkTo(bytes)
            return value
        } catch (failure: Throwable) { reserved.close(); throw failure }
    }

    fun write(output: OutputStream, audio: TtsAudio, check: () -> Unit) {
        val writer = WavCodec.FloatWriter(output, audio.frames.toLong(), audio.sampleRate, audio.channels)
        val window = FloatArray(4096 * audio.channels)
        var frame = 0
        while (frame < audio.frames) {
            check()
            val count = minOf(4096, audio.frames - frame)
            audio.copyInto(window, frame * audio.channels, count * audio.channels)
            writer.write(window, frameCount = count)
            frame += count
        }
        writer.finish()
    }

    fun checked(input: InputStream, check: () -> Unit): InputStream = object : FilterInputStream(input) {
        override fun read(): Int { check(); return `in`.read() }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int { check(); return `in`.read(bytes, offset, length) }
    }
}
