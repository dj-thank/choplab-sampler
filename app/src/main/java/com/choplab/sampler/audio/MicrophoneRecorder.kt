package com.choplab.sampler.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

private const val MICROPHONE_SAMPLE_RATE = 48_000

internal fun awaitRecorderWorker(worker: Thread?, timeoutMillis: Long): Boolean {
    if (worker == null) return true
    return try {
        worker.join(timeoutMillis.coerceAtLeast(1L))
        !worker.isAlive
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }
}

internal interface RecorderInput {
    val recordingState: Int
    fun startRecording()
    fun read(buffer: ShortArray): Int
    fun stop()
    fun release()
}

internal fun interface RecorderInputFactory {
    fun create(): RecorderInput
}

class MicrophoneRecorder internal constructor(
    private val inputFactory: RecorderInputFactory = RecorderInputFactory { createAndroidRecorderInput() },
    private val startupStopTimeoutMillis: Long = 2_000L,
) {
    constructor() : this(RecorderInputFactory { createAndroidRecorderInput() })

    private val lifecycleLock = Any()
    private val running = AtomicBoolean(false)
    @Volatile private var startingInput: RecorderInput? = null
    @Volatile private var audioRecord: RecorderInput? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var outputFile: File? = null
    @Volatile private var failureMessage: String? = null
    @Volatile private var deleteOutputWhenStopped = false
    @Volatile private var stopRequested = false
    @Volatile private var startCallActive = false
    @Volatile private var stopInProgress = false
    @Volatile private var startupFinished = CountDownLatch(0)

    val isRecording: Boolean
        get() = running.get()

    @SuppressLint("MissingPermission")
    fun start(
        file: File,
        onStarted: () -> Unit = {},
        onFailure: (String) -> Unit = {},
    ): Result<Unit> {
        val startup = synchronized(lifecycleLock) {
            if (startCallActive || stopInProgress || worker?.isAlive == true) {
                return Result.failure(IllegalStateException("マイク録音の停止処理中です"))
            }
            if (!running.compareAndSet(false, true)) {
                return Result.failure(IllegalStateException("すでにマイク録音中です"))
            }
            stopRequested = false
            startCallActive = true
            failureMessage = null
            deleteOutputWhenStopped = false
            outputFile = file
            CountDownLatch(1).also { startupFinished = it }
        }

        val startupThread = Thread({
            var recorder: RecorderInput? = null
            var createdWorker: Thread? = null
            runCatching {
                file.parentFile?.mkdirs()
                val candidate = inputFactory.create()
                recorder = candidate
                val admitted = synchronized(lifecycleLock) {
                    if (stopRequested || !running.get()) {
                        false
                    } else {
                        startingInput = candidate
                        true
                    }
                }
                if (!admitted) {
                    candidate.release()
                    error("マイク録音の開始はキャンセルされました")
                }

                candidate.startRecording()
                if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    error("マイク録音を開始できません")
                }

                val recordingWorker = Thread({
                    Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                    val bufferFrames = max(
                        AudioRecord.getMinBufferSize(
                            MICROPHONE_SAMPLE_RATE,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                        ) / Short.SIZE_BYTES,
                        2_048,
                    )
                    val buffer = ShortArray(bufferFrames)
                    val budget = RecordingBudget(
                        sampleRate = MICROPHONE_SAMPLE_RATE,
                        channelCount = 1,
                    )

                    try {
                        WavFileWriter(file, MICROPHONE_SAMPLE_RATE, 1).use { writer ->
                            while (running.get()) {
                                val read = candidate.read(buffer)
                                when {
                                    read > 0 -> {
                                        val requestedBytes = read * Short.SIZE_BYTES
                                        val decision = budget.decide(
                                            requestedBytes = requestedBytes,
                                            usableSpaceBytes = file.usableSpace.coerceAtLeast(0L),
                                        )
                                        if (decision.writableBytes > 0) {
                                            val writableSamples = decision.writableBytes / Short.SIZE_BYTES
                                            writer.writePcm16(buffer, writableSamples)
                                            budget.commit(decision.writableBytes)
                                        }
                                        when (decision.stopAfterWrite) {
                                            RecordingStopReason.DURATION_LIMIT -> {
                                                running.set(false)
                                                break
                                            }
                                            RecordingStopReason.LOW_DISK ->
                                                error("録音用の空き容量が不足しています")
                                            null -> Unit
                                        }
                                    }
                                    read == AudioRecord.ERROR_DEAD_OBJECT ->
                                        error("マイクとの接続が切れました")
                                    read < 0 && running.get() -> error("マイク読み取りエラー: $read")
                                }
                            }
                        }
                    } catch (throwable: Throwable) {
                        failureMessage = throwable.message ?: "マイク録音中にエラーが発生しました"
                        deleteOutputWhenStopped = true
                        runCatching { onFailure(failureMessage!!) }
                    } finally {
                        running.set(false)
                        runCatching { candidate.stop() }
                        runCatching { candidate.release() }
                        if (deleteOutputWhenStopped) runCatching { file.delete() }
                        synchronized(lifecycleLock) {
                            if (audioRecord === candidate) audioRecord = null
                            if (worker === Thread.currentThread()) worker = null
                        }
                    }
                }, "ChopLab-Microphone")
                createdWorker = recordingWorker
                synchronized(lifecycleLock) {
                    check(startingInput === candidate && !stopRequested && running.get()) {
                        "マイク録音の開始はキャンセルされました"
                    }
                    startingInput = null
                    audioRecord = candidate
                    worker = recordingWorker
                    recordingWorker.start()
                }
            }.onFailure { throwable ->
                running.set(false)
                deleteOutputWhenStopped = true
                val ownedInput = synchronized(lifecycleLock) {
                    val ownsStartingInput = startingInput === recorder
                    val ownsActiveInput = audioRecord === recorder
                    if (ownsStartingInput) startingInput = null
                    if (ownsActiveInput) audioRecord = null
                    if (worker === createdWorker) worker = null
                    recorder.takeIf { ownsStartingInput || ownsActiveInput }
                }
                runCatching { ownedInput?.stop() }
                runCatching { ownedInput?.release() }
                failureMessage = throwable.message
                runCatching { file.delete() }
                runCatching {
                    onFailure(throwable.message ?: "マイク録音を開始できません")
                }
            }.also {
                synchronized(lifecycleLock) {
                    startCallActive = false
                    startup.countDown()
                }
            }.onSuccess {
                runCatching { onStarted() }
            }
        }, "ChopLab-Microphone-Startup").apply { isDaemon = true }

        return runCatching { startupThread.start() }.onFailure { throwable ->
            running.set(false)
            deleteOutputWhenStopped = true
            failureMessage = throwable.message
            runCatching { file.delete() }
            synchronized(lifecycleLock) {
                startCallActive = false
                startup.countDown()
            }
        }
    }

    fun stop(): Result<File> {
        val (startup, pendingInput) = synchronized(lifecycleLock) {
            if (stopInProgress) {
                return Result.failure(IllegalStateException("マイク録音の停止処理中です"))
            }
            stopInProgress = true
            stopRequested = true
            running.set(false)
            val pending = startingInput
            startingInput = null
            startupFinished to pending
        }
        return try {
            pendingInput?.let(::releasePendingRecorderInput)
            if (!startup.await(startupStopTimeoutMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)) {
                deleteOutputWhenStopped = true
                runCatching { outputFile?.delete() }
                return Result.failure(IllegalStateException("マイク録音の開始取消に時間がかかっています"))
            }
            val wasRunning = audioRecord != null || worker != null
            val activeWorker = worker
            if (!wasRunning && activeWorker == null) {
                val file = outputFile
                val failure = failureMessage
                return when {
                    failure != null -> Result.failure(IllegalStateException(failure))
                    file != null && file.exists() && file.length() > WAV_HEADER_BYTES -> Result.success(file)
                    else -> Result.failure(IllegalStateException("マイク録音は開始されていません"))
                }
            }

            runCatching { audioRecord?.stop() }
            if (!awaitRecorderWorker(activeWorker, timeoutMillis = 2_000L)) {
                deleteOutputWhenStopped = true
                return Result.failure(IllegalStateException("マイク録音の停止に時間がかかっています"))
            }
            if (worker === activeWorker) worker = null

            val file = outputFile
            if (failureMessage != null) {
                runCatching { file?.delete() }
                Result.failure(IllegalStateException(failureMessage))
            } else if (file != null && file.exists() && file.length() > WAV_HEADER_BYTES) {
                Result.success(file)
            } else {
                runCatching { file?.delete() }
                Result.failure(IllegalStateException("録音された音声がありません"))
            }
        } finally {
            synchronized(lifecycleLock) { stopInProgress = false }
        }
    }

    private fun releasePendingRecorderInput(input: RecorderInput) {
        runCatching {
            Thread(
                { runCatching { input.release() } },
                "ChopLab-Microphone-StartupCancel",
            ).apply {
                isDaemon = true
                start()
            }
        }
    }

    private companion object {
        const val WAV_HEADER_BYTES = 44L
    }
}

@SuppressLint("MissingPermission")
private fun createAndroidRecorderInput(): RecorderInput {
    val minBuffer = AudioRecord.getMinBufferSize(
        MICROPHONE_SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )
    check(minBuffer > 0) { "この端末ではマイク録音形式を使用できません" }

    fun build(source: Int): AudioRecord = AudioRecord.Builder()
        .setAudioSource(source)
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(MICROPHONE_SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build(),
        )
        .setBufferSizeInBytes(max(minBuffer * 2, 8_192))
        .build()

    val preferred = runCatching { build(MediaRecorder.AudioSource.UNPROCESSED) }.getOrNull()
    if (preferred != null && preferred.state == AudioRecord.STATE_INITIALIZED) {
        return AndroidRecorderInput(preferred)
    }
    runCatching { preferred?.release() }

    val fallback = build(MediaRecorder.AudioSource.MIC)
    check(fallback.state == AudioRecord.STATE_INITIALIZED) { "マイクを初期化できません" }
    return AndroidRecorderInput(fallback)
}

private class AndroidRecorderInput(private val delegate: AudioRecord) : RecorderInput {
    override val recordingState: Int get() = delegate.recordingState
    override fun startRecording() = delegate.startRecording()
    override fun read(buffer: ShortArray): Int =
        delegate.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
    override fun stop() = delegate.stop()
    override fun release() = delegate.release()
}
