package com.choplab.apple

import com.choplab.core.ImportPort
import com.choplab.core.Location
import com.choplab.core.model.Asset
import com.choplab.core.model.ProjectLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Imports an original exactly as picked: the bytes are copied and hashed unchanged, the audio is decoded once to
 * prove it plays and to read its rate, channels and length, and only then is the asset published. [files] resolves
 * the opaque [Location] a picker produced; paths never enter the document.
 */
internal class IosImportPort(private val assets: IosAssetStore, private val files: IosLocations) : ImportPort {
    override suspend fun import(location: Location): Asset = withContext(Dispatchers.IO) {
        val picked = files.resolve(location)
        val extension = picked.name.substringAfterLast('.', "").lowercase()
        require(extension in Asset.EXTENSIONS) { "Unsupported audio file type" }
        val size = requireNotNull(regularFileSize(picked.path)) { "Picked file is unavailable" }
        require(size in 1..ProjectLimits.MAX_ASSET_BYTES) { "Audio file exceeds the size limit" }
        val pending = assets.pendingPath()
        try {
            val hash = Sha256()
            val buffer = ByteArray(64 * 1024)
            var copied = 0L
            FileReader(picked.path).use { reader ->
                FileWriter(pending).use { writer ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = reader.read(buffer)
                        if (count < 0) break
                        copied += count
                        require(copied <= ProjectLimits.MAX_ASSET_BYTES) { "Audio file exceeds the size limit" }
                        hash.update(buffer, 0, count)
                        writer.write(buffer, 0, count)
                    }
                    writer.syncAndClose()
                }
            }
            val context = currentCoroutineContext()
            // Decoding the whole file proves it is playable; the samples themselves are decoded again on demand.
            val info = IosAudioDecoder.decode(pending, extension, cancelled = { !context.isActive }).info
            context.ensureActive()
            val name = picked.name.replace(':', '_').take(256).ifBlank { "audio.$extension" }
            val asset = Asset(hash.hex(), extension, copied, info.sampleRate, info.channels, info.frames, name)
            assets.adopt(asset, pending) { !context.isActive }
            context.ensureActive()
            asset
        } finally { deleteFile(pending) }
    }
}

/** A file the user picked or a destination the host chose, behind an opaque handle. */
internal data class HostFile(val path: String, val name: String)

/** Maps opaque handles to host files for the lifetime of the editor; documents never see them. */
internal class IosLocations {
    private val lock = HostLock()
    private val files = mutableMapOf<String, HostFile>()
    fun register(file: HostFile): Location = lock.withLock {
        val handle = uniqueName("file-")
        files[handle] = file
        Location(handle)
    }
    fun resolve(location: Location): HostFile = lock.withLock { requireNotNull(files[location.handle]) { "Unknown location" } }
}
