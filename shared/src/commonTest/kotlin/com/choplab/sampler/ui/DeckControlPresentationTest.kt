package com.choplab.sampler.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeckControlPresentationTest {
    @Test fun regularButtonSeparatesPrimaryActionFromCaption() {
        val copy = deckButtonCopy("制作を保存\nSAVE PROJECT", false, 1f, 44f)
        assertEquals("制作を保存", copy.primary)
        assertEquals("SAVE PROJECT", copy.secondary)
        assertEquals(12f, copy.primarySizeSp)
    }

    @Test fun largeTextPrefersTheActionInsteadOfShrinkingItsType() {
        val normal = deckButtonCopy("制作を保存\nSAVE PROJECT", true, 1f, 44f)
        val large = deckButtonCopy("制作を保存\nSAVE PROJECT", true, 2f, 44f)
        assertEquals(normal.primarySizeSp, large.primarySizeSp)
        assertEquals(10f, large.primarySizeSp)
        assertEquals("制作を保存", large.primary)
        assertEquals(null, large.secondary)
        assertEquals(1, large.primaryMaxLines)
    }

    @Test fun narrowButtonNeverDropsNumericOrJapaneseState() {
        assertEquals("テンポ 120 BPM", deckButtonCopy("テンポ\n120 BPM", true, 2f, 32f).primary)
        assertEquals("BANK A 選択中", deckButtonCopy("BANK A\n選択中", true, 2f, 32f).primary)
        assertEquals("WAV EXPORT 4 BARS", deckButtonCopy("WAV\nEXPORT 4 BARS", true, 2f, 32f).primary)
    }

    @Test fun malformedDisplayInputsRemainBoundedWithoutChangingOriginalLabels() {
        for (scale in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f)) {
            val copy = deckButtonCopy("SAVE", true, scale, 40f)
            assertEquals("SAVE", copy.primary)
            assertTrue(copy.primarySizeSp.isFinite())
        }
        assertEquals(1, deckButtonCopy("a", true, 1f, Float.NaN).primaryMaxLines)
    }

    @Test fun everyAdjustmentHasAParameterDirectionAndCurrentValue() {
        for (label in listOf("BPM", "KEY", "TONE", "LEVEL", "SWING")) {
            val down = parameterAdjustmentDescription(label, "42", false)
            val up = parameterAdjustmentDescription(label, "42", true)
            assertTrue(down.contains(parameterAccessibleName(label)))
            assertTrue(down.contains("下げる。現在 42"))
            assertTrue(up.contains("上げる。現在 42"))
            assertFalse(down == up)
        }
    }

    @Test fun landscapeLabelsGetTheSamePreciseNamesWithoutInventingMissingValues() {
        assertEquals("テンポ BPMを下げる。現在 92", machineButtonAccessibleDescription("BPM -\n92"))
        assertEquals("テンポ BPMを上げる。現在 92", machineButtonAccessibleDescription("BPM +\n92"))
        assertEquals("スウィングを上げる。現在 54%", machineButtonAccessibleDescription("SW +\n54%"))
        assertEquals("音程 KEYを下げる", machineButtonAccessibleDescription("KEY -"))
        assertEquals("ZOOM +", machineButtonAccessibleDescription("ZOOM +"))
        assertEquals("+", machineButtonAccessibleDescription("+"))
        assertEquals("制作を保存\nSAVE PROJECT", machineButtonAccessibleDescription("制作を保存\nSAVE PROJECT"))
    }

    @Test fun finishTargetsNeverCollapseAtSupportedWidthsAndFontScales() {
        for (width in listOf(320f, 360f, 412f, 800f, 1280f)) {
            for (height in listOf(160f, 300f, 480f, 760f)) {
                for (scale in listOf(1f, 1.3f, 2f)) {
                    val geometry = finishWorkspaceGeometry(width, height, scale, 5)
                    assertTrue(geometry.actionRowHeightDp >= 48)
                    assertEquals(geometry.actionRowHeightDp * 5 + 20, geometry.actionsHeightDp)
                    if (geometry.sideBySide) assertTrue(height >= geometry.actionsHeightDp)
                    if (width < 720 || scale >= 1.2f) assertFalse(geometry.sideBySide)
                }
            }
        }
    }

    @Test fun wideSaveScreenUsesTwoColumnsOnlyWhenTargetsFit() {
        assertTrue(finishWorkspaceGeometry(1280f, 600f, 1f, 8).sideBySide)
        assertFalse(finishWorkspaceGeometry(1280f, 200f, 1f, 8).sideBySide)
        assertFalse(finishWorkspaceGeometry(1280f, 600f, 2f, 8).sideBySide)
    }

    @Test fun badViewportNeverCreatesNegativeOrOverflowingActionSizes() {
        for (size in listOf(Float.NaN, Float.POSITIVE_INFINITY, -10f)) {
            val geometry = finishWorkspaceGeometry(size, size, size, Int.MAX_VALUE)
            assertFalse(geometry.sideBySide)
            assertTrue(geometry.actionsHeightDp in 240..1000)
            assertTrue(geometry.summaryHeightDp >= 160)
        }
    }
}
