package com.choplab.sampler.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.choplab.sampler.source.*

@Composable
fun AudioSourceHub(
    state:AudioSourceState, canUseAudio:Boolean,
    onSection:(SourceSection)->Unit,onQuery:(String)->Unit,onSearch:()->Unit,onDownload:(YoutubeSource)->Unit,
    onPickFiles:()->Unit,onUse:(String)->Unit,onCancel:()->Unit,onClose:()->Unit,
    spotifyContent:@Composable ()->Unit,
) {
    Dialog(onDismissRequest=onClose,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        AudioSourceHubContent(state,canUseAudio,onSection,onQuery,onSearch,onDownload,onPickFiles,onUse,onCancel,onClose,spotifyContent)
    }
}

@Composable
fun AudioSourceHubContent(
    state:AudioSourceState, canUseAudio:Boolean,
    onSection:(SourceSection)->Unit,onQuery:(String)->Unit,onSearch:()->Unit,onDownload:(YoutubeSource)->Unit,
    onPickFiles:()->Unit,onUse:(String)->Unit,onCancel:()->Unit,onClose:()->Unit,
    spotifyContent:@Composable ()->Unit,
) {
        Surface(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.94f),shape=MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxSize().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("音源を追加",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                    TextButton(onClick=onClose){Text("閉じる")}
                }
                Button(onClick=onPickFiles,enabled=!state.busy,modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                    Text("ファイル・音源セットを追加")
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    SourceSection.entries.forEach { section ->
                        OutlinedButton(onClick={onSection(section)},modifier=Modifier.weight(1f),enabled=!state.busy,colors=ButtonDefaults.outlinedButtonColors(containerColor=if(state.section==section)MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
                            Text(when(section){SourceSection.LIBRARY->"ライブラリ";SourceSection.YOUTUBE->"YouTube";SourceSection.SPOTIFY->"Spotify"})
                        }
                    }
                }
                if(state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    TextButton(onClick=onCancel){Text("取り込みを中止")}
                }
                if(state.message.isNotBlank()) Text(state.message,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
                when(state.section) {
                    SourceSection.LIBRARY -> {
                        if(state.library.isEmpty())Text("取り込んだ音がここに残ります。毎回ファイルを探す必要はありません。")
                        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            items(state.library,key={it.id}) { item ->
                                OutlinedCard(Modifier.fillMaxWidth().clickable(enabled=canUseAudio&&!state.busy){onUse(item.id)}
                                    .semantics { contentDescription="ライブラリ音源 ${item.title}を使う" }) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(item.title,fontWeight=FontWeight.Bold)
                                        Text(if(item.origin.startsWith("https://www.youtube.com/"))"YouTubeから取り込み" else item.origin)
                                        Text("タップしてチョップへ")
                                    }
                                }
                            }
                        }
                    }
                    SourceSection.YOUTUBE -> {
                        OutlinedTextField(value=state.query,onValueChange=onQuery,enabled=!state.busy,
                            label={Text("YouTubeのURL、または曲名")},modifier=Modifier.fillMaxWidth())
                        Button(onClick=onSearch,enabled=!state.busy&&state.query.isNotBlank(),modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)) {
                            Text(if(state.query.startsWith("https://"))"URLから取り込む" else "曲名で探す")
                        }
                        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            items(state.candidates,key={it.id}) { source ->
                                OutlinedCard(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(source.title,fontWeight=FontWeight.Bold)
                                        Text(source.author)
                                        Button(onClick={onDownload(source)},enabled=!state.busy){Text("この音源を取り込む")}
                                    }
                                }
                            }
                        }
                    }
                    SourceSection.SPOTIFY -> spotifyContent()
                }
            }
        }
}

@Composable
fun SpotifySourcePicker(state:SpotifyImportState, importBusy:Boolean, redirectUri:String,
    onLogin:(String)->Unit,onDisconnect:()->Unit,onMore:()->Unit,onPick:(SourceTrack)->Unit,onOpen:(String)->Unit) {
    var clientId by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text("Spotifyのお気に入り",style=MaterialTheme.typography.titleMedium)
        Text("曲をタップして追加。音声は対応するYouTube動画から取り込みます。")
        Text(state.message)
        if(!state.connected) {
            if(!state.configured) {
            OutlinedTextField(value=clientId,onValueChange={clientId=it.trim()},enabled=!state.busy,label={Text("Spotify Client ID")},singleLine=true,modifier=Modifier.fillMaxWidth())
            Text("初回はDeveloper Dashboardに $redirectUri を登録してください。Client Secretは不要です。")
            TextButton(onClick={onOpen("https://developer.spotify.com/dashboard")}){Text("初回のアプリ登録を開く")}
            }
            Button(onClick={onLogin(clientId);clientId=""},enabled=!state.busy&&(state.configured||clientId.isNotBlank())){Text("Spotifyにログイン")}
        } else {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Button(onClick=onMore,enabled=!state.busy&&!importBusy&&(state.tracks.isEmpty()||state.hasMore)){Text(if(state.tracks.isEmpty())"お気に入りを表示" else "さらに読み込む")}
                TextButton(onClick=onDisconnect,enabled=!state.busy){Text("連携解除")}
            }
        }
        if(state.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick=onDisconnect){Text("認証・読み込みを中止")}
        }
        LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(state.tracks,key={it.spotifyUrl}) { track ->
                OutlinedCard(Modifier.fillMaxWidth().clickable(enabled=!state.busy&&!importBusy){onPick(track)}
                    .semantics { contentDescription="Spotifyのお気に入り ${track.artist} ${track.title}を取り込む" }) {
                    Column(Modifier.padding(12.dp)) {
                        Text(track.title,fontWeight=FontWeight.Bold);Text(track.artist)
                        Text("タップで取り込む")
                        TextButton(onClick={onOpen(track.spotifyUrl)}){Text("Spotifyで開く")}
                    }
                }
            }
        }
    }
}
