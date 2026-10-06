package com.choplab.desktop.separation

import com.choplab.sampler.separation.DrumSeparationService
import com.choplab.sampler.separation.SeparatorSpec
import com.choplab.sampler.separation.SeparatorModelStore
import com.choplab.desktop.DesktopProfile
import java.io.File

/**
 * Read an explicit/bundled model when present (including Mac's offline bundle). Otherwise
 * Windows downloads the pinned model on the separation worker into the shared private cache.
 */
internal fun defaultSeparatorModelsDir(): File {
    // Explicit configuration always wins, so tests and operators can pin the lookup.
    System.getProperty("choplab.separatorModels")?.let(::File)?.let { return it }
    System.getenv("CHOPLAB_SEPARATOR_MODELS")?.let(::File)?.let { return it }
    val codeSourceModels = runCatching {
        File(DrumSeparationService::class.java.protectionDomain.codeSource.location.toURI())
            .parentFile?.parentFile?.resolve("models")
    }.getOrNull()
    return listOfNotNull(
        codeSourceModels,
        File(System.getProperty("java.home")).parentFile?.resolve("models"),
        File("work/separator-models"),
        File("../work/separator-models"),
    ).firstOrNull { it.resolve(SeparatorSpec.MODEL_FILE).isFile }
        ?: DesktopProfile.modelDirectory()
}

internal fun defaultSeparatorModelStore() = SeparatorModelStore(defaultSeparatorModelsDir())
