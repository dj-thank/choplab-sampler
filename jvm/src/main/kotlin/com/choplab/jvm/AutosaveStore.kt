package com.choplab.jvm

import com.choplab.core.model.Project
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.nio.file.*
import java.util.UUID

data class Recovery(val project: Project, val revision: Long, val generation: Long)

/**
 * Exactly three atomic document slots. A slot contains its revision and hash in the same commit.
 * Required assets are flushed, hash/format checked and published before any document references them.
 * No destructive asset GC: callers can inspect protection roots, including Undo and active jobs.
 */
class AutosaveStore(directory: Path, private val assets: FileAssetStore, private val beforeDocumentCommit: () -> Unit = {}) {
    val directory: Path
    private val lock: Any
    init {
        Files.createDirectories(directory); require(!Files.isSymbolicLink(directory))
        this.directory = directory.toRealPath(); lock = StoreLocks.forPath(this.directory)
    }
    fun save(project: Project, revision: Long, newAssets: Map<String, ByteArray> = emptyMap()): Boolean = synchronized(lock) {
        require(revision >= 0)
        val generations = validGenerations()
        val previous = generations.maxByOrNull { it.generation }
        if (previous != null && revision <= previous.revision) return@synchronized false
        require(newAssets.keys.all { hash -> project.assets.any { it.hash == hash } })
        newAssets.forEach { (hash, bytes) -> assets.publish(project.asset(hash), ByteArrayInputStream(bytes)) }
        require(project.assets.filter { it.required }.all(assets::verified)) { "Autosave cannot commit missing assets" }
        val generation = (previous?.generation ?: -1L).also { check(it < Long.MAX_VALUE) } + 1
        val document = ProjectJson.encode(project)
        val envelope = ProjectJson.encodeElement(obj("generation" to num(generation), "revision" to num(revision), "documentHash" to str(sha256(document)), "project" to ProjectJson.toJson(project)))
        val pending = directory.resolve(".document-${UUID.randomUUID()}.pending")
        try {
            FileOutputStream(pending.toFile()).use { output -> output.write(envelope); output.fd.sync() }
            require(readGeneration(pending)?.let { it.project == project && it.revision == revision && it.generation == generation } == true)
            beforeDocumentCommit()
            val target = directory.resolve("autosave.${generation % 3}.json")
            require(!Files.isSymbolicLink(target))
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            true
        } finally { Files.deleteIfExists(pending) }
    }
    fun recover(): Recovery? = synchronized(lock) { validGenerations().maxByOrNull { it.generation } }
    fun protectedAssets(undoRedo: Set<String> = emptySet(), activeJobs: Set<String> = emptySet()): Set<String> = synchronized(lock) {
        (validGenerations().flatMap { g -> g.project.assets.map { it.hash } } + undoRedo + activeJobs).toSet()
    }
    private fun validGenerations(): List<Recovery> = (0..2).mapNotNull { readGeneration(directory.resolve("autosave.$it.json")) }
    private fun readGeneration(path: Path): Recovery? {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return null
        return try {
            require(Files.size(path) <= ProjectJson.MAX_BYTES)
            val root = Files.newInputStream(path).use { ProjectJson.parse(readBounded(it, ProjectJson.MAX_BYTES.toLong())).obj() }
                .fields("generation", "revision", "documentHash", "project")
            val generation = root.long("generation"); val revision = root.long("revision")
            require(generation >= 0 && revision >= 0)
            // Verify the stored document before materializing defaults added by a later reader.
            // Re-encoding the upgraded model would falsely corrupt an older schema10 envelope.
            val document = root.getValue("project").obj()
            require(sha256(ProjectJson.encodeElement(document)) == root.string("documentHash"))
            val project = ProjectJson.fromJson(document)
            require(project.assets.filter { it.required }.all(assets::verified))
            Recovery(project, revision, generation)
        } catch (_: Exception) { null }
    }
}
