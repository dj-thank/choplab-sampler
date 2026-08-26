package com.choplab.sampler.ui

import com.choplab.sampler.model.SamplerConfig

internal const val FOCUSED_STEP_MIN_TARGET_DP = 48f
internal const val FOCUSED_STEP_HEADER_HEIGHT_DP = 48f
internal const val FOCUSED_STEP_COMPACT_LANDSCAPE_GAP_DP = 2f

internal data class FocusedStepCellBounds(
    val stepIndex: Int,
    val row: Int,
    val column: Int,
    val leftDp: Float,
    val topDp: Float,
    val widthDp: Float,
    val heightDp: Float,
) {
    val stepNumber: Int
        get() = stepIndex + 1

    val rightDp: Float
        get() = leftDp + widthDp

    val bottomDp: Float
        get() = topDp + heightDp
}

internal data class FocusedStepLayout(
    val orientation: DeckOrientation,
    val columns: Int,
    val rows: Int,
    val headerHeightDp: Float,
    val cellGapDp: Float,
    val gridLeftDp: Float,
    val gridTopDp: Float,
    val gridWidthDp: Float,
    val gridHeightDp: Float,
    val cellWidthDp: Float,
    val cellHeightDp: Float,
    val cells: List<FocusedStepCellBounds>,
) {
    val gridRightDp: Float
        get() = gridLeftDp + gridWidthDp

    val gridBottomDp: Float
        get() = gridTopDp + gridHeightDp

    val minimumCellWidthDp: Float
        get() = cells.minOfOrNull(FocusedStepCellBounds::widthDp) ?: 0f

    val minimumCellHeightDp: Float
        get() = cells.minOfOrNull(FocusedStepCellBounds::heightDp) ?: 0f
}

internal fun resolveFocusedStepLayout(
    metrics: DeckLayoutMetrics,
    widthDp: Int,
    heightDp: Int,
): FocusedStepLayout? {
    if (widthDp <= 0 || heightDp <= 0) return null

    val viewportOrientation = if (widthDp > heightDp) {
        DeckOrientation.LANDSCAPE
    } else {
        DeckOrientation.PORTRAIT
    }
    if (viewportOrientation != metrics.orientation) return null

    val columns = if (metrics.orientation == DeckOrientation.PORTRAIT) 4 else 8
    if (SamplerConfig.STEP_COUNT % columns != 0) return null
    val rows = SamplerConfig.STEP_COUNT / columns
    val cellGapDp = if (
        metrics.orientation == DeckOrientation.LANDSCAPE &&
        metrics.density == DeckDensity.COMPACT
    ) {
        FOCUSED_STEP_COMPACT_LANDSCAPE_GAP_DP
    } else {
        metrics.gapDp.toFloat()
    }
    val contentPaddingDp = metrics.contentPaddingDp.toFloat()
    val gridLeftDp = contentPaddingDp
    val gridTopDp = contentPaddingDp + metrics.fixedChromeHeightDp +
        FOCUSED_STEP_HEADER_HEIGHT_DP + cellGapDp
    val gridWidthDp = widthDp - contentPaddingDp * 2f
    val workspaceHeightDp = heightDp - contentPaddingDp * 2f - metrics.fixedChromeHeightDp
    val gridHeightDp = workspaceHeightDp - FOCUSED_STEP_HEADER_HEIGHT_DP - cellGapDp
    if (gridWidthDp <= 0f || gridHeightDp <= 0f) return null

    val cellWidthDp = (gridWidthDp - cellGapDp * (columns - 1)) / columns
    val cellHeightDp = (gridHeightDp - cellGapDp * (rows - 1)) / rows
    if (
        !cellWidthDp.isFinite() ||
        !cellHeightDp.isFinite() ||
        cellWidthDp < FOCUSED_STEP_MIN_TARGET_DP ||
        cellHeightDp < FOCUSED_STEP_MIN_TARGET_DP
    ) {
        return null
    }

    val cells = List(SamplerConfig.STEP_COUNT) { stepIndex ->
        val row = stepIndex / columns
        val column = stepIndex % columns
        FocusedStepCellBounds(
            stepIndex = stepIndex,
            row = row,
            column = column,
            leftDp = gridLeftDp + column * (cellWidthDp + cellGapDp),
            topDp = gridTopDp + row * (cellHeightDp + cellGapDp),
            widthDp = cellWidthDp,
            heightDp = cellHeightDp,
        )
    }

    return FocusedStepLayout(
        orientation = metrics.orientation,
        columns = columns,
        rows = rows,
        headerHeightDp = FOCUSED_STEP_HEADER_HEIGHT_DP,
        cellGapDp = cellGapDp,
        gridLeftDp = gridLeftDp,
        gridTopDp = gridTopDp,
        gridWidthDp = gridWidthDp,
        gridHeightDp = gridHeightDp,
        cellWidthDp = cellWidthDp,
        cellHeightDp = cellHeightDp,
        cells = cells,
    )
}
