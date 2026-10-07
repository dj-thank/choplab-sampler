package com.choplab.sampler.source

import kotlin.test.*

class LibraryBrowserTest {
    private val items = (0 until 800).map { i -> AudioLibraryItem("id$i", "Song $i", "file", 100,
        "Artist ${i / 80}", "Album ${i / 20}") }

    @Test fun eightHundredTracksStartAsArtistsAndOpenOnlyTheSelectedAlbum() {
        val browser = LibraryBrowser()
        val initial = browser.page(items)
        assertTrue(initial.tracks.isEmpty())
        assertEquals(10, initial.groups.size)
        browser.open(initial.groups.first())
        assertEquals(4, browser.page(items).groups.size)
        browser.open(browser.page(items).groups.first())
        val album = browser.page(items)
        assertEquals(20, album.tracks.size)
        assertTrue(album.tracks.all { it.artist == browser.artist && it.album == browser.album })
        browser.back(); assertEquals(4, browser.page(items).groups.size)
        browser.back(); assertEquals(initial, browser.page(items))
        assertEquals(800, items.size)
    }

    @Test fun explicitSongsViewIsPagedAndSearchCoversTitleArtistAndAlbum() {
        val browser = LibraryBrowser()
        browser.section(LibraryBrowser.Section.TRACKS)
        assertEquals(40, browser.page(items).tracks.size)
        repeat(19) { browser.next(items) }
        assertEquals(760, browser.page(items).offset)
        assertFalse(browser.page(items).hasNext)
        browser.previous(); assertEquals(720, browser.page(items).offset)
        browser.search("ARTIST 3 album 12")
        assertEquals(20, browser.page(items).tracks.size)
        assertEquals(0, browser.page(items).offset)
    }

    @Test fun identicalAlbumNamesFromDifferentArtistsDoNotMergeAndMissingMetadataIsRetained() {
        val input = listOf(items[0].copy(artist = "One", album = "Same"), items[1].copy(artist = "Two", album = "Same"),
            items[2].copy(artist = "", album = ""))
        val browser = LibraryBrowser()
        browser.section(LibraryBrowser.Section.ALBUMS)
        assertEquals(3, browser.page(input).groups.size)
        browser.open(browser.page(input).groups.single { it.artist == "Two" })
        assertEquals(listOf(input[1]), browser.page(input).tracks)
        browser.section(LibraryBrowser.Section.ARTISTS)
        browser.open(browser.page(input).groups.single { it.artist == "" })
        browser.open(browser.page(input).groups.single())
        assertEquals(listOf(input[2]), browser.page(input).tracks)
    }
}
