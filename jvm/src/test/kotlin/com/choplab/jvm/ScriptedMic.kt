package com.choplab.jvm

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Hands out scripted sound as a device would: in real time, at most one buffer's worth per read; stop ends waiting reads. */
internal class ScriptedMic(override val sampleRate: Int = 48_000) : MicInput {
    val buffers = LinkedBlockingQueue<FloatArray>()
    @Volatile var stopped = false
    /** Released, and by which thread. */
    @Volatile var closedBy: Thread? = null
    @Volatile private var rest = FloatArray(0)
    override fun read(buffer: FloatArray): Int {
        check(closedBy == null) { "Read after release" }
        while (!stopped) {
            if (rest.isEmpty()) rest = buffers.poll(5, TimeUnit.MILLISECONDS) ?: continue
            val count = minOf(rest.size, buffer.size)
            rest.copyInto(buffer, 0, 0, count)
            rest = rest.copyOfRange(count, rest.size)
            // A read returns once its frames have been captured.
            Thread.sleep(count * 1_000L / sampleRate)
            return count
        }
        return -1
    }
    override fun stop() { stopped = true }
    override fun close() { closedBy = Thread.currentThread() }
    val drained: Boolean get() = buffers.isEmpty() && rest.isEmpty()
}

internal fun withinSeconds(seconds: Int, condition: () -> Boolean) {
    val deadline = System.nanoTime() + seconds * 1_000_000_000L
    while (!condition() && System.nanoTime() < deadline) Thread.sleep(5)
}
