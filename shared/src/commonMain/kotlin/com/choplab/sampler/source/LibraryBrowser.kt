package com.choplab.sampler.source

/** Metadata-only navigation. A group opens a bounded page; it never opens or decodes audio. */
class LibraryBrowser {
    enum class Section { ARTISTS, ALBUMS, TRACKS }
    data class Group(val artist: String, val album: String? = null, val count: Int)
    data class Page(val groups: List<Group>, val tracks: List<AudioLibraryItem>, val total: Int,
                    val offset: Int, val hasPrevious: Boolean, val hasNext: Boolean)
    var section = Section.ARTISTS; private set
    var artist: String? = null; private set
    var album: String? = null; private set
    var query = ""; private set
    private var offset = 0

    fun section(value: Section) { section = value; artist = null; album = null; offset = 0 }
    fun search(value: String) { query = value.take(240); offset = 0 }
    fun open(group: Group) {
        artist = group.artist; album = group.album
        section = if (group.album == null) Section.ALBUMS else Section.TRACKS
        offset = 0
    }
    fun back() {
        if (album != null) { album = null; section = Section.ALBUMS }
        else { artist = null; section = Section.ARTISTS }
        offset = 0
    }
    fun next(items: List<AudioLibraryItem>) { if (page(items).hasNext) offset += PAGE_SIZE }
    fun previous() { offset = (offset - PAGE_SIZE).coerceAtLeast(0) }
    fun page(items: List<AudioLibraryItem>): Page {
        val words = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
        val filtered = items.filter { item ->
            (artist == null || item.artist == artist) && (album == null || item.album == album) &&
                words.all { word -> listOf(item.title, item.artist, item.album).any { value ->
                    if (word.all(Char::isDigit)) word in value.split(NUMBER_SEPARATOR) else value.contains(word, true)
                } }
        }
        val groups = when (section) {
            Section.ARTISTS -> filtered.groupBy { it.artist }.map { (name, tracks) -> Group(name, count = tracks.size) }
            Section.ALBUMS -> filtered.groupBy { it.artist to it.album }.map { (key, tracks) -> Group(key.first, key.second, tracks.size) }
            Section.TRACKS -> emptyList()
        }.sortedWith(compareBy<Group> { it.album?.lowercase() ?: it.artist.lowercase() }.thenBy { it.artist.lowercase() })
        val total = if (section == Section.TRACKS) filtered.size else groups.size
        // A refresh or deletion can reduce the current collection; retain a valid last page.
        val start = offset.coerceAtMost(((total - 1).coerceAtLeast(0) / PAGE_SIZE) * PAGE_SIZE)
        offset = start
        return Page(groups.drop(start).take(PAGE_SIZE),
            if (section == Section.TRACKS) filtered.drop(start).take(PAGE_SIZE) else emptyList(),
            total, start, start > 0, start + PAGE_SIZE < total)
    }
    companion object { const val PAGE_SIZE = 40; private val NUMBER_SEPARATOR = Regex("\\D+") }
}
