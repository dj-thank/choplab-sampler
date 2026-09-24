package com.choplab.sampler.ui

import kotlin.math.ceil
import kotlin.math.max

internal data class DeckButtonCopy(
    val primary: String,
    val secondary: String?,
    val primarySizeSp: Float,
    val primaryLineHeightSp: Float,
    val primaryMaxLines: Int,
)

/** Prioritize the action, not a repeated English caption. Never cancel the user's font scale. */
internal fun deckButtonCopy(label: String, compact: Boolean, fontScale: Float, heightDp: Float): DeckButtonCopy {
    val scale = fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f
    val size = if (compact) 10f else 12f
    val line = if (compact) 13f else 15f
    val lines = label.split('\n', limit = 2)
    val caption = lines.getOrNull(1)
    val roomForCaption = heightDp.isFinite() && heightDp >= (line + 11f) * scale
    // Only redundant all-uppercase captions may be omitted. Numbers/Japanese remain available.
    val redundantCaption = caption != null && caption.isNotEmpty() && caption.all {
        it in 'A'..'Z' || it == ' ' || it == '/' || it == '_' || it == '-'
    }
    val secondary = caption?.takeIf { roomForCaption }
    val primary = if (caption != null && secondary == null && !redundantCaption) {
        "${lines[0]} $caption"
    } else lines[0]
    return DeckButtonCopy(
        primary, secondary, size, line,
        if (secondary == null && heightDp >= line * scale * 2f) 2 else 1,
    )
}

internal fun parameterAccessibleName(label: String): String = when (label.uppercase()) {
    "BPM", "TEMPO" -> "テンポ BPM"
    "KEY", "PITCH" -> "音程 KEY"
    "TONE" -> "音色 TONE"
    "LEVEL", "GAIN" -> "音量 LEVEL"
    "SW", "SWING" -> "スウィング"
    else -> label
}

internal fun parameterAdjustmentDescription(label: String, value: String, increase: Boolean): String =
    "${parameterAccessibleName(label)}を${if (increase) "上げる" else "下げる"}。現在 $value"

/** Label-based controls in the landscape deck get the same precise semantics as steppers. */
internal fun machineButtonAccessibleDescription(label: String): String {
    val heading = label.substringBefore('\n')
    val increase = when {
        heading.endsWith(" +") -> true
        heading.endsWith(" -") || heading.endsWith(" −") -> false
        else -> return label
    }
    val parameter = heading.dropLast(2)
    when (parameter) {
        "BPM", "TEMPO", "SW", "SWING", "KEY", "PITCH", "TONE", "LEVEL", "GAIN" -> Unit
        else -> return label
    }
    val current = label.substringAfter('\n', "")
    return if (current.isEmpty()) {
        "${parameterAccessibleName(parameter)}を${if (increase) "上げる" else "下げる"}"
    } else parameterAdjustmentDescription(parameter, current, increase)
}

internal data class FinishWorkspaceGeometry(
    val sideBySide: Boolean,
    val actionRowHeightDp: Int,
    val actionsHeightDp: Int,
    val summaryHeightDp: Int,
)

/** Four real action rows (play, export, save/open, undo/redo); spacing and font expansion never shrink their 48dp target. */
internal fun finishWorkspaceGeometry(
    widthDp: Float,
    heightDp: Float,
    fontScale: Float,
    gapDp: Int,
): FinishWorkspaceGeometry {
    val scale = fontScale.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(4f) ?: 1f
    val gap = gapDp.coerceIn(0, 32)
    val row = max(48, ceil(30f * scale + 8f).toInt())
    val actions = row * 4 + gap * 3
    val height = heightDp.takeIf { it.isFinite() && it > 0f }?.coerceAtMost(100_000f) ?: 0f
    val summaryMinimum = ceil(160f * max(1f, scale)).toInt()
    val wide = widthDp.isFinite() && widthDp >= 720f && scale < 1.2f && height >= actions
    return FinishWorkspaceGeometry(
        sideBySide = wide,
        actionRowHeightDp = row,
        actionsHeightDp = actions,
        summaryHeightDp = max(summaryMinimum, (height - actions - gap).toInt()),
    )
}
