package com.choplab.core.separation

enum class SeparationMemorySource(val estimated: Boolean) {
    MAC_FREE_AND_FILE_BACKED(true), WINDOWS_GLOBAL_MEMORY_STATUS(false), LINUX_MEM_AVAILABLE(true), ANDROID_ACTIVITY_MANAGER(false),
}

/** A fresh admission observation, never a reservation or a promise that a later allocation will succeed. */
data class SeparationMemoryReceipt(
    val source: SeparationMemorySource,
    val totalBytes: Long,
    val availableBytes: Long,
    val lowMemory: Boolean,
    val measuredAtEpochMillis: Long,
) {
    init { require(totalBytes > 0 && availableBytes in 0..totalBytes && measuredAtEpochMillis > 0) }
}
