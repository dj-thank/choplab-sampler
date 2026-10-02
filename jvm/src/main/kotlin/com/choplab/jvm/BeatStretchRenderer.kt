package com.choplab.jvm

import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.nio.file.Files
import java.nio.file.Path

/** Serial worker slot remains claimed through a non-cooperative read's real finally, even on Cancel. */
class BeatStretchRenderer(private val assets: FileAssetStore, private val pcm: WavPcmPort, private val scratch: Path,
                          private val diskReserveBytes: Long = 64L shl 20,
                          private val usableDiskBytes: (Path) -> Long = { it.toFile().usableSpace }) {
    private val serial = Mutex()
    suspend fun render(project: Project, draft: StretchDraft, name: String, progress: (Int, Int) -> Unit = { _, _ -> }): Asset {
        BeatStretchEdits.validate(project, draft)
        if (draft.sourceMilliBpm == draft.targetMilliBpm) { currentCoroutineContext().ensureActive(); return project.asset(draft.sourceAssetHash) }
        return produce(project, draft, name, false, progress)
    }
    suspend fun original(project: Project, draft: StretchDraft, name: String): Asset = produce(project, draft, name, true) { _, _ -> }

    private suspend fun produce(project: Project, draft: StretchDraft, name: String, original: Boolean, progress: (Int, Int) -> Unit): Asset {
        requireLabel(name); BeatStretchEdits.validate(project, draft)
        if (!serial.tryLock()) throw StretchException(StretchProblem.BUSY)
        try { return withContext(Dispatchers.IO) {
            val context = currentCoroutineContext()
            val check = { context.ensureActive() }
            val source = project.asset(draft.sourceAssetHash)
            val first = stretchFirstFrame(source, draft.sourceRange).toInt()
            val inputFrames = stretchInputFrames(source, draft.sourceRange).toInt()
            val outputFrames = if (original) inputFrames else stretchFrames(source, draft.sourceRange, draft.sourceMilliBpm, draft.targetMilliBpm).toInt()
            val bytes = 44L + outputFrames * 8L
            if (bytes > ProjectLimits.MAX_ASSET_BYTES) throw StretchException(StretchProblem.LIMIT)
            check()
            val directory = Files.createDirectories(scratch)
            if (usableDiskBytes(directory) - diskReserveBytes < bytes * 2 || usableDiskBytes(assets.directory) - diskReserveBytes < bytes ||
                assets.maxStoredBytes - assets.storedBytes() < bytes) throw StretchException(StretchProblem.LIMIT)
            val disk = try { PcmScratchBudget.reserve(bytes * 2) } catch (_: IllegalArgumentException) { throw StretchException(StretchProblem.LIMIT) }
            disk.use {
                // Admission of the workspace precedes acquisition, so a retained source cannot consume its budget.
                pcm.memory.reserve(WORKSPACE_BYTES).use {
                    pcm.acquire(source).use { lease ->
                        val pending = Files.createTempFile(directory, "stretch-", ".wav")
                        try {
                            val reader = WsolaReader { at, count, destination ->
                                check()
                                val window = runBlocking(context) { pcm.readWindow(lease.pcm, first + at, first + at + count) }
                                check(); window.copyInto(destination)
                            }
                            FileOutputStream(pending.toFile()).use { file ->
                                val stream = BufferedOutputStream(file, 65_536)
                                val writer = WavCodec.FloatWriter(stream, outputFrames.toLong())
                                WindowedWsola.process(reader, inputFrames, outputFrames,
                                    { samples, count -> check(); writer.write(samples, frameCount = count) }, check, progress)
                                writer.finish(); stream.flush(); file.fd.sync()
                            }
                            check()
                            val hash = Files.newInputStream(pending).use { input -> digest(object : FilterInputStream(input) {
                                override fun read(): Int { check(); return super.read() }
                                override fun read(b: ByteArray, off: Int, len: Int): Int { check(); return super.read(b, off, len) }
                            }, bytes) }
                            val asset = project.assets.firstOrNull { it.hash == hash } ?: Asset(hash, "wav", bytes, 48_000, 2, outputFrames.toLong(), name,
                                AssetRole.RENDERED, derivedFrom = source.hash)
                            assets.adopt(asset, pending) { !context.isActive }
                            check(); asset
                        } finally { Files.deleteIfExists(pending) }
                    }
                }
            }
        } } catch (cancel: CancellationException) { throw cancel }
        catch (_: PcmMemoryLimit) { throw StretchException(StretchProblem.LIMIT) }
        catch (failure: StretchException) { throw failure }
        catch (_: Exception) { throw StretchException(StretchProblem.FAILED) }
        finally { serial.unlock() }
    }
    companion object {
        // Returned readWindow, FloatWriter, stream and digest/verification buffers are charged as well.
        const val WORKSPACE_BYTES = WindowedWsola.WORKSPACE_BYTES + 131_072L
    }
}
