package com.choplab.desktop.audio

import com.choplab.sampler.audio.RecordingBudget
import com.choplab.sampler.audio.RecordingStopReason
import com.choplab.sampler.audio.WavFileWriter
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

internal data class SystemAudioStreamHeader(val sampleRate: Int, val channels: Int)

internal fun parseSystemAudioHeader(line: String): SystemAudioStreamHeader {
    val error = line.removePrefix("CHOPLAB-ERROR").trim()
    if (line.startsWith("CHOPLAB-ERROR")) {
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

/** ScreenCaptureKit helper: header line on stdout, then PCM-16, until stdin closes. */
internal class MacSystemAudioProcessRecorder(
    private val helper: File,
) : DesktopAudioRecorder {
    private val running = AtomicBoolean(false)
    @Volatile private var process: Process? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var outputFile: File? = null
    @Volatile private var failure: Throwable? = null

    override val isRecording: Boolean
        get() = running.get()

    override fun start(file: File): Result<Unit> = runCatching {
        check(!running.get()) { "録音の停止処理中です" }
        file.parentFile?.mkdirs()
        val started = ProcessBuilder(helper.absolutePath).start()
        process = started
        val header = try {
            readHeaderLine(started.inputStream)
        } catch (error: Throwable) {
            started.destroyForcibly()
            throw error
        }
        drain(started.errorStream)
        outputFile = file
        failure = null
        val recordingWorker = Thread({ record(started, header, file) }, "ChopLab-Mac-System-Audio").apply { isDaemon = true }
        running.set(true)
        worker = recordingWorker
        recordingWorker.start()
    }.onFailure {
        running.set(false)
        process?.destroyForcibly()
        process = null
        runCatching { file.delete() }
    }

    override fun stop(): Result<File> {
        running.set(false)
        runCatching { process?.outputStream?.close() }
        val active = worker
        if (active != null && active !== Thread.currentThread()) {
            active.join(500)
            if (active.isAlive) process?.destroyForcibly()
            active.join(STOP_TIMEOUT_MS)
        }
        val timedOut = active?.isAlive == true
        val file = outputFile
        val error = failure
        process = null
        worker = null
        return when {
            timedOut -> Result.failure(IllegalStateException("録音の停止に時間がかかっています"))
            error != null -> Result.failure(error)
            file != null && file.isFile && file.length() > WAV_HEADER_BYTES -> Result.success(file)
            else -> Result.failure(IllegalStateException("録音された音声がありません"))
        }
    }

    override fun close() {
        stop()
    }

    private fun record(started: Process, header: SystemAudioStreamHeader, file: File) {
        val frameBytes = header.channels * Short.SIZE_BYTES
        val budget = RecordingBudget(sampleRate = header.sampleRate, channelCount = header.channels)
        val pending = ByteArray(frameBytes)
        var pendingBytes = 0
        try {
            WavFileWriter(file, header.sampleRate, header.channels).use { writer ->
                val buffer = ByteArray(header.sampleRate * frameBytes / 10)
                while (running.get()) {
                    val read = started.inputStream.read(buffer)
                    if (read < 0) break
                    var offset = 0
                    while (offset < read && running.get()) {
                        val needed = frameBytes - pendingBytes
                        val copied = minOf(needed, read - offset)
                        buffer.copyInto(pending, pendingBytes, offset, offset + copied)
                        pendingBytes += copied
                        offset += copied
                        if (pendingBytes < frameBytes) continue
                        val decision = budget.decide(frameBytes, file.usableSpace.coerceAtLeast(0L))
                        if (decision.writableBytes > 0) {
                            writer.writePcm16Bytes(pending, decision.writableBytes)
                            budget.commit(decision.writableBytes)
                        }
                        pendingBytes = 0
                        if (decision.stopAfterWrite == RecordingStopReason.DURATION_LIMIT) {
                            running.set(false)
                            break
                        }
                        if (decision.stopAfterWrite == RecordingStopReason.LOW_DISK) {
                            error("録音用の空き容量が不足しています")
                        }
                    }
                }
            }
        } catch (throwable: Throwable) {
            if (running.get()) {
                failure = throwable
                runCatching { file.delete() }
            }
        } finally {
            running.set(false)
            started.destroyForcibly()
        }
    }

    private fun readHeaderLine(input: InputStream): SystemAudioStreamHeader {
        val holder = java.util.concurrent.atomic.AtomicReference<Result<SystemAudioStreamHeader>>()
        val reader = Thread {
            holder.set(runCatching {
                val line = ByteArray(240)
                var size = 0
                while (size < line.size) {
                    val value = input.read()
                    if (value < 0) break
                    if (value == '\n'.code) return@runCatching parseSystemAudioHeader(String(line, 0, size, Charsets.UTF_8))
                    line[size++] = value.toByte()
                }
                error("システムの音声録音を開始できませんでした。画面収録とシステムオーディオ録音の許可を確認してください")
            })
        }
        reader.isDaemon = true
        reader.start()
        reader.join(HEADER_TIMEOUT_MS)
        if (reader.isAlive) {
            error("システムの音声録音を開始できませんでした。画面収録とシステムオーディオ録音の許可を確認してください")
        }
        return holder.get()?.getOrThrow()
            ?: error("システムの音声録音を開始できませんでした。画面収録とシステムオーディオ録音の許可を確認してください")
    }

    private fun drain(stream: InputStream) {
        Thread({
            runCatching { stream.use { it.readBytes() } }
        }, "ChopLab-Mac-System-Audio-Error").apply { isDaemon = true }.start()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 2_000L
        const val WAV_HEADER_BYTES = 44L
        const val HEADER_TIMEOUT_MS = 60_000L
    }
}
