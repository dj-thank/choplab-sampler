package com.choplab.core.ai

import com.choplab.core.model.*

data class StructuredLyricPlacement(val lines: FrozenList<LyricLine>, val structure: LyricStructure)

/** Preserve the provider's whole bounded composition when the user confirms its placement. */
fun LyricProposal.placeStructured(startTick: Long, beatsPerLine: Int, idPrefix: String): StructuredLyricPlacement {
    val lines = place(startTick, beatsPerLine, idPrefix)
    var index = 0
    val structure = LyricStructure(title, language, sections.map { section ->
        LyricSection(section.name, section.kind, section.bars, section.lines.map { line ->
            LyricReading.create(lines[index++].id, line.text, line.reading, language)
        }.frozen())
    }.frozen())
    return StructuredLyricPlacement(lines, structure)
}

enum class FlowMode(val ticks: Long) {
    ONE_BAR(4L * ProjectLimits.PPQ), TWO_BARS(8L * ProjectLimits.PPQ), DOUBLE_TIME(2L * ProjectLimits.PPQ),
}
enum class FlowDensityUnit { MORA, WORDS }
enum class FlowAdvice { SPLIT_OR_TWO_BARS, TRY_DOUBLE_TIME }
enum class FlowProblem { NO_LINES, MISSING_STRUCTURE, MISSING_READING, STALE_READING, INVALID_PLACEMENT }
data class FlowIssue(val problem: FlowProblem, val lineId: String? = null)
data class FlowRow(val line: LyricLine, val reading: LyricReading, val mode: FlowMode, val units: Int,
                   val densityUnit: FlowDensityUnit, val advice: FlowAdvice?) {
    val sixteenths: Int get() = (mode.ticks / (ProjectLimits.PPQ / 4)).toInt()
    val unitsPerSixteenth: Double get() = units.toDouble() / sixteenths
}
data class FlowPlan(val rows: FrozenList<FlowRow>, val structure: LyricStructure) {
    val lines: FrozenList<LyricLine> get() = rows.map { it.line }.frozen()
    val hasDensityAdvice: Boolean get() = rows.any { it.advice != null }
}
sealed interface FlowResult {
    data class Ready(val plan: FlowPlan) : FlowResult
    data class Invalid(val issue: FlowIssue) : FlowResult
}

/** Musical placement only: no timing is inferred from network callbacks or UI wall time. */
object FlowPlanner {
    fun plan(project: Project, startTick: Long, mode: FlowMode, overrides: Map<String, FlowMode> = emptyMap()): FlowResult {
        val structure = project.lyricStructure ?: return invalid(FlowProblem.MISSING_STRUCTURE)
        val readings = structure.sections.flatMap { it.lines }
        if (readings.isEmpty()) return invalid(FlowProblem.NO_LINES)
        if (overrides.keys.any { id -> readings.none { it.lineId == id } }) return invalid(FlowProblem.INVALID_PLACEMENT)
        if (startTick < 0 || startTick % (ProjectLimits.PPQ / 4) != 0L) return invalid(FlowProblem.INVALID_PLACEMENT)
        val byId = project.lyrics.associateBy { it.id }
        var tick = startTick
        val rows = ArrayList<FlowRow>(readings.size)
        for (reading in readings) {
            val line = byId[reading.lineId] ?: return invalid(FlowProblem.MISSING_READING, reading.lineId)
            if (reading.text != line.text) return invalid(FlowProblem.STALE_READING, line.id)
            val units: Int
            val unit: FlowDensityUnit
            if (structure.language == LyricLanguage.JAPANESE) {
                units = reading.mora ?: return invalid(FlowProblem.MISSING_READING, line.id)
                unit = FlowDensityUnit.MORA
            } else {
                units = (reading.reading.ifBlank { line.text }).split(Regex("\\s+")).count { it.isNotBlank() }
                unit = FlowDensityUnit.WORDS
            }
            val rowMode = overrides[line.id] ?: mode
            if (tick > ProjectLimits.MAX_TIMELINE_TICKS - rowMode.ticks) return invalid(FlowProblem.INVALID_PLACEMENT, line.id)
            val slots = rowMode.ticks / (ProjectLimits.PPQ / 4)
            val advice = when {
                units > slots -> FlowAdvice.SPLIT_OR_TWO_BARS
                units * 4 < slots -> FlowAdvice.TRY_DOUBLE_TIME
                else -> null
            }
            // Old word anchors cannot be asserted after a new duration is chosen. Synthesis supplies new provenance.
            rows += FlowRow(line.copy(startTick = tick, endTick = tick + rowMode.ticks, words = frozenListOf()), reading, rowMode, units, unit, advice)
            tick += rowMode.ticks
        }
        return FlowResult.Ready(FlowPlan(rows.frozen(), structure))
    }
    private fun invalid(problem: FlowProblem, lineId: String? = null) = FlowResult.Invalid(FlowIssue(problem, lineId))
}
