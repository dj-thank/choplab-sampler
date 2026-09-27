package com.choplab.jvm

import com.choplab.core.Location
import com.choplab.core.model.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class OriginalAudioTest {
    private val bytes = "synthetic encoded fixture".toByteArray()
    private val samples = floatArrayOf(.25000012f, -.12500024f, .5f, -.25f)
    private fun decoder() = object : OriginalAudioDecoder {
        override fun inspect(path: Path, hash: String, cancelled: () -> Boolean): WavInfo {
            if (cancelled()) throw CancellationException()
            require(Files.readAllBytes(path).contentEquals(bytes))
            return WavInfo(48_000, 2, 2, 32, true)
        }
        override fun decode(path: Path, hash: String, cancelled: () -> Boolean) = WavAudio(inspect(path, hash, cancelled), samples.copyOf())
    }

    @Test fun cancellingPcmWaiterStopsTheHostMetadataDecode() = runBlocking {
        val root = Files.createTempDirectory("original-cancel-")
        val entered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        var waiting = false
        val codec = object : OriginalAudioDecoder {
            override fun inspect(path: Path, hash: String, cancelled: () -> Boolean): WavInfo {
                if (waiting) {
                    entered.complete(Unit)
                    val deadline = System.nanoTime() + 2_000_000_000
                    while (!cancelled() && System.nanoTime() < deadline) Thread.sleep(5)
                    if (cancelled()) { stopped.complete(Unit); throw CancellationException() }
                    error("Cancellation did not reach the decoder")
                }
                return WavInfo(48_000, 2, 2, 32, true)
            }
            override fun decode(path: Path, hash: String, cancelled: () -> Boolean) = error("Cancelled before PCM decode")
        }
        try {
            val asset = Asset(sha256(bytes), "flac", bytes.size.toLong(), 48_000, 2, 2, "fixture.flac")
            val store = FileAssetStore(root, decoder = codec)
            store.publish(asset, ByteArrayInputStream(bytes))
            waiting = true
            WavPcmPort(store, decoder = codec).use { pcm ->
                val job = async { pcm.load(asset) }
                withTimeout(5_000) { entered.await() }
                job.cancelAndJoin()
                withTimeout(1_000) { stopped.await() }
            }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun originalBytesSurvivePublicationArchiveAndFreshStoreWhileFloatChannelsArePreserved() = runBlocking {
        val root = Files.createTempDirectory("original-audio-")
        try {
            val input = root.resolve("音源.FLAC"); Files.write(input, bytes)
            val codec = decoder()
            val store = FileAssetStore(root.resolve("assets"), maxStoredBytes = bytes.size.toLong(), decoder = codec)
            val asset = OriginalAudioImportPort(store, { input }, codec).import(Location("test"))
            assertEquals("flac", asset.extension)
            assertEquals(AssetRole.ORIGINAL, asset.role)
            assertContentEquals(bytes, store.read(asset))
            assertEquals(bytes.size.toLong(), store.storedBytes(), "Snapshot must not be double-counted against quota")
            WavPcmPort(store, decoder = codec).use { pcm ->
                val audio = pcm.load(asset)
                assertEquals(samples[0], audio.sample(0, 0)); assertEquals(samples[1], audio.sample(0, 1))
            }
            val project = Project(assets = frozenListOf(asset), source = Source(asset.hash, FrameRange(0, 2)))
            val archive = ByteArrayOutputStream().also { ArchiveCodec().write(project, store, it) }.toByteArray()
            val reopened = FileAssetStore(root.resolve("reopened"), decoder = decoder())
            assertEquals(project, ArchiveCodec().read(ByteArrayInputStream(archive), reopened))
            assertContentEquals(bytes, reopened.read(asset))
            assertFalse(reopened.verified(asset.copy(frames = 3)))
            Files.write(reopened.verifiedPath(asset), ByteArray(bytes.size))
            assertFalse(reopened.verified(asset), "Cached metadata must never bypass byte verification")
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun unsupportedHostsRejectEncodedAssetsAndCancelledOrInvalidImportsPublishNothing() = runBlocking {
        val root = Files.createTempDirectory("original-rejection-")
        try {
            val input = root.resolve("fixture.flac"); Files.write(input, bytes)
            val codec = decoder()
            val store = FileAssetStore(root.resolve("assets"), decoder = codec)
            val invalid = root.resolve("broken.flac"); Files.writeString(invalid, "invalid")
            assertFailsWith<IllegalArgumentException> { OriginalAudioImportPort(store, { invalid }, codec).import(Location("test")) }
            val asset = Asset(sha256(bytes), "flac", bytes.size.toLong(), 48_000, 2, 2, "fixture.flac")
            assertFailsWith<IllegalArgumentException> { FileAssetStore(root.resolve("unsupported")).publish(asset, ByteArrayInputStream(bytes)) }
            val snapshot = root.resolve("snapshot"); Files.write(snapshot, bytes)
            assertFailsWith<CancellationException> { store.adopt(asset, snapshot) { true } }
            assertFalse(Files.exists(snapshot))
            assertEquals(0L, store.storedBytes())
        } finally { root.toFile().deleteRecursively() }
    }
}
