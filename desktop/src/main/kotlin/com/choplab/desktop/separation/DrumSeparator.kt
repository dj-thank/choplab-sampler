package com.choplab.desktop.separation

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
}

/** ONNX Runtime [ChunkInference] for the bundled drums specialist export. */
class OnnxDrumChunkInference(
    modelFile: File,
    threads: Int = max(1, Runtime.getRuntime().availableProcessors() - 1),
) : ChunkInference, AutoCloseable {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        require(modelFile.isFile) { "分離モデルが見つかりません: ${modelFile.absolutePath}" }
        val options = OrtSession.SessionOptions()
        options.setIntraOpNumThreads(threads)
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        session = env.createSession(modelFile.absolutePath, options)
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
        require(chunk.size == 2 * SeparatorSpec.SEGMENT_SAMPLES) { "Chunk buffer size mismatch" }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(chunk), longArrayOf(1, 2, SeparatorSpec.SEGMENT_SAMPLES.toLong())).use { input ->
            session.run(mapOf(SeparatorSpec.INPUT_NAME to input)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val batched = result.get(0).value as Array<Array<Array<FloatArray>>>
                require(batched.size == 1 && batched[0].size == 4) { "Unexpected stems batch shape" }
                val stems = batched[0]
                val flat = FloatArray(4 * 2 * SeparatorSpec.SEGMENT_SAMPLES)
                for (s in 0..3) {
                    for (ch in 0..1) {
                        stems[s][ch].copyInto(flat, (s * 2 + ch) * SeparatorSpec.SEGMENT_SAMPLES)
                    }
                }
                return flat
            }
        }
    }

    override fun close() {
        runCatching { session.close() }
    }
}
