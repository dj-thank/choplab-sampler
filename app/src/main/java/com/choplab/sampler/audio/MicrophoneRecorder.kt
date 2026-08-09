package com.choplab.sampler.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class MicrophoneRecorder {
    private val running = AtomicBoolean(false)
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var worker: Thread? = null
    @Volatile private var outputFile: File? = null
    @Volatile private var failureMessage: String? = null

    val isRecording: Boolean
        get() = running.get()

    @SuppressLint("MissingPermission")
    fun start(file: File, onFailure: (String) -> Unit = {}): Result<Unit> {
        if (!running.compareAndSet(false, true)) {
            return Result.failure(IllegalStateException("すでにマイク録音中です"))
        }

        failureMessage = null
        outputFile = file
        file.parentFile?.mkdirs()

        return runCatching {
            val recorder = createAudioRecord()
            audioRecord = recorder
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                error("マイク録音を開始できません")
            }

            worker = Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
                val bufferFrames = max(
                    AudioRecord.getMinBufferSize(
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    ) / Short.SIZE_BYTES,
                    2_048,
                )
                val buffer = ShortArray(bufferFrames)

                try {
                    WavFileWriter(file, SAMPLE_RATE, 1).use { writer ->
                        while (running.get()) {
                            val read = recorder.read(
                                buffer,
                                0,
                                buffer.size,
                                AudioRecord.READ_BLOCKING,
                            )
                            when {
                                read > 0 -> writer.writePcm16(buffer, read)
                                read == AudioRecord.ERROR_DEAD_OBJECT -> error("マイクとの接続が切れました")
                                read < 0 && running.get() -> error("マイク読み取りエラー: $read")
                            }
                        }
                    }
                } catch (throwable: Throwable) {
                    failureMessage = throwable.message ?: "マイク録音中にエラーが発生しました"
                    onFailure(failureMessage!!)
                } finally {
                    running.set(false)
                    runCatching { recorder.stop() }
                    runCatching { recorder.release() }
                    audioRecord = null
                }
            }, "ChopLab-Microphone").apply {
                start()
            }
        }.onFailure { throwable ->
            running.set(false)
            runCatching { audioRecord?.release() }
            audioRecord = null
            failureMessage = throwable.message
        }
    }

    fun stop(): Result<File> {
        if (!running.getAndSet(false)) {
            val file = outputFile
            val failure = failureMessage
            return when {
                failure != null -> Result.failure(IllegalStateException(failure))
                file != null && file.exists() && file.length() > 44L -> Result.success(file)
                else -> Result.failure(IllegalStateException("マイク録音は開始されていません"))
            }
        }

        runCatching { audioRecord?.stop() }
        runCatching { worker?.join(2_000L) }
        worker = null

        val file = outputFile
        return if (failureMessage != null) {
            Result.failure(IllegalStateException(failureMessage))
        } else if (file != null && file.exists() && file.length() > 44L) {
            Result.success(file)
        } else {
            Result.failure(IllegalStateException("録音された音声がありません"))
        }
    }

    @SuppressLint("MissingPermission")
    private fun createAudioRecord(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBuffer > 0) { "この端末ではマイク録音形式を使用できません" }

        fun build(source: Int): AudioRecord = AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(max(minBuffer * 2, 8_192))
            .build()

        val preferred = runCatching { build(MediaRecorder.AudioSource.UNPROCESSED) }.getOrNull()
        if (preferred != null && preferred.state == AudioRecord.STATE_INITIALIZED) return preferred
        runCatching { preferred?.release() }

        val fallback = build(MediaRecorder.AudioSource.MIC)
        check(fallback.state == AudioRecord.STATE_INITIALIZED) { "マイクを初期化できません" }
        return fallback
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
    }
}
