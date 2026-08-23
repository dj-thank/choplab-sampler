package com.choplab.sampler.persistence

import com.choplab.sampler.audio.AudioResourceLimits
import com.choplab.sampler.model.ProjectLimits
import com.choplab.sampler.model.SamplerUiState
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

data class RecoveredProjectState(
    val state: SamplerUiState,
    val revision: Long?,
)

/** Three-generation app-owned autosave with a synced, validated temporary replacement. */
class AtomicProjectStore(
    private val directory: File,
    private val maxResidentPcmBytes: Long = AudioResourceLimits.MAX_MOBILE_PROJECT_PCM_BYTES,
) {
    init {
        require(maxResidentPcmBytes in 1L..ProjectLimits.MAX_TOTAL_PCM_BYTES) {
            "自動保存プロジェクト読込メモリ上限が不正です"
        }
    }
    internal val primaryFile = File(directory, "autosave.choplab")
    private val backupFile = File(directory, "autosave.previous.choplab")
    private val olderBackupFile = File(directory, "autosave.previous2.choplab")
    private val temporaryFile = File(directory, "autosave.pending.choplab")
    private val primaryMetadataFile = File(directory, "autosave.revision")
    private val backupMetadataFile = File(directory, "autosave.previous.revision")
    private val olderBackupMetadataFile = File(directory, "autosave.previous2.revision")
    private val temporaryMetadataFile = File(directory, "autosave.pending.revision")
    private var newestCommittedRevision = Long.MIN_VALUE

    @Synchronized
    fun save(state: SamplerUiState) {
        newestCommittedRevision = maxOf(newestCommittedRevision, newestRevisionOnDisk())
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
        require(directory.exists() || directory.mkdirs()) { "自動保存フォルダーを作成できません" }
        newestCommittedRevision = maxOf(newestCommittedRevision, newestRevisionOnDisk())
        if (revision <= newestCommittedRevision) return false
        runCatching { temporaryFile.delete() }
        runCatching { temporaryMetadataFile.delete() }
        try {
            writeArchive(temporaryFile, state)
            temporaryFile.inputStream().buffered().use { input ->
                ProjectArchiveCodec.read(input, maxResidentPcmBytes)
            }
            writeMetadata(temporaryMetadataFile, revision, sha256(temporaryFile))
            if (backupFile.exists()) {
                moveGeneration(backupFile, backupMetadataFile, olderBackupFile, olderBackupMetadataFile)
            }
            if (primaryFile.exists()) {
                moveGeneration(primaryFile, primaryMetadataFile, backupFile, backupMetadataFile)
            }
            try {
                moveReplacing(temporaryFile, primaryFile)
                moveReplacing(temporaryMetadataFile, primaryMetadataFile)
            } catch (failure: Throwable) {
                if (!primaryFile.exists() && backupFile.exists()) {
                    runCatching {
                        moveGeneration(backupFile, backupMetadataFile, primaryFile, primaryMetadataFile)
                    }
                }
                throw failure
            }
            newestCommittedRevision = revision
            return true
        } finally {
            runCatching { temporaryFile.delete() }
            runCatching { temporaryMetadataFile.delete() }
        }
    }

    @Synchronized
    fun load(): SamplerUiState? = loadWithRevision()?.state

    @Synchronized
    fun loadWithRevision(): RecoveredProjectState? {
        val candidates = generations().filter { it.archive.isFile }
        if (candidates.isEmpty()) return null
        var firstFailure: Throwable? = null
        val decoded = candidates.mapNotNull { generation ->
            runCatching {
                val revision = readVerifiedRevision(generation)
                val state = generation.archive.inputStream().buffered().use { input ->
                    ProjectArchiveCodec.read(input, maxResidentPcmBytes)
                }
                DecodedGeneration(state, revision, generation.priority)
            }.onFailure { if (firstFailure == null) firstFailure = it }.getOrNull()
        }
        decoded.maxWithOrNull(
            compareBy<DecodedGeneration> { it.revision ?: Long.MIN_VALUE }
                .thenBy { it.priority },
        )?.let { return RecoveredProjectState(it.state, it.revision) }
        throw IllegalStateException("自動保存プロジェクトを復元できません", firstFailure)
    }

    internal fun writePendingForTest(state: SamplerUiState, revision: Long) {
        require(directory.exists() || directory.mkdirs())
        writeArchive(temporaryFile, state)
        writeMetadata(temporaryMetadataFile, revision, sha256(temporaryFile))
    }

    private fun writeArchive(file: File, state: SamplerUiState) {
        FileOutputStream(file).use { output ->
            ProjectArchiveCodec.write(state, output)
            output.flush()
            output.fd.sync()
        }
    }

    private fun generations(): List<Generation> = listOf(
        Generation(primaryFile, primaryMetadataFile, priority = 4),
        Generation(temporaryFile, temporaryMetadataFile, priority = 3),
        Generation(backupFile, backupMetadataFile, priority = 2),
        Generation(olderBackupFile, olderBackupMetadataFile, priority = 1),
    )

    private fun newestRevisionOnDisk(): Long = generations().mapNotNull { generation ->
        runCatching { readVerifiedRevision(generation) }.getOrNull()
    }.maxOrNull() ?: Long.MIN_VALUE

    private fun readVerifiedRevision(generation: Generation): Long? {
        if (!generation.metadata.isFile) return null
        val fields = generation.metadata.readText(Charsets.UTF_8).trim().split('\t')
        require(fields.size == 2) { "自動保存revision情報が不正です" }
        val revision = fields[0].toLongOrNull() ?: error("自動保存revision情報が不正です")
        require(fields[1].equals(sha256(generation.archive), ignoreCase = true)) {
            "自動保存revision情報とプロジェクトが一致しません"
        }
        return revision
    }

    private fun writeMetadata(file: File, revision: Long, digest: String) {
        FileOutputStream(file).use { output ->
            output.write("$revision\t$digest\n".toByteArray(Charsets.UTF_8))
            output.flush()
            output.fd.sync()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun moveGeneration(
        sourceArchive: File,
        sourceMetadata: File,
        destinationArchive: File,
        destinationMetadata: File,
    ) {
        moveReplacing(sourceArchive, destinationArchive)
        if (sourceMetadata.exists()) {
            moveReplacing(sourceMetadata, destinationMetadata)
        } else {
            runCatching { destinationMetadata.delete() }
        }
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
        syncDirectoryBestEffort(destination.absoluteFile.parentFile)
    }

    private fun syncDirectoryBestEffort(parent: File?) {
        if (parent == null || !parent.isDirectory) return
        runCatching {
            java.nio.channels.FileChannel.open(parent.toPath(), java.nio.file.StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        }
    }

    private data class Generation(val archive: File, val metadata: File, val priority: Int)
    private data class DecodedGeneration(
        val state: SamplerUiState,
        val revision: Long?,
        val priority: Int,
    )
}
