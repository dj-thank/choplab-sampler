package com.choplab.desktop.persistence

import com.choplab.sampler.audio.AudioResourceLimits
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.persistence.ProjectArchiveCodec
import java.io.File

/**
 * Manual project-file boundary for Windows.
 *
 * The shared JVM codec owns the archive schema and validation. This adapter only
 * adds an atomic sibling write so an interrupted save cannot replace a valid file
 * with partial bytes.
 */
object DesktopProjectFiles {
    fun save(target: File, state: SamplerUiState): File {
        val output = withProjectExtension(target)
        replaceWithAtomicSibling(output) { temporary ->
            temporary.outputStream().buffered().use { stream -> ProjectArchiveCodec.write(state, stream) }
            temporary.inputStream().buffered().use { input ->
                ProjectArchiveCodec.read(input, AudioResourceLimits.MAX_DESKTOP_PROJECT_PCM_BYTES)
            }
        }
        return output
    }

    fun load(source: File): SamplerUiState {
        require(source.isFile) { "制作ファイルが見つかりません" }
        return source.inputStream().buffered().use { input ->
            ProjectArchiveCodec.read(input, AudioResourceLimits.MAX_DESKTOP_PROJECT_PCM_BYTES)
        }
    }

    fun withProjectExtension(file: File): File =
        if (file.extension.equals("choplab", ignoreCase = true)) {
            file
        } else {
            File(file.parentFile, "${file.nameWithoutExtension}.choplab")
        }
}
