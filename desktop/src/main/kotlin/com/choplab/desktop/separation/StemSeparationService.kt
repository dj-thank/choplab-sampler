package com.choplab.desktop.separation

import com.choplab.sampler.audio.WavFileWriter
import com.choplab.sampler.model.PcmAudio
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single-job offline drum separation worker. Decodes the source, runs the
 * bundled drums specialist, and writes a `drums.wav` stem next to a caller
 * supplied output file. Progress and completion callbacks fire on the worker
 * thread; callers must hop to the UI thread themselves.
 */
internal fun defaultSeparatorModelsDir(): File {
    // Explicit configuration always wins, so tests and operators can pin the lookup.
    System.getProperty("choplab.separatorModels")?.let(::File)?.let { return it }
    System.getenv("CHOPLAB_SEPARATOR_MODELS")?.let(::File)?.let { return it }
    val codeSourceModels = runCatching {
        File(DrumSeparationService::class.java.protectionDomain.codeSource.location.toURI())
            .parentFile?.parentFile?.resolve("models")
    }.getOrNull()
    return listOfNotNull(
        codeSourceModels,
        File(System.getProperty("java.home")).parentFile?.resolve("models"),
        File("work/separator-models"),
        File("../work/separator-models"),
    ).firstOrNull { it.resolve(SeparatorSpec.MODEL_FILE).isFile }
        ?: codeSourceModels
        ?: File("work/separator-models")
}

class DrumSeparationService(
    private val modelsDir: File,
    private val decode: (File) -> PcmAudio,
    private val backendFactory: (File) -> ChunkInference = { OnnxDrumChunkInference(it) },
) : AutoCloseable {
    data class Request(
        val sourceFile: File,
        val outputFile: File,
        val onProgress: (Float) -> Unit = {},
        val onDone: (File) -> Unit = {},
        val onError: (String) -> Unit = {},
        val onCancelled: () -> Unit = {},
    )

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ChopLab-Drum-Separation").apply { isDaemon = true }
    }
    private val cancelled = AtomicBoolean(false)
    @Volatile private var current: Future<*>? = null
    @Volatile private var backend: ChunkInference? = null

    @get:Synchronized
    val isRunning: Boolean
        get() = current?.isDone == false

    fun modelFile(): File = modelsDir.resolve(SeparatorSpec.MODEL_FILE)

    fun isModelAvailable(): Boolean = modelFile().isFile

    /** Starts one job; returns false when another job is already running. */
    @Synchronized
    fun separate(request: Request): Boolean {
        if (isRunning) return false
        cancelled.set(false)
        current = executor.submit {
            try {
                run(request)
            } catch (cancelledError: CancellationException) {
                request.onCancelled()
            } catch (error: Exception) {
                request.onError(error.message ?: error.javaClass.simpleName)
            }
        }
        return true
    }

    fun cancel() {
        cancelled.set(true)
        current?.cancel(true)
    }

    private fun run(request: Request) {
        val model = modelFile()
        if (!model.isFile) {
            throw IllegalStateException("分離モデルが見つかりません: ${model.absolutePath}")
        }
        request.onProgress(0f)
        val audio = decode(request.sourceFile)
        val maxFrames = SeparatorSpec.SAMPLE_RATE * SeparatorSpec.MAX_JOB_SECONDS
        val estimatedFrames = (audio.frameCount.toLong() * SeparatorSpec.SAMPLE_RATE / audio.sampleRate.coerceAtLeast(1))
        if (estimatedFrames > maxFrames) {
            throw IllegalStateException("曲が長すぎます（${SeparatorSpec.MAX_JOB_SECONDS / 60}分まで対応）")
        }
        val (mix, frames) = SeparatorDsp.pcmToFloatStereo44100(audio)
        val active = synchronized(this) {
            backend ?: backendFactory(model).also { backend = it }
        }
        val drums = DrumSeparatorPipeline.separate(
            mix = mix,
            frames = frames,
            infer = active,
            onProgress = { request.onProgress(0.05f + 0.9f * it) },
            isCancelled = cancelled::get,
        )
        if (cancelled.get()) throw CancellationException("drum separation cancelled")
        val stem = SeparatorDsp.floatStereoToPcm16(drums, frames, request.outputFile.nameWithoutExtension)
        request.outputFile.parentFile?.mkdirs()
        WavFileWriter(request.outputFile, SeparatorSpec.SAMPLE_RATE, SeparatorSpec.CHANNELS).use { writer ->
            writer.writePcm16(stem.samples)
        }
        request.onProgress(1f)
        request.onDone(request.outputFile)
    }

    @Synchronized
    override fun close() {
        cancel()
        executor.shutdownNow()
        (backend as? AutoCloseable)?.close()
        backend = null
    }
}
