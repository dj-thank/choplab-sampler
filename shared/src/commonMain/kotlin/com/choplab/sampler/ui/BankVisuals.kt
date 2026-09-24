package com.choplab.sampler.ui

import androidx.compose.ui.graphics.Color

fun bankRoleAccent(bankIndex: Int): Color = when (bankIndex) {
    0 -> Color(0xFF3F6745)
    1 -> Color(0xFF9A4A18)
    2 -> Color(0xFF56507A)
    else -> Color(0xFF7A3D48)
}


internal data class PadVisualColors(
    val background: Color,
    val foreground: Color,
)

/** Opaque surfaces avoid theme-dependent contrast and keep a press distinct from selection. */
internal fun padVisualColors(bankIndex: Int, assigned: Boolean, pressed: Boolean, hovered: Boolean): PadVisualColors {
    val accent = bankRoleAccent(bankIndex)
    return when {
        pressed -> PadVisualColors(Color(0xFFFFB25E), Color(0xFF2A1500))
        assigned -> PadVisualColors(
            if (hovered) accent else Color(
                red = accent.red * 0.72f + 15f / 255f * 0.28f,
                green = accent.green * 0.72f + 13f / 255f * 0.28f,
                blue = accent.blue * 0.72f + 8f / 255f * 0.28f,
                alpha = 1f,
            ),
            Color(0xFFFFE8B8),
        )
        else -> PadVisualColors(
            if (hovered) Color(0xFF38301D) else Color(0xFF262116),
            Color(0xFFC7B58E),
        )
    }
}
