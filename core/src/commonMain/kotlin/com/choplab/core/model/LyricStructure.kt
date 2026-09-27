package com.choplab.core.model

import com.choplab.core.ai.KanaMetrics
import com.choplab.core.ai.LyricLanguage
import com.choplab.core.ai.LyricSectionKind

enum class WordTimingOrigin { MANUAL, RETURNED, ESTIMATED }

/** The text snapshot binds a reading to its actual words, not merely to a reusable row ID. */
data class LyricReading(val lineId: String, val text: String, val reading: String, val mora: Int?, val rhymeVowels: String?) {
    init {
        requireId(lineId)
        requireLabel(text, 512)
        require(reading.length <= 512 && reading.none { it < ' ' || it == '\u007f' })
        require(mora == null || mora in 1..512)
        require(rhymeVowels == null || rhymeVowels.length in 1..512 && rhymeVowels.all { it in "aiueonq" })
    }
    companion object {
        fun create(lineId: String, text: String, reading: String, language: LyricLanguage): LyricReading {
            val metrics = if (language == LyricLanguage.JAPANESE) requireNotNull(KanaMetrics.analyze(reading)) { "Reading must use kana" } else null
            return LyricReading(lineId, text, reading, metrics?.mora, metrics?.vowels)
        }
    }
}

data class LyricSection(val name: String, val kind: LyricSectionKind, val bars: Int, val lines: FrozenList<LyricReading>) {
    init { requireLabel(name, 80); require(bars in 1..64 && lines.size in 1..64) }
}

/** Durable composition metadata. No provider keys, prompts, cache paths or session state. */
data class LyricStructure(val title: String, val language: LyricLanguage, val sections: FrozenList<LyricSection>) {
    init {
        requireLabel(title, 160); require(sections.size <= 8)
        val lines = sections.flatMap { it.lines }
        require(lines.size <= 64 && lines.map { it.lineId }.distinct().size == lines.size)
        require(lines.sumOf { it.text.length + it.reading.length } <= 32_768)
        lines.forEach { line ->
            val metrics = if (language == LyricLanguage.JAPANESE) requireNotNull(KanaMetrics.analyze(line.reading)) else null
            require(line.mora == metrics?.mora && line.rhymeVowels == metrics?.vowels) { "Reading metrics must be recomputed" }
        }
    }

    fun reading(lineId: String): LyricReading? = sections.firstNotNullOfOrNull { s -> s.lines.firstOrNull { it.lineId == lineId } }

    /** Keep stale text visible for correction, but never carry deleted rows' readings into new rows. */
    fun retainFor(lines: List<LyricLine>): LyricStructure {
        val ids = lines.map { it.id }.toSet()
        return copy(sections = sections.mapNotNull { section ->
            section.lines.filter { it.lineId in ids }.takeIf { it.isNotEmpty() }?.let { section.copy(lines = it.frozen()) }
        }.frozen())
    }
}
