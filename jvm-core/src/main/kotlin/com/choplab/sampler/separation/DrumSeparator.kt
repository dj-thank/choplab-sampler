package com.choplab.sampler.separation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.nio.FloatBuffer
import java.util.concurrent.CancellationException
import kotlin.math.max

/**
 * One fixed-size chunk inference. Input is channel-major `[2][segment]`
 * flattened floats; output is stems-major `[4][2][segment]` flattened floats
 * in [SeparatorSpec.STEM_NAMES] order.
 */
fun interface ChunkInference {
    fun infer(chunk: FloatArray): FloatArray
}

/**
 * Chunked overlap-add separation orchestration. The model backend is injected,
 * so the windowing math is verifiable without a 166 MB model file.
 */
object DrumSeparatorPipeline {
    /** Whole-buffer reference form. */
    fun separate(
        mix: FloatArray,
        frames: Int,
        infer: ChunkInference,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): FloatArray {
        require(mix.size == SeparatorSpec.CHANNELS * frames) { "Mix buffer size mismatch" }
        require(frames > 0) { "空の音源は分離できません" }
        val segment = SeparatorSpec.SEGMENT_SAMPLES
        val window = SeparatorDsp.transitionWindow(segment, SeparatorSpec.OVERLAP_SAMPLES)
        val starts = SeparatorDsp.chunkStarts(frames, segment, SeparatorSpec.STRIDE_SAMPLES)
        val out = FloatArray(2 * frames)
        val weight = FloatArray(frames)
        starts.forEachIndexed { index, start ->
            if (isCancelled()) throw CancellationException("drum separation cancelled")
            val end = minOf(start + segment, frames)
            val len = end - start
            val chunk = FloatArray(2 * segment)
            for (ch in 0..1) {
                mix.copyInto(chunk, ch * segment, start + ch * frames, end + ch * frames)
            }
            val stems = infer.infer(chunk)
            require(stems.size == 4 * 2 * segment) { "Unexpected stems buffer size" }
            val drum = SeparatorSpec.DRUM_STEM_INDEX * 2 * segment
            for (ch in 0..1) {
                for (i in 0 until len) {
                    out[ch * frames + start + i] += stems[drum + ch * segment + i] * window[i]
                }
            }
            for (i in 0 until len) {
                weight[start + i] += window[i]
            }
            onProgress((index + 1).toFloat() / starts.size)
        }
        for (ch in 0..1) {
            for (i in 0 until frames) {
                out[ch * frames + i] /= max(weight[i], 1e-8f)
            }
        }
        return out
    }

    /**
     * Streaming form of [separate] with identical per-frame sums and division. Only one segment
     * of accumulators is retained, so memory does not grow with song length. [emit] receives
     * finalized frames in order; its arrays are reused and valid only during the call.
     */
    fun separateStreaming(
        source: SeparatorSourceReader,
        infer: ChunkInference,
        emit: (left: FloatArray, right: FloatArray, count: Int) -> Unit,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
        segment: Int = SeparatorSpec.SEGMENT_SAMPLES,
        overlap: Int = SeparatorSpec.OVERLAP_SAMPLES,
    ) {
        val frames = source.frames
        require(frames > 0) { "空の音源は分離できません" }
        val stride = segment - overlap
        val window = SeparatorDsp.transitionWindow(segment, overlap)
        val starts = SeparatorDsp.chunkStarts(frames, segment, stride)
        val accLeft = FloatArray(segment)
        val accRight = FloatArray(segment)
        val accWeight = FloatArray(segment)
        val outLeft = FloatArray(segment)
        val outRight = FloatArray(segment)
        val chunk = FloatArray(2 * segment)
        val drum = SeparatorSpec.DRUM_STEM_INDEX * 2 * segment
        starts.forEachIndexed { index, start ->
            if (isCancelled()) throw CancellationException("drum separation cancelled")
            val len = minOf(start + segment, frames) - start
            chunk.fill(0f)
            source.read(start, len, chunk, segment)
            val stems = infer.infer(chunk)
            require(stems.size == 4 * 2 * segment) { "Unexpected stems buffer size" }
            for (i in 0 until len) {
                accLeft[i] += stems[drum + i] * window[i]
                accRight[i] += stems[drum + segment + i] * window[i]
                accWeight[i] += window[i]
            }
            val last = index == starts.lastIndex
            // Later chunks start at start + stride, so earlier frames are complete.
            val finalized = if (last) len else stride
            for (i in 0 until finalized) {
                val weight = max(accWeight[i], 1e-8f)
                outLeft[i] = accLeft[i] / weight
                outRight[i] = accRight[i] / weight
            }
            emit(outLeft, outRight, finalized)
            if (!last) {
                for (buffer in arrayOf(accLeft, accRight, accWeight)) {
                    buffer.copyInto(buffer, 0, stride, segment)
                    buffer.fill(0f, segment - stride, segment)
                }
            }
            onProgress((index + 1).toFloat() / starts.size)
        }
    }
}

/** ONNX Runtime [ChunkInference] for the drums specialist export (desktop JAR or Android AAR). */
class OnnxDrumChunkInference(
    modelFile: File,
    threads: Int = max(1, Runtime.getRuntime().availableProcessors() - 1),
    /** Mobile: return activation memory between chunks instead of retaining a peak-sized arena. */
    lowMemory: Boolean = false,
) : ChunkInference, AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        require(modelFile.isFile) { "分離モデルが見つかりません: ${modelFile.absolutePath}" }
        session = OrtSession.SessionOptions().use { options ->
            options.setIntraOpNumThreads(threads)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (lowMemory) {
                options.setCPUArenaAllocator(false)
                options.setMemoryPatternOptimization(false)
            }
            env.createSession(modelFile.absolutePath, options)
        }
        val inputShape = (session.inputInfo[SeparatorSpec.INPUT_NAME]?.info as? TensorInfo)?.shape
        check(inputShape != null && inputShape.size == 3 && inputShape[1] == 2L &&
            inputShape[2] == SeparatorSpec.SEGMENT_SAMPLES.toLong()
        ) { "モデルの入力形状が想定外です: ${inputShape?.toList()}" }
        val outputShape = (session.outputInfo[SeparatorSpec.OUTPUT_NAME]?.info as? TensorInfo)?.shape
        check(outputShape != null && outputShape.size == 4 && outputShape[1] == 4L) {
            "モデルの出力形状が想定外です: ${outputShape?.toList()}"
        }
    }

    override fun infer(chunk: FloatArray): FloatArray {
        val segment = SeparatorSpec.SEGMENT_SAMPLES
        require(chunk.size == 2 * segment) { "Chunk buffer size mismatch" }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), longArrayOf(1, 2, segment.toLong())).use { input ->
            session.run(mapOf(SeparatorSpec.INPUT_NAME to input)).use { result ->
                val output = result.get(0) as? OnnxTensor ?: error("Unexpected stems output")
                val shape = output.info.shape
                require(shape.contentEquals(longArrayOf(1, 4, 2, segment.toLong()))) {
                    "Unexpected stems batch shape: ${shape.toList()}"
                }
                val size = 4 * 2 * segment
                // getFloatBuffer() already returns a heap copy; reuse its array when it is exact.
                val buffer = output.floatBuffer
                return if (buffer.hasArray() && buffer.arrayOffset() == 0 && buffer.array().size == size && buffer.remaining() == size) {
                    buffer.array()
                } else {
                    FloatArray(size).also { buffer.get(it) }
                }
            }
        }
    }

    override fun close() {
        runCatching { session.close() }
    }
}
