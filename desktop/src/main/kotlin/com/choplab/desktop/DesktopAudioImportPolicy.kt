package com.choplab.desktop

import java.io.File
import javax.swing.filechooser.FileNameExtensionFilter

/** The packaged Windows importer includes FFmpeg for compressed audio and video containers. */
internal object DesktopAudioImportPolicy {
    private val extensions = (com.choplab.sampler.source.LocalAudioLibrary.extensions + setOf("choplib","zip")).toTypedArray()

    val fileFilter = FileNameExtensionFilter(
        "音声・動画・音源セット",
        *extensions,
    )

    fun accepts(file: File): Boolean =
        file.isFile && file.extension.lowercase() in extensions
}

internal data class AudioImportSelection(
    val accepted: List<File>,
    val rejected: List<File>,
)

/** A single-file Swing selection leaves [selectedFiles] empty on macOS. */
internal fun chooserAudioFiles(selectedFiles: Array<File>?, selectedFile: File?): List<File> {
    val many = selectedFiles?.toList().orEmpty()
    return many.ifEmpty { listOfNotNull(selectedFile) }
}

internal fun selectAudioImports(files: List<File>): AudioImportSelection {
    val existing = files.filter { it.isFile }
    return AudioImportSelection(
        accepted = existing.filter(DesktopAudioImportPolicy::accepts),
        rejected = existing.filterNot(DesktopAudioImportPolicy::accepts),
    )
}
