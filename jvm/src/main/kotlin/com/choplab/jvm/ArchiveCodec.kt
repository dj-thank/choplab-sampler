package com.choplab.jvm

import com.choplab.core.model.Project
import com.choplab.core.model.ProjectLimits
import java.io.*
import java.util.zip.*

data class ArchiveLimits(
    val maxDocumentBytes: Int = ProjectJson.MAX_BYTES,
    val maxAssetBytes: Long = ProjectLimits.MAX_ASSET_BYTES,
    val maxExpandedBytes: Long = ProjectLimits.MAX_TOTAL_BYTES,
    val maxArchiveBytes: Long = ProjectLimits.MAX_TOTAL_BYTES + 16L * 1024 * 1024,
) {
    init {
        require(maxDocumentBytes in 1..ProjectJson.MAX_BYTES && maxAssetBytes in 1..ProjectLimits.MAX_ASSET_BYTES)
        require(maxExpandedBytes in 1..ProjectLimits.MAX_TOTAL_BYTES && maxArchiveBytes in 1..(ProjectLimits.MAX_TOTAL_BYTES + 16L * 1024 * 1024))
    }
}

/** Archives contain one document followed by lexically ordered content-addressed assets. */
class ArchiveCodec(private val limits: ArchiveLimits = ArchiveLimits()) {
    fun write(project: Project, assets: FileAssetStore, output: OutputStream) {
        val manifest = ProjectJson.encode(project)
        require(manifest.size <= limits.maxDocumentBytes)
        val included = project.assets.filter { if (it.required) { require(assets.verified(it)); true } else assets.verified(it) }.sortedBy { it.entryName }
        require(included.all { it.byteCount <= limits.maxAssetBytes } && included.sumOf { it.byteCount } <= limits.maxExpandedBytes)
        ZipOutputStream(BoundedOutput(NonClosingOutput(output), limits.maxArchiveBytes)).use { zip ->
            zip.setLevel(9)
            zip.putNextEntry(entry("project.json")); zip.write(manifest); zip.closeEntry()
            included.forEach { asset ->
                zip.putNextEntry(entry(asset.entryName))
                assets.openVerified(asset).use { input ->
                    val buffer = ByteArray(8192); var count = 0L
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    while (true) {
                        val n = input.read(buffer); require(n != 0); if (n < 0) break
                        count += n; require(count <= asset.byteCount); digest.update(buffer, 0, n); zip.write(buffer, 0, n)
                    }
                    require(count == asset.byteCount && digest.digest().hex() == asset.hash)
                }
                zip.closeEntry()
            }
        }
    }

    /** Files are published asset-first; on failure no Project is returned. Orphans remain safe for retry. */
    fun read(input: InputStream, assets: FileAssetStore, cancelled: () -> Boolean = { false }): Project {
        ZipInputStream(BoundedInput(NonClosingInput(input), limits.maxArchiveBytes)).use { zip ->
            val first = requireNotNull(zip.nextEntry) { "Missing project.json" }
            validateName(first.name)
            require(first.name == "project.json" && !first.isDirectory) { "project.json must be first" }
            require(first.size == -1L || first.size <= limits.maxDocumentBytes)
            val project = ProjectJson.decode(readBounded(zip, limits.maxDocumentBytes.toLong()))
            zip.closeEntry()
            require(project.assets.all { it.byteCount <= limits.maxAssetBytes })
            require(project.assets.sumOf { it.byteCount } <= limits.maxExpandedBytes)
            val remaining = project.assets.associateBy { it.entryName }.toMutableMap()
            val seen = mutableSetOf("project.json")
            var previous = ""
            var expanded = 0L
            var entries = 1
            while (true) {
                require(!cancelled()) { "Archive cancelled" }
                val current = zip.nextEntry ?: break
                require(++entries <= ProjectLimits.MAX_ASSETS + 1)
                validateName(current.name)
                require(!current.isDirectory && seen.add(current.name.lowercase())) { "Duplicate or case-colliding ZIP entry" }
                require(current.name > previous) { "Assets must be in canonical order" }
                previous = current.name
                val asset = requireNotNull(remaining.remove(current.name)) { "Unknown ZIP entry" }
                require(current.size == -1L || current.size == asset.byteCount)
                expanded += asset.byteCount; require(expanded <= limits.maxExpandedBytes)
                assets.publish(asset, zip, cancelled)
                zip.closeEntry() // ZipInputStream verifies CRC as the entry reaches EOF.
            }
            require(remaining.values.none { it.required }) { "Missing required asset entry" }
            require(project.assets.filter { it.required }.all(assets::verified))
            return project
        }
    }
    private fun entry(name: String) = ZipEntry(name).apply { time = 0L }
}

internal fun validateName(name: String) {
    require(name.length in 1..160 && name.all { it.code in 32..126 })
    require(!name.startsWith('/') && '\\' !in name && ':' !in name && name.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe ZIP path" }
}
internal class NonClosingInput(input: InputStream) : FilterInputStream(input) { override fun close() {} }
internal class NonClosingOutput(output: OutputStream) : FilterOutputStream(output) {
    override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
    override fun close() { flush() }
}
internal class BoundedInput(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var bytes = 0L
    override fun read(): Int = `in`.read().also { if (it >= 0) count(1) }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also { require(it != 0 || length == 0); if (it > 0) count(it) }
    private fun count(n: Int) { bytes += n; require(bytes <= limit) { "Compressed input exceeds limit" } }
}
internal class BoundedOutput(output: OutputStream, private val limit: Long) : FilterOutputStream(output) {
    private var bytes = 0L
    override fun write(value: Int) { require(++bytes <= limit); out.write(value) }
    override fun write(buffer: ByteArray, offset: Int, length: Int) { bytes += length; require(bytes <= limit); out.write(buffer, offset, length) }
}
