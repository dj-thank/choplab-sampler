package com.choplab.sampler

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioOpenDocumentContractTest {
    @Test
    fun importIntentUsesAudioAsItsBaseTypeInsteadOfAllFiles() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        val intent = AudioOpenDocumentContract().createIntent(context, Unit)

        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
        assertEquals(AUDIO_IMPORT_MIME_TYPE, intent.type)
        assertTrue(intent.categories.orEmpty().contains(Intent.CATEGORY_OPENABLE))
        assertFalse(intent.hasExtra(Intent.EXTRA_MIME_TYPES))
    }
}
