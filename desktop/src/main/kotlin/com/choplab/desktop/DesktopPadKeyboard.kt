package com.choplab.desktop

import androidx.compose.ui.input.key.Key

sealed interface DesktopPadKeyAction {
    val padIndex: Int

    data class Press(override val padIndex: Int) : DesktopPadKeyAction
    data class Release(override val padIndex: Int) : DesktopPadKeyAction
}

fun desktopPadOffsetForKey(key: Key): Int? = when (key) {
    Key.One -> 0
    Key.Two -> 1
    Key.Three -> 2
    Key.Four -> 3
    Key.Q -> 4
    Key.W -> 5
    Key.E -> 6
    Key.R -> 7
    Key.A -> 8
    Key.S -> 9
    Key.D -> 10
    Key.F -> 11
    Key.Z -> 12
    Key.X -> 13
    Key.C -> 14
    Key.V -> 15
    else -> null
}

/**
 * Owns a desktop key from the first accepted key-down until its key-up.
 *
 * Windows may emit repeated key-down events while a key is held. Keeping the
 * ownership here prevents duplicate sampler voices and guarantees that key-up
 * releases the same global PAD even if the visible bank/page changes meanwhile.
 */
class DesktopPadKeyOwner {
    private val ownedPads = mutableMapOf<Key, Int>()

    fun press(
        key: Key,
        visiblePadIndices: List<Int>,
        playablePadIndices: Set<Int>,
        inputEnabled: Boolean,
        ctrl: Boolean = false,
        alt: Boolean = false,
        meta: Boolean = false,
    ): DesktopPadKeyAction.Press? {
        if (!inputEnabled || ctrl || alt || meta || key in ownedPads) return null
        val offset = desktopPadOffsetForKey(key) ?: return null
        val padIndex = visiblePadIndices.getOrNull(offset) ?: return null
        if (padIndex !in playablePadIndices) return null
        ownedPads[key] = padIndex
        return DesktopPadKeyAction.Press(padIndex)
    }

    fun release(key: Key): DesktopPadKeyAction.Release? =
        ownedPads.remove(key)?.let(DesktopPadKeyAction::Release)

    fun releaseAll(): List<DesktopPadKeyAction.Release> {
        val releases = ownedPads.values.map(DesktopPadKeyAction::Release)
        ownedPads.clear()
        return releases
    }
}
