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
import java.util.concurrent.CancellationException

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
        if (nextRevision > newestCommittedRevision) saveValidatedRevision(state, nextRevision)
    }

    /** Returns false without touching disk when a newer project revision is already committed. */
    @Synchronized
    fun save(state: SamplerUiState, revision: Long): Boolean {
        // This instance never accepts a revision below its own committed high-water mark.
        // Reject it before any filesystem work; fresh revisions still verify current disk bytes.
        if (revision <= newestCommittedRevision) return false
        newestCommittedRevision = maxOf(newestCommittedRevision, newestRevisionOnDisk())
        if (revision <= newestCommittedRevision) return false
        saveValidatedRevision(state, revision)
        return true
    }

    /** Caller holds the store monitor and has already verified the on-disk high-water mark. */
    private fun saveValidatedRevision(state: SamplerUiState, revision: Long) {
        require(directory.exists() || directory.mkdirs()) { "自動保存フォルダーを作成できません" }
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
        } finally {
            runCatching { temporaryFile.delete() }
            runCatching { temporaryMetadataFile.delete() }
        }
    }

    @Synchronized
    fun load(): SamplerUiState? = loadWithRevision()?.state

    fun loadWithRevision(): RecoveredProjectState? = loadWithRevision { archive ->
        archive.inputStream().buffered().use { input ->
            ProjectArchiveCodec.read(input, maxResidentPcmBytes)
        }
    }

    /**
     * Rank the small revision sidecars before hashing/decoding audio. A revision is
     * only a hint until its archive digest AND the bounded codec both validate.
     * Stop at the first valid candidate: retaining every decoded generation can
     * multiply startup PCM memory and work by four.
     *
     * The reader seam lets JVM tests count actual codec calls without timing gates.
     * It runs under the same store lock as save, including the ranking phase.
     */
    @Synchronized
    internal fun loadWithRevision(readArchive: (File) -> SamplerUiState): RecoveredProjectState? {
        val candidates = generations().filter { it.archive.isFile }
        if (candidates.isEmpty()) return null
        var firstFailure: Throwable? = null
        val ranked = candidates.mapNotNull { generation ->
            recoverableArchiveAttempt {
                RankedGeneration(generation, readRevisionMetadata(generation))
            }.onFailure { if (firstFailure == null) firstFailure = it }.getOrNull()
        }.sortedWith(
            compareByDescending<RankedGeneration> { it.metadata?.revision ?: Long.MIN_VALUE }
                .thenByDescending { it.generation.priority },
        )
        for (candidate in ranked) {
            val result = recoverableArchiveAttempt {
                candidate.metadata?.let { verifyRevisionDigest(candidate.generation, it) }
                RecoveredProjectState(
                    readArchive(candidate.generation.archive),
                    candidate.metadata?.revision,
                )
            }
            if (result.isSuccess) return result.getOrThrow()
            if (firstFailure == null) firstFailure = result.exceptionOrNull()
        }
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

    /** Rank tiny hints, then hash only until the highest valid revision is found. */
    @Synchronized
    internal fun newestRevisionOnDisk(hashArchive: (File) -> String = ::sha256): Long {
        val ranked = generations().mapNotNull { generation ->
            recoverableArchiveAttempt {
                readRevisionMetadata(generation)?.let { RankedGeneration(generation, it) }
            }.getOrNull()
        }.sortedWith(
            compareByDescending<RankedGeneration> { requireNotNull(it.metadata).revision }
                .thenByDescending { it.generation.priority },
        )
        for (candidate in ranked) {
            val metadata = requireNotNull(candidate.metadata)
            val verified = recoverableArchiveAttempt {
                require(metadata.digest.equals(hashArchive(candidate.generation.archive), ignoreCase = true)) {
                    "自動保存revision情報とプロジェクトが一致しません"
                }
                metadata.revision
            }
            if (verified.isSuccess) return verified.getOrThrow()
        }
        return Long.MIN_VALUE
    }

    private fun readRevisionMetadata(generation: Generation): RevisionMetadata? {
        if (!generation.metadata.isFile) return null
        // Writer output is at most 86 ASCII bytes (Long, tab, SHA-256, newline).
        // Allow surrounding whitespace, but never allocate from an untrusted sidecar size.
        val bytes = ByteArray(128)
        val count = generation.metadata.inputStream().use { input ->
            var offset = 0
            while (offset < bytes.size) {
                val read = input.read(bytes, offset, bytes.size - offset)
                if (read < 0) break
                require(read > 0) { "自動保存revision情報を読み込めません" }
                offset += read
            }
            require(input.read() == -1) { "自動保存revision情報が大きすぎます" }
            offset
        }
        val fields = String(bytes, 0, count, Charsets.UTF_8).trim().split('\t')
        require(fields.size == 2) { "自動保存revision情報が不正です" }
        val revision = fields[0].toLongOrNull() ?: error("自動保存revision情報が不正です")
        val digest = fields[1]
        require(digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "自動保存revision情報のダイジェストが不正です"
        }
        return RevisionMetadata(revision, digest)
    }

    private fun verifyRevisionDigest(generation: Generation, metadata: RevisionMetadata) {
        require(metadata.digest.equals(sha256(generation.archive), ignoreCase = true)) {
            "自動保存revision情報とプロジェクトが一致しません"
        }
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
        return sha256DigestHex(digest.digest())
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
    private data class RevisionMetadata(val revision: Long, val digest: String)
    private data class RankedGeneration(
        val generation: Generation,
        val metadata: RevisionMetadata?,
    )
}

/** Corruption permits backup recovery; cancellation and fatal VM/linkage errors do not. */
private inline fun <T> recoverableArchiveAttempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (canceled: CancellationException) {
    throw canceled
} catch (failure: Exception) {
    Result.failure(failure)
}

/** SHA-256 is fixed-size; avoid 32 Formatter instances and intermediate strings per file. */
internal fun sha256DigestHex(digest: ByteArray): String {
    require(digest.size == 32) { "SHA-256 digest must contain 32 bytes" }
    val digits = "0123456789abcdef"
    val output = CharArray(64)
    var index = 0
    while (index < digest.size) {
        val value = digest[index].toInt() and 0xff
        output[index * 2] = digits[value ushr 4]
        output[index * 2 + 1] = digits[value and 0x0f]
        index++
    }
    return String(output)
}
