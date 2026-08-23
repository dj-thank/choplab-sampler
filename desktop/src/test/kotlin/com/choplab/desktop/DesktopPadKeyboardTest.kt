package com.choplab.desktop

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DesktopPadKeyboardTest {
    @Test
    fun fourByFourComputerKeysMapToTheVisiblePadOrder() {
        val expected = listOf(
            Key.One,
            Key.Two,
            Key.Three,
            Key.Four,
            Key.Q,
            Key.W,
            Key.E,
            Key.R,
            Key.A,
            Key.S,
            Key.D,
            Key.F,
            Key.Z,
            Key.X,
            Key.C,
            Key.V,
        )

        assertEquals((0 until 16).toList(), expected.map(::desktopPadOffsetForKey))
    }

    @Test
    fun pressAndReleaseOwnTheExactGlobalPadAcrossPageChanges() {
        val owner = DesktopPadKeyOwner()
        val visiblePage = (48 until 64).toList()

        assertEquals(
            DesktopPadKeyAction.Press(53),
            owner.press(
                key = Key.W,
                visiblePadIndices = visiblePage,
                playablePadIndices = visiblePage.toSet(),
                inputEnabled = true,
            ),
        )
        assertEquals(DesktopPadKeyAction.Release(53), owner.release(Key.W))
    }

    @Test
    fun repeatedKeyDownCannotCreateDuplicatePadVoices() {
        val owner = DesktopPadKeyOwner()
        val visiblePage = (0 until 16).toList()

        assertEquals(
            DesktopPadKeyAction.Press(0),
            owner.press(Key.One, visiblePage, visiblePage.toSet(), inputEnabled = true),
        )
        assertNull(owner.press(Key.One, visiblePage, visiblePage.toSet(), inputEnabled = true))
        assertEquals(DesktopPadKeyAction.Release(0), owner.release(Key.One))
        assertNull(owner.release(Key.One))
    }

    @Test
    fun shortcutsDisabledContextsAndEmptyPadsNeverEnterPadOwnership() {
        val owner = DesktopPadKeyOwner()
        val visiblePage = (16 until 32).toList()
        val playable = setOf(16, 18)

        assertNull(owner.press(Key.Two, visiblePage, playable, inputEnabled = true))
        assertNull(owner.press(Key.One, visiblePage, playable, inputEnabled = false))
        assertNull(owner.press(Key.One, visiblePage, playable, inputEnabled = true, ctrl = true))
        assertNull(owner.press(Key.One, visiblePage, playable, inputEnabled = true, alt = true))
        assertNull(owner.press(Key.One, visiblePage, playable, inputEnabled = true, meta = true))
        assertNull(owner.release(Key.One))
    }

    @Test
    fun unknownKeysAreNotConsumed() {
        val owner = DesktopPadKeyOwner()

        assertNull(desktopPadOffsetForKey(Key.Spacebar))
        assertNull(owner.press(Key.Spacebar, (0 until 16).toList(), (0 until 16).toSet(), true))
    }
}
