package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.model.*
import com.choplab.engine.MixerProgram
import kotlinx.coroutines.*
import java.io.FilterOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * One immutable prepared program is replayed from its initial FX/seed state for each stem. Entries
 * stream directly into the atomic output; there is no private full-song/all-stem decoded scratch.
 * Work is proportional to stem count and is reported per pass. Only one graph renders at a time.
 */
class FileStemExportPort(private val compiler: ProgramCompiler, private val resolve: (Location) -> Path) : StemExportPort {
    override suspend fun export(project: Project, target: PlaybackTarget, request: StemExportRequest,
        progress: (StemExportProgress) -> Unit): StemExportReceipt = withContext(Dispatchers.IO) {
        ensureActive()
        val program = compiler.compile(project, target, 0)
        try {
            val context = currentCoroutineContext()
            val cancelled = { !context.isActive }
            val tail = if (request.tailMode == ExportTailMode.INCLUDE_GRAPH_TAIL) maxOf(request.tailFrames, program.mixer.tailFrames) else request.tailFrames
            val frames = request.frames.toLong() + tail
            val selected = buildList {
                for (bus in 0 until MixerProgram.MAX_BUSES) {
                    val id = program.mixer.busId(bus)
                    if (id != null || (bus == MixerProgram.UNROUTED_BUS && target is PlaybackTarget.Pattern))
                        add(bus to (id ?: "pads-unrouted"))
                }
                if (program.mixer.settings.delay.enabled) add(MixerProgram.DELAY_RETURN to "delay-return")
                if (program.mixer.settings.reverb.enabled) add(MixerProgram.REVERB_RETURN to "reverb-return")
            }
            require(selected.isNotEmpty()) { "No production stems" }
            val files = selected.mapIndexed { i, (_, id) -> StemFile(id, "stem-${(i + 1).toString().padStart(2, '0')}.wav") }.frozen()
            val manifest = ProjectJson.encodeElement(obj("sampleRate" to num(48_000), "channels" to num(2), "frames" to num(frames),
                "format" to str(request.format.name), "point" to str(StemOutputPoint.POST_FADER_POST_INSERT_PRE_MASTER.name),
                "files" to arr(files.map { obj("busId" to str(it.busId), "fileName" to str(it.fileName)) })))
            val destination = resolve(request.location).toAbsolutePath().normalize()
            val parent = requireNotNull(destination.parent)
            require(Files.isDirectory(parent) && !Files.isSymbolicLink(destination))
            val entryBytes = 44 + frames * 2 * request.format.bytes
            require(entryBytes <= WavCodec.MAX_EXPORT_WAV_BYTES)
            val maximumBytes = selected.size * entryBytes + manifest.size
            // DEFLATE level zero permits streaming CRC/data descriptors; the JDK owns ZIP64 offsets.
            // No full entry is buffered. Bound stored-block/header/ZIP64 overhead before opening output.
            val diskBytes = maximumBytes + maximumBytes / 1000 + selected.size * 256 + 1024 * 1024
            require(Files.getFileStore(parent).usableSpace >= diskBytes) { "Insufficient space for atomic stem export" }
            // Includes the native zlib workspace and its input/output windows, not only JVM arrays.
            PcmMemoryBudget.shared.reserve(512 * 1024L).use {
                atomicOutput(destination, cancelled) { raw ->
                    ZipOutputStream(object : FilterOutputStream(raw) {
                        override fun write(bytes: ByteArray, offset: Int, length: Int) = out.write(bytes, offset, length)
                        override fun close() = flush()
                    }).use { zip ->
                        zip.setLevel(Deflater.NO_COMPRESSION)
                        zip.putNextEntry(ZipEntry("manifest.json").also { it.time = 0; it.size = manifest.size.toLong() })
                        zip.write(manifest); zip.closeEntry()
                        for ((index, selection) in selected.withIndex()) {
                            ensureActive()
                            val (bus, id) = selection
                            progress(StemExportProgress(StemExportPhase.RENDERING, index, selected.size, id, 0, frames))
                            zip.putNextEntry(ZipEntry(files[index].fileName).also { it.time = 0; it.size = entryBytes })
                            StreamingStemRenderer.render(program, listOf(bus to zip), request.frames, tail, request.format, request.seed,
                                cancelled = cancelled, prepared = { windows, render -> runBlocking(context) { compiler.prepared(windows, render) } },
                                progress = { written -> progress(StemExportProgress(StemExportPhase.RENDERING, index, selected.size, id, written, frames)) })
                            zip.closeEntry()
                        }
                        progress(StemExportProgress(StemExportPhase.PUBLISHING, selected.size, selected.size, null, frames, frames))
                    }
                }
            }
            progress(StemExportProgress(StemExportPhase.COMPLETE, selected.size, selected.size, null, frames, frames))
            StemExportReceipt(files, frames, request.format)
        } finally { program.releasePreparation() }
    }
}
