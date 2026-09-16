package com.choplab.sampler.ui

import androidx.compose.ui.graphics.Color
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PadVisualContrastTest {
    @Test fun allBankStatesUseOpaqueSurfacesAndReadablePrimaryText() {
        for (bank in 0..3) for (assigned in listOf(false, true)) {
            for (pressed in listOf(false, true)) for (hovered in listOf(false, true)) {
                val colors = padVisualColors(bank, assigned, pressed, hovered)
                assertEquals(1f, colors.background.alpha)
                assertTrue(contrast(colors.foreground, colors.background) >= 4.5,
                    "bank=$bank assigned=$assigned pressed=$pressed hovered=$hovered")
            }
        }
    }

    @Test fun pressingChangesTheSurfaceAndKeepsItsTextReadable() {
        for (bank in 0..3) {
            val resting = padVisualColors(bank, true, false, false)
            val pressed = padVisualColors(bank, true, true, false)
            assertFalse(resting.background == pressed.background)
            assertTrue(contrast(pressed.foreground, pressed.background) >= 7.0)
        }
    }

    @Test fun inactivePadsRemainDifferentFromAssignedPads() {
        for (bank in 0..3) {
            assertFalse(padVisualColors(bank, false, false, false).background ==
                padVisualColors(bank, true, false, false).background)
        }
    }

    private fun luminance(color: Color): Double {
        fun linear(c: Float): Double = if (c <= 0.04045f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return linear(color.red) * 0.2126 + linear(color.green) * 0.7152 + linear(color.blue) * 0.0722
    }
    private fun contrast(a: Color, b: Color): Double {
        val first = luminance(a); val second = luminance(b)
        return (maxOf(first, second) + 0.05) / (minOf(first, second) + 0.05)
    }
}
