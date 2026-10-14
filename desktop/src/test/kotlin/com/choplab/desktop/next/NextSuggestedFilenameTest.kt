package com.choplab.desktop.next

import kotlin.test.*

class NextSuggestedFilenameTest {
    @Test fun projectTitleSuggestsAllFormatsWithoutPathsOrInvalidBasenames() {
        for (ext in listOf("choplab", "wav", "zip")) {
            assertEquals("夜の川.$ext", nextSuggestedFilename("夜の川", ext, true))
            assertEquals("Song.$ext", nextSuggestedFilename("Song.choplab", ext, false))
            assertEquals("Project.$ext", nextSuggestedFilename(" ... ", ext, false))
            assertEquals("作品.$ext", nextSuggestedFilename("", ext, true))
            assertEquals("a_b_c.$ext", nextSuggestedFilename("a/b\\c", ext, false))
            assertEquals("_CON.$ext", nextSuggestedFilename("CON", ext, false))
            val long = nextSuggestedFilename("曲".repeat(200), ext, true)
            assertTrue(long.toByteArray(Charsets.UTF_8).size < 255)
            assertTrue(nextSuggestedFilename("test\u0000?:*|\"<>name", ext, false).none { it.isISOControl() || it in "/\\:*?\"<>|" })
        }
    }
}
