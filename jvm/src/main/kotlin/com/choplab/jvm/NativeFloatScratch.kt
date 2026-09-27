package com.choplab.jvm

import com.choplab.core.model.ProjectLimits
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/** Formats actually supplied by a host decoder. Never downmixes, clips headroom or quantizes to PCM16. */
enum class NativePcmEncoding(val bytes: Int) { FLOAT32(4), SIGNED32(4), SIGNED24(3), SIGNED16(2), UNSIGNED8(1) }

class NativeFloatScratch(directory: Path, private val rate: Int, private val channels: Int) : Closeable {
    init { require(rate in 8_000..192_000 && channels in 1..2) }
    private val memory = kotlinx.coroutines.runBlocking { PcmMemoryBudget.shared.reserve(128 * 1024L) }
    private val quota = try { PcmScratchBudget.reserve(ProjectLimits.MAX_FRAMES * channels * 4) }
        catch (failure: Throwable) { memory.close(); throw failure }
    private val path = try { Files.createTempFile(directory, "decoded-", ".f32") } catch (failure: Throwable) { quota.close(); memory.close(); throw failure }
    private var output: java.io.OutputStream? = try { Files.newOutputStream(path).buffered(64 * 1024) } catch (failure: Throwable) {
        Files.deleteIfExists(path); quota.close(); memory.close(); throw failure
    }
    private var bytes: ByteBuffer? = ByteBuffer.allocate(64 * 1024).order(ByteOrder.LITTLE_ENDIAN)
    private var frames = 0L
    private var transferred = false
    private var closed = false
    fun write(input: ByteBuffer, encoding: NativePcmEncoding, cancelled: () -> Boolean) {
        check(!closed && !transferred)
        val bytes = checkNotNull(bytes)
        val output = checkNotNull(output)
        require(input.remaining() % (channels * encoding.bytes) == 0)
        val count = input.remaining() / (channels * encoding.bytes)
        require(frames + count <= ProjectLimits.MAX_FRAMES) { "Audio exceeds source frame limit" }
        input.order(ByteOrder.LITTLE_ENDIAN)
        while (input.hasRemaining()) {
            if (cancelled()) throw java.util.concurrent.CancellationException("Audio decode cancelled")
            bytes.clear()
            val samples = minOf(bytes.capacity() / 4, input.remaining() / encoding.bytes)
            repeat(samples) {
                val sample = when (encoding) {
                    NativePcmEncoding.FLOAT32 -> input.float
                    NativePcmEncoding.SIGNED32 -> (input.int.toDouble() / 2147483648.0).toFloat()
                    NativePcmEncoding.SIGNED24 -> ((input.get().toInt() and 255) or ((input.get().toInt() and 255) shl 8) or (input.get().toInt() shl 16)) / 8388608f
                    NativePcmEncoding.SIGNED16 -> input.short / 32768f
                    NativePcmEncoding.UNSIGNED8 -> ((input.get().toInt() and 255) - 128) / 128f
                }
                require(sample.isFinite()) { "Non-finite decoded audio" }
                bytes.putFloat(sample)
            }
            output.write(bytes.array(), 0, bytes.position())
        }
        frames += count
    }
    fun finish(): PcmFrameSource {
        check(!closed && !transferred)
        require(frames > 0) { "Empty decoded audio" }
        output?.close(); output = null
        bytes = null; memory.close()
        val result = RawFloatFrameSource(path, WavInfo(rate, channels, frames, 32, true)) {
            try { Files.deleteIfExists(path) } finally { quota.close() }
        }
        transferred = true
        return result
    }
    override fun close() {
        if (closed) return
        closed = true
        try { if (!transferred) try { output?.close() } finally { try { Files.deleteIfExists(path) } finally { quota.close() } } }
        finally { output = null; bytes = null; memory.close() }
    }
}
