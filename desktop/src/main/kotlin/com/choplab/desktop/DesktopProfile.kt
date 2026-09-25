package com.choplab.desktop

import java.io.File

/** One profile boundary for every writable desktop location. Packaged tools stay read-only. */
internal object DesktopProfile {
    val preview: Boolean get() = java.lang.Boolean.getBoolean("choplab.preview")

    fun dataDirectory(
        localAppData: String? = System.getenv("LOCALAPPDATA"),
        userHome: String = System.getProperty("user.home"),
        preview: Boolean = this.preview,
    ): File {
        val parent = localAppData?.takeIf(String::isNotBlank)?.let(::File)
            ?: File(userHome, "AppData/Local")
        return File(parent, directoryName(preview))
    }

    fun recordingDirectory(
        temporaryRoot: String = System.getProperty("java.io.tmpdir"),
        preview: Boolean = this.preview,
    ): File = File(File(temporaryRoot, directoryName(preview)), "recordings")

    private fun directoryName(preview: Boolean) = if (preview) "ChopLab Preview" else "ChopLab"
}
