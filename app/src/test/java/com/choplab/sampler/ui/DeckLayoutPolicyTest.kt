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
        assertTrue(metrics.fixedChromeHeightDp < 160)
        assertTrue(640 - metrics.fixedChromeHeightDp >= 480)
    }

    @Test
    fun regularPortraitAllocatesLargerControlsWithoutRequiringScroll() {
        val metrics = resolveDeckLayout(widthDp = 412, heightDp = 820)

        assertEquals(DeckOrientation.PORTRAIT, metrics.orientation)
        assertEquals(DeckDensity.REGULAR, metrics.density)
        assertTrue(metrics.controlHeightDp >= 44)
        assertTrue(820 - metrics.fixedChromeHeightDp >= 650)
    }

    @Test
    fun compactLandscapeUsesWideConsoleComposition() {
        val metrics = resolveDeckLayout(widthDp = 800, heightDp = 320)

        assertEquals(DeckOrientation.LANDSCAPE, metrics.orientation)
        assertEquals(DeckDensity.COMPACT, metrics.density)
        assertEquals(false, metrics.showStatusStrip)
        assertEquals(3, metrics.gapDp)
        assertTrue(metrics.waveformHeightDp <= 128)
        assertTrue(320 - metrics.fixedChromeHeightDp >= 230)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidBoundsAreRejected() {
        resolveDeckLayout(widthDp = 0, heightDp = 640)
    }
}
