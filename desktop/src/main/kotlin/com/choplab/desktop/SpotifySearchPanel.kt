package com.choplab.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.choplab.desktop.provider.*
import com.choplab.library.resources.*
import com.choplab.sampler.source.*
import org.jetbrains.compose.resources.stringResource
import com.choplab.ui.resources.ce_spotify_disconnect
import kotlinx.coroutines.delay

@Composable
internal fun SpotifySearchPanel(state: SpotifyDesktopState, session: SpotifyDesktopSession,
    onAdd: (List<SourceTrack>) -> Boolean, onLibrary: () -> Unit, onDisconnect: () -> Unit) {
    val browser = remember(session) { SpotifyCatalogBrowser(session) }
    DisposableEffect(browser) { onDispose { browser.close() } }
    SpotifyCatalogPanel(state, browser, onAdd, onLibrary, onDisconnect)
}

/** The same browse surface is used for legacy downloads and NEXT metadata-only browsing. */
@Composable
internal fun SpotifyCatalogPanel(state: SpotifyDesktopState, browser: SpotifyCatalogBrowser,
    onAdd: ((List<SourceTrack>) -> Boolean)? = null, onLibrary: (() -> Unit)? = null,
    onDisconnect: (() -> Unit)? = null, onOpenSpotify: ((SourceTrack) -> Unit)? = null) {
    var revision by remember { mutableIntStateOf(0) }
    var clockTick by remember { mutableIntStateOf(0) }
    var notice by remember { mutableStateOf("") }
    // Re-enable explicit actions when Retry-After expires, without issuing an automatic request.
    LaunchedEffect(state.problem, revision) {
        while (browser.retryWaitSeconds > 0) {
            delay(1_000)
            clockTick++
        }
    }
    val available = remember(state, revision, clockTick) { browser.canFetch }
    fun navigate(action: () -> Unit) { action(); notice = ""; revision++ }
    val page = remember(state, revision) { browser.page }
    val selected = browser.selectedTracks
    val queued = stringResource(Res.string.music_queued)
    val rejected = stringResource(Res.string.music_queue_full)
    Surface(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SpotifyCatalogKind.entries.forEach { kind ->
                FilterChip(browser.kind == kind, { navigate { browser.root(kind) } }, enabled = available,
                    label = { Text(stringResource(when (kind) {
                        SpotifyCatalogKind.TRACK -> Res.string.music_favorites
                        SpotifyCatalogKind.ARTIST -> Res.string.music_artists
                        SpotifyCatalogKind.ALBUM -> Res.string.music_albums
                    })) }, modifier = Modifier.heightIn(min = 48.dp).testTag("spotify-${kind.name.lowercase()}"))
            }
            onLibrary?.let { TextButton(it, Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.music_downloaded)) } }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(browser.query, { navigate { browser.setQuery(it) } },
                label = { Text(stringResource(Res.string.music_search)) }, singleLine = true,
                modifier = Modifier.weight(1f).testTag("spotify-query"))
            Button({ navigate(browser::search) }, enabled = available && browser.query.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("spotify-search")) { Text(stringResource(Res.string.music_search_button)) }
        }
        if (browser.canBack || page?.request?.title?.isNotBlank() == true) Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ navigate(browser::back) }, enabled = browser.canBack, modifier = Modifier.testTag("spotify-back")) { Text(stringResource(Res.string.music_back)) }
            Text(page?.request?.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.problem != null) {
            Text(state.message)
            TextButton({ navigate(browser::retry) }, enabled = available && browser.request != null) { Text(stringResource(Res.string.music_open)) }
        }
        if (notice.isNotBlank()) Text(notice)
        if (page == null && !state.busy) Text(stringResource(if (browser.kind == SpotifyCatalogKind.ARTIST)
            Res.string.music_artist_search else Res.string.music_start))
        if (page?.entries?.isEmpty() == true) Text(stringResource(Res.string.music_empty))
        if (page?.entries?.firstOrNull()?.kind in listOf(SpotifyCatalogKind.ARTIST, SpotifyCatalogKind.ALBUM))
            LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.weight(1f).testTag("spotify-page"),
                verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                gridItems(page?.entries.orEmpty(), key = { it.kind.name + it.id }) { entry ->
                    com.choplab.sampler.ui.MusicCollectionCard(entry.title, entry.artist,
                        entry.kind == SpotifyCatalogKind.ARTIST, { navigate { browser.open(entry) } },
                        Modifier.testTag("spotify-open-${entry.id}"), available)
                }
            }
        else LazyColumn(Modifier.weight(1f).testTag("spotify-page"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(page?.entries.orEmpty(), key = { it.kind.name + it.id }) { entry ->
                OutlinedCard(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                    .clickable(enabled = available && entry.kind != SpotifyCatalogKind.TRACK) { navigate { browser.open(entry) } }) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        entry.track?.takeIf { onAdd != null }?.let { track ->
                            Checkbox(track in selected, { navigate { browser.toggle(track) } }, enabled = available,
                                modifier = Modifier.testTag("spotify-select-${entry.id}"))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(entry.title, style = MaterialTheme.typography.titleMedium)
                            if (entry.artist.isNotBlank() && entry.artist != entry.title) Text(entry.artist)
                            entry.track?.album?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        if (entry.track == null) Text(stringResource(Res.string.music_open))
                        else entry.track?.let { track -> onOpenSpotify?.let { open -> TextButton({ open(track) }) { Text("Spotify") } } }
                    }
                }
            }
        }
        if (onAdd != null && page?.entries?.any { it.track != null } == true) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ navigate(browser::selectPage) }, enabled = available) { Text(stringResource(Res.string.music_select_page)) }
                TextButton({ navigate(browser::clearSelection) }, enabled = selected.isNotEmpty()) { Text(stringResource(Res.string.music_clear_selection)) }
            }
            Button({ notice = if (browser.addSelected(onAdd)) queued else rejected; revision++ },
                enabled = available && selected.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("spotify-download-selection")) {
                Text(stringResource(Res.string.music_selected_add, selected.size))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton({ navigate(browser::previous) }, enabled = available && (page?.request?.offset ?: 0) > 0) { Text(stringResource(Res.string.music_previous)) }
            page?.let { Text("${it.request.offset + if (it.entries.isEmpty()) 0 else 1}–${it.request.offset + it.entries.size}" + (it.total?.let { total -> " / $total" } ?: "")) }
            TextButton({ navigate(browser::next) }, enabled = available && page?.hasMore == true,
                modifier = Modifier.testTag("spotify-next")) { Text(stringResource(Res.string.music_next)) }
        }
        onDisconnect?.let { TextButton(it) { Text(stringResource(com.choplab.ui.resources.Res.string.ce_spotify_disconnect)) } }
    }
    }
}
