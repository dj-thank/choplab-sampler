package com.choplab.desktop.audio

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Native authorization is requested only by an explicit microphone action, never by enumeration/startup. */
internal class MacMicrophonePermission(
    private val helper: () -> File? = ::locateMacSystemAudioHelper,
    private val launch: (File) -> Process = { ProcessBuilder(it.absolutePath, "--microphone-permission").redirectError(ProcessBuilder.Redirect.DISCARD).start() },
    private val timeoutMillis: Long = 60_000,
) {
    enum class Status { AUTHORIZED, DENIED, RESTRICTED, UNKNOWN, CANCELLED, TIMEOUT }
    private val opening = AtomicReference<Process?>()
    private val generation = AtomicLong()
    fun cancel() { generation.incrementAndGet(); opening.getAndSet(null)?.destroyForcibly() }
    fun request(): Status {
        val token = generation.get()
        val executable = helper() ?: return Status.UNKNOWN
        var process: Process? = null
        return try {
            process = launch(executable)
            if (!opening.compareAndSet(null, process)) return Status.UNKNOWN
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
            val line = StringBuilder()
            while (true) {
                if (generation.get() != token) return Status.CANCELLED
                if (System.nanoTime() >= deadline) return Status.TIMEOUT
                if (process.inputStream.available() == 0) {
                    if (!process.isAlive) return Status.UNKNOWN
                    Thread.sleep(10); continue
                }
                val byte = process.inputStream.read()
                if (byte == 10) break
                if (byte < 0 || line.length >= 80) return Status.UNKNOWN
                line.append(byte.toChar())
            }
            parse(line.toString())
        } catch (_: Exception) { if (generation.get() != token) Status.CANCELLED else Status.UNKNOWN }
        finally { process?.let { opening.compareAndSet(it, null); if (it.isAlive) it.destroyForcibly() } }
    }
    companion object {
        internal fun parse(line: String): Status = when (line) {
            "CHOPLAB-MIC AUTHORIZED" -> Status.AUTHORIZED
            "CHOPLAB-MIC DENIED" -> Status.DENIED
            "CHOPLAB-MIC RESTRICTED" -> Status.RESTRICTED
            else -> Status.UNKNOWN
        }
    }
}
