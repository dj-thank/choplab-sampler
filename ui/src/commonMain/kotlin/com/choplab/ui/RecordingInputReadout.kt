package com.choplab.ui

/** Facts sampled from the owned input, never a device name or private path. Unknown values stay null. */
data class RecordingInputReadout(
    val recordedMillis: Long = 0,
    val limitMillis: Long? = null,
    val peakLevel: Float? = null,
    /** A stopped recording still needs explicit acceptance into the document, or explicit discard. */
    val pendingSave: Boolean = false,
    val interruption: RecordingInterruption? = null,
)

enum class RecordingInterruption { DEVICE_LOST, READ_FAILED, STORAGE_FAILED, OUTPUT_LOST, UNKNOWN }
