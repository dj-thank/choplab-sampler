package com.choplab.sampler.source

/** Provider observations, not a quality rating. Unknown fields stay unknown. No signed media URLs. */
data class YoutubeMetadata(
    val thumbnailUrl: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val uploaderVerified: Boolean? = null,
    val formats: List<YoutubeAudioFormat> = emptyList(),
)

data class YoutubeAudioFormat(
    val id: String,
    val container: String,
    val codec: String?,
    val sampleRate: Int?,
    val channels: Int?,
    val bitrate: Int?,
    val approximateBitrate: Boolean,
    val bytes: Long?,
    val language: String?,
    val trackName: String?,
    val trackType: String?,
    val dynamicRangeCompressed: Boolean?,
)

enum class YoutubeSearchKind { VIDEOS, MUSIC }

/** Shared provider capability; UI projections carry this limit instead of guessing from displayed text. */
object OnlineSourceLimits { const val MAX_AUDIO_BYTES = 256L * 1024 * 1024 }
fun YoutubeAudioFormat.acquisitionProblem(): OnlineSourceProblem? = when {
    bytes == null -> null
    bytes <= 0 -> OnlineSourceProblem.MALFORMED_RESPONSE
    bytes > OnlineSourceLimits.MAX_AUDIO_BYTES -> OnlineSourceProblem.TOO_LARGE
    else -> null
}

enum class OnlineSourceProblem {
    INVALID_INPUT, UNAVAILABLE, RESTRICTED, RATE_LIMITED, TOO_LARGE, MALFORMED_RESPONSE,
    NETWORK, TIMED_OUT, CANCELLED, CLOSED, BUSY, UNSUPPORTED_FORMAT, FORMAT_CHANGED, INVALID_AUDIO, STORAGE,
}
