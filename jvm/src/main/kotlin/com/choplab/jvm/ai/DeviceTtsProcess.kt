package com.choplab.jvm.ai

import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class SpeechProcessResult(val exitCode: Int, val output: ByteArray)
interface SpeechProcessRunner {
    suspend fun run(arguments: List<String>, directory: Path): SpeechProcessResult
    fun close()
}

/** Argument-vector execution only. Text goes in private temporary files, never shell code or logs. */
class DeviceTtsProcess : SpeechProcessRunner {
    private val closed = AtomicBoolean()
    private val active = ConcurrentHashMap.newKeySet<Process>()
    override suspend fun run(arguments: List<String>, directory: Path): SpeechProcessResult = withContext(Dispatchers.IO) {
        check(!closed.get())
        val process = ProcessBuilder(arguments).directory(directory.toFile()).redirectErrorStream(true).start()
        active += process
        try {
            if (closed.get()) throw CancellationException("Speech process closed")
            process.outputStream.close()
            withTimeout(60_000) {
                coroutineScope {
                    val output = async(Dispatchers.IO) {
                        process.inputStream.use { input ->
                            val bytes = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                            while (true) {
                                ensureActive(); val count = input.read(buffer); if (count < 0) break
                                require(bytes.size() + count <= 65_536) { "Speech process output limit" }
                                bytes.write(buffer, 0, count)
                            }
                            bytes.toByteArray()
                        }
                    }
                    while (!process.waitFor(20, TimeUnit.MILLISECONDS)) { ensureActive(); if (closed.get()) throw CancellationException("Speech process closed") }
                    SpeechProcessResult(process.exitValue(), output.await())
                }
            }
        } finally {
            active -= process
            if (process.isAlive) process.destroyForcibly()
            process.inputStream.close(); process.errorStream.close()
        }
    }
    override fun close() { if (closed.compareAndSet(false, true)) active.forEach { it.destroyForcibly() } }
}
