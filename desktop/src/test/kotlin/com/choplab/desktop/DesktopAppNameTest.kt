package com.choplab.desktop

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopAppNameTest {
    @Test fun japaneseLocalesUseOtohiroi() {
        assertEquals("おとひろい", desktopAppName(Locale.JAPAN, preview = false))
        assertEquals("おとひろい", desktopAppName(Locale.JAPANESE, preview = false))
        assertEquals("おとひろい — 連携・音源追加", desktopSourceWindowTitle(Locale.JAPAN, preview = false))
    }

    @Test fun englishAndUnsupportedLocalesUseEarthSong() {
        for (locale in listOf(Locale.US, Locale.UK, Locale.FRANCE, Locale.ROOT)) {
            assertEquals("Earth Song", desktopAppName(locale, preview = false))
            assertEquals("Earth Song — Audio sources", desktopSourceWindowTitle(locale, preview = false))
        }
    }

    @Test fun previewNamesStayDistinctInBothLanguages() {
        assertEquals("おとひろい Preview", desktopAppName(Locale.JAPAN, preview = true))
        assertEquals("Earth Song Preview", desktopAppName(Locale.US, preview = true))
        assertEquals("Earth Song Preview — Audio sources", desktopSourceWindowTitle(Locale.US, preview = true))
    }
}
