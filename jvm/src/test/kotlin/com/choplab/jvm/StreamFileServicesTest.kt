package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.model.Asset
import kotlinx.coroutines.runBlocking
import java.io.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.*

/** Stream-only documents (Android's Storage Access Framework) reach the shared codecs through scratch files. */
class StreamFileServicesTest {
    private fun directory(): Path = Files.createTempDirectory("choplab-stream-")
    private fun Path.isEmptyDirectory() = Files.list(this).use { it.findFirst().isEmpty }

    private class MemoryDocuments : HostDocuments {
        val inputs = ConcurrentHashMap<String, ByteArray>()
        val names = ConcurrentHashMap<String, String>()
        val outputs = ConcurrentHashMap<String, ByteArray>()
        val discarded = ConcurrentHashMap.newKeySet<String>()
        @Volatile var failAfterBytes: Int? = null
        override fun openInput(location: Location): InputStream = ByteArrayInputStream(inputs.getValue(location.handle))
        override fun openOutput(location: Location): OutputStream = object : ByteArrayOutputStream() {
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                failAfterBytes?.let { limit -> if (size() + length > limit) throw IOException("Storage provider went away") }
                super.write(bytes, offset, length)
            }
            override fun close() { outputs[location.handle] = toByteArray() }
        }
        override fun displayName(location: Location): String? = names[location.handle]
        override fun discard(location: Location) { discarded += location.handle; outputs.remove(location.handle) }
    }

    private class Setup(val documents: MemoryDocuments = MemoryDocuments(), decoder: HostDecoder? = null) {
        val root: Path = Files.createTempDirectory("choplab-stream-")
        val scratch: Path = root.resolve("scratch")
        val assets = FileAssetStore(root.resolve("assets"))
        val compiler = ProgramCompiler(WavPcmPort(assets))
        val services = StreamFileServices(documents, scratch, decoder).create(assets, compiler)
    }

    private val loop = Fixtures.wav(samples = ShortArray(4096) { (it * 37 % 2000 - 1000).toShort() })

    @Test fun wavImportKeepsTheNameTheUserSawAndLeavesNoScratch() = runBlocking<Unit> {
        val setup = Setup()
        setup.documents.inputs["a"] = loop
        setup.documents.names["a"] = "Download/ループ:1.wav"
        val asset = setup.services.importer.import(Location("a"))
        assertEquals("ループ_1.wav", asset.name)
        assertEquals(sha256(loop), asset.hash, "WAV bytes are kept exactly as picked")
        assertTrue(setup.assets.verified(asset))
        assertTrue(setup.scratch.isEmptyDirectory())
    }

    @Test fun otherFormatsGoThroughTheHostDecoderOnce() = runBlocking<Unit> {
        val calls = AtomicInteger()
        val setup = Setup(decoder = { location, target ->
            assertEquals("song", location.handle)
            calls.incrementAndGet()
            Files.write(target, loop)
        })
        setup.documents.inputs["song"] = "ID3 compressed audio".toByteArray()
        setup.documents.names["song"] = "Song.mp3"
        val asset = setup.services.importer.import(Location("song"))
        assertEquals(1, calls.get())
        assertEquals("Song.mp3", asset.name)
        assertEquals("wav", asset.extension)
        assertTrue(setup.scratch.isEmptyDirectory())
    }

    @Test fun withoutADecoderOnlyWavIsAccepted() = runBlocking<Unit> {
        val setup = Setup()
        setup.documents.inputs["m"] = "ID3 compressed audio".toByteArray()
        assertFailsWith<IllegalArgumentException> { setup.services.importer.import(Location("m")) }
        setup.documents.inputs["short"] = "RIFF".toByteArray()
        assertFailsWith<IllegalArgumentException> { setup.services.importer.import(Location("short")) }
        assertTrue(setup.scratch.isEmptyDirectory())
    }

    @Test fun oversizedDocumentsStopAtTheirLimit() {
        val target = directory().resolve("copy")
        assertFailsWith<IllegalArgumentException> { copyBounded(ByteArrayInputStream(ByteArray(100)), target, 50, EmptyCoroutineContext) }
        copyBounded(ByteArrayInputStream(ByteArray(50)), directory().resolve("copy"), 50, EmptyCoroutineContext)
    }

    @Test fun savedProjectReachesTheHostWhole() = runBlocking<Unit> {
        val setup = Setup()
        val asset = seed(setup)
        val project = Fixtures.project(asset)
        setup.services.projects.save(project, 3, Location("out"))
        val stored = requireNotNull(setup.documents.outputs["out"])
        val reopened = ArchiveCodec().read(ByteArrayInputStream(stored), FileAssetStore(directory()))
        assertEquals(project, reopened)
        assertTrue(setup.scratch.isEmptyDirectory())

        setup.documents.inputs["in"] = stored
        assertEquals(project, setup.services.projects.open(Location("in")))
        assertTrue(setup.scratch.isEmptyDirectory())
    }

    @Test fun interruptedWriteDiscardsTheHalfWrittenDocument() = runBlocking<Unit> {
        val setup = Setup()
        val asset = seed(setup)
        setup.documents.failAfterBytes = 100
        assertFailsWith<IOException> { setup.services.projects.save(Fixtures.project(asset), 1, Location("out")) }
        assertEquals(setOf("out"), setup.documents.discarded)
        assertNull(setup.documents.outputs["out"])
        assertTrue(setup.scratch.isEmptyDirectory())
    }

    @Test fun exportPublishesTheRenderedWav() = runBlocking<Unit> {
        val setup = Setup()
        val project = Fixtures.project(seed(setup))
        val receipt = setup.services.exporter.export(project, PlaybackTarget.Pattern("pattern-1"), ExportRequest(Location("wav"), 4096, bits = 16))
        assertEquals(4096L, receipt.frames)
        val audio = WavCodec.read(ByteArrayInputStream(requireNotNull(setup.documents.outputs["wav"])))
        assertEquals(4096, audio.info.frames.toInt())
        assertEquals(2, audio.info.channels)
        assertTrue(setup.scratch.isEmptyDirectory())
    }

    @Test fun scratchLeftByACrashIsRemoved() {
        val root = directory()
        val leftover = root.resolve("scratch/io-1234/document")
        Files.createDirectories(leftover.parent)
        Files.write(leftover, ByteArray(16))
        StreamFileServices(MemoryDocuments(), root.resolve("scratch"))
        assertTrue(root.resolve("scratch").isEmptyDirectory())
    }

    @Test fun hostNamesLoseDirectoriesAndControlCharacters() {
        assertEquals("take.wav", hostName("a/b\\take.wav"))
        assertEquals("take 2.wav", hostName(" take\u0000 2.wav\n"))
        assertNull(hostName(".."))
        assertNull(hostName("dir/"))
        assertNull(hostName(null))
        assertEquals(256, hostName("x".repeat(400))?.length)
    }

    @Test fun decodedSixteenBitSamplesAreStoredExactly() {
        val samples = shortArrayOf(Short.MIN_VALUE, -1, 0, 1, Short.MAX_VALUE, 12345)
        val bytes = ByteArrayOutputStream().also { WavCodec.writePcm16(it, samples, 44_100, 2) }.toByteArray()
        assertContentEquals(Fixtures.wav(channels = 2, rate = 44_100, samples = samples), bytes)
        val info = WavCodec.inspect(ByteArrayInputStream(bytes))
        assertEquals(WavInfo(44_100, 2, 3, 16, false), info)
        assertFailsWith<IllegalArgumentException> { WavCodec.writePcm16(ByteArrayOutputStream(), shortArrayOf(1, 2, 3), 48_000, 2) }
    }

    private fun seed(setup: Setup): Asset {
        val asset = Fixtures.asset(loop)
        setup.assets.publish(asset, ByteArrayInputStream(loop))
        return asset
    }
}
