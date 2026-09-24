package com.choplab.sampler.source

/** Distinct redirect schemes let the stable and preview packages coexist. */
internal fun spotifyCallbackScheme(applicationId: String): String =
    if (applicationId == "com.choplab.sampler.preview") "choplab-preview" else "choplab"
