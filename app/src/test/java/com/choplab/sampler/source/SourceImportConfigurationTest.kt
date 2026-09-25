package com.choplab.sampler.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceImportConfigurationTest {
    @Test
    fun productionCallbackIsStableAndPreviewHasItsOwnScheme() {
        val production = spotifyCallbackScheme("com.choplab.sampler")
        val preview = spotifyCallbackScheme("com.choplab.sampler.preview")
        assertEquals("choplab", production)
        assertEquals("choplab-preview", preview)
        assertNotEquals(production, preview)
    }
}
