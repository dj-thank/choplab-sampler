package com.choplab.core.ai

import com.choplab.core.model.*

object TtsLimits {
    const val MAX_SECONDS = 30
    const val MAX_DECODED_BYTES = 192_000L * MAX_SECONDS * 2 * 4
    const val MAX_WAV_BYTES = MAX_DECODED_BYTES + 65_536
}

/** Public synthesis identity, never a path or credential. Versions prevent cross-engine cache reuse. */
data class TtsEngine(val provider: String, val providerVersion: String, val model: String, val modelVersion: String) {
    init { listOf(provider, providerVersion, model, modelVersion).forEach { requireLabel(it, 160) } }
}
data class TtsVoice(val engine: TtsEngine, val id: String, val name: String, val locale: String, val version: String,
                    val language: LyricLanguage, val offline: Boolean = true, val supportsPitch: Boolean = false) {
    init { listOf(id, name, version).forEach { requireLabel(it, 256) }; requireLabel(locale, 32) }
}

/** Integer settings are stable cache identity; unsupported settings must be refused, never ignored. */
data class TtsSettings(val ratePermille: Int = 1000, val pitchPermille: Int = 1000, val volumePermille: Int = 1000,
                       val style: String = "neutral") {
    init {
        require(ratePermille in 500..2000 && pitchPermille in 500..2000 && volumePermille in 0..1000)
        requireLabel(style, 80)
    }
}
data class TtsRequest(val text: String, val reading: String, val voice: TtsVoice, val settings: TtsSettings = TtsSettings()) {
    init { requireLabel(text, 512); require(reading.length <= 512 && reading.none { it < ' ' || it == '\u007f' }) }
    val spokenText: String get() = reading.ifBlank { text }
    override fun toString() = "TtsRequest([private text])"
}

/** Both boundaries refer to frames of the returned native PCM. Unreturned ends are estimates. */
data class TtsWord(val text: String, val startFrame: Long, val endFrame: Long, val origin: WordTimingOrigin) {
    init { requireLabel(text, 256); require(startFrame >= 0 && endFrame > startFrame) }
}
class TtsAudio private constructor(private val samples: FloatArray, val sampleRate: Int, val channels: Int, val words: FrozenList<TtsWord>) {
    val frames: Int get() = samples.size / channels
    val bytes: Long get() = samples.size.toLong() * 4
    fun copySamples(): FloatArray = samples.copyOf()
    companion object {
        /** Input ownership stays with the caller; no caller mutation can alter a prepared proposal. */
        fun fromPcm(samples: FloatArray, sampleRate: Int, channels: Int, words: List<TtsWord> = emptyList()): TtsAudio {
            require(sampleRate in 8_000..192_000 && channels in 1..2)
            require(samples.isNotEmpty() && samples.size % channels == 0 && samples.size.toLong() * 4 <= TtsLimits.MAX_DECODED_BYTES)
            val frames = samples.size / channels
            require(frames <= sampleRate * TtsLimits.MAX_SECONDS && samples.all { it.isFinite() })
            require(words.size <= 256 && words.all { it.endFrame <= frames } && words.zipWithNext().all { (a, b) -> a.endFrame <= b.startFrame })
            return TtsAudio(samples.copyOf(), sampleRate, channels, words.frozen())
        }
    }
    override fun toString() = "TtsAudio(frames=$frames, sampleRate=$sampleRate, channels=$channels)"
}

enum class TtsProblem {
    UNAVAILABLE, NO_OFFLINE_VOICE, VOICE_CHANGED, UNSUPPORTED_SETTINGS, INVALID_INPUT, INVALID_AUDIO, TOO_LARGE,
    SILENT_AUDIO, TOO_SHORT, CANNOT_FIT, CANCELLED, CLOSED, TIMEOUT, FAILED, CACHE_FULL, STALE_DOCUMENT,
    RECORDING, BUSY, APPLY_REJECTED, DENSITY_CONFIRMATION,
}
data class TtsFailure(val problem: TtsProblem, val speedRequired: Double? = null) {
    init { require(speedRequired == null || speedRequired.isFinite() && speedRequired >= 0) }
}
sealed interface TtsResult<out T> {
    data class Success<T>(val value: T) : TtsResult<T>
    data class Failure(val failure: TtsFailure) : TtsResult<Nothing>
}
fun ttsFailure(problem: TtsProblem, speed: Double? = null) = TtsResult.Failure(TtsFailure(problem, speed))

/** Worker-only synthesis. Hosts supply installed device voices; a missing voice is not silent success. */
interface TtsProvider {
    suspend fun voices(): TtsResult<FrozenList<TtsVoice>>
    suspend fun synthesize(request: TtsRequest): TtsResult<TtsAudio>
    fun close()
}
