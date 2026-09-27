package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.model.Asset
import com.choplab.core.model.Project
import com.choplab.core.model.ProjectLimits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/** Documents a host reaches only as streams, such as Android's Storage Access Framework. */
interface HostDocuments {
    /** Opens [location] for reading; the caller bounds how much it takes. */
    fun openInput(location: Location): InputStream
    /** Opens [location] for writing from its start, replacing earlier content. */
    fun openOutput(location: Location): OutputStream
    /** The name the user saw, without directories, or null. */
    fun displayName(location: Location): String?
    /** Called after a write to [location] failed or was cancelled, so a document this app created is not left half-written. */
    fun discard(location: Location) {}
}

/** Turns a document that is not WAV into a WAV file at [target]. A host without decoders passes none. */
fun interface HostDecoder {
    suspend fun decodeToWav(location: Location, target: Path)
}

/**
 * [HostFileServices] over [HostDocuments]. Every document passes through a private scratch file, so the
 * shared codecs keep their bounded, verified, path-based checks, and a destination receives only a finished
 * file. Scratch files never outlive the operation; ones left by a crash are removed on construction.
 */
class StreamFileServices(
    private val documents: HostDocuments,
    private val scratch: Path,
    private val decoder: HostDecoder? = null,
) {
    init {
        Files.createDirectories(scratch)
        require(!Files.isSymbolicLink(scratch)) { "Scratch directory must not be a symbolic link" }
        Files.list(scratch).use { stale -> stale.forEach { it.toFile().deleteRecursively() } }
    }

    fun create(assets: FileAssetStore, compiler: ProgramCompiler): HostFileServices {
        val importer = object : ImportPort {
            override suspend fun import(location: Location): Asset = withContext(Dispatchers.IO) {
                inScratch { file ->
                    val wav = copyIfWav(documents.openInput(location), file, ProjectLimits.MAX_ASSET_BYTES, coroutineContext)
                    if (!wav) {
                        val host = decoder ?: throw IllegalArgumentException("This audio format needs a decoder")
                        Files.deleteIfExists(file)
                        host.decodeToWav(location, file)
                        coroutineContext.ensureActive()
                    }
                    WavImportPort(assets, { file }, { hostName(documents.displayName(location)) }).import(location)
                }
            }
        }
        val projects = object : ProjectPort {
            override suspend fun save(project: Project, revision: Long, location: Location) = withContext(Dispatchers.IO) {
                inScratch { file ->
                    FileProjectPort(assets, { file }).save(project, revision, location)
                    publish(file, location)
                }
            }
            override suspend fun open(location: Location): Project = withContext(Dispatchers.IO) {
                inScratch { file ->
                    copyBounded(documents.openInput(location), file, ArchiveLimits().maxArchiveBytes, coroutineContext)
                    FileProjectPort(assets, { file }).open(location)
                }
            }
        }
        val exporter = object : ExportPort {
            override suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt =
                withContext(Dispatchers.IO) {
                    inScratch { file -> WavExportPort(compiler) { file }.export(project, patternId, request).also { publish(file, request.location) } }
                }
            override suspend fun export(project: Project, target: PlaybackTarget, request: ExportRequest): ExportReceipt =
                withContext(Dispatchers.IO) {
                    inScratch { file -> WavExportPort(compiler) { file }.export(project, target, request).also { publish(file, request.location) } }
                }
        }
        return HostFileServices(importer, projects, exporter)
    }

    private suspend fun <T> inScratch(block: suspend (Path) -> T): T {
        val directory = Files.createTempDirectory(scratch, "io-")
        try { return block(directory.resolve("document")) }
        finally { directory.toFile().deleteRecursively() }
    }

    /** Copies the finished scratch file to the host document; a failed or cancelled copy is discarded. */
    private suspend fun publish(file: Path, location: Location) {
        val context = coroutineContext
        try {
            documents.openOutput(location).use { output ->
                Files.newInputStream(file).use { input -> pump(input, output, Long.MAX_VALUE, context) }
                output.flush()
            }
        } catch (failure: Throwable) {
            try { documents.discard(location) } catch (_: Exception) { }
            throw failure
        }
    }
}

/** A user-visible name for an asset: no directories or control characters, never blank. */
internal fun hostName(name: String?): String? {
    val leaf = name?.substringAfterLast('/')?.substringAfterLast('\\')?.filterNot { it.isISOControl() }?.trim()
    return leaf?.takeIf { it.isNotEmpty() && it != "." && it != ".." }?.take(256)
}

private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
private val WAVE = "WAVE".toByteArray(Charsets.US_ASCII)

/** Copies [input] to [target] when it starts like a RIFF/WAVE file; otherwise copies nothing. */
internal fun copyIfWav(input: InputStream, target: Path, limit: Long, context: CoroutineContext): Boolean = input.use { source ->
    // InputStream.readNBytes needs Android API 33; this module also runs on API 29.
    val header = ByteArray(12)
    var filled = 0
    while (filled < header.size) { val count = source.read(header, filled, header.size - filled); if (count < 0) break; filled += count }
    val wav = filled == 12 && header.copyOfRange(0, 4).contentEquals(RIFF) && header.copyOfRange(8, 12).contentEquals(WAVE)
    if (wav) Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output ->
        output.write(header)
        pump(source, output, limit - header.size, context)
    }
    wav
}

internal fun copyBounded(input: InputStream, target: Path, limit: Long, context: CoroutineContext) = input.use { source ->
    Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use { output -> pump(source, output, limit, context) }
}

private fun pump(input: InputStream, output: OutputStream, limit: Long, context: CoroutineContext) {
    val buffer = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        context.ensureActive()
        val count = input.read(buffer)
        if (count < 0) return
        total += count
        require(total <= limit) { "Document exceeds its size limit" }
        output.write(buffer, 0, count)
    }
}
