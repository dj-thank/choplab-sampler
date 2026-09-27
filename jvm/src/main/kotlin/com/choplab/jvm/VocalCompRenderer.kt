package com.choplab.jvm

import com.choplab.core.model.*
import com.choplab.core.vocal.*
import com.choplab.engine.PcmAsset
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path

/** Worker-only float render. Two source leases and three <=4096-frame buffers; no full-take/comp PCM array. */
class VocalCompRenderer(
    private val assets: FileAssetStore,
    private val pcm: WavPcmPort,
    private val scratch: Path,
    private val diskReserveBytes: Long = 64L shl 20,
    private val usableDiskBytes: (Path) -> Long = { it.toFile().usableSpace },
) {
    private val serial = Mutex()

    suspend fun render(project: Project, draft: VocalCompDraft, name: String, blockFrames: Int = 4096): Asset = serial.withLock {
        requireLabel(name)
        require(blockFrames in 1..4096)
        val plan = VocalCompMix.plan(project, draft)
        val frames = draft.endFrame - draft.startFrame
        val bytes = 44 + frames * 8
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            val directory = Files.createDirectories(scratch)
            if (usableDiskBytes(directory) - diskReserveBytes < bytes || assets.maxStoredBytes - assets.storedBytes() < bytes)
                throw VocalEditException(VocalProblem.LIMIT)
            PcmScratchBudget.reserve(bytes).use {
                val pending = Files.createTempFile(directory, "comp-", ".wav")
                try {
                    // Strong references keep at most the currently blended sources alive across worker reads.
                    val leases = LinkedHashMap<String, PcmAsset>()
                    val mixed = FloatArray(blockFrames * 2)
                    suspend fun read(take: Take?, at: Long, count: Int): FloatArray? {
                        if (take == null) return null
                        val source = project.asset(take.assetHash)
                        val loaded = leases.getOrPut(take.assetHash) { error("Source lease was not prepared") }
                        val first = takeSourceFrame48(take.range.start, source.sampleRate) + at - take.correctedStartFrame()
                        require(first >= 0 && first + count <= loaded.frameCount)
                        return pcm.readWindow(loaded, first.toInt(), (first + count).toInt())
                    }
                    FileOutputStream(pending.toFile()).use { file ->
                        val output = BufferedOutputStream(file, 64 * 1024)
                        val writer = WavCodec.FloatWriter(output, frames)
                        for (span in plan.spans) {
                            currentCoroutineContext().ensureActive()
                            val needed = listOfNotNull(span.from, span.to).map { it.assetHash }.toSet()
                            leases.keys.retainAll(needed)
                            for (hash in needed) if (hash !in leases) leases[hash] = pcm.load(project.asset(hash))
                            var at = span.start
                            while (at < span.end) {
                                currentCoroutineContext().ensureActive()
                                val count = minOf(blockFrames.toLong(), span.end - at).toInt()
                                val first = read(span.from, at, count)
                                val second = if (span.to == span.from) first else read(span.to, at, count)
                                for (frame in 0 until count) for (channel in 0..1) {
                                    val index = frame * 2 + channel
                                    mixed[index] = span.mix(first?.get(index) ?: 0f, second?.get(index) ?: 0f, at + frame)
                                }
                                writer.write(mixed, frameCount = count)
                                at += count
                            }
                        }
                        writer.finish(); output.flush(); file.fd.sync()
                    }
                    currentCoroutineContext().ensureActive()
                    val hash = Files.newInputStream(pending).use { digest(it, bytes) }
                    val rendered = Asset(hash, "wav", bytes, 48_000, 2, frames, name, AssetRole.RENDERED)
                    val context = currentCoroutineContext()
                    assets.adopt(rendered, pending) { !context.isActive }
                    currentCoroutineContext().ensureActive()
                    rendered
                } finally { Files.deleteIfExists(pending) }
            }
        }
    }
}
