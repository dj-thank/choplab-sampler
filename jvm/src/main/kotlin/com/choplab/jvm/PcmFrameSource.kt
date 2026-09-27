package com.choplab.jvm

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/** Worker-only random access at original rate/channels. Never supplied as an engine callback. */
interface PcmFrameSource : Closeable {
    val info: WavInfo
    fun read(firstFrame: Int, frameCount: Int, cancelled: () -> Boolean = { false }): FloatArray
    override fun close() = Unit
}

/** Verified WAV samples; opens only the requested byte window and preserves float bits/stereo identity. */
class WavFrameSource private constructor(private val path: Path, override val info: WavInfo,
    private val dataOffset: Long) : PcmFrameSource {
    override fun read(firstFrame: Int, frameCount: Int, cancelled: () -> Boolean): FloatArray {
        require(firstFrame >= 0 && frameCount > 0 && firstFrame.toLong() + frameCount <= info.frames)
        require(frameCount <= 262_144) { "A decoder window must stay bounded" }
        val sampleBytes = info.bits / 8
        val output = FloatArray(frameCount * info.channels)
        val bytes = ByteArray(32 * 1024)
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            var position = dataOffset + firstFrame.toLong() * info.channels * sampleBytes
            var at = 0
            while (at < output.size) {
                if (cancelled()) throw java.util.concurrent.CancellationException("PCM read cancelled")
                val count = minOf(bytes.size / sampleBytes, output.size - at)
                val buffer = ByteBuffer.wrap(bytes, 0, count * sampleBytes)
                while (buffer.hasRemaining()) {
                    val size = channel.read(buffer, position)
                    require(size > 0) { "Truncated PCM source" }
                    position += size
                }
                repeat(count) { sample ->
                    val offset = sample * sampleBytes
                    output[at + sample] = when {
                        info.floatingPoint -> Float.fromBits(bytes.u32(offset).toInt())
                        info.bits == 16 -> bytes.u16(offset).toShort().toFloat() / 32768f
                        else -> ((bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8) or
                            (bytes[offset + 2].toInt() shl 16)).toFloat() / 8388608f
                    }.also { require(it.isFinite()) { "Non-finite PCM source" } }
                }
                at += count
            }
        }
        return output
    }

    companion object {
        fun open(path: Path, cancelled: () -> Boolean = { false }): WavFrameSource {
            val info = Files.newInputStream(path).use { source ->
                WavCodec.inspect(object : java.io.FilterInputStream(source) {
                    override fun read(bytes: ByteArray, offset: Int, count: Int): Int {
                        if (cancelled()) throw java.util.concurrent.CancellationException("PCM inspection cancelled")
                        return super.read(bytes, offset, count)
                    }
                })
            }
            FileChannel.open(path, StandardOpenOption.READ).use { channel ->
                var position = 12L
                val chunk = ByteArray(8)
                while (position < channel.size()) {
                    val buffer = ByteBuffer.wrap(chunk)
                    while (buffer.hasRemaining()) {
                        val size = channel.read(buffer, position + buffer.position())
                        require(size > 0)
                    }
                    val bytes = chunk.u32(4)
                    if (chunk.ascii(0, 4) == "data") return WavFrameSource(path, info, position + 8)
                    position += 8 + bytes + (bytes and 1)
                }
            }
            error("Missing PCM data")
        }
    }
}

internal class MemoryFrameSource(private val audio: WavAudio) : PcmFrameSource {
    override val info get() = audio.info
    override fun read(firstFrame: Int, frameCount: Int, cancelled: () -> Boolean): FloatArray {
        if (cancelled()) throw java.util.concurrent.CancellationException("PCM read cancelled")
        require(firstFrame >= 0 && frameCount > 0 && firstFrame.toLong() + frameCount <= info.frames)
        return audio.samples.copyOfRange(firstFrame * info.channels, (firstFrame + frameCount) * info.channels)
    }
}
