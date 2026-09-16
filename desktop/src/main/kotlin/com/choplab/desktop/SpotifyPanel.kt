package com.choplab.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.choplab.desktop.provider.SpotifyDesktopState
import com.choplab.desktop.provider.SpotifyConnectionPhase
import com.choplab.sampler.source.SourceSection

/** Import destinations first; remote Spotify playback is an optional secondary action. */
@Composable
internal fun SpotifyPanel(
    state:SpotifyDesktopState, libraryCount:Int, onOpenSource:(SourceSection)->Unit,
    onRefreshPlayback:()->Unit,onPause:()->Unit,onResume:()->Unit,onDisconnect:()->Unit,
    onPickFiles:()->Unit={onOpenSource(SourceSection.LIBRARY)},
) {
    var playbackExpanded by remember { mutableStateOf(false) }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("使いたい音を、ChopLabへ",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.Bold)
            Text("音源を選ぶと内部ライブラリに保存され、そのままチョップへ進めます。")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("Spotify",style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.Bold)
                        Text(when(state.phase) {
                            SpotifyConnectionPhase.CONNECTED->"接続済み"
                            SpotifyConnectionPhase.AUTHENTICATING->"ログイン中"
                            else->"ログインして使う"
                        },modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
                    }
                    Text("お気に入りの曲を選んで追加。音声は対応するYouTube動画から取り込みます。")
                    Button(onClick={onOpenSource(SourceSection.SPOTIFY)},modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)
                        .semantics { contentDescription="Spotifyのお気に入りから音源を追加" }) {
                        Text(if(state.phase==SpotifyConnectionPhase.CONNECTED)"お気に入りから音源を追加" else "Spotifyに接続して音源を追加")
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick={onOpenSource(SourceSection.YOUTUBE)},modifier=Modifier.weight(1f).heightIn(min=56.dp)
                    .semantics { contentDescription="YouTubeから音源を追加" }) { Text("YouTubeから追加") }
                OutlinedButton(onClick=onPickFiles,modifier=Modifier.weight(1f).heightIn(min=56.dp)
                    .semantics { contentDescription="PCのファイルから音源を追加" }) { Text("PCのファイルから追加") }
            }
            TextButton(onClick={onOpenSource(SourceSection.LIBRARY)},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp)
                .semantics { contentDescription="内部ライブラリを開く" }) { Text("取り込んだ音源を見る（${libraryCount}件）") }
            HorizontalDivider()
            TextButton(onClick={playbackExpanded=!playbackExpanded}) {
                Text(if(playbackExpanded)"Spotifyの再生操作を閉じる" else "Spotifyの再生操作")
            }
            if(playbackExpanded) {
                Text("Spotifyアプリ側の再生を操作します。")
                if(state.phase==SpotifyConnectionPhase.CONNECTED) {
                    Text(state.currentTrack)
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick=onRefreshPlayback,enabled=state.canUsePlaybackControls){Text("再生情報を更新")}
                        OutlinedButton(onClick=onPause,enabled=state.canUsePlaybackControls){Text("一時停止")}
                        OutlinedButton(onClick=onResume,enabled=state.canUsePlaybackControls){Text("再開")}
                    }
                    Text(state.message,modifier=Modifier.semantics { liveRegion=LiveRegionMode.Polite })
                    TextButton(onClick=onDisconnect,enabled=state.canDisconnect){Text("Spotifyの連携を解除")}
                } else {
                    Text("上の「Spotifyに接続して音源を追加」からログインしてください。")
                }
            }
        }
    }
}
