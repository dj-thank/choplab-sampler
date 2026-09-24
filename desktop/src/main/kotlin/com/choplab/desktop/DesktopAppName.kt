package com.choplab.desktop

import java.util.Locale

internal fun desktopAppName(
    locale: Locale = Locale.getDefault(Locale.Category.DISPLAY),
    preview: Boolean = java.lang.Boolean.getBoolean("choplab.preview"),
): String {
    val name = if (locale.language == Locale.JAPANESE.language) "おとひろい" else "Earth Song"
    return if (preview) "$name Preview" else name
}

internal fun desktopSourceWindowTitle(
    locale: Locale = Locale.getDefault(Locale.Category.DISPLAY),
    preview: Boolean = java.lang.Boolean.getBoolean("choplab.preview"),
): String =
    "${desktopAppName(locale, preview)} — " +
        if (locale.language == Locale.JAPANESE.language) "連携・音源追加" else "Audio sources"
