package com.choplab.desktop.persistence

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Completes a same-directory temporary file before replacing [target].
 *
 * An existing target is never routed through a non-atomic fallback. Filesystems
 * that cannot atomically replace it fail closed and leave its bytes untouched.
 */
internal fun <T> replaceWithAtomicSibling(
    target: File,
    writeTemporary: (File) -> T,
): T {
    val output = target.absoluteFile
    val parent = output.parentFile ?: error("保存先フォルダーを取得できません")
    if (!parent.isDirectory) {
        require(parent.mkdirs() || parent.isDirectory) { "保存先フォルダーを作成できません" }
    }

    val temporary = Files.createTempFile(parent.toPath(), ".choplab-", ".tmp").toFile()
    var primaryFailure: Throwable? = null
    var published = false
    try {
        val result = writeTemporary(temporary)
        require(temporary.isFile) { "一時ファイルが生成されませんでした" }
        FileChannel.open(temporary.toPath(), StandardOpenOption.WRITE).use { channel ->
            channel.force(true)
        }
        moveCompletedSibling(temporary, output)
        published = true
        return result
    } catch (failure: Throwable) {
        primaryFailure = failure
        throw failure
    } finally {
        if (!published) {
            try {
                Files.deleteIfExists(temporary.toPath())
            } catch (cleanupFailure: Exception) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }
}

private fun moveCompletedSibling(source: File, target: File) {
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (unsupported: AtomicMoveNotSupportedException) {
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw IOException(
                "既存ファイルを安全に置換できない保存先です",
                unsupported,
            )
        }
        Files.move(source.toPath(), target.toPath())
    }
    syncDirectoryBestEffort(target.absoluteFile.parentFile)
}

private fun syncDirectoryBestEffort(directory: File?) {
    if (directory == null || !directory.isDirectory) return
    try {
        Files.newByteChannel(directory.toPath(), StandardOpenOption.READ).use { channel ->
            (channel as? java.nio.channels.FileChannel)?.force(true)
        }
    } catch (_: Exception) {
        // The completed file has already been atomically published.
    }
}
