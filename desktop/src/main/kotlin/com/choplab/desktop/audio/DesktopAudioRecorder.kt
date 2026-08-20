package com.choplab.desktop.audio

import com.choplab.sampler.audio.WavFileWriter
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.TargetDataLine

interface DesktopAudioRecorder : AutoCloseable {
    val isRecording: Boolean
    fun start(file: File): Result<Unit>
    fun stop(): Result<File>
}

/** Shared bounded TargetDataLine -> PCM-16 WAV lifecycle. */
internal class DesktopTargetLineRecorder(
    private val lineFactory: () -> DesktopCaptureLine,
    private val threadName: String,
) : DesktopAudioRecorder {
    private val running = AtomicBoolean(false)
    @Volatile private var line: TargetDataLine? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var outputFile: File? = null
    @Volatile private var failure: Throwable? = null

    override val isRecording: Boolean
        get() = running.get()

    @Synchronized
    override fun start(file: File): Result<Unit> {
        if (running.get() || worker?.isAlive == true) {
            return Result.failure(IllegalStateException("録音の停止処理中です"))
        }
        return runCatching {
            val capture = lineFactory()
            val target = capture.line
            val format = capture.format
            file.parentFile?.mkdirs()
            target.open(format)
            target.start()
            line = target
            outputFile = file
            failure = null
            running.set(true)
            val recordingWorker = Thread({ record(target, format, file) }, threadName).apply { isDaemon = true }
            worker = recordingWorker
            recordingWorker.start()
        }.onFailure {
            running.set(false)
            runCatching { line?.close() }
            line = null
            runCatching { file.delete() }
        }
    }

    override fun stop(): Result<File> {
        running.set(false)
        line?.let { runCatching { it.stop() }; runCatching { it.close() } }
        val activeWorker = worker
        if (activeWorker != null && activeWorker !== Thread.currentThread()) {
            runCatching { activeWorker.join(STOP_TIMEOUT_MS) }
        }
        val timedOut = activeWorker?.isAlive == true
        if (!timedOut) worker = null
        line = null
        val file = outputFile
        val error = failure
        return when {
            timedOut -> Result.failure(IllegalStateException("録音の停止に時間がかかっています"))
            error != null -> Result.failure(error)
            file != null && file.isFile && file.length() > 44L -> Result.success(file)
            else -> Result.failure(IllegalStateException("録音された音声がありません"))
        }
    }

    private fun record(target: TargetDataLine, format: AudioFormat, file: File) {
        val buffer = ByteArray(format.sampleRate.toInt() * 2 / 10)
        try {
            WavFileWriter(file, format.sampleRate.toInt(), format.channels).use { writer ->
                while (running.get()) {
                    val read = target.read(buffer, 0, buffer.size)
                    if (read > 0) writer.writePcm16Bytes(buffer, read)
                    if (read < 0 && running.get()) error("音声入力エラー: $read")
                }
            }
        } catch (throwable: Throwable) {
            if (running.get()) {
                failure = throwable
                runCatching { file.delete() }
            }
        } finally {
            running.set(false)
            runCatching { target.stop() }
            runCatching { target.close() }
        }
    }

    override fun close() {
        stop()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 2_000L
    }
}

internal data class DesktopCaptureLine(
    val line: TargetDataLine,
    val format: AudioFormat,
)
