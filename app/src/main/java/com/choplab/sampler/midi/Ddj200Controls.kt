package com.choplab.sampler.midi

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choplab.sampler.SamplerViewModel

@Composable
fun Ddj200Controls(viewModel: SamplerViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var client by remember(viewModel) { mutableStateOf<AndroidDdj200?>(null) }
    var visible by remember { mutableStateOf(false) }
    val connection = client
    val state = if (connection == null) DdjConnectionState() else {
        val observed by connection.state.collectAsStateWithLifecycle()
        observed
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val active = client
        if (visible && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            if (active?.permissionsGranted() == true) active.scanBluetooth()
            else active?.message("権限が許可されませんでした。USB接続はBluetooth権限なしで利用できます")
        }
    }
    DisposableEffect(connection, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) connection?.disconnect("バックグラウンド移行で切断しました。戻ったら再接続してください")
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            connection?.close()
        }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            val active = client ?: AndroidDdj200(context, Ddj200DeckTarget(viewModel) { viewModel.uiState.value }).also { client = it }
            visible = true
            active.refreshUsb()
        }) { Text("DDJ-200") }
        Text(if (state.connected) "接続済み${if (state.receivedPackets > 0) " · 入力あり" else ""}" else "外部コントローラー",
            modifier = Modifier.weight(1f).padding(top = 14.dp),
            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (visible && connection != null) {
        fun dismiss() { connection.stopScan(); visible = false }
        AlertDialog(
            onDismissRequest = ::dismiss,
            title = { Text("DDJ-200 / おとひろい") },
            confirmButton = { TextButton(onClick = ::dismiss) { Text("閉じる") } },
            text = {
                Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.message)
                    Text("音声はスマートフォン側から出ます。USB検索にBluetooth権限は不要です。")
                    OutlinedButton(onClick = connection::refreshUsb, enabled = !state.opening,
                        modifier = Modifier.fillMaxWidth()) { Text("USBを再検索") }
                    OutlinedButton(onClick = {
                        if (connection.permissionsGranted()) connection.scanBluetooth()
                        else permissionLauncher.launch(connection.bluetoothPermissions())
                    }, enabled = !state.connected && !state.opening && !state.scanning,
                        modifier = Modifier.fillMaxWidth()) { Text("Bluetoothを検索") }
                    if (state.scanning) TextButton(onClick = connection::stopScan) { Text("検索を停止") }
                    state.choices.forEachIndexed { index, choice ->
                        OutlinedButton(onClick = { connection.connect(choice.id) }, enabled = !state.opening,
                            modifier = Modifier.fillMaxWidth()) { Text("${index + 1}. ${choice.label} に接続") }
                    }
                    if (state.connected || state.opening) {
                        TextButton(onClick = { connection.disconnect() }) { Text("切断 / 接続をキャンセル") }
                        TextButton(onClick = viewModel::stopAllSounds) { Text("ALL STOP") }
                    }
                    Text("操作割り当て", style = MaterialTheme.typography.titleSmall)
                    Text("PAD: 左1–8 / 右9–16。SHIFT併用: 17–32。現在のBANKに対応。\n" +
                        "SYNC: 左は前のBANK / 右は次のBANK。SHIFT + SYNC: Undo / Redo。\n" +
                        "PLAY: 左は素材 / 右はBEAT。CUEまたはSHIFT + PLAY: ALL STOP。SHIFT + CUE: ステップ録音待機。\n" +
                        "JOG上面: 左は素材 / 右は選択PADのスクラッチ。TEMPO: 左は素材ピッチ / 右はBPM。\n" +
                        "CHフェーダー / COLOR FX: 各側で最後に押したPADの音量 / TONE。現在値を通過してから反映します。")
                    Text("試験対応: 実機での操作感は未確認。EQ・クロスフェーダー・ヘッドホン分離・LED同期は未対応です。切断時は安全のため再生を停止します。",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
        )
    }
}
