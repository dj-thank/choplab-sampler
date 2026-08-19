package com.choplab.desktop.audio

import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import java.util.concurrent.atomic.AtomicBoolean

/** Windows/JVM microphone adapter using the standard TargetDataLine API. */
class DesktopMicrophoneRecorder : AutoCloseable {
    companion object {
        private const val SAMPLE_RATE = 48_000f
    }

    private val running = AtomicBoolean(false)
    @Volatile private var line: TargetDataLine? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var outputFile: File? = null
    @Volatile private var failure: Throwable? = null

    val isRecording: Boolean
        get() = running.get()

    @Synchronized
    fun start(file: File): Result<Unit> {
        if (running.get() || worker?.isAlive == true) {
            return Result.failure(IllegalStateException("マイク録音の停止処理中です"))
        }
        val format = AudioFormat(SAMPLE_RATE, 16, 1, true, false)
        val target = AudioSystem.getLine(DataLine.Info(TargetDataLine::class.java, format)) as TargetDataLine
        return runCatching {
            file.parentFile?.mkdirs()
            target.open(format)
            target.start()
            line = target
            outputFile = file
            failure = null
            running.set(true)
            val recordingWorker = Thread({ record(target, format, file) }, "ChopLab-Windows-Microphone")
            worker = recordingWorker
            recordingWorker.start()
        }.onFailure {
            running.set(false)
            runCatching { target.close() }
            line = null
            runCatching { file.delete() }
        }
    }

    fun stop(): Result<File> {
        running.set(false)
        line?.let { runCatching { it.stop() }; runCatching { it.close() } }
        val activeWorker = worker
        if (activeWorker != null && activeWorker !== Thread.currentThread()) {
            runCatching { activeWorker.join(2_000L) }
        }
        worker = null
        line = null
        val file = outputFile
        val error = failure
        return when {
            error != null -> Result.failure(error)
            file != null && file.isFile && file.length() > 44L -> Result.success(file)
            else -> Result.failure(IllegalStateException("録音された音声がありません"))
        }
    }

    private fun record(target: TargetDataLine, format: AudioFormat, file: File) {
        val buffer = ByteArray(format.sampleRate.toInt() * 2 / 10)
        try {
            DesktopWavFileWriter(file, format.sampleRate.toInt()).use { writer ->
                while (running.get()) {
                    val read = target.read(buffer, 0, buffer.size)
                    if (read > 0) writer.writePcm16Bytes(buffer, read)
                    if (read < 0) error("マイク読み取りエラー: $read")
                }
            }
        } catch (throwable: Throwable) {
            failure = throwable
            runCatching { file.delete() }
        } finally {
            running.set(false)
            runCatching { target.stop() }
            runCatching { target.close() }
        }
    }

    override fun close() {
        stop()
    }
}
