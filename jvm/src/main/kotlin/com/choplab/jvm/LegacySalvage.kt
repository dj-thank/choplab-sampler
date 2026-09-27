package com.choplab.jvm

import com.choplab.core.ProgramCompiler
import com.choplab.core.model.*
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.ZipInputStream

data class SalvagedAsset(val originalId: Long, val sourceEntry: String, val sourceHash: String, val asset: Asset)
data class SalvageResult(val schema: Int, val audio: FrozenList<SalvagedAsset>) {
    /**
     * A new document with rescued audio only. The first sound the engine can load becomes the original. Each other
     * sound, whole, goes on the next PAD in order while the engine could still keep all of them loaded together with the
     * original, so chopping the original onto PADs later still fits. The rest stay in the document only. Old PADs,
     * edits and arrangement are not inferred.
     */
    fun newProject(id: String, title: String): Project {
        val unique = audio.map { it.asset }.distinctBy { it.hash }
        fun frames48(asset: Asset) = ProgramCompiler.sourceFrameTo48k(asset.frames, asset.sampleRate)
        val loadable = unique.filter { maxOf(it.frames, frames48(it)) <= ProgramCompiler.RESIDENT_FRAME_LIMIT }
        val original = loadable.firstOrNull()
        var resident = original?.let(::frames48) ?: 0L
        val placed = loadable.drop(1).filter { asset ->
            (resident + frames48(asset) <= ProgramCompiler.RESIDENT_FRAME_LIMIT).also { fits -> if (fits) resident += frames48(asset) }
        }.take(ProjectLimits.PAD_COUNT)
        val pads = (0 until ProjectLimits.PAD_COUNT).map { id ->
            placed.getOrNull(id)?.let { Pad(id, it.hash, FrameRange(0, it.frames), padName(it.name, id)) } ?: Pad(id)
        }
        return Project(id = id, title = title, assets = unique.frozen(), pads = pads.frozen(),
            source = original?.let { Source(it.hash, FrameRange(0, it.frames)) })
    }

    /** What [project], made by [newProject], says it rescued: every sound, and those neither the original nor on a PAD. */
    fun notice(project: Project): com.choplab.core.Notice.Rescued {
        val used = project.pads.mapNotNull { it.assetHash }.toSet() + listOfNotNull(project.source?.assetHash)
        return com.choplab.core.Notice.Rescued(project.assets.size, project.assets.count { it.hash !in used })
    }

    private fun padName(name: String, id: Int): String {
        val short = if (name.length <= 80) name else name.take(if (name[79].isHighSurrogate()) 79 else 80)
        return short.takeIf { it.isNotBlank() } ?: "PAD ${id + 1}"
    }
}

/** Read-only source salvage for the real project.txt layouts 1–7. Schemas 8/9 are rejected. */
class LegacySalvage(private val limits: ArchiveLimits = ArchiveLimits()) {
    companion object {
        /** Whether [input] starts like a project file of the earlier app: a ZIP whose first entry is project.txt. */
        fun recognizes(input: InputStream): Boolean = try {
            ZipInputStream(BoundedInput(NonClosingInput(input), 256L * 1024)).use { it.nextEntry?.name == "project.txt" }
        } catch (_: IOException) { false } catch (_: IllegalArgumentException) { false }
    }
    private data class Metadata(val index: Int, val id: Long, val rate: Int, val frames: Long, val channels: Int, val name: String, val entry: String)
    fun read(input: InputStream, destination: FileAssetStore, cancelled: () -> Boolean = { false }): SalvageResult {
        ZipInputStream(BoundedInput(NonClosingInput(input), limits.maxArchiveBytes)).use { zip ->
            val first = requireNotNull(zip.nextEntry)
            require(first.name == "project.txt" && !first.isDirectory)
            val manifest = readBounded(zip, 256L * 1024)
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(manifest)).toString()
            val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()
            require(lines.size >= 2)
            val header = lines[0].split('\t'); require(header.size == 2 && header[0] == "CHOPLAB_PROJECT")
            val schema = header[1].toInt(); require(schema in 1..7) { "Unsupported legacy schema; only 1–7 are verified" }
            val countLine = lines[1].split('\t'); require(countLine.size == 2 && countLine[0] == "audioCount")
            val count = countLine[1].toInt(); require(count in 0..ProjectLimits.MAX_ASSETS && lines.size >= count + 2)
            val records = (0 until count).map { index ->
                val values = lines[index + 2].split('\t')
                require(values.size == if (schema == 7) 8 else 7)
                require(values[0] == "audio" && values[1].toInt() == index)
                val nameIndex = if (schema == 7) 6 else 5
                val decoded = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(Base64.getUrlDecoder().decode(values[nameIndex]))).toString()
                require(decoded.isNotBlank() && decoded.length <= 256)
                // Only a display basename survives; no legacy local path enters schema 10.
                val name = decoded.substringAfterLast('/').substringAfterLast('\\').replace(':', '_').ifBlank { "Recovered audio" }
                val entry = values[nameIndex + 1]; validateName(entry)
                require(entry == "audio/$index.${if (schema == 1) "pcm" else "wav"}")
                Metadata(index, values[2].toLong(), values[3].toInt(), values[4].toLong(), if (schema == 7) values[5].toInt() else 1, name, entry).also {
                    require(it.rate in 8_000..192_000 && it.frames in 1..ProjectLimits.MAX_FRAMES && it.channels in 1..2)
                }
            }
            require(records.map { it.id }.distinct().size == records.size)
            require(records.sumOf { it.frames * it.channels * 2 } <= limits.maxExpandedBytes)
            zip.closeEntry()
            val remaining = records.associateBy { it.entry }.toMutableMap()
            val seen = mutableSetOf("project.txt")
            val rescued = mutableListOf<SalvagedAsset>()
            var expanded = 0L
            while (true) {
                require(!cancelled()) { "Salvage cancelled" }
                val entry = zip.nextEntry ?: break
                validateName(entry.name)
                require(!entry.isDirectory && seen.add(entry.name.lowercase()))
                val metadata = requireNotNull(remaining.remove(entry.name)) { "Unknown legacy asset" }
                val sourceBytes = readBounded(zip, limits.maxAssetBytes)
                expanded += sourceBytes.size; require(expanded <= limits.maxExpandedBytes)
                val bytes = if (schema == 1) {
                    require(sourceBytes.size.toLong() == metadata.frames * 2)
                    ByteArrayOutputStream(sourceBytes.size + 44).also { output ->
                        output.ascii("RIFF"); output.le32(36L + sourceBytes.size); output.ascii("WAVEfmt "); output.le32(16)
                        output.le16(1); output.le16(1); output.le32(metadata.rate.toLong()); output.le32(metadata.rate.toLong() * 2)
                        output.le16(2); output.le16(16); output.ascii("data"); output.le32(sourceBytes.size.toLong()); output.write(sourceBytes)
                    }.toByteArray()
                } else sourceBytes
                val info = WavCodec.inspect(ByteArrayInputStream(bytes))
                require(!info.floatingPoint && info.bits == 16 && info.frames == metadata.frames && info.channels == metadata.channels && info.sampleRate == metadata.rate)
                val asset = Asset(sha256(bytes), "wav", bytes.size.toLong(), info.sampleRate, info.channels, info.frames, metadata.name)
                destination.publish(asset, ByteArrayInputStream(bytes), cancelled)
                rescued += SalvagedAsset(metadata.id, metadata.entry, sha256(sourceBytes), asset)
                zip.closeEntry()
            }
            require(remaining.isEmpty()) { "Missing legacy audio" }
            return SalvageResult(schema, rescued.sortedBy { item -> records.first { it.id == item.originalId }.index }.frozen())
        }
    }
}
