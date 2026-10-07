package com.choplab.desktop.provider

import com.choplab.sampler.source.*

/** Navigation owns metadata only. The legacy host supplies the explicitly selected download port. */
internal class SpotifyCatalogBrowser(private val session: SpotifyDesktopSession) : AutoCloseable {
    var kind = SpotifyCatalogKind.ARTIST; private set
    var request: SpotifyCatalogRequest? = null; private set
    private val history = ArrayDeque<SpotifyCatalogRequest?>()
    private val cache = linkedMapOf<SpotifyCatalogRequest, SpotifyCatalogPage>()
    private val selection = linkedSetOf<String>()
    private var closed = false
    private var account = session.state.value.connectionRevision
    private fun ensureAccount() {
        val current = session.state.value.connectionRevision
        if (current != account) {
            account = current; cache.clear(); history.clear(); selection.clear(); request = null
        }
    }
    val canFetch get(): Boolean {
        ensureAccount()
        return !closed && session.connected && !session.state.value.busy && session.retryWaitSeconds() == 0L
    }
    val retryWaitSeconds get() = session.retryWaitSeconds()
    val canEditQuery get() = !closed && session.state.value.phase != SpotifyConnectionPhase.AUTHENTICATING
    val canPrevious get(): Boolean {
        ensureAccount()
        val previous = previousRequest() ?: return false
        return !closed && session.connected && (previous in cache || canFetch)
    }
    val canBack get(): Boolean { ensureAccount(); return history.isNotEmpty() }
    val query get() = session.state.value.searchQuery
    val page: SpotifyCatalogPage? get() {
        ensureAccount()
        if (closed || !session.connected) { cache.clear(); history.clear(); selection.clear(); return null }
        val selected = request ?: return null
        val fetched = session.state.value.catalogPage?.takeIf { it.request == selected }
        if (fetched != null) {
            cache[selected] = fetched
            while (cache.size > 32) cache.remove(cache.keys.first())
        }
        return fetched ?: cache[selected]
    }
    val selectedTracks get() = page?.entries.orEmpty().mapNotNull { it.track }.filter { it.spotifyUrl in selection }

    fun home() { if (closed) return; session.cancelPendingOperations(); request = null; history.clear(); selection.clear() }
    fun root(value: SpotifyCatalogKind) {
        if (!canFetch) return
        home(); kind = value
        when (value) {
            SpotifyCatalogKind.TRACK -> load(SpotifyCatalogRequest(SpotifyCatalogRoute.FAVORITES))
            SpotifyCatalogKind.ALBUM -> load(SpotifyCatalogRequest(SpotifyCatalogRoute.SAVED_ALBUMS))
            SpotifyCatalogKind.ARTIST -> Unit // Only an explicit search requests artists.
        }
    }
    fun setQuery(value: String) {
        if (!canEditQuery) return
        if (query != value) {
            session.cancelPendingOperations(); session.setSearchQuery(value)
            selection.clear()
            if (request?.route == SpotifyCatalogRoute.SEARCH) { request = null; history.clear() }
        }
    }
    fun search() { if (canFetch && query.isNotBlank()) { history.clear(); load(SpotifyCatalogRequest(SpotifyCatalogRoute.SEARCH, kind, query = query.trim())) } }
    fun open(entry: SpotifyCatalogEntry) {
        if (!canFetch || entry !in page?.entries.orEmpty()) return
        val next = when (entry.kind) {
            SpotifyCatalogKind.ARTIST -> SpotifyCatalogRequest(SpotifyCatalogRoute.ARTIST_ALBUMS, id = entry.id, title = entry.title)
            SpotifyCatalogKind.ALBUM -> SpotifyCatalogRequest(SpotifyCatalogRoute.ALBUM_TRACKS, id = entry.id, title = entry.title)
            SpotifyCatalogKind.TRACK -> return
        }
        history.addLast(request)
        while (history.size > 20) history.removeFirst()
        load(next)
    }
    fun back() {
        if (closed || history.isEmpty()) return
        session.cancelPendingOperations(); selection.clear()
        request = history.removeLast()
        session.clearCatalogProblem()
        request?.takeIf { it !in cache }?.let(::load)
    }
    fun next() { if (canFetch) page?.takeIf { it.hasMore }?.request?.let { load(it.copy(offset = it.offset + it.pageSize)) } }
    fun previous() {
        if (!canPrevious) return
        val previous = previousRequest() ?: return
        if (previous in cache) {
            session.cancelPendingOperations(); selection.clear(); request = previous
            session.clearCatalogProblem()
        } else load(previous)
    }
    private fun previousRequest() = request?.takeIf { it.offset > 0 }?.let { it.copy(offset = (it.offset - it.pageSize).coerceAtLeast(0)) }
    fun toggle(track: SourceTrack) {
        if (!canFetch || page?.entries.orEmpty().none { it.track == track }) return
        if (!selection.add(track.spotifyUrl)) selection.remove(track.spotifyUrl)
    }
    fun selectPage() { if (canFetch) page?.entries.orEmpty().mapNotNull { it.track }.forEach { selection.add(it.spotifyUrl) } }
    fun clearSelection() { selection.clear() }
    fun addSelected(add: (List<SourceTrack>) -> Boolean): Boolean {
        if (!canFetch || selectedTracks.isEmpty()) return false
        return add(selectedTracks).also { if (it) selection.clear() }
    }
    fun retry() { if (canFetch) request?.let(::load) }
    private fun load(value: SpotifyCatalogRequest) { selection.clear(); request = value; cache.remove(value); session.browse(value) }
    override fun close() { if (!closed) { closed = true; cache.clear(); history.clear(); selection.clear(); session.cancelPendingOperations() } }
}
