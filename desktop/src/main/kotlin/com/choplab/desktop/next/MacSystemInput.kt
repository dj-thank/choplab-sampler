package com.choplab.desktop.next

import com.choplab.desktop.audio.locateMacSystemAudioHelper
import com.choplab.jvm.MicInput
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Owns only the helper being opened. Once returned, the recording thread owns its input. */
internal class MacSystemInput(
    private val helper: () -> File? = { locateMacSystemAudioHelper() },
    private val launch: (File) -> Process = { ProcessBuilder(it.absolutePath, "--float32").redirectError(ProcessBuilder.Redirect.DISCARD).start() },
    private val headerTimeoutMillis: Long = 60_000,
    private val idleTimeoutMillis: Long = 5_000,
) : AutoCloseable {
    enum class Failure { NONE, UNAVAILABLE, DENIED, NO_DISPLAY, TIMEOUT, CANCELLED, INVALID }
    @Volatile var failure = Failure.NONE
        private set
    @Volatile private var closed = false
    private val generation = AtomicLong()
    private val opening = AtomicReference<Process?>()

    fun cancelOpening() { generation.incrementAndGet(); opening.getAndSet(null)?.destroyForcibly() }
    override fun close() { closed = true; cancelOpening() }

    /** Called off the UI thread. Permission waiting is bounded and cancellable from another thread. */
    fun open(): MicInput? {
        if (closed) { failure = Failure.CANCELLED; return null }
        val token = generation.get()
        val executable = helper() ?: run { failure = Failure.UNAVAILABLE; return null }
        failure = Failure.NONE
        var process: Process? = null
        var transferred = false
        try {
            process = launch(executable)
            check(opening.compareAndSet(null, process)) { "Another capture is opening" }
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(headerTimeoutMillis)
            val line = StringBuilder()
            while (true) {
                if (closed || generation.get() != token) { failure = Failure.CANCELLED; return null }
                if (System.nanoTime() >= deadline) { failure = Failure.TIMEOUT; return null }
                if (process.inputStream.available() == 0) {
                    if (!process.isAlive) { failure = Failure.INVALID; return null }
                    Thread.sleep(10); continue
                }
                val byte = process.inputStream.read()
                if (byte == 10) break
                if (byte < 0 || line.length >= 1024) { failure = Failure.INVALID; return null }
                line.append(byte.toChar())
            }
            if (line.startsWith("CHOPLAB-ERROR")) {
                failure = when (line.toString().removePrefix("CHOPLAB-ERROR ").substringBefore(' ')) {
                    "NO_DISPLAY" -> Failure.NO_DISPLAY
                    "DENIED" -> Failure.DENIED
                    else -> Failure.UNAVAILABLE
                }
                return null
            }
            if (line.toString() != "CHOPLAB-FLOAT32 48000 2") { failure = Failure.INVALID; return null }
            if (closed || generation.get() != token) { failure = Failure.CANCELLED; return null }
            transferred = true
            return Input(process, idleTimeoutMillis)
        } catch (_: Exception) {
            failure = if (closed || generation.get() != token) Failure.CANCELLED else Failure.UNAVAILABLE
            return null
        } finally {
            process?.let { opening.compareAndSet(it, null); if (!transferred) terminate(it) }
        }
    }

    /** Strict stereo frames: partial pipe reads never swap channels or turn an incomplete frame into sound. */
    private class Input(private val process: Process, private val idleTimeoutMillis: Long) : MicInput {
        override val sampleRate = 48_000
        override val channels = 2
        @Volatile private var stopped = false
        private val bytes = ByteArray(8192)
        private var remaining = 0
        override fun read(buffer: FloatArray): Int {
            val limit = minOf(bytes.size, buffer.size / 2 * 8)
            require(limit >= 8)
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(idleTimeoutMillis)
            while (!stopped) {
                val available = process.inputStream.available()
                if (available > 0) {
                    val count = process.inputStream.read(bytes, remaining, minOf(available, limit - remaining))
                    if (count < 0) break
                    remaining += count
                    val samples = remaining / 8 * 2
                    if (samples > 0) {
                        for (i in 0 until samples) {
                            val at = i * 4
                            val bits = (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or
                                ((bytes[at + 2].toInt() and 255) shl 16) or (bytes[at + 3].toInt() shl 24)
                            buffer[i] = Float.fromBits(bits).also { require(it.isFinite()) }
                        }
                        val consumed = samples * 4
                        bytes.copyInto(bytes, 0, consumed, remaining)
                        remaining -= consumed
                        return samples
                    }
                } else if (!process.isAlive) break
                if (System.nanoTime() >= deadline) error("System capture stopped delivering audio")
                Thread.sleep(5)
            }
            return -1
        }
        override fun stop() { stopped = true; terminate(process) }
        override fun close() { stopped = true; terminate(process) }
    }

    private companion object {
        fun terminate(process: Process) {
            try { process.outputStream.close() } catch (_: Exception) { }
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) process.destroyForcibly()
        }
    }
}
