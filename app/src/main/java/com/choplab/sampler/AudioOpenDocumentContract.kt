package com.choplab.sampler

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract

/**
 * Opens only documents reported by their provider as audio.
 *
 * ActivityResultContracts.OpenDocument always uses the unrestricted wildcard
 * as the base Intent type and carries requested MIME types only as an extra. Some document
 * providers therefore show the picker as "all files". A dedicated contract
 * keeps an audio wildcard as the authoritative base type while the decoder still
 * validates that the selected bytes contain a supported audio track.
 */
internal class AudioOpenDocumentContract : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(AUDIO_IMPORT_MIME_TYPE)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data.takeIf { resultCode == Activity.RESULT_OK }
}

internal const val AUDIO_IMPORT_MIME_TYPE = "audio/*"
