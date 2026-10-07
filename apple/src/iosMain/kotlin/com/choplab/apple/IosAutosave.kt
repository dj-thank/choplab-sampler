package com.choplab.apple

import com.choplab.core.model.Project
import com.choplab.core.persistence.*

internal data class Recovery(val project: Project, val revision: Long, val generation: Long)

/**
 * Exactly three atomic document slots, with the JVM hosts' envelope: a slot holds its generation, revision and the
 * document's hash in one file, written to a synced pending file and renamed into place. Required assets must already
 * be verified in the store; a slot that fails any check is ignored, so recovery falls back to an older one.
 */
internal class IosAutosave(val directory: String, private val assets: IosAssetStore) {
    private val lock = HostLock()
    init { ensureDirectory(directory) }

    fun save(project: Project, revision: Long): Boolean = lock.withLock {
        require(revision >= 0)
        val previous = validGenerations().maxByOrNull { it.generation }
        if (previous != null && revision <= previous.revision) return@withLock false
        require(project.assets.filter { it.required }.all(assets::verified)) { "Autosave cannot commit missing assets" }
        val generation = (previous?.generation ?: -1L).also { check(it < Long.MAX_VALUE) } + 1
        val document = ProjectJson.encode(project)
        val envelope = ProjectJson.encodeElement(obj("generation" to num(generation), "revision" to num(revision),
            "documentHash" to str(sha256(document)), "project" to ProjectJson.toJson(project)))
        val pending = "$directory/." + uniqueName("document-") + ".pending"
        try {
            FileWriter(pending).use { writer -> writer.write(envelope); writer.syncAndClose() }
            require(readGeneration(pending)?.let { it.project == project && it.revision == revision && it.generation == generation } == true)
            renameReplacing(pending, "$directory/autosave.${generation % 3}.json")
            true
        } finally { deleteFile(pending) }
    }

    fun recover(): Recovery? = lock.withLock { validGenerations().maxByOrNull { it.generation } }

    /** Whether any slot file exists, valid or not; a failed recovery must then not start over silently. */
    fun hasSavedDocument(): Boolean = (0..2).any { regularFileSize("$directory/autosave.$it.json") != null }

    private fun validGenerations(): List<Recovery> = (0..2).mapNotNull { readGeneration("$directory/autosave.$it.json") }

    private fun readGeneration(path: String): Recovery? = try {
        val root = ProjectJson.parse(readFile(path, ProjectJson.MAX_BYTES.toLong())).obj()
            .fields("generation", "revision", "documentHash", "project")
        val generation = root.long("generation"); val revision = root.long("revision")
        require(generation >= 0 && revision >= 0)
        // Verify the stored document before materializing defaults added by a later reader.
        val document = root.getValue("project").obj()
        require(sha256(ProjectJson.encodeElement(document)) == root.string("documentHash"))
        val project = ProjectJson.fromJson(document)
        require(project.assets.filter { it.required }.all(assets::verified))
        Recovery(project, revision, generation)
    } catch (_: Exception) { null }
}
