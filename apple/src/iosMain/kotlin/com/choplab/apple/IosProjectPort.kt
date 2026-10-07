package com.choplab.apple

import com.choplab.core.Location
import com.choplab.core.ProjectPort
import com.choplab.core.model.Asset
import com.choplab.core.model.Project
import com.choplab.core.model.ProjectLimits
import com.choplab.core.persistence.ProjectJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * `.choplab` archives with the JVM hosts' rules: `project.json` first, then the document's assets in canonical name
 * order, each hash-verified before the document is returned. Opening publishes assets first; on any failure no
 * project is returned and already published assets stay safe for a retry.
 */
internal class IosProjectPort(private val assets: IosAssetStore, private val files: IosLocations) : ProjectPort {
    override suspend fun save(project: Project, revision: Long, location: Location) = withContext(Dispatchers.IO) {
        require(revision >= 0)
        val context = currentCoroutineContext()
        val target = files.resolve(location).path
        val pending = target.substringBeforeLast('/') + "/." + uniqueName("choplab-")
        try {
            FileWriter(pending).use { output ->
                IosArchive.write(project, assets, output) { !context.isActive }
                output.syncAndClose()
            }
            require(context.isActive) { "Save cancelled" }
            renameReplacing(pending, target)
        } finally { deleteFile(pending) }
    }

    override suspend fun open(location: Location): Project = withContext(Dispatchers.IO) {
        val context = currentCoroutineContext()
        IosArchive.read(files.resolve(location).path, assets) { !context.isActive }
    }
}

internal object IosArchive {
    private val maxArchiveBytes = ProjectLimits.MAX_TOTAL_BYTES + 16L * 1024 * 1024

    fun write(project: Project, assets: IosAssetStore, output: FileWriter, cancelled: () -> Boolean) {
        require(!cancelled()) { "Archive cancelled" }
        val manifest = ProjectJson.encode(project)
        val included = project.assets.filter { if (it.required) { require(assets.verified(it)) { "Missing asset" }; true } else assets.verified(it) }
            .sortedBy { it.entryName }
        require(included.all { it.byteCount <= ProjectLimits.MAX_ASSET_BYTES } && included.sumOf { it.byteCount } <= ProjectLimits.MAX_TOTAL_BYTES)
        val zip = IosZip.Writer(output)
        zip.add("project.json", cancelled) { sink -> sink(manifest, manifest.size) }
        for (asset in included) zip.add(asset.entryName, cancelled) { sink -> copyVerified(asset, assets, cancelled, sink) }
        require(!cancelled()) { "Archive cancelled" }
        zip.finish()
        require(output.written <= maxArchiveBytes) { "Archive exceeds limit" }
    }

    fun read(path: String, assets: IosAssetStore, cancelled: () -> Boolean): Project {
        val size = requireNotNull(regularFileSize(path)) { "Project file unavailable" }
        require(size <= maxArchiveBytes) { "Compressed input exceeds limit" }
        val entries = IosZip.entries(path)
        val first = entries.first()
        validateName(first.name)
        require(first.name == "project.json") { "project.json must be first" }
        val document = ArrayOutput()
        IosZip.extract(path, first, ProjectJson.MAX_BYTES.toLong(), cancelled) { bytes, count -> document.write(bytes, 0, count) }
        val project = ProjectJson.decode(document.toByteArray())
        require(project.assets.all { it.byteCount <= ProjectLimits.MAX_ASSET_BYTES })
        require(project.assets.sumOf { it.byteCount } <= ProjectLimits.MAX_TOTAL_BYTES)
        val remaining = project.assets.associateBy { it.entryName }.toMutableMap()
        val seen = mutableSetOf("project.json")
        var previous = ""
        var expanded = 0L
        require(entries.size <= ProjectLimits.MAX_ASSETS + 1)
        for (entry in entries.drop(1)) {
            require(!cancelled()) { "Archive cancelled" }
            validateName(entry.name)
            require(!entry.name.endsWith("/") && seen.add(entry.name.lowercase())) { "Duplicate or case-colliding ZIP entry" }
            require(entry.name > previous) { "Assets must be in canonical order" }
            previous = entry.name
            val asset = requireNotNull(remaining.remove(entry.name)) { "Unknown ZIP entry" }
            require(entry.size == asset.byteCount)
            expanded += asset.byteCount; require(expanded <= ProjectLimits.MAX_TOTAL_BYTES)
            publish(path, entry, asset, assets, cancelled)
        }
        require(remaining.values.none { it.required }) { "Missing required asset entry" }
        require(project.assets.filter { it.required }.all(assets::verified))
        return project
    }

    private fun publish(path: String, entry: IosZip.Entry, asset: Asset, assets: IosAssetStore, cancelled: () -> Boolean) {
        if (assets.verified(asset)) {
            // Already held: still read the entry so its bytes and CRC are checked, but keep the stored copy.
            val hash = Sha256()
            IosZip.extract(path, entry, asset.byteCount, cancelled) { bytes, count -> hash.update(bytes, 0, count) }
            require(hash.hex() == asset.hash) { "Incoming asset hash mismatch" }
            return
        }
        val pending = assets.pendingPath()
        try {
            FileWriter(pending).use { writer ->
                IosZip.extract(path, entry, asset.byteCount, cancelled) { bytes, count -> writer.write(bytes, 0, count) }
                writer.syncAndClose()
            }
            assets.adopt(asset, pending, cancelled)
        } finally { deleteFile(pending) }
    }

    private fun copyVerified(asset: Asset, assets: IosAssetStore, cancelled: () -> Boolean, sink: (ByteArray, Int) -> Unit) {
        val hash = Sha256()
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        FileReader(assets.verifiedPath(asset)).use { reader ->
            while (true) {
                require(!cancelled()) { "Archive cancelled" }
                val n = reader.read(buffer)
                if (n < 0) break
                count += n
                require(count <= asset.byteCount)
                hash.update(buffer, 0, n)
                sink(buffer, n)
            }
        }
        require(count == asset.byteCount && hash.hex() == asset.hash) { "Asset changed while archiving" }
    }

    internal fun validateName(name: String) {
        require(name.length in 1..160 && name.all { it.code in 32..126 })
        require(!name.startsWith('/') && '\\' !in name && ':' !in name && name.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "Unsafe ZIP path" }
    }
}
