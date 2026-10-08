package com.choplab.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
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
    val page = remember(state, revision) { browser.page }
    val request = browser.request
    val accountRevision = browser.accountRevision
    val navigationRevision = browser.navigationRevision
    val viewport = browser.viewport(request)
    val listState = key(accountRevision, request, navigationRevision) { rememberLazyListState(viewport.index, viewport.offset) }
    val gridState = key(accountRevision, request, navigationRevision) { rememberLazyGridState(viewport.index, viewport.offset) }
    val isGrid = page?.entries?.firstOrNull()?.kind in listOf(SpotifyCatalogKind.ARTIST, SpotifyCatalogKind.ALBUM)
    fun rememberPosition() = browser.rememberViewport(request, accountRevision,
        if (isGrid) gridState.firstVisibleItemIndex else listState.firstVisibleItemIndex,
        if (isGrid) gridState.firstVisibleItemScrollOffset else listState.firstVisibleItemScrollOffset, navigationRevision)
    DisposableEffect(accountRevision, request, navigationRevision, isGrid, listState, gridState) { onDispose { rememberPosition() } }
    fun navigate(action: () -> Unit) { rememberPosition(); action(); notice = ""; revision++ }
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
                enabled = browser.canEditQuery,
                label = { Text(stringResource(when (browser.kind) {
                    SpotifyCatalogKind.ARTIST -> Res.string.music_search_artists
                    SpotifyCatalogKind.ALBUM -> Res.string.music_search_albums
                    SpotifyCatalogKind.TRACK -> Res.string.music_search_tracks
                })) }, singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { navigate(browser::search) }),
                modifier = Modifier.weight(1f).testTag("spotify-query"))
            Button({ navigate(browser::search) }, enabled = available && browser.query.isNotBlank(),
                modifier = Modifier.heightIn(min = 48.dp).testTag("spotify-search")) { Text(stringResource(Res.string.music_search_button)) }
        }
        if (browser.kind == SpotifyCatalogKind.TRACK) Text(stringResource(Res.string.music_search_tracks_hint), style = MaterialTheme.typography.bodySmall)
        if (browser.canBack || page?.request?.title?.isNotBlank() == true) Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton({ navigate(browser::back) }, enabled = browser.canBack, modifier = Modifier.testTag("spotify-back")) { Text(stringResource(Res.string.music_back)) }
            Text(page?.request?.title.orEmpty(), style = MaterialTheme.typography.titleLarge)
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        val problem = state.problem ?: if (browser.retryWaitSeconds > 0) SpotifyProblem(SpotifyProblemKind.API_FAILED, 429) else null
        if (problem != null) {
            Text(spotifyProblemText(problem, browser.retryWaitSeconds), Modifier.testTag("spotify-problem"))
            TextButton({ navigate(browser::retry) }, enabled = available && browser.request != null) { Text(stringResource(Res.string.music_retry)) }
        }
        if (notice.isNotBlank()) Text(notice)
        if (page == null && !state.busy) Text(stringResource(if (browser.kind == SpotifyCatalogKind.ARTIST)
            Res.string.music_artist_search else Res.string.music_start))
        if ((page?.unreadable ?: 0) > 0) Text(stringResource(if (page?.entries?.isEmpty() == true)
            Res.string.music_catalog_unreadable else Res.string.music_catalog_partial, page!!.unreadable), Modifier.testTag("spotify-unreadable"))
        else if (page?.entries?.isEmpty() == true) Text(stringResource(Res.string.music_empty))
        if (page?.entries?.firstOrNull()?.kind in listOf(SpotifyCatalogKind.ARTIST, SpotifyCatalogKind.ALBUM))
            LazyVerticalGrid(GridCells.Adaptive(180.dp), Modifier.weight(1f).testTag("spotify-page"),
                state = gridState, verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                gridItems(page?.entries.orEmpty(), key = { it.kind.name + it.id }) { entry ->
                    com.choplab.sampler.ui.MusicCollectionCard(entry.title, entry.artist,
                        entry.kind == SpotifyCatalogKind.ARTIST, { navigate { browser.open(entry) } },
                        Modifier.testTag("spotify-open-${entry.id}"), available)
                }
            }
        else LazyColumn(Modifier.weight(1f).testTag("spotify-page"), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                            entry.track?.let { track -> Text(formatTrackDuration(track.durationSeconds)?.let {
                                stringResource(Res.string.music_duration, it)
                            } ?: stringResource(Res.string.music_duration_unknown), style = MaterialTheme.typography.bodySmall) }
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
            TextButton({ navigate(browser::previous) }, enabled = browser.canPrevious,
                modifier = Modifier.testTag("spotify-previous")) { Text(stringResource(Res.string.music_previous)) }
            page?.let { Text("${it.request.offset + if (it.entries.isEmpty()) 0 else 1}–${it.request.offset + it.entries.size}" + (it.total?.let { total -> " / $total" } ?: "")) }
            TextButton({ navigate(browser::next) }, enabled = available && page?.hasMore == true,
                modifier = Modifier.testTag("spotify-next")) { Text(stringResource(Res.string.music_next)) }
        }
        onDisconnect?.let { TextButton(it) { Text(stringResource(com.choplab.ui.resources.Res.string.ce_spotify_disconnect)) } }
    }
    }
}

internal fun formatTrackDuration(seconds: Double): String? {
    if (!seconds.isFinite() || seconds <= 0 || seconds >= Long.MAX_VALUE.toDouble()) return null
    val value = seconds.toLong()
    return if (value < 3600) "${value / 60}:${(value % 60).toString().padStart(2, '0')}"
        else "${value / 3600}:${(value / 60 % 60).toString().padStart(2, '0')}:${(value % 60).toString().padStart(2, '0')}"
}

@Composable
private fun spotifyProblemText(problem: SpotifyProblem, wait: Long): String = when {
    problem.statusCode == 429 && wait > 0 -> stringResource(Res.string.music_problem_wait, wait)
    problem.statusCode == 429 -> stringResource(Res.string.music_problem_rate)
    problem.statusCode == 403 -> stringResource(Res.string.music_problem_forbidden)
    problem.kind == SpotifyProblemKind.CANCELLED -> stringResource(Res.string.music_problem_cancelled)
    problem.kind == SpotifyProblemKind.AUTH_EXPIRED || problem.statusCode == 401 -> stringResource(Res.string.music_problem_auth)
    problem.kind == SpotifyProblemKind.NETWORK -> stringResource(Res.string.music_problem_network)
    problem.kind == SpotifyProblemKind.INVALID_RESPONSE -> stringResource(Res.string.music_problem_response)
    else -> stringResource(Res.string.music_problem_retry)
}
