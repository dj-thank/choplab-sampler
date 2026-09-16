package com.choplab.sampler.midi

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
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
import androidx.compose.material3.Button
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
import kotlin.math.roundToInt

@Composable
fun Ddj200Controls(viewModel: SamplerViewModel) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var client by remember(viewModel) { mutableStateOf<AndroidDdj200?>(null) }
    var visible by remember { mutableStateOf(false) }
    var wideSearch by remember { mutableStateOf(false) }
    var confirmSplit by remember { mutableStateOf(false) }
    val connection = client
    val mix by AndroidDdjPerformance.state.collectAsStateWithLifecycle()
    val route by AndroidDdjPerformance.route.collectAsStateWithLifecycle()
    val state = if (connection == null) DdjConnectionState() else {
        val observed by connection.state.collectAsStateWithLifecycle()
        observed
    }
    fun foreground() = visible && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val active = client
        if (foreground()) {
            if (active?.permissionsGranted() == true && active.bluetoothEnabled()) active.scanBluetooth(wideSearch)
            else active?.message("Bluetoothが有効になっていません。オンにして接続をやり直してください")
        }
    }
    fun scanOrEnable(active: AndroidDdj200) {
        if (active.bluetoothEnabled()) active.scanBluetooth(wideSearch)
        else runCatching { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
            .onFailure { active.message("Bluetooth設定からBluetoothをオンにしてください") }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val active = client
        if (foreground()) {
            if (active?.permissionsGranted() == true) scanOrEnable(active)
            else active?.message("権限が許可されませんでした。「アプリの権限設定」で付近のデバイス（Android 10/11は位置情報）を許可してください")
        }
    }
    fun openSettings(intent: Intent) {
        runCatching { context.startActivity(intent) }
            .onFailure { client?.message("設定を開けませんでした。Androidの設定アプリから変更してください") }
    }
    DisposableEffect(connection, owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                confirmSplit = false
                connection?.disconnect("バックグラウンド移行で切断しました。戻ったら再接続してください")
            }
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
            if (client == null) {
                client = AndroidDdj200(context,
                    Ddj200DeckTarget(viewModel) { viewModel.uiState.value }) {
                    val current = viewModel.uiState.value
                    var mask = 0
                    val start = current.selectedBank.coerceIn(0, 3) * 32
                    for (pad in 0 until 32) {
                        if (current.pads.getOrNull(start + pad)?.isAssigned == true) mask = mask or (1 shl pad)
                    }
                    DdjLedSnapshot(mask, current.sourcePlaying, current.transportPlaying, current.recordArmed)
                }
            }
            visible = true
        }) { Text("DDJ-200") }
        Text(if (state.connected) "接続済み${if (state.receivedPackets > 0) " · 入力あり" else " · PADを押して確認"}"
            else "Bluetoothで操作",
            modifier = Modifier.weight(1f).padding(top = 14.dp),
            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (visible && connection != null) {
        fun dismiss() { connection.stopScan(); confirmSplit = false; visible = false }
        fun requestScan(wide: Boolean) {
            wideSearch = wide
            if (connection.permissionsGranted()) scanOrEnable(connection)
            else permissionLauncher.launch(connection.bluetoothPermissions())
        }
        AlertDialog(
            onDismissRequest = ::dismiss,
            title = { Text("DDJ-200 / Bluetooth接続") },
            confirmButton = { TextButton(onClick = ::dismiss) { Text("閉じる") } },
            text = {
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(state.message)
                    if (!state.connected) {
                        Text("① DDJ-200をUSB電源で起動\n② 他のDJアプリとの接続を解除\n③ 下のボタンで検索し、DDJ-200を選択")
                        Text("Androidの通常のペアリング一覧ではなく、このアプリ内からBLE MIDI接続します。")
                        Button(onClick = { requestScan(false) },
                            enabled = !state.opening && !state.scanning,
                            modifier = Modifier.fillMaxWidth()) { Text("Bluetoothで接続する") }
                        if (!state.scanning && !state.opening) {
                            TextButton(onClick = { requestScan(true) }) { Text("見つからない場合の追加検索（10秒）") }
                        }
                    }
                    if (state.scanning) TextButton(onClick = connection::stopScan) { Text("検索を停止") }
                    if (!state.connected) state.choices.forEachIndexed { index, choice ->
                        OutlinedButton(onClick = { connection.connect(choice.id) }, enabled = !state.opening,
                            modifier = Modifier.fillMaxWidth()) { Text("${index + 1}. ${choice.label} に接続") }
                    }
                    if (state.connected || state.opening) {
                        TextButton(onClick = { confirmSplit = false; connection.disconnect() }) { Text("切断 / 接続をキャンセル") }
                        TextButton(onClick = viewModel::stopAllSounds) { Text("ALL STOP") }
                    }
                    if (state.connected) {
                        Text("MIDI受信 ${state.receivedPackets} パケット · ${state.ledStatus}",
                            style = MaterialTheme.typography.bodySmall)
                        Text("ミキサー", style = MaterialTheme.typography.titleSmall)
                        Text("左PAD群＋素材 ⇄ 右PAD群\nクロスフェーダー ${(mix.crossfader * 100).roundToInt()}% · " +
                            "CH左 ${(mix.leftFader * 100).roundToInt()}% / 右 ${(mix.rightFader * 100).roundToInt()}%")
                        Text("EQ LOW / MID / HI（つまみ位置、中央50%）\n" +
                            "左 ${(mix.leftLow * 100).roundToInt()} / ${(mix.leftMid * 100).roundToInt()} / ${(mix.leftHigh * 100).roundToInt()}%\n" +
                            "右 ${(mix.rightLow * 100).roundToInt()} / ${(mix.rightMid * 100).roundToInt()} / ${(mix.rightHigh * 100).roundToInt()}%")
                        Text("ヘッドホンCUE: 左${if (mix.cueLeft) " ON" else " OFF"} / " +
                            "右${if (mix.cueRight) " ON" else " OFF"} / MASTER${if (mix.cueMaster) " ON" else " OFF"}")
                        if (mix.splitCue) {
                            Text("分離出力中: L=スピーカー / R=ヘッドホン（それぞれモノラル）")
                            OutlinedButton(onClick = { connection.setSplitCue(false) }) { Text("通常ステレオに戻す（再生停止）") }
                        } else {
                            OutlinedButton(onClick = { confirmSplit = true }, enabled = route.splitCapable) {
                                Text("ヘッドホン分離を設定")
                            }
                            if (!route.splitCapable) Text("有線ステレオ出力を選択して一度音を再生すると分離設定が有効になります。")
                            if (confirmSplit) {
                                Text("DJ用スプリットケーブルを接続し、L側をスピーカー、R側をヘッドホンに接続してください。通常の二股分配ケーブルでは分離できません。音量を下げて左右を確認してください。")
                                Button(onClick = { confirmSplit = false; connection.setSplitCue(true) }, enabled = route.splitCapable) {
                                    Text("配線を確認したので分離する（再生停止）")
                                }
                                TextButton(onClick = { confirmSplit = false }) { Text("キャンセル") }
                            }
                        }
                    }
                    Text("操作割り当て", style = MaterialTheme.typography.titleSmall)
                    Text("PAD: 左1–8 / 右9–16。SHIFT併用: 17–32。現在BANKに対応。\n" +
                        "SYNC: 前/次BANK。SHIFT + SYNC: Undo/Redo。\n" +
                        "PLAY: 左は素材 / 右はBEAT。CUEまたはSHIFT + PLAY: ALL STOP。SHIFT + CUE: 録音待機。\n" +
                        "JOG上面: 左は素材 / 右は選択PADスクラッチ。TEMPO: 左は素材ピッチ / 右はBPM。\n" +
                        "CHフェーダー/EQ: 左右PAD群。クロスフェーダー: 左右群のミックス。COLOR FX: 最後に押したPADのTONE。現在値を通過してから追従します。")
                    Text("Bluetoothは操作信号用です。音声はAndroid側から出ます。ミキサー/EQ/分離はライブ再生専用で保存・書き出しには反映されません。LEDは割当/再生状態を送信しますが、実機点灯と操作感は未確認です。Windows/iOS MIDI接続は未対応です。",
                        style = MaterialTheme.typography.bodySmall)
                    Text("接続の設定・その他", style = MaterialTheme.typography.titleSmall)
                    TextButton(onClick = { openSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) { Text("Bluetooth設定") }
                    TextButton(onClick = { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}"))) }) { Text("アプリの権限設定") }
                    if (Build.VERSION.SDK_INT < 31) {
                        TextButton(onClick = { openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("位置情報設定（Android 10/11）") }
                    }
                    OutlinedButton(onClick = connection::refreshUsb, enabled = !state.opening && !state.connected,
                        modifier = Modifier.fillMaxWidth()) { Text("USBで接続する / 再検索") }
                }
            },
        )
    }
}
