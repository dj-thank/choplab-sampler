package com.choplab.sampler.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.choplab.sampler.source.SourceTrack

/**
 * Connected Spotify tab shared by Windows and Android: search metadata, add a result
 * (its matching YouTube source is imported), re-sync liked tracks, or disconnect.
 */
@Composable
fun SpotifySearchPanel(
    query: String,
    results: List<SourceTrack>,
    message: String,
    busy: Boolean,
    importBusy: Boolean,
    onQuery: (String) -> Unit,
    onSearch: () -> Unit,
    onAdd: (SourceTrack) -> Boolean,
    onLibrary: () -> Unit,
    onSync: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var notice by remember(query) { mutableStateOf("") }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(query, onQuery, modifier = Modifier.fillMaxWidth(), singleLine = true,
            enabled = !busy, label = { Text("曲名・アーティストで検索") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onSearch, enabled = !busy && query.isNotBlank()) { Text("検索") }
            TextButton(onLibrary) { Text("ライブラリを開く") }
        }
        Text("検索した曲の対応YouTube音源をライブラリに追加します。")
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(if (notice.isNotEmpty()) notice else message)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(results, key = { it.spotifyUrl }) { track ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(track.title, style = MaterialTheme.typography.titleSmall)
                        Text(track.artist)
                        Button(onClick = {
                            notice = if (onAdd(track)) {
                                if (importBusy) "追加を予約しました。進行中の取り込みが終わると追加します" else "ライブラリへの追加を開始します"
                            } else "追加できませんでした。接続状態か取り込み待ちの件数を確認してください"
                        }) { Text("追加") }
                    }
                }
            }
        }
        Row {
            TextButton(onSync, enabled = !busy && !importBusy) { Text("お気に入りを同期") }
            TextButton(onDisconnect) { Text("連携解除") }
        }
    }
}
