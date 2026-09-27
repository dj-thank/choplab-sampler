package com.choplab.desktop.audio

import com.choplab.sampler.audio.RecordingBudget
import com.choplab.sampler.audio.RecordingStopReason
import com.choplab.sampler.audio.WavFileWriter
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal data class SystemAudioStreamHeader(val sampleRate: Int, val channels: Int)

internal fun parseSystemAudioHeader(line: String): SystemAudioStreamHeader {
    val error = line.removePrefix("CHOPLAB-ERROR").trim()
    if (line.startsWith("CHOPLAB-ERROR")) {
        if (error.substringBefore(' ') == "NO_DISPLAY") {
            error("システムの音声を録音できません。Macの画面ロックを解除してデスクトップを表示し、もう一度録音を開始してください。外部ディスプレイを使っている場合は接続も確認してください")
        }
        error(
            "システムの音声を録音できません。システム設定の「プライバシーとセキュリティ」で画面収録とシステムオーディオ録音を許可してください" +
                if (error.isEmpty()) "" else "（$error）",
        )
    }
    val parts = line.trim().split(Regex("\\s+"))
    val rate = parts.getOrNull(1)?.toIntOrNull()
    val channels = parts.getOrNull(2)?.toIntOrNull()
    require(parts.firstOrNull() == "CHOPLAB-PCM" && rate != null && rate > 0 && channels != null && channels in 1..2) {
        "録音ツールの応答が不正です"
    }
    return SystemAudioStreamHeader(rate, channels)
}

internal fun locateMacSystemAudioHelper(
    property: String? = System.getProperty("choplab.systemAudioHelper"),
    codeSourceDirectory: File? = runCatching {
        File(MacSystemAudioProcessRecorder::class.java.protectionDomain.codeSource.location.toURI()).parentFile
    }.getOrNull(),
    workingDirectory: File = File(System.getProperty("user.dir")),
): File? = listOfNotNull(
    property?.takeIf(String::isNotBlank)?.let(::File),
    codeSourceDirectory?.resolve("choplab-sck-audio"),
    workingDirectory.resolve("desktop/build/choplab-sck-audio"),
).firstOrNull { it.isFile && it.canExecute() }

/**
 * ScreenCaptureKit helper: header line on stdout, then PCM-16, until stdin closes.
 * [start] waits only briefly for the header, so a pending permission prompt never freezes the
 * window; a helper that never starts, or stops before [stop], is reported by [stop] as a failure.
 */
internal class MacSystemAudioProcessRecorder(
    private val helper: File,
    private val launch: (File) -> Process = { ProcessBuilder(it.absolutePath).start() },
    private val quickStartMillis: Long = QUICK_START_MS,
    private val headerTimeoutMillis: Long = HEADER_TIMEOUT_MS,
) : DesktopAudioRecorder {
    /** Everything one recording owns. A worker left over from a slow stop only touches its own session. */
    private class Session(val process: Process, val file: File) {
        val running = AtomicBoolean(true)
        val header = CompletableFuture<SystemAudioStreamHeader>()
        @Volatile var failure: Throwable? = null
        @Volatile var worker: Thread? = null
    }
    private val lifecycleLock = Any()
    @Volatile private var session: Session? = null

    override val isRecording: Boolean
        get() = session?.running?.get() == true

    override fun start(file: File): Result<Unit> {
        synchronized(lifecycleLock) {
            val previous = session
            if (previous != null && (previous.running.get() || previous.worker?.isAlive == true)) {
                return Result.failure(IllegalStateException("録音の停止処理中です"))
            }
            session = null
        }
        var started: Session? = null
        return runCatching {
            file.parentFile?.mkdirs()
            val process = launch(helper)
            val current = Session(process, file)
            started = current
            drain(process.errorStream)
            val worker = Thread({ record(current) }, "ChopLab-Mac-System-Audio").apply { isDaemon = true }
            current.worker = worker
            synchronized(lifecycleLock) { session = current }
            worker.start()
            // A granted or refused helper answers within about a second. A permission prompt can take
            // much longer: recording continues in the background and stop() reports the outcome.
            try {
                current.header.get(quickStartMillis, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
            } catch (failure: ExecutionException) {
                throw failure.cause ?: failure
            }
            Unit
        }.onFailure {
            started?.let { failed ->
                failed.running.set(false)
                failed.process.destroyForcibly()
                failed.worker?.join(STOP_TIMEOUT_MS)
                synchronized(lifecycleLock) { if (session === failed) session = null }
            }
            runCatching { file.delete() }
        }
    }

    override fun stop(): Result<File> {
        val current = session ?: return Result.failure(IllegalStateException("録音された音声がありません"))
        current.running.set(false)
        runCatching { current.process.outputStream.close() }
        val worker = current.worker
        if (worker != null && worker !== Thread.currentThread()) {
            worker.join(500)
            if (worker.isAlive) current.process.destroyForcibly()
            worker.join(STOP_TIMEOUT_MS)
        }
        val error = current.failure
        return when {
            worker?.isAlive == true -> Result.failure(IllegalStateException("録音の停止に時間がかかっています"))
            error != null -> Result.failure(error)
            !current.header.isDone || current.header.isCompletedExceptionally -> Result.failure(IllegalStateException(NOT_STARTED))
            current.file.isFile && current.file.length() > WAV_HEADER_BYTES -> Result.success(current.file)
            else -> Result.failure(IllegalStateException("録音された音声がありません"))
        }
    }

    override fun close() {
        if (session != null) stop()
    }

    private fun record(current: Session) {
        val input = current.process.inputStream
        try {
            val header = readHeader(current)
            current.header.complete(header)
            val frameBytes = header.channels * Short.SIZE_BYTES
            val budget = RecordingBudget(sampleRate = header.sampleRate, channelCount = header.channels)
            WavFileWriter(current.file, header.sampleRate, header.channels).use { writer ->
                // About 100 ms per write, like the Java Sound recorders. A frame split across two
                // pipe reads is carried to the front of the buffer.
                val buffer = ByteArray((header.sampleRate / 10).coerceAtLeast(1) * frameBytes)
                var filled = 0
                while (current.running.get()) {
                    val read = input.read(buffer, filled, buffer.size - filled)
                    if (read < 0) {
                        // The helper exits before stdin closes only when capture failed.
                        check(!current.running.get()) { STOPPED_EARLY }
                        break
                    }
                    filled += read
                    val complete = filled - filled % frameBytes
                    if (complete == 0) continue
                    val decision = budget.decide(complete, current.file.usableSpace.coerceAtLeast(0L))
                    if (decision.writableBytes > 0) {
                        writer.writePcm16Bytes(buffer, decision.writableBytes)
                        budget.commit(decision.writableBytes)
                    }
                    buffer.copyInto(buffer, 0, complete, filled)
                    filled -= complete
                    when (decision.stopAfterWrite) {
                        RecordingStopReason.DURATION_LIMIT -> {
                            current.running.set(false)
                            break
                        }
                        RecordingStopReason.LOW_DISK -> error("録音用の空き容量が不足しています")
                        null -> Unit
                    }
                }
            }
        } catch (throwable: Throwable) {
            current.header.completeExceptionally(throwable)
            if (current.running.get()) {
                current.failure = throwable
                runCatching { current.file.delete() }
            }
        } finally {
            current.running.set(false)
            current.process.destroyForcibly()
        }
    }

    /** Reads the one-line header on the worker. A helper silent past the deadline is ended. */
    private fun readHeader(current: Session): SystemAudioStreamHeader {
        val watchdog = Thread({
            try {
                Thread.sleep(headerTimeoutMillis)
                if (!current.header.isDone) current.process.destroyForcibly()
            } catch (_: InterruptedException) {
            }
        }, "ChopLab-Mac-System-Audio-Start").apply { isDaemon = true; start() }
        try {
            val input = current.process.inputStream
            val line = ByteArray(240)
            var size = 0
            while (size < line.size) {
                val value = input.read()
                if (value < 0) break
                if (value == '\n'.code) return parseSystemAudioHeader(String(line, 0, size, Charsets.UTF_8))
                line[size++] = value.toByte()
            }
            error(NOT_STARTED)
        } finally {
            watchdog.interrupt()
        }
    }

    private fun drain(stream: InputStream) {
        Thread({
            runCatching { stream.use { it.readBytes() } }
        }, "ChopLab-Mac-System-Audio-Error").apply { isDaemon = true }.start()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 2_000L
        const val WAV_HEADER_BYTES = 44L
        const val QUICK_START_MS = 1_500L
        const val HEADER_TIMEOUT_MS = 60_000L
        const val NOT_STARTED = "システムの音声録音を開始できませんでした。画面収録とシステムオーディオ録音の許可を確認してください"
        const val STOPPED_EARLY = "システムの音声録音が途中で止まりました。画面収録とシステムオーディオ録音の許可を確認して、もう一度録音してください"
    }
}
