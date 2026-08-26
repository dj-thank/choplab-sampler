package com.choplab.sampler.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FocusedStepEditorPolicyTest {
    @Test
    fun supportedViewportsKeepAllSixteenTargetsAtLeastFortyEightDp() {
        val viewports = listOf(
            ReferenceViewport(360, 640, DeckOrientation.PORTRAIT, columns = 4, rows = 4),
            ReferenceViewport(412, 820, DeckOrientation.PORTRAIT, columns = 4, rows = 4),
            ReferenceViewport(640, 360, DeckOrientation.LANDSCAPE, columns = 8, rows = 2),
            ReferenceViewport(632, 328, DeckOrientation.LANDSCAPE, columns = 8, rows = 2),
            ReferenceViewport(1280, 720, DeckOrientation.LANDSCAPE, columns = 8, rows = 2),
        )

        viewports.forEach { viewport ->
            listOf(1f, 1.3f, 2f).forEach { fontScale ->
                val metrics = resolveDeckLayout(viewport.widthDp, viewport.heightDp, fontScale)
                val layout = assertNotNull(
                    resolveFocusedStepLayout(metrics, viewport.widthDp, viewport.heightDp),
                    "${viewport.widthDp}x${viewport.heightDp} at font scale $fontScale",
                )

                assertEquals(viewport.orientation, layout.orientation)
                assertEquals(viewport.columns, layout.columns)
                assertEquals(viewport.rows, layout.rows)
                assertEquals(16, layout.cells.size)
                assertTrue(layout.minimumCellWidthDp >= FOCUSED_STEP_MIN_TARGET_DP)
                assertTrue(layout.minimumCellHeightDp >= FOCUSED_STEP_MIN_TARGET_DP)
                assertTrue(layout.gridRightDp <= viewport.widthDp - metrics.contentPaddingDp)
                assertTrue(layout.gridBottomDp <= viewport.heightDp - metrics.contentPaddingDp)
            }
        }
    }

    @Test
    fun cellsStayRowMajorAndCoverStepsOneThroughSixteenExactlyOnce() {
        listOf(
            Triple(360, 640, 2f),
            Triple(640, 360, 2f),
        ).forEach { (widthDp, heightDp, fontScale) ->
            val metrics = resolveDeckLayout(widthDp, heightDp, fontScale)
            val layout = assertNotNull(resolveFocusedStepLayout(metrics, widthDp, heightDp))

            assertEquals((0 until 16).toList(), layout.cells.map(FocusedStepCellBounds::stepIndex))
            assertEquals((1..16).toList(), layout.cells.map(FocusedStepCellBounds::stepNumber))
            layout.cells.forEachIndexed { index, cell ->
                assertEquals(index / layout.columns, cell.row)
                assertEquals(index % layout.columns, cell.column)
                assertEquals(layout.cellWidthDp, cell.widthDp)
                assertEquals(layout.cellHeightDp, cell.heightDp)
                assertTrue(cell.leftDp >= layout.gridLeftDp)
                assertTrue(cell.topDp >= layout.gridTopDp)
                assertTrue(cell.rightDp <= layout.gridRightDp)
                assertTrue(cell.bottomDp <= layout.gridBottomDp)
            }
        }
    }

    @Test
    fun invalidOrTooSmallViewportFailsClosed() {
        val metrics = resolveDeckLayout(widthDp = 360, heightDp = 640, fontScale = 2f)

        assertNull(resolveFocusedStepLayout(metrics, widthDp = 0, heightDp = 640))
        assertNull(resolveFocusedStepLayout(metrics, widthDp = 360, heightDp = 0))
        assertNull(resolveFocusedStepLayout(metrics, widthDp = -1, heightDp = 640))
        assertNull(resolveFocusedStepLayout(metrics, widthDp = 200, heightDp = 200))
    }

    @Test
    fun workspaceModesExposeExactlyOneSurfaceAndUnknownStateRestoresQuick() {
        val quick = beatWorkspaceSurface(BeatWorkspaceMode.QUICK)
        val focused = beatWorkspaceSurface(BeatWorkspaceMode.FOCUSED_STEPS)
        val fine = beatWorkspaceSurface(BeatWorkspaceMode.FINE_CONTROLS)

        assertTrue(quick.showPadGrid)
        assertFalse(quick.showFocusedStepEditor)
        assertFalse(quick.showDetailedSequencer)

        assertFalse(focused.showPadGrid)
        assertTrue(focused.showFocusedStepEditor)
        assertFalse(focused.showDetailedSequencer)

        assertFalse(fine.showPadGrid)
        assertFalse(fine.showFocusedStepEditor)
        assertTrue(fine.showDetailedSequencer)

        assertEquals(BeatWorkspaceMode.QUICK, restoreBeatWorkspaceMode(null))
        assertEquals(BeatWorkspaceMode.QUICK, restoreBeatWorkspaceMode("UNKNOWN"))
        BeatWorkspaceMode.entries.forEach { mode ->
            assertEquals(mode, restoreBeatWorkspaceMode(mode.name))
        }
    }

    @Test
    fun explicitNavigationRoundTripsQuickFocusedAndFineControls() {
        var mode = BeatWorkspaceMode.QUICK

        mode = transitionBeatWorkspace(mode, BeatWorkspaceAction.SHOW_FOCUSED_STEPS)
        assertEquals(BeatWorkspaceMode.FOCUSED_STEPS, mode)
        mode = transitionBeatWorkspace(mode, BeatWorkspaceAction.SHOW_FINE_CONTROLS)
        assertEquals(BeatWorkspaceMode.FINE_CONTROLS, mode)
        mode = transitionBeatWorkspace(mode, BeatWorkspaceAction.SHOW_FOCUSED_STEPS)
        assertEquals(BeatWorkspaceMode.FOCUSED_STEPS, mode)
        mode = transitionBeatWorkspace(mode, BeatWorkspaceAction.SHOW_QUICK)
        assertEquals(BeatWorkspaceMode.QUICK, mode)
        assertEquals(
            BeatWorkspaceMode.QUICK,
            transitionBeatWorkspace(mode, BeatWorkspaceAction.SHOW_QUICK),
        )
    }

    private data class ReferenceViewport(
        val widthDp: Int,
        val heightDp: Int,
        val orientation: DeckOrientation,
        val columns: Int,
        val rows: Int,
    )
}
