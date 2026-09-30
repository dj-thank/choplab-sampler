package com.choplab.jvm

/** Cue-relative duration at the 48 kHz song clock; input maps both boundaries to native frames with ceil. */
data class VoiceCaptureWindow(val frames48k: Long, val armingSeconds: Int = 20) {
    init { require(frames48k in 1..300L * 48_000 && armingSeconds in 20..32) }
    /** Ceil of the absolute end, not ceil(start) + ceil(length), which can add a native frame at 44.1 kHz. */
    internal fun nativeEnd(relativeCueNanos: Long, sampleRate: Int): Long {
        require(sampleRate in 8_000..48_000)
        val cue = Math.multiplyExact(relativeCueNanos, sampleRate.toLong())
        val duration = frames48k * sampleRate
        val denominator = 48_000L * 1_000_000_000
        val fraction = Math.floorMod(cue, 1_000_000_000L) * 48_000 + (duration % 48_000) * 1_000_000_000
        return Math.floorDiv(cue, 1_000_000_000L) + duration / 48_000 + (fraction + denominator - 1) / denominator
    }
}
