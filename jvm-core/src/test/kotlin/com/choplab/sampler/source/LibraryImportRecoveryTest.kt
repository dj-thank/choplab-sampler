package com.choplab.sampler.source

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class LibraryImportRecoveryTest {
    private fun isolated(action: (File) -> Unit) {
        val root = Files.createTempDirectory("library-import-recovery-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
    private val tags = linkedMapOf("TITLE" to "曲の名前", "ARTIST" to "Artist 日本語", "ALBUM" to "Album", "TRACKNUMBER" to "3/12", "DISCNUMBER" to "2/2")
    private fun flac(): ByteArray {
        val comments = ByteArrayOutputStream()
        fun integer(value: Int) { comments.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()) }
        integer(0); integer(tags.size)
        tags.forEach { (key, value) -> val bytes = "$key=$value".toByteArray(); integer(bytes.size); comments.write(bytes) }
        val bytes = comments.toByteArray()
        return "fLaC".toByteArray() + byteArrayOf(0x84.toByte(), (bytes.size shr 16).toByte(), (bytes.size shr 8).toByte(), bytes.size.toByte()) + bytes + byteArrayOf(1, 2, 3)
    }
    private fun mp3(): ByteArray {
        val names = listOf("TIT2", "TPE1", "TALB", "TRCK", "TPOS")
        val frames = ByteArrayOutputStream()
        DataOutputStream(frames).use { output -> tags.values.forEachIndexed { i, text ->
            val bytes = byteArrayOf(3) + text.toByteArray()
            output.writeBytes(names[i]); output.writeInt(bytes.size); output.writeShort(0); output.write(bytes)
        } }
        val n = frames.size()
        return byteArrayOf(73, 68, 51, 3, 0, 0, (n shr 21).toByte(), ((n shr 14) and 127).toByte(), ((n shr 7) and 127).toByte(), (n and 127).toByte()) + frames.toByteArray() + byteArrayOf(1, 2, 3)
    }

    @Test fun observedFlacAndId3TagsPopulateArtistAlbumTrackOrderWithoutChangingOriginalBytes() = isolated { root ->
        var validations = 0
        val library = LocalAudioLibrary(File(root, "library")) { validations++ }
        for ((ext, bytes) in listOf("flac" to flac(), "mp3" to mp3())) {
            val file = File(root, "001.$ext").apply { writeBytes(bytes) }
            val result = library.importFileResult(file)
            assertNull(result.tagProblem)
            with(result.item) {
                assertEquals("曲の名前", title); assertEquals("Artist 日本語", artist); assertEquals("Album", album)
                assertEquals(3, trackNumber); assertEquals(2, discNumber)
                assertArrayEquals(bytes, library.resolve(id).readBytes()); assertArrayEquals(bytes, file.readBytes())
            }
            assertFalse(library.importFileResult(file).added)
        }
        assertEquals(2, validations)
        val browser = LibraryBrowser()
        browser.open(browser.page(library.list()).groups.single())
        browser.open(browser.page(library.list()).groups.single())
        assertEquals(2, browser.page(library.list()).tracks.size)
        assertEquals(2, validations) // Metadata browsing does not decode.
    }

    @Test fun missingMalformedAndOversizedTagsFallBackWithoutDiscardingTheAudio() = isolated { root ->
        val inputs = listOf(
            "plain.mp3" to byteArrayOf(1, 2, 3),
            "broken.mp3" to ("ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 0, 2, 0)),
            "large.mp3" to ("ID3".toByteArray() + byteArrayOf(3, 0, 0, 0, 65, 0, 0)))
        val library = LocalAudioLibrary(File(root, "library")) {}
        inputs.forEachIndexed { index, (name, bytes) ->
            val file = File(root, name).apply { writeBytes(bytes) }
            val result = library.importFileResult(file)
            assertEquals(listOf(null, LocalAudioTags.Problem.MALFORMED, LocalAudioTags.Problem.TOO_LARGE)[index], result.tagProblem)
            assertEquals(file.nameWithoutExtension, result.item.title); assertEquals("", result.item.artist)
            assertArrayEquals(bytes, library.resolve(result.item.id).readBytes())
        }
    }

    @Test fun sameSizeAndTruncatedCorruptionCannotMasqueradeAsReuseAndOnlyExplicitRepairRestoresIt() = isolated { root ->
        var validations = 0
        val source = File(root, "original.wav").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val library = LocalAudioLibrary(File(root, "library")) { validations++ }
        val item = library.importFile(source)
        val payload = library.resolve(item.id)
        for (broken in listOf(byteArrayOf(4, 3, 2, 1), byteArrayOf(1))) {
            payload.writeBytes(broken)
            assertThrows(LocalAudioLibrary.CorruptPayload::class.java) { library.importFileResult(source) }
            assertArrayEquals(broken, payload.readBytes())
            val repaired = library.repairFromOriginal(source)
            assertTrue(repaired.repaired); assertFalse(repaired.added)
            assertArrayEquals(source.readBytes(), payload.readBytes())
            assertTrue(File(library.directory, ".recovery").listFiles()!!.any { it.readBytes().contentEquals(broken) })
            val output = File(root, "verified.choplib")
            library.exportBundle(output, listOf(item.id)); assertTrue(output.length() > 0)
        }
        val before = validations
        assertFalse(library.importFileResult(source).added); assertEquals(before, validations)
    }

    @Test fun secondAdoptionFailureReportsAllCompletedItemsAndRetryReusesTheirExactBytes() = isolated { root ->
        val bundle = File(root, "set.choplib")
        ZipOutputStream(bundle.outputStream()).use { output -> repeat(3) { n ->
            output.putNextEntry(ZipEntry("Song $n.wav")); output.write(byteArrayOf(n.toByte(), 2, 3)); output.closeEntry()
        } }
        val directory = File(root, "library")
        var commits = 0; var fail = true
        val library = LocalAudioLibrary(directory) { file ->
            if (file.parentFile == directory && ++commits == 2 && fail) throw IOException("Injected second adoption failure")
        }
        val failure = assertThrows(LocalAudioLibrary.PartialBundle::class.java) { library.importBundleResults(bundle) }
        assertEquals(2, failure.completed.size); assertEquals(1, failure.failures.size)
        assertEquals(failure.completed.map { it.item.id }.toSet(), library.list().map { it.id }.toSet())
        val originals = library.list().associate { it.id to library.resolve(it.id).readBytes() }
        fail = false
        val retry = library.importBundleResults(bundle)
        assertEquals(1, retry.count { it.added }); assertEquals(2, retry.count { !it.added })
        assertEquals(3, library.list().size)
        originals.forEach { (id, bytes) -> assertArrayEquals(bytes, library.resolve(id).readBytes()) }
    }

    @Test fun catalogDistinguishesEmptyPartialAndWhollyUnreadablePagesWithoutExposingProviderText() {
        val request = SpotifyCatalogRequest(SpotifyCatalogRoute.FAVORITES)
        fun page(items: String) = SpotifyCatalogJson.page("""{"items":[$items],"total":3,"next":"bounded"}""", request)
        val good = """{"track":{"id":"${"1".repeat(22)}","name":"Known"}}"""
        assertEquals(0, page("").unreadable)
        assertEquals(1, page("$good,{\"track\":null}").unreadable)
        assertEquals("Known", page("$good,{\"track\":null}").entries.single().title)
        val missing = page("{\"track\":null},{\"track\":{\"name\":\"private response\"}}")
        assertEquals(2, missing.unreadable); assertTrue(missing.entries.isEmpty()); assertTrue(missing.hasMore)
    }
}
