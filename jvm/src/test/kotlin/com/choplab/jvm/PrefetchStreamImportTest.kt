package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.model.*
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PrefetchStreamImportTest {
    @Test fun streamPickerPreservesEncodedOriginalAndReopensLongPagedPcmWithoutPcm16Intermediate() = runBlocking<Unit> {
        val root = Files.createTempDirectory("prefetch-stream-")
        val encoded = "fLaC synthetic stream fixture".toByteArray()
        val nativeInfo = WavInfo(48_000, 2, 19_200_000, 32, true)
        val decoder = object : OriginalAudioDecoder {
            override fun inspect(path: Path, hash: String, cancelled: () -> Boolean): WavInfo {
                assertContentEquals(encoded, Files.readAllBytes(path)); return nativeInfo
            }
            override fun decode(path: Path, hash: String, cancelled: () -> Boolean): WavAudio = error("Long material cannot use a resident decode")
            override fun openPcm(path: Path, hash: String, cancelled: () -> Boolean) = object : PcmFrameSource {
                override val info = nativeInfo
                override fun read(firstFrame: Int, frameCount: Int, cancelled: () -> Boolean) = FloatArray(frameCount * 2) { if (it % 2 == 0) 1.234567f else -.00000017f }
            }
        }
        try {
            val documents = object : HostDocuments {
                override fun openInput(location: Location) = ByteArrayInputStream(encoded)
                override fun openOutput(location: Location) = error("No output in this fixture")
                override fun displayName(location: Location) = "No extension"
            }
            val store = FileAssetStore(root.resolve("assets"), decoder = decoder)
            WavPcmPort(store, decoder = decoder).use { pcm ->
                val services = StreamFileServices(documents, root.resolve("scratch"), originalDecoder = decoder).create(store, ProgramCompiler(pcm))
                val asset = services.importer.import(Location("source"))
                assertEquals("flac", asset.extension); assertEquals("No extension", asset.name)
                assertContentEquals(encoded, store.read(asset))
                val project = Project(assets = frozenListOf(asset), source = Source(asset.hash, FrameRange(0, asset.frames)))
                val archive = ByteArrayOutputStream().also { ArchiveCodec().write(project, store, it) }.toByteArray()
                val reopened = FileAssetStore(root.resolve("reopened"), decoder = decoder)
                assertEquals(project, ArchiveCodec().read(ByteArrayInputStream(archive), reopened))
                assertContentEquals(encoded, reopened.read(asset))
                val audio = pcm.load(asset)
                pcm.prefetch(audio, 18_000_000, 18_004_000)
                assertEquals(1.234567f, audio.sample(18_000_000, 0)); assertEquals(-.00000017f, audio.sample(18_000_000, 1))
                assertEquals(0L, Files.list(root.resolve("scratch")).use { it.count() })
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
