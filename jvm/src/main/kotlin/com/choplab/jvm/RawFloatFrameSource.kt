package com.choplab.jvm

import com.choplab.core.model.ProjectLimits
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Private decoded scratch quota, independent of the immutable encoded asset store. */
object PcmScratchBudget {
    private var used = 0L
    @Synchronized fun reserve(bytes: Long): Closeable {
        require(bytes in 1..ProjectLimits.MAX_TOTAL_BYTES && used + bytes <= ProjectLimits.MAX_TOTAL_BYTES) { "Decoded scratch budget exceeded" }
        used += bytes
        val closed = AtomicBoolean()
        return Closeable { if (closed.compareAndSet(false, true)) synchronized(this) { used -= bytes } }
    }
}

/** Native-rate float32 file, retaining headroom and channel order. No resampling/quantization here. */
class RawFloatFrameSource(private val path: Path, override val info: WavInfo,
                          private val release: () -> Unit = {}) : PcmFrameSource {
    private val closed = AtomicBoolean()
    init { require(info.floatingPoint && info.bits == 32 && Files.size(path) == info.frames * info.channels * 4) }
    override fun read(first: Int, count: Int, cancelled: () -> Boolean): FloatArray {
        check(!closed.get())
        require(first >= 0 && count > 0 && count <= 262_144 && first.toLong() + count <= info.frames)
        val output = FloatArray(count * info.channels)
        FileChannel.open(path, StandardOpenOption.READ).use { channel ->
            channel.position(first.toLong() * info.channels * 4)
            val buffer = ByteBuffer.allocate(minOf(64 * 1024, output.size * 4)).order(ByteOrder.LITTLE_ENDIAN)
            var at = 0
            while (at < output.size) {
                if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("PCM read cancelled")
                buffer.clear(); buffer.limit(minOf(buffer.capacity(), (output.size - at) * 4))
                while (buffer.hasRemaining()) require(channel.read(buffer) > 0) { "Truncated decoded audio" }
                buffer.flip()
                while (buffer.hasRemaining()) {
                    val value = buffer.float
                    require(value.isFinite()) { "Non-finite audio" }
                    output[at++] = value
                }
            }
        }
        return output
    }
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}
