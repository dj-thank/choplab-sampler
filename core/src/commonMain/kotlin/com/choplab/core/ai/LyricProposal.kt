package com.choplab.core.ai

import com.choplab.core.model.*

enum class LyricLanguage(val code: String) { JAPANESE("ja"), ENGLISH("en") }
enum class LyricStyle { RAP, SONG }
/** A host decision after checking provider use eligibility, separate from per-request sending consent. */
enum class LyricProviderAvailability { UNVERIFIED, AVAILABLE }

/** Only these explicitly entered fields are sent. No Project, audio, filename or document ID is provider input. */
class LyricRequest(
    val model: String, val theme: String, val mood: String, val language: LyricLanguage,
    val style: LyricStyle, val structure: String, val rhyme: String, val keepLines: String,
    val googleAttempt: GoogleLyricAttempt? = null,
) {
    val maxOutputTokens: Int get() = googleAttempt?.maxOutputTokens ?: 8192
    fun forAttempt(attempt: GoogleLyricAttempt) = LyricRequest(model, theme, mood, language, style, structure, rhyme, keepLines, attempt)
    init {
        require(model.matches(Regex("gemini-[a-z0-9][a-z0-9._-]{0,79}")))
        require(theme.isNotBlank() && theme.length <= 1_024)
        require(mood.length <= 512 && structure.length <= 1_024 && rhyme.length <= 512 && keepLines.length <= 4_096)
        require(listOf(theme, mood, structure, rhyme, keepLines).all { value -> value.none { it == '\u0000' || it == '\r' } })
    }
    override fun toString() = "LyricRequest([private text])"
}

/** Session input only. Never serializable, saved, or included in diagnostics. Closing drops and clears our own copy. */
class SessionApiKey(value: String) {
    internal val identity = Any()
    private val characters = value.toCharArray()
    private var closed = false
    init { require(value.length in 1..256 && value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) }
    fun <T> useValue(block: (String) -> T): T { check(!closed); return block(characters.concatToString()) }
    fun close() { closed = true; characters.fill('\u0000') }
    override fun toString() = "SessionApiKey([redacted])"
}

enum class LyricSectionKind { INTRO, VERSE, CHORUS, BRIDGE, OUTRO }

class ProposalLine private constructor(val text: String, val reading: String, val mora: Int?, val rhymeVowels: String?) {
    companion object {
        fun create(text: String, reading: String, language: LyricLanguage): ProposalLine {
            require(singleLine(text, 512) && text.isNotBlank() && singleLine(reading, 512))
            val metrics = if (language == LyricLanguage.JAPANESE) KanaMetrics.analyze(reading) else null
            require(language != LyricLanguage.JAPANESE || metrics != null)
            return ProposalLine(text, reading, metrics?.mora, metrics?.vowels)
        }
    }
    override fun toString() = "ProposalLine([private text])"
}
class ProposalSection(val name: String, val kind: LyricSectionKind, val bars: Int, val lines: FrozenList<ProposalLine>) {
    init { require(singleLine(name, 80) && name.isNotBlank() && bars in 1..64 && lines.size in 1..16) }
    override fun toString() = "ProposalSection(lines=${lines.size})"
}
class LyricProposal(val title: String, val language: LyricLanguage, val sections: FrozenList<ProposalSection>) {
    init {
        require(singleLine(title, 160) && title.isNotBlank() && sections.size in 1..8)
        require(sections.sumOf { it.lines.size } <= 64)
        require(sections.sumOf { section -> section.lines.sumOf { it.text.length + it.reading.length } } <= 32_768)
    }
    val lineCount: Int get() = sections.sumOf { it.lines.size }

    /** Explicit manual placement, not inferred word alignment or an automatic flow plan. */
    fun place(startTick: Long, beatsPerLine: Int, idPrefix: String): FrozenList<LyricLine> {
        require(startTick >= 0 && beatsPerLine in 1..16 && idPrefix.matches(Regex("[A-Za-z0-9_-]{1,48}")))
        val length = beatsPerLine.toLong() * ProjectLimits.PPQ
        require(startTick <= ProjectLimits.MAX_TIMELINE_TICKS - lineCount * length)
        return sections.flatMap { it.lines }.mapIndexed { index, line ->
            val from = startTick + index * length
            LyricLine("$idPrefix-$index", line.text, from, from + length)
        }.frozen()
    }
    override fun toString() = "LyricProposal(lines=$lineCount)"
}

/** Provider-reported token counts only. Null means unreported, never a zero/price estimate. */
data class LyricUsage(val inputTokens: Long?, val outputTokens: Long?, val totalTokens: Long?) {
    init { require(listOfNotNull(inputTokens, outputTokens, totalTokens).all { it in 0..1_000_000_000 }) }
}
enum class LyricAiProblem {
    INVALID_INPUT, PROVIDER_UNVERIFIED, CONSENT_REQUIRED, AUTHENTICATION, UNKNOWN_MODEL, RATE_LIMITED, OFFLINE, TIMEOUT,
    CANCELLED, PROVIDER_REJECTED, INVALID_RESPONSE, STALE_DOCUMENT, APPLY_REJECTED, CLOSED,
    SESSION_ADMISSION_REFUSED,
}
data class LyricAiFailure(val problem: LyricAiProblem, val retryAfterSeconds: Long? = null,
    /** Cancellation/timeout after attempting HTTP can still incur provider charges. */ val costUnknown: Boolean = false,
    val admissionProblem: GoogleAdmissionProblem? = null) {
    init { require(retryAfterSeconds == null || retryAfterSeconds in 0..31_536_000) }
}
sealed interface LyricProviderResult {
    data class Success(val proposal: LyricProposal, val usage: LyricUsage?, val modelVersion: String?) : LyricProviderResult
    data class Failure(val failure: LyricAiFailure) : LyricProviderResult
}
interface LlmProvider {
    suspend fun lyrics(request: LyricRequest, key: SessionApiKey): LyricProviderResult
    fun close() {}
}

private fun singleLine(value: String, max: Int) = value.length <= max && value.none { it.code < 32 || it == '\u007f' }

data class KanaAnalysis(val mora: Int, val vowels: String)

/** Deterministic kana-only estimate. Kanji/Latin readings are rejected instead of inventing a pronunciation. */
object KanaMetrics {
    fun analyze(reading: String): KanaAnalysis? {
        var count = 0
        val vowels = StringBuilder()
        for (raw in reading) {
            val ch = if (raw in 'ァ'..'ヶ') (raw.code - 0x60).toChar() else raw
            if (ch.isWhitespace() || ch in "、。・！？!?.,") continue
            if (ch == 'ー') {
                if (vowels.isEmpty() || vowels.last() !in "aiueo") return null
                count++; vowels.append(vowels.last()); continue
            }
            val vowel = when (ch) {
                in "あかがさざただなはばぱまやらわぁゃゎ" -> 'a'
                in "いきぎしじちぢにひびぴみりゐぃ" -> 'i'
                in "うくぐすずつづぬふぶぷむゆるゔぅゅ" -> 'u'
                in "えけげせぜてでねへべぺめれゑぇ" -> 'e'
                in "おこごそぞとどのほぼぽもよろをぉょ" -> 'o'
                'ん' -> 'n'
                'っ' -> 'q'
                else -> return null
            }
            if (ch in "ぁぃぅぇぉゃゅょゎ") {
                if (vowels.isEmpty() || vowels.last() !in "aiueo") return null
                vowels.setCharAt(vowels.lastIndex, vowel)
            } else { count++; vowels.append(vowel) }
        }
        return if (count == 0) null else KanaAnalysis(count, vowels.toString())
    }
}
