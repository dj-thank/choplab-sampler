package com.choplab.sampler.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PersistableAudioDisplayNameTest {
    @Test
    fun selectsFirstArchiveSafeCandidate() {
        assertEquals("preferred.wav", persistableAudioDisplayName("preferred.wav", "fallback.wav"))
        assertEquals("fallback.wav", persistableAudioDisplayName("  ", "fallback.wav"))
        assertEquals("sample", persistableAudioDisplayName("\t", null))
    }

    @Test
    fun fallsBackWhenBoundedPrefixIsBlank() {
        assertEquals(
            "fallback.wav",
            persistableAudioDisplayName(
                " ".repeat(ProjectLimits.MAX_ASSET_NAME_CHARS) + "visible.wav",
                "fallback.wav",
            ),
        )
    }

    @Test
    fun boundsWithoutSplittingSurrogatePair() {
        val sourceName = "a".repeat(ProjectLimits.MAX_ASSET_NAME_CHARS - 1) +
            "\uD83C\uDFB5.wav"

        val persistedName = persistableAudioDisplayName(sourceName, null)

        assertEquals(ProjectLimits.MAX_ASSET_NAME_CHARS - 1, persistedName.length)
        assertFalse(persistedName.last() in '\uD800'..'\uDBFF')
    }
}
