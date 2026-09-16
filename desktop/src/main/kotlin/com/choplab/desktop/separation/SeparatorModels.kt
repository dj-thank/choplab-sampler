package com.choplab.desktop.separation

import com.choplab.sampler.separation.DrumSeparationService
import com.choplab.sampler.separation.SeparatorSpec
import java.io.File

/**
 * Windows lookup for the bundled drum model used by the shared [DrumSeparationService]:
 * explicit configuration, then the app image `models` folder, then the development folder.
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
        ?: codeSourceModels
        ?: File("work/separator-models")
}
