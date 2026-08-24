package com.choplab.desktop.audio

import com.choplab.sampler.audio.RecordingBudget
import com.choplab.sampler.audio.RecordingStopReason
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
    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    private var starting = false
    private var stopRequested = false
    private var startingLine: TargetDataLine? = null
    @Volatile private var line: TargetDataLine? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var outputFile: File? = null
    @Volatile private var failure: Throwable? = null

    override val isRecording: Boolean
        get() = running.get()

    override fun start(file: File): Result<Unit> {
        synchronized(lifecycleLock) {
            if (starting || running.get() || worker?.isAlive == true) {
                return Result.failure(IllegalStateException("録音の停止処理中です"))
            }
            starting = true
            stopRequested = false
        }
        var acquiredLine: TargetDataLine? = null
        var startingLinePublished = false
        val result = runCatching {
            val capture = lineFactory()
            val target = capture.line
            acquiredLine = target
            val format = capture.format
            require(format.sampleSizeInBits == 16 && !format.isBigEndian) {
                "PCM-16 little-endian形式が必要です"
            }
            require(format.channels in 1..2) { "モノラルまたはステレオ録音が必要です" }
            file.parentFile?.mkdirs()
            target.open(format)
            synchronized(lifecycleLock) {
                check(!stopRequested) { "録音の開始はキャンセルされました" }
                startingLine = target
                startingLinePublished = true
            }
            target.start()
            val recordingWorker = Thread({ record(target, format, file) }, threadName).apply { isDaemon = true }
            synchronized(lifecycleLock) {
                check(!stopRequested && startingLine === target) { "録音の開始はキャンセルされました" }
                startingLine = null
                line = target
                outputFile = file
                failure = null
                running.set(true)
                worker = recordingWorker
                try {
                    recordingWorker.start()
                } catch (throwable: Throwable) {
                    worker = null
                    line = null
                    outputFile = null
                    running.set(false)
                    startingLine = target
                    throw throwable
                }
            }
        }.onFailure {
            val lineToClose = synchronized(lifecycleLock) {
                val target = acquiredLine
                val ownedStartingLine = when {
                    target == null -> null
                    startingLine === target -> target.also { startingLine = null }
                    !startingLinePublished -> target
                    else -> null
                }
                running.set(false)
                line = null
                worker = null
                outputFile = null
                failure = null
                ownedStartingLine
            }
            runCatching { lineToClose?.close() }
            runCatching { file.delete() }
        }
        synchronized(lifecycleLock) { starting = false }
        return result
    }

    override fun stop(): Result<File> {
        val (pendingLine, activeResources) = synchronized(lifecycleLock) {
            stopRequested = true
            running.set(false)
            val pending = startingLine
            startingLine = null
            pending to (line to worker)
        }
        val (activeLine, activeWorker) = activeResources
        pendingLine?.let { runCatching { it.close() } }
        activeLine?.takeUnless { it === pendingLine }?.let {
            runCatching { it.stop() }
            runCatching { it.close() }
        }
        if (activeWorker != null && activeWorker !== Thread.currentThread()) {
            runCatching { activeWorker.join(STOP_TIMEOUT_MS) }
        }
        val timedOut = activeWorker?.isAlive == true
        synchronized(lifecycleLock) {
            if (!timedOut && worker === activeWorker) worker = null
            if (line === activeLine) line = null
        }
        val file = outputFile
        val error = failure
        return when {
            timedOut -> Result.failure(IllegalStateException("録音の停止に時間がかかっています"))
            error != null -> Result.failure(error)
            file != null && file.isFile && file.length() > WAV_HEADER_BYTES -> Result.success(file)
            else -> Result.failure(IllegalStateException("録音された音声がありません"))
        }
    }

    private fun record(target: TargetDataLine, format: AudioFormat, file: File) {
        val sampleRate = format.sampleRate.toInt()
        val channels = format.channels
        val frameBytes = channels * Short.SIZE_BYTES
        val requestedBufferBytes = sampleRate * frameBytes / 10
        val bufferSize = (requestedBufferBytes / frameBytes).coerceAtLeast(1) * frameBytes
        val buffer = ByteArray(bufferSize)
        val budget = RecordingBudget(sampleRate = sampleRate, channelCount = channels)

        try {
            WavFileWriter(file, sampleRate, channels).use { writer ->
                while (running.get()) {
                    val read = target.read(buffer, 0, buffer.size)
                    when {
                        read < 0 && running.get() -> error("音声入力エラー: $read")
                        read <= 0 -> continue
                        read % frameBytes != 0 -> error("音声入力に不完全なPCMフレームがあります")
                    }

                    val decision = budget.decide(read, file.usableSpace.coerceAtLeast(0L))
                    if (decision.writableBytes > 0) {
                        writer.writePcm16Bytes(buffer, decision.writableBytes)
                        budget.commit(decision.writableBytes)
                    }
                    when (decision.stopAfterWrite) {
                        RecordingStopReason.DURATION_LIMIT -> {
                            running.set(false)
                            break
                        }
                        RecordingStopReason.LOW_DISK -> error("録音用の空き容量が不足しています")
                        null -> Unit
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
            runCatching { target.stop() }
            runCatching { target.close() }
        }
    }

    override fun close() {
        stop()
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 2_000L
        const val WAV_HEADER_BYTES = 44L
    }
}

internal data class DesktopCaptureLine(
    val line: TargetDataLine,
    val format: AudioFormat,
)
