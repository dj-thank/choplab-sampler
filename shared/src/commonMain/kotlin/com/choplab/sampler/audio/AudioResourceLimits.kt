package com.choplab.sampler.audio

/**
 * Portable resource ceilings used at platform I/O boundaries.
 *
 * Archive-format limits remain in ProjectLimits. These values describe what a running
 * preview is willing to materialize or record without exhausting typical devices.
 */
object AudioResourceLimits {
    const val MAX_IMPORT_DURATION_SECONDS = 10L * 60L
    const val MAX_DECODED_MONO_FRAMES = 30_000_000
    const val MAX_IMPORT_FILE_BYTES = 256L * 1024L * 1024L
    const val MAX_RECORDING_DURATION_SECONDS = 10L * 60L
    const val MIN_FREE_DISK_RESERVE_BYTES = 64L * 1024L * 1024L
    const val MAX_MOBILE_PROJECT_PCM_BYTES = 192L * 1024L * 1024L
    const val MAX_DESKTOP_PROJECT_PCM_BYTES = 384L * 1024L * 1024L

    fun requireImportFileSize(sizeBytes: Long?) {
        if (sizeBytes == null || sizeBytes < 0L) return
        require(sizeBytes <= MAX_IMPORT_FILE_BYTES) {
            "音声ファイルが大きすぎます。256 MiB以下のファイルを使用してください"
        }
    }

    fun maxRecordingPcmBytes(sampleRate: Int, channelCount: Int): Long {
        require(sampleRate in 8_000..192_000) { "Unsupported recording sample rate" }
        require(channelCount in 1..2) { "Unsupported recording channel count" }
        return sampleRate.toLong() *
            channelCount.toLong() *
            Short.SIZE_BYTES.toLong() *
            MAX_RECORDING_DURATION_SECONDS
    }
}

enum class RecordingStopReason {
    DURATION_LIMIT,
    LOW_DISK,
}

data class RecordingWriteDecision(
    val writableBytes: Int,
    val stopAfterWrite: RecordingStopReason? = null,
)

/**
 * Frame-aligned byte budget for streaming PCM-16 recording.
 *
 * Call [decide] before every write and [commit] only after the writer reports success.
 * This keeps duration and disk-reserve arithmetic deterministic and testable without an
 * audio device.
 */
class RecordingBudget(
    sampleRate: Int,
    channelCount: Int,
    private val minimumFreeDiskReserveBytes: Long = AudioResourceLimits.MIN_FREE_DISK_RESERVE_BYTES,
    maximumPcmBytes: Long = AudioResourceLimits.maxRecordingPcmBytes(sampleRate, channelCount),
) {
    private val blockAlign = channelCount * Short.SIZE_BYTES
    private val maximumPcmBytes = maximumPcmBytes.alignedDown(blockAlign)

    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channelCount in 1..2) { "Only mono and stereo are supported" }
        require(minimumFreeDiskReserveBytes >= 0L) { "minimumFreeDiskReserveBytes must not be negative" }
        require(maximumPcmBytes > 0L) { "maximumPcmBytes must be positive" }
    }

    var pcmBytesWritten: Long = 0L
        private set

    val exhausted: Boolean
        get() = pcmBytesWritten >= maximumPcmBytes

    fun decide(requestedBytes: Int, usableSpaceBytes: Long): RecordingWriteDecision {
        require(requestedBytes >= 0) { "requestedBytes must not be negative" }
        require(usableSpaceBytes >= 0L) { "usableSpaceBytes must not be negative" }
        if (requestedBytes == 0) return RecordingWriteDecision(0)

        val requestedAligned = requestedBytes.toLong().alignedDown(blockAlign)
        if (requestedAligned == 0L) return RecordingWriteDecision(0)

        val durationRemaining = (maximumPcmBytes - pcmBytesWritten).coerceAtLeast(0L)
        if (durationRemaining == 0L) {
            return RecordingWriteDecision(0, RecordingStopReason.DURATION_LIMIT)
        }

        val diskWritable = (usableSpaceBytes - minimumFreeDiskReserveBytes)
            .coerceAtLeast(0L)
            .alignedDown(blockAlign)
        if (diskWritable == 0L) {
            return RecordingWriteDecision(0, RecordingStopReason.LOW_DISK)
        }

        val writable = minOf(requestedAligned, durationRemaining, diskWritable)
            .alignedDown(blockAlign)
        val reason = when {
            writable < requestedAligned && durationRemaining <= diskWritable ->
                RecordingStopReason.DURATION_LIMIT
            writable < requestedAligned -> RecordingStopReason.LOW_DISK
            writable == durationRemaining -> RecordingStopReason.DURATION_LIMIT
            else -> null
        }
        return RecordingWriteDecision(writable.toInt(), reason)
    }

    fun commit(writtenBytes: Int) {
        require(writtenBytes >= 0) { "writtenBytes must not be negative" }
        require(writtenBytes % blockAlign == 0) { "writtenBytes must contain complete PCM frames" }
        val next = pcmBytesWritten + writtenBytes.toLong()
        require(next <= maximumPcmBytes) { "Recording budget exceeded" }
        pcmBytesWritten = next
    }

    private fun Long.alignedDown(alignment: Int): Long = this - this % alignment
}
