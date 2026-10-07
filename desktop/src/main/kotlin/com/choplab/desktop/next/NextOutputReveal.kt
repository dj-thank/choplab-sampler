package com.choplab.desktop.next

import java.awt.Desktop
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Native navigation is separate from output writing and never changes the project or its history. */
internal class NextOutputRevealer(
    val available: () -> Boolean = Desktop::isDesktopSupported,
    private val browse: (Path) -> Boolean = { file ->
        val desktop = Desktop.getDesktop()
        when {
            desktop.isSupported(Desktop.Action.BROWSE_FILE_DIR) -> { desktop.browseFileDirectory(file.toFile()); true }
            desktop.isSupported(Desktop.Action.OPEN) -> { desktop.open(file.parent.toFile()); true }
            else -> false
        }
    },
) {
    suspend fun reveal(file: Path): Boolean = withContext(Dispatchers.IO) {
        runCatching { Files.isRegularFile(file) && available() && browse(file) }.getOrDefault(false)
    }
}
