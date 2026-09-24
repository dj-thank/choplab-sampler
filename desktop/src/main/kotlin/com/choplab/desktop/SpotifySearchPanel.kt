package com.choplab.desktop

import androidx.compose.runtime.Composable
import com.choplab.desktop.provider.SpotifyDesktopState
import com.choplab.sampler.source.SourceTrack

/** Windows adapter for the shared connected Spotify tab. */
@Composable
fun SpotifySearchPanel(state: SpotifyDesktopState, importBusy: Boolean,
    onQuery:(String)->Unit,onSearch:()->Unit,onAdd:(SourceTrack)->Boolean,
    onLibrary:()->Unit,onSync:()->Unit,onDisconnect:()->Unit) {
    com.choplab.sampler.ui.SpotifySearchPanel(
        query = state.searchQuery,
        results = state.searchResults,
        message = state.searchMessage.ifEmpty { state.message },
        busy = state.busy,
        importBusy = importBusy,
        onQuery = onQuery,
        onSearch = onSearch,
        onAdd = onAdd,
        onLibrary = onLibrary,
        onSync = onSync,
        onDisconnect = onDisconnect,
    )
}
