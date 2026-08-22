package com.choplab.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Button
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.choplab.desktop.provider.SpotifyDesktopSession
import com.choplab.desktop.provider.SpotifyDesktopState
import com.choplab.desktop.provider.SpotifyConnectionPhase

@Composable
internal fun SpotifyPanel(session: SpotifyDesktopSession, state: SpotifyDesktopState) {
    var clientId by remember { mutableStateOf("") }
    var editingClientId by remember { mutableStateOf(!state.clientIdConfigured) }

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Spotify Connect", fontWeight = FontWeight.Bold)
        Text(
            text = "状態: ${state.connection}",
            modifier = Modifier.semantics {
                contentDescription = "Spotify接続状態: ${state.connection}"
                liveRegion = LiveRegionMode.Polite
            },
        )
        Text("表示とSpotify Connectの再生制御のみです。Spotify Contentのダウンロード・録音・MP3化は行いません。")

        if (editingClientId) {
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it.trim() },
                label = { Text("Spotify Client ID（この起動中のみ）") },
                placeholder = { Text("Client Secretは入力しません") },
                singleLine = true,
                enabled = state.canConfigureClientId,
                modifier = Modifier.fillMaxWidth().semantics {
                    contentDescription = "Spotifyの公開Client ID。Client Secretは入力しないでください。この起動中だけ使用します"
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (session.configureClientId(clientId)) {
                            clientId = ""
                            editingClientId = false
                        }
                    },
                    enabled = state.canConfigureClientId,
                ) { Text("Client IDを設定") }
                if (state.clientIdConfigured) {
                    Button(onClick = { editingClientId = false }, enabled = state.canConfigureClientId) { Text("変更をやめる") }
                }
            }
        } else {
            Text("Client ID: ${state.clientIdSource ?: "設定済み"}（ディスクには保存しません）")
            Button(onClick = { editingClientId = true }, enabled = state.canConfigureClientId) { Text("Client IDを変更") }
        }

        Text("最初の設定", fontWeight = FontWeight.SemiBold)
        Text("1. Spotify Developer DashboardでWeb APIアプリを作成")
        Text("2. Redirect URIに http://127.0.0.1/callback をポートなしで登録")
        Text("3. Development Modeではアプリ所有者のPremiumと許可ユーザーを確認")

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = session::login, enabled = state.canLogin) { Text(if (state.phase == SpotifyConnectionPhase.ERROR) "もう一度ログイン" else "Spotifyにログイン") }
            if (state.canCancelLogin) {
                Button(onClick = session::cancelLogin) { Text("認証をキャンセル") }
            }
            Button(onClick = session::disconnect, enabled = state.canDisconnect) { Text("連携解除") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = session::showCurrentPlayback, enabled = state.canUsePlaybackControls) { Text("再生中を更新") }
            Button(onClick = session::showLibrary, enabled = state.canUsePlaybackControls) { Text("ライブラリ") }
            Button(onClick = session::pause, enabled = state.canUsePlaybackControls) { Text("一時停止") }
            Button(onClick = session::resume, enabled = state.canUsePlaybackControls) { Text("再開") }
        }
        Text(
            state.currentTrack,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics {
                contentDescription = "Spotify現在再生: ${state.currentTrack}"
                liveRegion = LiveRegionMode.Polite
            },
        )
        Text(
            state.message,
            modifier = Modifier.semantics {
                contentDescription = "Spotify案内: ${state.message}"
                liveRegion = LiveRegionMode.Polite
            },
        )
        Text("保存済みトラック（最大20件・タイトル／アーティストのメタデータのみ）")
        Text(
            state.librarySummary,
            modifier = Modifier.semantics {
                contentDescription = "Spotifyライブラリ: ${state.librarySummary}"
                liveRegion = LiveRegionMode.Polite
            },
        )
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).semantics {
                contentDescription = "Spotify保存済みトラック一覧。${state.savedTracks.size}件"
            },
        ) {
            items(state.savedTracks) { track -> Text(track, Modifier.padding(vertical = 4.dp)) }
        }
    }
}
