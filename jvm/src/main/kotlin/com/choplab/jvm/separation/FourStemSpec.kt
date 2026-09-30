package com.choplab.jvm.separation

import com.choplab.core.separation.SeparationProblem
import com.choplab.core.separation.SeparationMemoryReceipt

/** Standard, single-model HT-Demucs; the FT drums specialist is a separate feature and remains unchanged. */
object FourStemSpec {
    const val RATE = 44_100
    const val FRAMES = 343_980
    const val OVERLAP = FRAMES / 4
    const val STRIDE = FRAMES - OVERLAP
    const val MODEL_COMMIT = "d54ed9eb60e258ea82131c6ee14578628816456a"
    const val MODEL_FILE = "htdemucs_fp16weights.onnx"
    const val MODEL_SHA256 = "d05c269d0178d2a72ad484b10b11dd370193fc923201c3b27a99f848745db70a"
    const val MODEL_BYTES = 165_612_636L
    const val MODEL_URL = "https://huggingface.co/StemSplitio/htdemucs-onnx/resolve/$MODEL_COMMIT/$MODEL_FILE"
    const val MODEL_CARD = "https://huggingface.co/StemSplitio/htdemucs-onnx/blob/$MODEL_COMMIT/README.md"
    // Actual native activation/RSS is separate from owned PCM. This is admission headroom, not a quality/latency guarantee.
    const val REQUIRED_AVAILABLE_RAM = 1536L * 1024 * 1024
    const val MIN_TOTAL_RAM = 3584L * 1024 * 1024
    const val PIPELINE_PCM_BYTES = FRAMES * 4L * 14 + 4097L * 512 * 4 + 256 * 1024
    const val NATIVE_IO_PCM_BYTES = FRAMES * 4L * (2 + 8 + 8 + 8)
}
data class SeparationMemory @JvmOverloads constructor(val totalBytes: Long, val availableBytes: Long, val lowMemory: Boolean,
                                                     val receipt: SeparationMemoryReceipt? = null) {
    fun refusal(): SeparationProblem? = when {
        totalBytes <= 0 || availableBytes <= 0 || availableBytes > totalBytes -> SeparationProblem.RAM_UNAVAILABLE
        lowMemory || totalBytes < FourStemSpec.MIN_TOTAL_RAM || availableBytes < FourStemSpec.REQUIRED_AVAILABLE_RAM -> SeparationProblem.LOW_MEMORY
        else -> null
    }
}
internal class SeparationException(val problem: SeparationProblem) : IllegalStateException(problem.name)
