package com.choplab.sampler.persistence

import com.choplab.sampler.model.SamplerUiState
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Two-generation app-owned autosave with a synced temporary file and recoverable replacement. */
class AtomicProjectStore(private val directory: File) {
    internal val primaryFile = File(directory, "autosave.choplab")
    private val backupFile = File(directory, "autosave.previous.choplab")
    private val temporaryFile = File(directory, "autosave.pending.choplab")

    fun save(state: SamplerUiState) {
        require(directory.exists() || directory.mkdirs()) { "自動保存フォルダーを作成できません" }
        runCatching { temporaryFile.delete() }
        try {
            FileOutputStream(temporaryFile).use { output ->
                ProjectArchiveCodec.write(state, output)
                output.flush()
                output.fd.sync()
            }
            if (primaryFile.exists()) moveReplacing(primaryFile, backupFile)
            try {
                moveReplacing(temporaryFile, primaryFile)
            } catch (failure: Throwable) {
                if (!primaryFile.exists() && backupFile.exists()) {
                    runCatching { moveReplacing(backupFile, primaryFile) }
                }
                throw failure
            }
        } finally {
            runCatching { temporaryFile.delete() }
        }
    }

    fun load(): SamplerUiState? {
        val candidates = listOf(primaryFile, backupFile).filter(File::isFile)
        if (candidates.isEmpty()) return null
        var firstFailure: Throwable? = null
        candidates.forEach { file ->
            runCatching { file.inputStream().buffered().use(ProjectArchiveCodec::read) }
                .onSuccess { return it }
                .onFailure { if (firstFailure == null) firstFailure = it }
        }
        throw IllegalStateException("自動保存プロジェクトを復元できません", firstFailure)
    }

    private fun moveReplacing(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
