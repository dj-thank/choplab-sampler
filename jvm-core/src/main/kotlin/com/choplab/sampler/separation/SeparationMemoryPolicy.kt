package com.choplab.sampler.separation

import kotlin.math.roundToInt

/**
 * Android runs the drums model without graph optimization (see [OnnxDrumChunkInference]): one
 * 7.8 s segment then peaks near 1.0 GB of working memory instead of 4.7 GB, at any thread count
 * (ONNX Runtime 1.29). The segment length is fixed by the model, so devices below 3.5 GiB of
 * physical memory (smaller than 4 GB-class phones) are refused before any download or allocation.
 */
object SeparationMemoryPolicy {
    const val MIN_TOTAL_MEMORY_BYTES = 3584L * 1024 * 1024

    /** [totalMemoryBytes] is 0 when the platform cannot report it; that alone does not block. */
    fun blockedReason(totalMemoryBytes: Long, systemLowMemory: Boolean): String? = when {
        totalMemoryBytes in 1 until MIN_TOTAL_MEMORY_BYTES ->
            "この端末のメモリ（約${(totalMemoryBytes / 1_000_000_000.0).roundToInt()}GB）ではドラム分離を実行できません。" +
                "4GB以上の端末かPC版で分離してください"
        systemLowMemory -> "端末のメモリが不足しています。他のアプリを閉じてから、もう一度ドラム分離を試してください"
        else -> null
    }
}
