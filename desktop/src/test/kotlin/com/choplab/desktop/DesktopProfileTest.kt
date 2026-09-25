package com.choplab.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DesktopProfileTest {
    @Test
    fun existingProductionDataLocationIsPreserved() {
        assertEquals(File("local/ChopLab"), DesktopProfile.dataDirectory("local", "home", false))
    }

    @Test
    fun previewNeverSharesProductionProjectsLibraryOrRecordings() {
        val production = DesktopProfile.dataDirectory("local", "home", false)
        val preview = DesktopProfile.dataDirectory("local", "home", true)
        for (leaf in listOf("projects", "audio-library", "settings", "cache")) {
            assertNotEquals(File(production, leaf), File(preview, leaf))
        }
        assertNotEquals(
            DesktopProfile.recordingDirectory("temporary", false),
            DesktopProfile.recordingDirectory("temporary", true),
        )
    }

    @Test
    fun absentOrBlankLocalAppDataUsesIsolatedHomeFallback() {
        val expected = File("home/AppData/Local/ChopLab Preview")
        assertEquals(expected, DesktopProfile.dataDirectory(null, "home", true))
        assertEquals(expected, DesktopProfile.dataDirectory(" ", "home", true))
    }
}
