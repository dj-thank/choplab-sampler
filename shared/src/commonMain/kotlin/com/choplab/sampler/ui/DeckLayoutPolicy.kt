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

enum class FocusedCaptureEntryLayout {
    STACKED,
    WIDE_SPLIT,
}

data class DeckLayoutMetrics(
    val orientation: DeckOrientation,
    val density: DeckDensity,
    val largeText: Boolean,
    val workflowRows: Int,
    val contentPaddingDp: Int,
    val gapDp: Int,
    val headerHeightDp: Int,
    val modeBarHeightDp: Int,
    val statusHeightDp: Int,
    val controlHeightDp: Int,
    val waveformHeightDp: Int,
) {
    val productionDockHeightDp: Int
        get() = controlHeightDp

    val showStatusStrip: Boolean
        get() = orientation != DeckOrientation.LANDSCAPE || density != DeckDensity.COMPACT

    val showInlineHeaderStatus: Boolean
        get() = !showStatusStrip

    val focusedCaptureNeedsScroll: Boolean
        get() = largeText ||
            (orientation == DeckOrientation.LANDSCAPE && density == DeckDensity.COMPACT)

    val beatWorkspaceNeedsScroll: Boolean
        get() = largeText

    val performanceWorkspaceNeedsScroll: Boolean
        get() = largeText

    val touchSafePadGridHeightDp: Int
        get() = 4 * maxOf(48, controlHeightDp) + 3 * gapDp

    val beatPadGridHeightDp: Int
        get() = touchSafePadGridHeightDp

    val fixedChromeHeightDp: Int
        get() = headerHeightDp + modeBarHeightDp +
            (if (showStatusStrip) statusHeightDp else 0) +
            gapDp * (if (showStatusStrip) 3 else 2)

    fun workspaceHeightAfterProductionDock(totalHeightDp: Int): Int =
        totalHeightDp - fixedChromeHeightDp - productionDockHeightDp
}

fun resolveDeckLayout(widthDp: Int, heightDp: Int, fontScale: Float = 1f): DeckLayoutMetrics {
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
    val largeText = fontScale.isFinite() && fontScale >= 1.2f
    val workflowRows = if (largeText) 2 else 1

    return if (compact) {
        val primaryControlHeight = if (orientation == DeckOrientation.PORTRAIT || largeText) 48 else 40
        DeckLayoutMetrics(
            orientation = orientation,
            density = DeckDensity.COMPACT,
            largeText = largeText,
            workflowRows = workflowRows,
            contentPaddingDp = 6,
            gapDp = if (orientation == DeckOrientation.LANDSCAPE) 3 else 5,
            headerHeightDp = if (largeText) 60 else primaryControlHeight,
            modeBarHeightDp = if (largeText) primaryControlHeight * 2 + 5 else primaryControlHeight,
            statusHeightDp = if (largeText) 72 else 28,
            controlHeightDp = primaryControlHeight,
            waveformHeightDp = if (orientation == DeckOrientation.PORTRAIT) 104 else 128,
        )
    } else {
        DeckLayoutMetrics(
            orientation = orientation,
            density = DeckDensity.REGULAR,
            largeText = largeText,
            workflowRows = workflowRows,
            contentPaddingDp = 10,
            gapDp = 8,
            headerHeightDp = if (largeText) 64 else 48,
            modeBarHeightDp = if (largeText) 104 else 48,
            statusHeightDp = if (largeText) 72 else 34,
            controlHeightDp = 48,
            waveformHeightDp = if (orientation == DeckOrientation.PORTRAIT) 132 else 160,
        )
    }
}

fun performanceWorkspaceLayout(metrics: DeckLayoutMetrics): PerformanceWorkspaceLayout =
    if (metrics.orientation == DeckOrientation.LANDSCAPE && !metrics.performanceWorkspaceNeedsScroll) {
        PerformanceWorkspaceLayout.SPLIT_PAD_GRID
    } else {
        PerformanceWorkspaceLayout.STACKED
    }

fun focusedCaptureEntryLayout(metrics: DeckLayoutMetrics): FocusedCaptureEntryLayout =
    if (
        metrics.orientation == DeckOrientation.LANDSCAPE &&
        metrics.density == DeckDensity.REGULAR &&
        !metrics.largeText
    ) {
        FocusedCaptureEntryLayout.WIDE_SPLIT
    } else {
        FocusedCaptureEntryLayout.STACKED
    }
