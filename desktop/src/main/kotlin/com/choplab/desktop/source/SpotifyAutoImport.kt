package com.choplab.desktop.source

import com.choplab.desktop.provider.SpotifyConnectionPhase
import com.choplab.desktop.provider.SpotifyDesktopState
import com.choplab.sampler.source.AudioSourceController
import com.choplab.sampler.source.SourceTrack
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One sync per connection/request; closing a panel has no effect on the queue. */
class SpotifyAutoImport(
    private val spotify: StateFlow<SpotifyDesktopState>,
    private val sources: AudioSourceController,
    private val loadLibrary: () -> Unit,
) : AutoCloseable {
    private enum class Stage { WAIT_CONNECTION, FETCHING, WAIT_IMPORT, IMPORTING, DONE, PAUSED }
    private var stage = Stage.WAIT_CONNECTION
    private var expectedRevision = 0L
    private var tracks = emptyList<SourceTrack>()
    private var closed = false
    private val requests = MutableStateFlow(0L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    init {
        scope.launch {
            combine(spotify,sources.state,requests) { session,source,_ -> session to source }
                .collect { advance() }
        }
    }

    @Synchronized private fun advance() {
        if(closed)return
        val session=spotify.value
        val source=sources.state.value
        if(session.phase != SpotifyConnectionPhase.CONNECTED) {
            if(stage == Stage.IMPORTING)sources.cancelSpotify()
            tracks = emptyList()
            stage = Stage.WAIT_CONNECTION
            return
        }
        when(stage) {
            Stage.WAIT_CONNECTION -> if(!session.busy) {
                expectedRevision = session.importLibraryRevision + 1
                stage = Stage.FETCHING
                loadLibrary()
            }
            Stage.FETCHING -> if(!session.busy) {
                if(session.importLibraryRevision < expectedRevision) stage = Stage.DONE
                else { tracks=session.sourceTracks.toList();stage = Stage.WAIT_IMPORT; advance() }
            }
            Stage.WAIT_IMPORT -> if(!source.busy && source.pendingUseId==null && sources.syncSpotifyFavorites(tracks)) stage = Stage.IMPORTING
            Stage.IMPORTING -> if(!source.busy || source.spotifySync==null) stage = Stage.DONE
            Stage.DONE, Stage.PAUSED -> Unit
        }
    }

    @Synchronized fun syncAgain() {
        if(closed || spotify.value.busy || sources.state.value.busy)return
        stage = Stage.WAIT_CONNECTION
        requests.value++
    }

    @Synchronized fun cancel() {
        val owned = stage == Stage.IMPORTING
        stage = Stage.PAUSED
        if(owned)sources.cancelSpotify()
    }

    @Synchronized override fun close() {
        closed = true
        scope.cancel()
        cancel()
    }
}
