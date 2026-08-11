package com.choplab.sampler.ui

enum class DeckOrientation {
    PORTRAIT,
    LANDSCAPE,
}

enum class DeckDensity {
    COMPACT,
    REGULAR,
}

enum class PerformanceWorkspaceLayout {
    STACKED,
    SPLIT_PAD_GRID,
}

data class DeckLayoutMetrics(
    val orientation: DeckOrientation,
    val density: DeckDensity,
    val contentPaddingDp: Int,
    val gapDp: Int,
    val headerHeightDp: Int,
    val modeBarHeightDp: Int,
    val statusHeightDp: Int,
    val controlHeightDp: Int,
    val waveformHeightDp: Int,
) {
    val showStatusStrip: Boolean
        get() = orientation != DeckOrientation.LANDSCAPE || density != DeckDensity.COMPACT

    val fixedChromeHeightDp: Int
        get() = headerHeightDp + modeBarHeightDp +
            (if (showStatusStrip) statusHeightDp else 0) +
            gapDp * (if (showStatusStrip) 3 else 2)
}

fun resolveDeckLayout(widthDp: Int, heightDp: Int): DeckLayoutMetrics {
    require(widthDp > 0) { "widthDp must be positive" }
    require(heightDp > 0) { "heightDp must be positive" }

    val orientation = if (widthDp > heightDp) {
        DeckOrientation.LANDSCAPE
    } else {
        DeckOrientation.PORTRAIT
    }
    val compact = if (orientation == DeckOrientation.PORTRAIT) {
        heightDp < 720 || widthDp < 380
    } else {
        heightDp < 440
    }

    return if (compact) {
        DeckLayoutMetrics(
            orientation = orientation,
            density = DeckDensity.COMPACT,
            contentPaddingDp = 6,
            gapDp = if (orientation == DeckOrientation.LANDSCAPE) 3 else 5,
            headerHeightDp = 40,
            modeBarHeightDp = 40,
            statusHeightDp = 28,
            controlHeightDp = 40,
            waveformHeightDp = if (orientation == DeckOrientation.PORTRAIT) 104 else 128,
        )
    } else {
        DeckLayoutMetrics(
            orientation = orientation,
            density = DeckDensity.REGULAR,
            contentPaddingDp = 10,
            gapDp = 8,
            headerHeightDp = 48,
            modeBarHeightDp = 46,
            statusHeightDp = 34,
            controlHeightDp = 46,
            waveformHeightDp = if (orientation == DeckOrientation.PORTRAIT) 132 else 160,
        )
    }
}

fun performanceWorkspaceLayout(metrics: DeckLayoutMetrics): PerformanceWorkspaceLayout =
    if (metrics.orientation == DeckOrientation.LANDSCAPE) {
        PerformanceWorkspaceLayout.SPLIT_PAD_GRID
    } else {
        PerformanceWorkspaceLayout.STACKED
    }
