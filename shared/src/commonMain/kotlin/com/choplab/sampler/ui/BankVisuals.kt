package com.choplab.sampler.ui

import androidx.compose.ui.graphics.Color

fun bankRoleAccent(bankIndex: Int): Color = when (bankIndex) {
    0 -> Color(0xFF3F6745)
    1 -> Color(0xFF9A4A18)
    2 -> Color(0xFF56507A)
    else -> Color(0xFF7A3D48)
}
