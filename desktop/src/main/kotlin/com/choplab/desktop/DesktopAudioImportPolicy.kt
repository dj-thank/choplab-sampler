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
