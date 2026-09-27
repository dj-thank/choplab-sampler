package com.choplab.desktop.source

import java.io.File

internal data class DesktopMediaTools(
    val ytDlp: File,
    val ffmpeg: File,
    val ffprobe: File,
    val node: File,
)

internal fun mediaToolFileName(tool: String, windows: Boolean): String =
    if (windows) "$tool.exe" else tool

/**
 * Prefers one prepared directory (the Windows app image). Otherwise accepts the same tools
 * from different directories, which is how Homebrew installs them on macOS.
 */
internal fun locateDesktopMediaTools(
    explicitDirectories: List<File>,
    searchDirectories: List<File>,
    windows: Boolean,
): DesktopMediaTools? {
    explicitDirectories.firstNotNullOfOrNull { directory ->
        toolsInOneDirectory(directory, windows)
    }?.let { return it }
    val ytDlp = firstExecutable(searchDirectories, "yt-dlp", windows) ?: return null
    val ffmpeg = firstExecutable(searchDirectories, "ffmpeg", windows) ?: return null
    val ffprobe = firstExecutable(searchDirectories, "ffprobe", windows) ?: return null
    val node = firstExecutable(searchDirectories, "node", windows) ?: return null
    return DesktopMediaTools(ytDlp, ffmpeg, ffprobe, node)
}

internal fun defaultMediaSearchDirectories(
    path: String,
    homebrewPrefixes: List<File> = listOf(File("/opt/homebrew"), File("/usr/local")),
): List<File> {
    val pathDirectories = path.split(File.pathSeparator).filter(String::isNotBlank).map(::File)
    val kegDirectories = homebrewPrefixes.flatMap { prefix ->
        File(prefix, "opt").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("node@") }
            .map { File(it, "bin") }
    }
    return pathDirectories + homebrewPrefixes.map { File(it, "bin") } + kegDirectories
}

private fun toolsInOneDirectory(directory: File, windows: Boolean): DesktopMediaTools? {
    val tools = listOf("yt-dlp", "ffmpeg", "ffprobe", "node").map { tool ->
        File(directory, mediaToolFileName(tool, windows))
    }
    if (tools.any { !it.isFile || !it.canExecute() }) return null
    return DesktopMediaTools(tools[0], tools[1], tools[2], tools[3])
}

private fun firstExecutable(directories: List<File>, tool: String, windows: Boolean): File? =
    directories.firstNotNullOfOrNull { directory ->
        File(directory, mediaToolFileName(tool, windows)).takeIf { it.isFile && it.canExecute() }
    }
