package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.model.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

/** Android's stream import composition, with a synthetic native codec and no device/provider operation. */
class OnlineLibraryStreamTest {
    @Test fun namedLibraryReceiptPreservesEncodedBytesUndoArchiveAndAutosaveAndRejectsChangedInput() = runBlocking<Unit> {
        val root = Files.createTempDirectory("online-stream-")
        val encoded = "fLaC synthetic online receipt".toByteArray()
        val hash = sha256(encoded)
        val info = WavInfo(48_000, 2, 4_096, 32, true)
        var input = encoded
        val outputs = mutableMapOf<String, ByteArray>()
        var decoded = 0
        fun decoder() = object : OriginalAudioDecoder {
            override fun inspect(path: Path, hash: String, cancelled: () -> Boolean): WavInfo {
                assertContentEquals(encoded, Files.readAllBytes(path)); decoded++; return info
            }
            override fun decode(path: Path, hash: String, cancelled: () -> Boolean) = WavAudio(info, FloatArray(8192) { if (it % 2 == 0) .1234567f else -.00000017f })
            override fun openPcm(path: Path, hash: String, cancelled: () -> Boolean) = object : PcmFrameSource {
                override val info = info
                override fun read(firstFrame: Int, frameCount: Int, cancelled: () -> Boolean) = FloatArray(frameCount * 2) { if (it % 2 == 0) .1234567f else -.00000017f }
            }
        }
        val documents = object : HostDocuments {
            override fun openInput(location: Location) = if (location.handle == "library")
                VerifiedLibraryInput(ByteArrayInputStream(input), hash) else ByteArrayInputStream(outputs.getValue(location.handle))
            override fun openOutput(location: Location) = object : ByteArrayOutputStream() {
                override fun close() { outputs[location.handle] = toByteArray() }
            }
            override fun displayName(location: Location) = if (location.handle == "library") "Title.wav.flac" else "Project.choplab"
        }
        fun backend(): EditorBackend {
            val codec = decoder()
            return EditorBackend.create(root.resolve("profile"), { StreamingEnginePort(it, { error("No native output") }) },
                StreamFileServices(documents, root.resolve("scratch"), originalDecoder = codec)::create, codec)
        }
        suspend fun ready(studio: Studio) {
            withTimeout(5_000) { studio.work.first { it.jobId == null && it.preparationId == null } }
            studio.dispatch(Action.RefreshTransport)
        }
        try {
            val editor = backend()
            val saved: Project
            try {
                val before = editor.studio.document.value
                assertTrue(editor.studio.dispatch(Action.Import(Location("library"), before.revision)).accepted); ready(editor.studio)
                val imported = editor.studio.document.value.project
                val asset = imported.assets.single()
                assertEquals("flac", asset.extension); assertEquals("Title.wav.flac", asset.name)
                assertEquals(hash, asset.hash); assertContentEquals(encoded, editor.assets.read(asset))
                assertEquals(before.revision + 1, editor.studio.document.value.revision)
                assertTrue(editor.studio.dispatch(Action.Undo).accepted)
                assertEquals(before.project, editor.studio.document.value.project)
                assertFalse(editor.studio.document.value.canUndo)
                assertTrue(editor.studio.dispatch(Action.Redo).accepted)
                assertEquals(imported, editor.studio.document.value.project)
                assertTrue(editor.studio.dispatch(Action.Save(Location("archive"))).accepted); ready(editor.studio)
                val fresh = FileAssetStore(root.resolve("archive-assets"), decoder = decoder())
                assertEquals(imported, ArchiveCodec().read(ByteArrayInputStream(outputs.getValue("archive")), fresh))
                assertContentEquals(encoded, fresh.read(asset))
                val current = editor.studio.document.value
                val calls = decoded
                input = encoded.copyOf().also { it[it.lastIndex] = 0 }
                assertTrue(editor.studio.dispatch(Action.Import(Location("library"), current.revision)).accepted); ready(editor.studio)
                assertEquals(current, editor.studio.document.value)
                assertEquals(calls, decoded, "Changed bytes are rejected before any codec/asset adoption")
                assertContentEquals(encoded, editor.assets.read(asset))
                assertEquals(0L, Files.list(root.resolve("scratch")).use { it.count() })
                saved = imported
                editor.flushAutosave()
            } finally { editor.shutdown() }
            val reopened = backend()
            try {
                assertEquals(saved, reopened.studio.document.value.project)
                assertContentEquals(encoded, reopened.assets.read(saved.assets.single()))
            } finally { reopened.shutdown() }
        } finally { root.toFile().deleteRecursively() }
    }
}
