package com.choplab.sampler.source

import kotlin.test.*

class LibraryBrowserTest {
    private val items = (0 until 800).map { i -> AudioLibraryItem("id$i", "Song $i", "file", 100,
        "Artist ${i / 80}", "Album ${i / 20}") }

    @Test fun backRestoresTheActualGlobalAlbumPageQueryAndViewport() {
        val input = (0 until 90).map { AudioLibraryItem("id$it", "Song", "file", 100, "Artist", "Album ${it.toString().padStart(2, '0')}") }
        val browser = LibraryBrowser()
        browser.section(LibraryBrowser.Section.ALBUMS); browser.search("Album"); browser.next(input)
        val origin = browser.location
        browser.rememberViewport(origin, 12, 8)
        browser.open(browser.page(input).groups[12])
        assertEquals(LibraryBrowser.Viewport(), browser.viewport(browser.location))
        browser.search("Song")
        browser.back()
        assertEquals(origin, browser.location)
        assertEquals(40, browser.page(input).offset)
        assertEquals(LibraryBrowser.Viewport(12, 8), browser.viewport(browser.location))
        browser.search("Album 89")
        assertEquals(0, browser.page(input).offset)
        assertEquals(LibraryBrowser.Viewport(), browser.viewport(browser.location))
    }

    @Test fun albumTracksFollowObservedDiscAndTrackNumbersAndUnknownsAreStable() {
        val input = listOf(
            items[0].copy(title = "Unknown Z"), items[1].copy(title = "Disc 2", trackNumber = 1, discNumber = 2),
            items[2].copy(title = "Track 2", trackNumber = 2), items[3].copy(title = "Track 1", trackNumber = 1),
            items[4].copy(title = "Unknown A"))
        val browser = LibraryBrowser()
        browser.section(LibraryBrowser.Section.TRACKS)
        assertEquals(input, browser.page(input).tracks, "All songs keeps the existing recency order")
        browser.section(LibraryBrowser.Section.ALBUMS); browser.open(browser.page(input).groups.single())
        val expected = listOf("Track 1", "Track 2", "Disc 2", "Unknown A", "Unknown Z")
        assertEquals(expected, browser.page(input).tracks.map { it.title })
        assertEquals(expected, browser.page(input.reversed()).tracks.map { it.title })
        assertNull(input.first().trackNumber)
    }

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
