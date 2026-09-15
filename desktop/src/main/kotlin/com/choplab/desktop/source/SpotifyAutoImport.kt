package com.choplab.desktop.source

import com.choplab.desktop.provider.SpotifyConnectionPhase
import com.choplab.desktop.provider.SpotifyDesktopState
import com.choplab.sampler.source.AudioSourceController
import com.choplab.sampler.source.SpotifyFavoritesAutoImport
import kotlinx.coroutines.flow.StateFlow

/** Windows binding of the shared liked-track synchronization; closing a panel has no effect on the queue. */
class SpotifyAutoImport(
    spotify: StateFlow<SpotifyDesktopState>,
    sources: AudioSourceController,
    loadLibrary: () -> Unit,
) : SpotifyFavoritesAutoImport<SpotifyDesktopState>(
    spotify = spotify,
    sources = sources,
    loadLibrary = loadLibrary,
    isConnected = { it.phase == SpotifyConnectionPhase.CONNECTED },
    isBusy = { it.busy },
    libraryRevision = { it.importLibraryRevision },
    libraryTracks = { it.sourceTracks },
)
