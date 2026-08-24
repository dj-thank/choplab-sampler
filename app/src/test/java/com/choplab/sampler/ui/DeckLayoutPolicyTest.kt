package com.choplab.sampler.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeckLayoutPolicyTest {
    @Test
    fun compactPortraitKeepsChromeBelowOneQuarterOfShortPhone() {
        val metrics = resolveDeckLayout(widthDp = 360, heightDp = 640)

        assertEquals(DeckOrientation.PORTRAIT, metrics.orientation)
        assertEquals(DeckDensity.COMPACT, metrics.density)
        assertTrue(metrics.headerHeightDp >= 48)
        assertTrue(metrics.modeBarHeightDp >= 48)
        assertTrue(metrics.controlHeightDp >= 48)
        assertTrue(metrics.fixedChromeHeightDp < 160)
        assertTrue(640 - metrics.fixedChromeHeightDp >= 480)
        assertEquals(metrics.controlHeightDp, metrics.productionDockHeightDp)
        assertTrue(metrics.workspaceHeightAfterProductionDock(640) >= 440)
    }

    @Test
    fun regularPortraitAllocatesLargerControlsWithoutRequiringScroll() {
        val metrics = resolveDeckLayout(widthDp = 412, heightDp = 820, fontScale = 1f)

        assertEquals(DeckOrientation.PORTRAIT, metrics.orientation)
        assertEquals(DeckDensity.REGULAR, metrics.density)
        assertTrue(metrics.headerHeightDp >= 48)
        assertTrue(metrics.modeBarHeightDp >= 48)
        assertTrue(metrics.controlHeightDp >= 48)
        assertTrue(820 - metrics.fixedChromeHeightDp >= 650)
        assertTrue(metrics.workspaceHeightAfterProductionDock(820) >= 600)
        assertEquals(1, metrics.workflowRows)
        assertEquals(false, metrics.largeText)
        assertEquals(false, metrics.focusedCaptureNeedsScroll)
        assertEquals(false, metrics.beatWorkspaceNeedsScroll)
        assertEquals(false, metrics.performanceWorkspaceNeedsScroll)
        assertEquals(false, metrics.showInlineHeaderStatus)
    }

    @Test
    fun largeTextUsesTwoWorkflowRowsAndScrollableTouchSafeBeatBody() {
        val medium = resolveDeckLayout(widthDp = 412, heightDp = 820, fontScale = 1.3f)
        val largest = resolveDeckLayout(widthDp = 360, heightDp = 640, fontScale = 2f)

        listOf(medium, largest).forEach { metrics ->
            assertEquals(2, metrics.workflowRows)
            assertEquals(true, metrics.largeText)
            assertTrue(metrics.headerHeightDp >= 60)
            assertTrue(metrics.modeBarHeightDp >= 96)
            assertTrue(metrics.statusHeightDp >= 64)
            assertEquals(true, metrics.focusedCaptureNeedsScroll)
            assertEquals(true, metrics.beatWorkspaceNeedsScroll)
            assertEquals(true, metrics.performanceWorkspaceNeedsScroll)
            assertTrue(metrics.beatPadGridHeightDp >= 4 * 48 + 3 * metrics.gapDp)
            assertEquals(metrics.touchSafePadGridHeightDp, metrics.beatPadGridHeightDp)
        }
        assertTrue(medium.workspaceHeightAfterProductionDock(820) >= 480)
        assertTrue(largest.workspaceHeightAfterProductionDock(640) >= 300)
    }

    @Test
    fun compactLandscapeUsesWideConsoleComposition() {
        val metrics = resolveDeckLayout(widthDp = 800, heightDp = 320)

        assertEquals(DeckOrientation.LANDSCAPE, metrics.orientation)
        assertEquals(DeckDensity.COMPACT, metrics.density)
        assertEquals(
            PerformanceWorkspaceLayout.SPLIT_PAD_GRID,
            performanceWorkspaceLayout(metrics),
        )
        assertEquals(false, metrics.showStatusStrip)
        assertEquals(false, metrics.showInlineHeaderStatus)
        assertEquals(true, metrics.focusedCaptureNeedsScroll)
        assertEquals(false, metrics.beatWorkspaceNeedsScroll)
        assertEquals(false, metrics.performanceWorkspaceNeedsScroll)
        assertEquals(3, metrics.gapDp)
        assertTrue(metrics.waveformHeightDp <= 128)
        assertTrue(320 - metrics.fixedChromeHeightDp >= 230)
        assertTrue(metrics.workspaceHeightAfterProductionDock(320) >= 190)
    }

    @Test
    fun largeTextCompactLandscapeKeepsEveryActionRowAtLeastFortyEightDp() {
        listOf(1.3f, 2f).forEach { fontScale ->
            val metrics = resolveDeckLayout(widthDp = 640, heightDp = 360, fontScale = fontScale)

            assertEquals(DeckOrientation.LANDSCAPE, metrics.orientation)
            assertEquals(DeckDensity.COMPACT, metrics.density)
            assertTrue(metrics.largeText)
            assertEquals(2, metrics.workflowRows)
            assertTrue(metrics.controlHeightDp >= 48)
            assertTrue(metrics.modeBarHeightDp >= 48 * 2 + metrics.gapDp)
            assertTrue(metrics.productionDockHeightDp >= 48)
            assertTrue(metrics.performanceWorkspaceNeedsScroll)
            assertEquals(PerformanceWorkspaceLayout.STACKED, performanceWorkspaceLayout(metrics))
            assertTrue(metrics.touchSafePadGridHeightDp >= 4 * 48 + 3 * metrics.gapDp)
            assertTrue(metrics.showInlineHeaderStatus)
        }
    }

    @Test
    fun portraitKeepsTheFullWidthStackedPerformanceDeck() {
        val metrics = resolveDeckLayout(widthDp = 412, heightDp = 820)

        assertEquals(
            PerformanceWorkspaceLayout.STACKED,
            performanceWorkspaceLayout(metrics),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidBoundsAreRejected() {
        resolveDeckLayout(widthDp = 0, heightDp = 640)
    }
}
