package com.choplab.apple

import com.choplab.core.model.Project
import com.choplab.core.persistence.*

internal data class Recovery(val project: Project, val revision: Long, val generation: Long)

/**
 * Exactly three atomic document slots, with the JVM hosts' envelope: a slot holds its generation, revision and the
 * document's hash in one file, written to a synced pending file and renamed into place. Required assets must already
 * be verified in the store; a slot that fails any check is ignored, so recovery falls back to an older one.
 */
internal class IosAutosave(directory: String, private val assets: IosAssetStore,
    private val beforeCommit: () -> Unit = {}) {
    val directory = canonicalDirectory(directory)
    private val lock = HostLock()
    private var baselineInitialized = false
    private var lastPersisted: Recovery? = null
    init { ensureDirectory(directory) }

    /** False means this exact document/revision is already durable, never that a newer unreadable slot won. */
    fun save(project: Project, revision: Long): Boolean = lock.withLock {
        require(revision >= 0)
        if (!baselineInitialized) initializeBaseline(validGenerations().maxByOrNull { it.generation })
        lastPersisted?.let { previous ->
            require(revision >= previous.revision) { "Stale autosave revision" }
            if (revision == previous.revision) {
                require(project == previous.project) { "Conflicting autosave revision" }
                if (readGeneration("$directory/autosave.${previous.generation % 3}.json") == previous) return@withLock false
            }
        }
        // A temporarily unavailable asset must not reset the generation counter and make an older slot win later.
        val previous = (0..2).mapNotNull { readGeneration("$directory/autosave.$it.json", verifyAssets = false) }.maxByOrNull { it.generation }
        require(project.assets.filter { it.required }.all(assets::verified)) { "Autosave cannot commit missing assets" }
        val generation = (previous?.generation ?: -1L).also { check(it < Long.MAX_VALUE) } + 1
        val document = ProjectJson.encode(project)
        val envelope = ProjectJson.encodeElement(obj("generation" to num(generation), "revision" to num(revision),
            "documentHash" to str(sha256(document)), "project" to ProjectJson.toJson(project)))
        val pending = "$directory/." + uniqueName("document-") + ".pending"
        try {
            requireDiskSpace(directory, envelope.size.toLong())
            FileWriter(pending, maxBytes = ProjectJson.MAX_BYTES.toLong()).use { writer -> writer.write(envelope); writer.syncAndClose() }
            require(readGeneration(pending)?.let { it.project == project && it.revision == revision && it.generation == generation } == true)
            beforeCommit()
            val destination = "$directory/autosave.${generation % 3}.json"
            // A broken generation is evidence, not expendable history. Keep it outside the rotating slots.
            if (regularFileSize(destination) != null && readGeneration(destination) == null)
                renameReplacing(destination, "$directory/" + uniqueName("retained-unreadable-") + ".json")
            renameReplacing(pending, destination)
            lastPersisted = Recovery(project, revision, generation)
            true
        } finally { deleteFile(pending) }
    }

    fun recover(): Recovery? = lock.withLock {
        validGenerations().maxByOrNull { it.generation }.also { if (!baselineInitialized) initializeBaseline(it) }
    }

    fun contains(project: Project, revision: Long): Boolean = lock.withLock {
        validGenerations().any { it.revision == revision && it.project == project }
    }

    private fun initializeBaseline(value: Recovery?) { lastPersisted = value; baselineInitialized = true }

    /** Whether any slot file exists, valid or not; a failed recovery must then not start over silently. */
    fun hasSavedDocument(): Boolean = (0..2).any { regularFileSize("$directory/autosave.$it.json") != null }

    private fun validGenerations(): List<Recovery> = (0..2).mapNotNull { readGeneration("$directory/autosave.$it.json") }

    private fun readGeneration(path: String, verifyAssets: Boolean = true): Recovery? = try {
        val root = ProjectJson.parse(readFile(path, ProjectJson.MAX_BYTES.toLong())).obj()
            .fields("generation", "revision", "documentHash", "project")
        val generation = root.long("generation"); val revision = root.long("revision")
        require(generation >= 0 && revision >= 0)
        // Verify the stored document before materializing defaults added by a later reader.
        val document = root.getValue("project").obj()
        require(sha256(ProjectJson.encodeElement(document)) == root.string("documentHash"))
        val project = ProjectJson.fromJson(document)
        if (verifyAssets) require(project.assets.filter { it.required }.all(assets::verified))
        Recovery(project, revision, generation)
    } catch (_: Exception) { null }
}
