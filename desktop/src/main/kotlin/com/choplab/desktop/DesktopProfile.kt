package com.choplab.desktop

import java.io.File

/** One profile boundary for every writable desktop location. Packaged tools stay read-only. */
internal object DesktopProfile {
    val preview: Boolean get() = java.lang.Boolean.getBoolean("choplab.preview")

    fun dataDirectory(
        localAppData: String? = System.getenv("LOCALAPPDATA"),
        userHome: String = System.getProperty("user.home"),
        preview: Boolean = this.preview,
        osName: String = System.getProperty("os.name").orEmpty(),
    ): File {
        val name = directoryName(preview)
        localAppData?.takeIf(String::isNotBlank)?.let { return File(it.trim(), name) }
        if (osName.startsWith("Mac", ignoreCase = true)) {
            val modern = File(userHome, "Library/Application Support/$name")
            val legacy = File(userHome, "AppData/Local/$name")
            if (!modern.exists() && legacy.exists()) return legacy
            return modern
        }
        return File(userHome, "AppData/Local/$name")
    }

    fun recordingDirectory(
        temporaryRoot: String = System.getProperty("java.io.tmpdir"),
        preview: Boolean = this.preview,
    ): File = File(File(temporaryRoot, directoryName(preview)), "recordings")

    private fun directoryName(preview: Boolean) = if (preview) "ChopLab Preview" else "ChopLab"
}
