package com.choplab.sampler.persistence

import com.choplab.sampler.model.SamplerUiState
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Three-generation app-owned autosave with a synced, validated temporary replacement. */
class AtomicProjectStore(private val directory: File) {
    internal val primaryFile = File(directory, "autosave.choplab")
    private val backupFile = File(directory, "autosave.previous.choplab")
    private val olderBackupFile = File(directory, "autosave.previous2.choplab")
    private val temporaryFile = File(directory, "autosave.pending.choplab")
    private var newestCommittedRevision = Long.MIN_VALUE

    @Synchronized
    fun save(state: SamplerUiState) {
        val nextRevision = if (newestCommittedRevision == Long.MAX_VALUE) {
            Long.MAX_VALUE
        } else {
            newestCommittedRevision + 1L
        }
        save(state, nextRevision)
    }

    /** Returns false without touching disk when a newer project revision is already committed. */
    @Synchronized
    fun save(state: SamplerUiState, revision: Long): Boolean {
        if (revision < newestCommittedRevision) return false
        require(directory.exists() || directory.mkdirs()) { "自動保存フォルダーを作成できません" }
        runCatching { temporaryFile.delete() }
        try {
            FileOutputStream(temporaryFile).use { output ->
                ProjectArchiveCodec.write(state, output)
                output.flush()
                output.fd.sync()
            }
            temporaryFile.inputStream().buffered().use(ProjectArchiveCodec::read)
            if (backupFile.exists()) moveReplacing(backupFile, olderBackupFile)
            if (primaryFile.exists()) moveReplacing(primaryFile, backupFile)
            try {
                moveReplacing(temporaryFile, primaryFile)
            } catch (failure: Throwable) {
                if (!primaryFile.exists() && backupFile.exists()) {
                    runCatching { moveReplacing(backupFile, primaryFile) }
                }
                throw failure
            }
            newestCommittedRevision = revision
            return true
        } finally {
            runCatching { temporaryFile.delete() }
        }
    }

    @Synchronized
    fun load(): SamplerUiState? {
        val candidates = listOf(primaryFile, temporaryFile, backupFile, olderBackupFile)
            .filter(File::isFile)
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
