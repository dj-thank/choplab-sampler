package com.choplab.sampler.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.choplab.library.resources.*
import com.choplab.sampler.source.AudioLibraryItem
import com.choplab.sampler.source.LibraryBrowser
import org.jetbrains.compose.resources.stringResource

@Composable
fun LibraryBrowserPanel(items: List<AudioLibraryItem>, enabled: Boolean, onUse: (String) -> Unit, modifier: Modifier = Modifier) {
    val browser = remember { LibraryBrowser() }
    var revision by remember { mutableIntStateOf(0) }
    fun navigate(action: () -> Unit) { action(); revision++ }
    val page = remember(items, revision) { browser.page(items) }
    val unknownArtist = stringResource(Res.string.music_unknown_artist)
    val unknownAlbum = stringResource(Res.string.music_unknown_album)
    Surface(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LibraryBrowser.Section.entries.forEach { section ->
                val title = when (section) {
                    LibraryBrowser.Section.ARTISTS -> Res.string.music_artists
                    LibraryBrowser.Section.ALBUMS -> Res.string.music_albums
                    LibraryBrowser.Section.TRACKS -> Res.string.music_tracks
                }
                FilterChip(selected = browser.section == section && browser.artist == null,
                    onClick = { navigate { browser.section(section) } }, label = { Text(stringResource(title)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("library-${section.name.lowercase()}"))
            }
        }
        OutlinedTextField(browser.query, { value -> navigate { browser.search(value) } },
            label = { Text(stringResource(Res.string.music_search)) }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("library-search"))
        if (browser.artist != null) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton({ navigate(browser::back) }, Modifier.heightIn(min = 48.dp).testTag("library-back")) {
                Text(stringResource(Res.string.music_back))
            }
            Text(listOfNotNull(browser.artist?.ifBlank { unknownArtist }, browser.album?.ifBlank { unknownAlbum }).joinToString(" / "))
        }
        if (page.total == 0) Text(stringResource(Res.string.music_empty))
        if (browser.section != LibraryBrowser.Section.TRACKS) LazyVerticalGrid(GridCells.Adaptive(180.dp),
            Modifier.weight(1f).testTag("library-page"), verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            gridItems(page.groups, key = { it.artist + "\u0000" + it.album.orEmpty() }) { group ->
                MusicCollectionCard(group.album?.ifBlank { unknownAlbum } ?: group.artist.ifBlank { unknownArtist },
                    listOfNotNull(if (group.album != null) group.artist.ifBlank { unknownArtist } else null,
                        stringResource(Res.string.music_count, group.count)).joinToString(" · "), group.album == null,
                    { navigate { browser.open(group) } }, Modifier.testTag("library-group-${group.artist}-${group.album.orEmpty()}"))
            }
        } else LazyColumn(Modifier.weight(1f).testTag("library-page"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(page.tracks, key = { it.id }) { item ->
                OutlinedCard(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("library-track-${item.id}").clickable(enabled = enabled) { onUse(item.id) }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium)
                        Text(listOf(item.artist.ifBlank { unknownArtist }, item.album.ifBlank { unknownAlbum }).joinToString(" · "))
                    }
                }
            }
        }
        Text(stringResource(Res.string.music_page, if (page.total == 0) 0 else page.offset + 1,
            minOf(page.offset + LibraryBrowser.PAGE_SIZE, page.total), page.total))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton({ navigate(browser::previous) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), enabled = page.hasPrevious) { Text(stringResource(Res.string.music_previous)) }
            TextButton({ navigate { browser.next(items) } }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("library-next"), enabled = page.hasNext) { Text(stringResource(Res.string.music_next)) }
        }
    }
    }
}
