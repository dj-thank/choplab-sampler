package com.choplab.desktop

import java.io.File
import javax.swing.filechooser.FileNameExtensionFilter

/** Windows currently decodes PCM WAV only; never advertise formats the packaged app cannot open. */
internal object DesktopAudioImportPolicy {
    private val extensions = arrayOf("wav")

    val fileFilter = FileNameExtensionFilter(
        "音声ファイル（WAV）",
        *extensions,
    )

    fun accepts(file: File): Boolean =
        file.isFile && file.extension.equals("wav", ignoreCase = true)
}
