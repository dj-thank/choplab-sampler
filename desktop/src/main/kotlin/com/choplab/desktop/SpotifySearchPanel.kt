package com.choplab.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.choplab.desktop.provider.SpotifyDesktopState
import com.choplab.sampler.source.SourceTrack

@Composable
fun SpotifySearchPanel(state: SpotifyDesktopState, importBusy: Boolean,
    onQuery:(String)->Unit,onSearch:()->Unit,onAdd:(SourceTrack)->Boolean,
    onLibrary:()->Unit,onSync:()->Unit,onDisconnect:()->Unit) {
    var notice by remember(state.searchQuery) { mutableStateOf("") }
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(state.searchQuery,onQuery,modifier=Modifier.fillMaxWidth(),singleLine=true,
            enabled=!state.busy,label={Text("曲名・アーティストで検索")})
        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onSearch,enabled=!state.busy&&state.searchQuery.isNotBlank()){Text("検索")}
            TextButton(onLibrary){Text("ライブラリを開く")}
        }
        Text("検索した曲の対応YouTube音源をライブラリに追加します。")
        if(state.busy)LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(if(notice.isNotEmpty())notice else state.searchMessage.ifEmpty { state.message })
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            items(state.searchResults,key={it.spotifyUrl}) { track ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(track.title,style=MaterialTheme.typography.titleSmall)
                        Text(track.artist)
                        Button(onClick={notice=if(onAdd(track)) {
                            if(importBusy)"追加を予約しました。進行中の取り込みが終わると追加します" else "ライブラリへの追加を開始します"
                        } else "追加できませんでした。接続状態か取り込み待ちの件数を確認してください"}){Text("追加")}
                    }
                }
            }
        }
        Row {
            TextButton(onSync,enabled=!state.busy&&!importBusy){Text("お気に入りを同期")}
            TextButton(onDisconnect){Text("連携解除")}
        }
    }
}
