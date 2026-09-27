package com.choplab.desktop.next

import com.choplab.jvm.LrcTextIO
import com.choplab.ui.LyricFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Reuses the host's native picker; writes through a sibling file before replacing an approved destination. */
internal class DesktopLyricFiles(private val choose: suspend (save: Boolean) -> Path?) : LyricFiles {
    override suspend fun importLrc(): String? {
        val path = choose(false) ?: return null
        return withContext(Dispatchers.IO) { Files.newInputStream(path).use(LrcTextIO::read) }
    }
    override suspend fun exportLrc(text: String): Boolean {
        val chosen = choose(true) ?: return false
        return withContext(Dispatchers.IO) {
            val path = chosen.toAbsolutePath().normalize()
            val pending = Files.createTempFile(requireNotNull(path.parent), ".lyrics-", ".pending")
            try {
                Files.newOutputStream(pending).use { LrcTextIO.write(it, text) }
                ensureActive()
                Files.move(pending, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                true
            } finally { Files.deleteIfExists(pending) }
        }
    }
}
