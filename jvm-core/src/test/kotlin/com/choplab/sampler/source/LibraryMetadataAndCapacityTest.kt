package com.choplab.sampler.source

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Properties
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class LibraryMetadataAndCapacityTest {
    private fun root(action: (File) -> Unit) {
        val root = Files.createTempDirectory("library-metadata-").toFile()
        try { action(root) } finally { root.deleteRecursively() }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    /** Existing on-disk metadata, including a library created by the older unbounded importer. */
    private fun fixture(directory: File, n: Int): AudioLibraryItem {
        val bytes = "synthetic-$n".toByteArray()
        val id = hash(bytes)
        File(directory, "$id.wav").writeBytes(bytes)
        File(directory, "$id.properties").also { file ->
            file.writer().use { writer -> Properties().apply {
                setProperty("title", "Song $n"); setProperty("extension", "wav"); setProperty("origin", "file")
            }.store(writer, "Synthetic test metadata") }
            file.setLastModified(1_000_000L + n)
        }
        return AudioLibraryItem(id, "Song $n", "file", bytes.size.toLong())
    }

    @Test fun selectedAlbumCanBeExportedFromEightHundredSongsWithExactMetadataAndBytes() = root { root ->
        val library = LocalAudioLibrary(File(root, "library")) {}
        val all = (0 until 800).map { fixture(library.directory, it) }
        val selected = all.subList(100, 120).mapIndexed { index, item ->
            library.enrich(item.id, AudioLibraryMetadata("Artist 日本語", "Album / : ?", index + 1, 1))
        }
        // A title unsuitable as a ZIP filename must still survive exactly in the sidecar.
        val original = File(root, "special.wav").apply { writeText("special original") }
        val special = library.importFileResult(original, "Live: 曲 / take?", metadata = AudioLibraryMetadata("Artist", "Album")).item
        val bundle = File(root, "selection.choplib")
        val chosen = selected + special
        library.exportBundle(bundle, chosen.map { it.id })
        ZipFile(bundle).use { assertEquals(22, it.size()); assertNotNull(it.getEntry(LibraryBundleMetadata.ENTRY)) }
        val restored = LocalAudioLibrary(File(root, "restored")) {}
        val imported = restored.importBundleResults(bundle)
        assertEquals(21, imported.count { it.added })
        assertEquals(chosen.associateBy { it.id }, restored.list().associateBy { it.id }.mapValues { (id, item) -> item.copy(origin = chosen.single { it.id == id }.origin) })
        chosen.forEach { assertArrayEquals(library.resolve(it.id).readBytes(), restored.resolve(it.id).readBytes()) }
        assertTrue(restored.importBundleResults(bundle).all { !it.added })
        assertEquals(801, library.list().size)
    }

    @Test fun malformedOrMismatchedSidecarsNeverAdoptAudioAndOldBundlesStillImport() = root { root ->
        val source = LocalAudioLibrary(File(root, "source")) {}
        val input = File(root, "source.wav").apply { writeText("original bytes") }
        val item = source.importFile(input)
        val good = LibraryBundleMetadata.encode(listOf(item))
        val bad = listOf(
            good.toString(Charsets.UTF_8).replace(item.id, "0".repeat(64)).toByteArray(),
            good.toString(Charsets.UTF_8).replace("\"version\":1", "\"version\":1,\"version\":1").toByteArray(),
            good.toString(Charsets.UTF_8).replace("\"trackNumber\":null", "\"trackNumber\":0").toByteArray(),
            ByteArray(LibraryBundleMetadata.MAX_BYTES + 1) { ' '.code.toByte() })
        val target = LocalAudioLibrary(File(root, "target")) {}
        bad.forEachIndexed { index, manifest ->
            val bundle = File(root, "$index.zip")
            ZipOutputStream(bundle.outputStream()).use {
                it.putNextEntry(ZipEntry("source.wav")); it.write(input.readBytes()); it.closeEntry()
                it.putNextEntry(ZipEntry(LibraryBundleMetadata.ENTRY)); it.write(manifest); it.closeEntry()
            }
            assertThrows(Exception::class.java) { target.importBundle(bundle) }
            assertTrue(target.list().isEmpty()); assertTrue(target.directory.listFiles()!!.isEmpty())
        }
        val old = File(root, "old.zip")
        ZipOutputStream(old.outputStream()).use { it.putNextEntry(ZipEntry("Old title.wav")); it.write(input.readBytes()); it.closeEntry() }
        val oldItem = target.importBundle(old).single()
        assertEquals("Old title", oldItem.title); assertEquals("", oldItem.artist); assertNull(oldItem.trackNumber)
        assertArrayEquals(input.readBytes(), target.resolve(oldItem.id).readBytes())
    }

    @Test fun fullLibraryRefusesNewItemsWithoutLossAndOlderOverflowEntriesRemainReachable() = root { root ->
        val library = LocalAudioLibrary(File(root, "library")) {}
        val all = (0 until LocalAudioLibrary.MAX_ITEMS).map { fixture(library.directory, it) }
        val newFile = File(root, "new.wav").apply { writeText("new bytes") }
        assertThrows(LocalAudioLibrary.CapacityExceeded::class.java) { library.importFile(newFile) }
        assertEquals(2000, library.listing().total)
        assertFalse(library.importFileResult(library.resolve(all.first().id)).added)
        val overflow = fixture(library.directory, 2000)
        val first = library.listing()
        val older = library.listing(2000)
        assertEquals(2000, first.items.size); assertTrue(first.hasNext)
        assertEquals(1, older.items.size); assertTrue(older.hasPrevious); assertFalse(older.hasNext)
        assertEquals((all + overflow).map { it.id }.toSet(), (first.items + older.items).map { it.id }.toSet())
        assertEquals(all.first().id, library.get(all.first().id).id)
        assertEquals("No pending files or eviction", 4002, library.directory.listFiles()!!.size)
        assertThrows(IllegalArgumentException::class.java) { library.get("../wrong") }
    }

    @Test fun listingReportsUnreadableMetadataWithoutHidingReadableAudio() = root { root ->
        val library = LocalAudioLibrary(File(root, "library")) {}
        val good = fixture(library.directory, 1)
        File(library.directory, "${"0".repeat(64)}.properties").writeText("broken")
        val listing = library.listing()
        assertEquals(listOf(good), listing.items); assertEquals(1, listing.unreadable); assertEquals(2, listing.total)
        assertEquals(3, library.directory.listFiles()!!.size)
    }

    @Test fun spotifyObservedNumbersAreOptionalAndPropagateWithoutInventingUnknownValues() {
        val id = "1".repeat(22)
        val body = """{"items":[{"id":"$id","name":"Track","artists":[{"name":"Artist"}],"track_number":3,"disc_number":2}],"next":null}"""
        val request = SpotifyCatalogRequest(SpotifyCatalogRoute.ALBUM_TRACKS, id = id, title = "Album")
        val track = SpotifyCatalogJson.page(body, request).entries.single().track!!
        assertEquals(3, track.trackNumber); assertEquals(2, track.discNumber); assertEquals("Album", track.album)
        val unknown = SpotifyCatalogJson.page(body.replace("\"track_number\":3", "\"track_number\":0").replace("\"disc_number\":2", "\"disc_number\":null"), request).entries.single().track!!
        assertNull(unknown.trackNumber); assertNull(unknown.discNumber)
    }
}
