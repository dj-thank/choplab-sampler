package com.choplab.sampler.source

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Platform-neutral Spotify liked-track synchronization shared by Windows and Android.
 *
 * One metadata fetch runs per connection or explicit request, followed by one cancellable
 * library import. Closing a panel has no effect on the queue. Search additions queue behind
 * an active import and never refetch or overwrite liked tracks.
 */
open class SpotifyFavoritesAutoImport<S>(
    private val spotify: StateFlow<S>,
    private val sources: AudioSourceController,
    private val loadLibrary: () -> Unit,
    private val isConnected: (S) -> Boolean,
    private val isBusy: (S) -> Boolean,
    private val libraryRevision: (S) -> Long,
    private val libraryTracks: (S) -> List<SourceTrack>,
) : AutoCloseable {
    private enum class Stage { WAIT_CONNECTION, FETCHING, WAIT_IMPORT, IMPORTING, DONE, PAUSED }

    private var stage = Stage.WAIT_CONNECTION
    private var expectedRevision = 0L
    private var tracks = emptyList<SourceTrack>()
    private var closed = false
    private val selectedTracks = linkedMapOf<String, SourceTrack>()
    private val requests = MutableStateFlow(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            combine(spotify, sources.state, requests) { session, source, _ -> session to source }
                .collect { advance() }
        }
    }

    @Synchronized
    private fun advance() {
        if (closed) return
        val session = spotify.value
        val source = sources.state.value
        if (!isConnected(session)) {
            if (stage == Stage.IMPORTING) sources.cancelSpotify()
            tracks = emptyList()
            selectedTracks.clear()
            stage = Stage.WAIT_CONNECTION
            return
        }
        if (stage in listOf(Stage.DONE, Stage.PAUSED, Stage.IMPORTING) && !source.busy &&
            source.pendingUseId == null && selectedTracks.isNotEmpty()
        ) {
            tracks = selectedTracks.values.toList()
            selectedTracks.clear()
            stage = Stage.WAIT_IMPORT
        }
        when (stage) {
            Stage.WAIT_CONNECTION -> if (!isBusy(session)) {
                expectedRevision = libraryRevision(session) + 1
                stage = Stage.FETCHING
                loadLibrary()
            }
            Stage.FETCHING -> if (!isBusy(session)) {
                if (libraryRevision(session) < expectedRevision) {
                    stage = Stage.DONE
                } else {
                    tracks = libraryTracks(session).toList()
                    stage = Stage.WAIT_IMPORT
                    advance()
                }
            }
            Stage.WAIT_IMPORT -> if (!source.busy && source.pendingUseId == null && sources.syncSpotifyFavorites(tracks)) {
                stage = Stage.IMPORTING
            }
            Stage.IMPORTING -> if (!source.busy || source.spotifySync == null) stage = Stage.DONE
            Stage.DONE, Stage.PAUSED -> Unit
        }
    }

    /** Explicit re-synchronization after a completed, failed or paused run. */
    @Synchronized
    fun syncAgain() {
        if (closed || isBusy(spotify.value) || sources.state.value.busy) return
        stage = Stage.WAIT_CONNECTION
        requests.value++
    }

    /** Queues one searched track (100 max); it starts once the current import finishes. */
    @Synchronized
    fun addTrack(track: SourceTrack): Boolean {
        if (closed || !isConnected(spotify.value) || selectedTracks.size >= 100) return false
        selectedTracks[track.spotifyUrl] = track
        requests.value++
        return true
    }

    /** Pauses automation and cancels only the import this coordinator owns. */
    @Synchronized
    fun cancel() {
        val owned = stage == Stage.IMPORTING
        stage = Stage.PAUSED
        selectedTracks.clear()
        if (owned) sources.cancelSpotify()
    }

    @Synchronized
    override fun close() {
        closed = true
        scope.cancel()
        cancel()
    }
}
