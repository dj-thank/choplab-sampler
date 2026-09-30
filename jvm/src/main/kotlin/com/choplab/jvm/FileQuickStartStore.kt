package com.choplab.jvm

import kotlinx.coroutines.*
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** One small UI preference under the selected profile, outside project/asset/autosave files. */
class FileQuickStartStore(private val directory: Path) {
    private val flag get() = directory.resolve("quick-start-v1")
    suspend fun completed(): Boolean = withContext(Dispatchers.IO) {
        if (!Files.exists(flag)) return@withContext false
        require(!Files.isSymbolicLink(flag) && Files.isRegularFile(flag) && Files.size(flag) == 2L)
        Files.readAllBytes(flag).contentEquals(byteArrayOf('1'.code.toByte(), '\n'.code.toByte()))
    }
    suspend fun complete() = withContext(Dispatchers.IO) {
        ensureActive()
        Files.createDirectories(directory)
        require(!Files.isSymbolicLink(directory))
        val temporary = Files.createTempFile(directory, "quick-start-", ".pending")
        try {
            FileOutputStream(temporary.toFile()).use { it.write(byteArrayOf('1'.code.toByte(), '\n'.code.toByte())); it.fd.sync() }
            ensureActive()
            Files.move(temporary, flag, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            Unit
        } finally { Files.deleteIfExists(temporary) }
    }
}
