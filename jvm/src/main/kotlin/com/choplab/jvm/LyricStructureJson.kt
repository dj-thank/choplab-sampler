package com.choplab.jvm

import com.choplab.core.ai.LyricLanguage
import com.choplab.core.ai.LyricSectionKind
import com.choplab.core.model.*
import kotlinx.serialization.json.*

internal fun lyricStructureJson(value: LyricStructure?): JsonElement = value?.let { structure ->
    obj("title" to str(structure.title), "language" to str(structure.language.name),
        "sections" to arr(structure.sections.map { section -> obj("name" to str(section.name), "kind" to str(section.kind.name), "bars" to num(section.bars),
            "lines" to arr(section.lines.map { line -> obj("lineId" to str(line.lineId), "text" to str(line.text), "reading" to str(line.reading),
                "mora" to (line.mora?.let(::num) ?: JsonNull), "rhymeVowels" to nullable(line.rhymeVowels)) })) }))
} ?: JsonNull

internal fun readLyricStructure(value: JsonElement): LyricStructure? {
    if (value == JsonNull) return null
    val structure = value.obj().fields("title", "language", "sections")
    return LyricStructure(structure.string("title"), LyricLanguage.valueOf(structure.string("language")), structure.list("sections", 8) { item ->
        val section = item.obj().fields("name", "kind", "bars", "lines")
        LyricSection(section.string("name"), LyricSectionKind.valueOf(section.string("kind")), section.int("bars"), section.list("lines", 64) { entry ->
            val line = entry.obj().fields("lineId", "text", "reading", "mora", "rhymeVowels")
            LyricReading(line.string("lineId"), line.string("text"), line.string("reading"),
                if (line.getValue("mora") == JsonNull) null else line.int("mora"), line.optionalString("rhymeVowels"))
        })
    })
}
