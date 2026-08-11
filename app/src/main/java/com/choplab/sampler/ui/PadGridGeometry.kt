package com.choplab.sampler.ui

import kotlin.math.min

data class SquarePadGridGeometry(
    val cellSize: Float,
    val contentWidth: Float,
    val contentHeight: Float,
)

fun resolveSquarePadGrid(
    width: Float,
    height: Float,
    gap: Float,
    columns: Int,
    padCount: Int = 16,
): SquarePadGridGeometry {
    require(columns in 1..padCount) { "columns must be between 1 and padCount" }
    require(padCount % columns == 0) { "columns must divide padCount" }
    val rows = padCount / columns
    val safeGap = gap.coerceAtLeast(0f)
    val availableWidth = (width - safeGap * (columns - 1)).coerceAtLeast(0f)
    val availableHeight = (height - safeGap * (rows - 1)).coerceAtLeast(0f)
    val cellSize = min(availableWidth / columns, availableHeight / rows).coerceAtLeast(0f)
    return SquarePadGridGeometry(
        cellSize = cellSize,
        contentWidth = cellSize * columns + safeGap * (columns - 1),
        contentHeight = cellSize * rows + safeGap * (rows - 1),
    )
}
