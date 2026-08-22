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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.choplab.desktop.provider.SpotifyDesktopSession
import com.choplab.desktop.provider.SpotifyDesktopState

@Composable
internal fun SpotifyPanel(session: SpotifyDesktopSession, state: SpotifyDesktopState) {
    var clientId by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Spotify Connect", fontWeight = FontWeight.Bold)
        Text("状態: ${state.connection}")
        Text("音声のダウンロード・録音・MP3化は行いません。表示とSpotify Connectの再生制御のみです。")
        if (!state.clientIdConfigured) {
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it.trim() },
                label = { Text("Spotify Client ID（この起動中のみ）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = { session.configureClientId(clientId) }) { Text("Client IDを設定") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = session::login, enabled = state.clientIdConfigured && !state.busy) { Text("ログイン") }
            Button(onClick = session::showCurrentPlayback, enabled = state.connection == "接続済み" && !state.busy) { Text("再生中を更新") }
            Button(onClick = session::showLibrary, enabled = state.connection == "接続済み" && !state.busy) { Text("ライブラリ") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = session::pause, enabled = state.connection == "接続済み" && !state.busy) { Text("一時停止") }
            Button(onClick = session::resume, enabled = state.connection == "接続済み" && !state.busy) { Text("再開") }
            Button(onClick = session::disconnect, enabled = state.connection != "未接続") { Text("連携解除") }
        }
        Text(state.currentTrack, fontWeight = FontWeight.SemiBold)
        Text(state.message)
        Text("保存済みトラック（最大20件・メタデータのみ）")
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(state.savedTracks) { track -> Text(track, Modifier.padding(vertical = 4.dp)) }
        }
    }
}
