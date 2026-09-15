package com.choplab.sampler.separation

import com.choplab.sampler.audio.WavFileWriter
import com.choplab.sampler.model.PcmAudio
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-job offline drum separation worker shared by Windows and Android. It reads the
 * source, runs the drums specialist chunk by chunk and streams a stereo 44.1 kHz WAV stem.
 * Progress and completion callbacks fire on the worker thread; callers must hop to their
 * UI thread themselves.
 */
class DrumSeparationService(
    private val modelsDir: File,
    private val decode: (File) -> PcmAudio,
    private val backendFactory: (File) -> ChunkInference = { OnnxDrumChunkInference(it) },
    /** Supplies a verified model (for example by downloading it). Null requires [modelsDir]. */
    private val modelProvider: ((onProgress: (Float) -> Unit, isCancelled: () -> Boolean) -> File)? = null,
    /** Mobile callers release the native session after each job instead of caching it. */
    private val releaseBackendAfterJob: Boolean = false,
) : AutoCloseable {
    data class Request(
        val sourceFile: File? = null,
        val outputFile: File,
        /** In-memory source; avoids writing and decoding a temporary copy. */
        val sourceAudio: PcmAudio? = null,
        val onModelProgress: (Float) -> Unit = {},
        val onProgress: (Float) -> Unit = {},
        val onDone: (File) -> Unit = {},
        val onError: (String) -> Unit = {},
        val onCancelled: () -> Unit = {},
    ) {
        init {
            require((sourceFile == null) != (sourceAudio == null)) { "Exactly one separation source is required" }
        }
    }

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ChopLab-Drum-Separation").apply { isDaemon = true }
    }
    private val cancelled = AtomicBoolean(false)
    @Volatile private var current: Future<*>? = null
    private var backend: ChunkInference? = null
    private var running = false
    private var closed = false

    @get:Synchronized
    val isRunning: Boolean
        get() = running

    fun modelFile(): File = modelsDir.resolve(SeparatorSpec.MODEL_FILE)

    fun isModelAvailable(): Boolean = modelFile().isFile

    /** Starts one job; returns false when another job is already running or the service is closed. */
    @Synchronized
    fun separate(request: Request): Boolean {
        if (running || closed) return false
        cancelled.set(false)
        running = true
        current = try {
            executor.submit {
                try {
                    run(request)
                } catch (cancelledError: CancellationException) {
                    request.onCancelled()
                } catch (error: Exception) {
                    if (cancelled.get()) request.onCancelled() else request.onError(error.message ?: error.javaClass.simpleName)
                } finally {
                    synchronized(this) {
                        running = false
                        if (releaseBackendAfterJob || closed) releaseBackendLocked()
                    }
                }
            }
        } catch (rejected: java.util.concurrent.RejectedExecutionException) {
            running = false
            return false
        }
        return true
    }

    fun cancel() {
        cancelled.set(true)
        current?.cancel(true)
    }

    private fun run(request: Request) {
        val model = modelProvider?.invoke(request.onModelProgress, cancelled::get) ?: modelFile().also {
            if (!it.isFile) throw IllegalStateException("分離モデルが見つかりません: ${it.absolutePath}")
        }
        if (cancelled.get()) throw CancellationException("drum separation cancelled")
        request.onProgress(0f)
        val audio = request.sourceAudio ?: decode(requireNotNull(request.sourceFile))
        val maxFrames = SeparatorSpec.SAMPLE_RATE * SeparatorSpec.MAX_JOB_SECONDS
        val estimatedFrames = (audio.frameCount.toLong() * SeparatorSpec.SAMPLE_RATE / audio.sampleRate.coerceAtLeast(1))
        if (estimatedFrames > maxFrames) {
            throw IllegalStateException("曲が長すぎます（${SeparatorSpec.MAX_JOB_SECONDS / 60}分まで対応）")
        }
        val source = SeparatorSourceReader(audio)
        val active = synchronized(this) {
            backend ?: backendFactory(model).also { backend = it }
        }
        val output = request.outputFile
        output.parentFile?.mkdirs()
        try {
            WavFileWriter(output, SeparatorSpec.SAMPLE_RATE, SeparatorSpec.CHANNELS).use { writer ->
                val pcm = ShortArray(2 * SeparatorSpec.SEGMENT_SAMPLES)
                DrumSeparatorPipeline.separateStreaming(
                    source = source,
                    infer = active,
                    emit = { left, right, count ->
                        for (i in 0 until count) {
                            pcm[2 * i] = SeparatorDsp.toPcm16(left[i])
                            pcm[2 * i + 1] = SeparatorDsp.toPcm16(right[i])
                        }
                        writer.writePcm16(pcm, 2 * count)
                    },
                    onProgress = { request.onProgress(0.05f + 0.9f * it) },
                    isCancelled = cancelled::get,
                )
            }
            if (cancelled.get()) throw CancellationException("drum separation cancelled")
        } catch (failure: Throwable) {
            output.delete()
            throw failure
        }
        request.onProgress(1f)
        request.onDone(output)
    }

    private fun releaseBackendLocked() {
        (backend as? AutoCloseable)?.let { runCatching { it.close() } }
        backend = null
    }

    /** Cancels any job; a running job releases its native session when its current chunk ends. */
    @Synchronized
    override fun close() {
        closed = true
        cancel()
        executor.shutdown()
        if (!running) releaseBackendLocked()
    }
}
