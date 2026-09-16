package com.choplab.sampler.midi

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class DdjDeviceChoice(val id: String, val label: String)
internal data class DdjConnectionState(
    val choices: List<DdjDeviceChoice> = emptyList(),
    val connected: Boolean = false,
    val opening: Boolean = false,
    val scanning: Boolean = false,
    val message: String = "DDJ-200を接続して検索してください",
    val receivedPackets: Long = 0,
    val ledStatus: String = "LED: 未接続",
)

/** One explicitly selected device. No service lookup, scan or permission request at app startup. */
internal class AndroidDdj200(
    context: Context,
    private val target: Ddj200Target,
    private val ledSnapshot: () -> DdjLedSnapshot,
) : AutoCloseable {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val epoch = AtomicInteger()
    private val faultPosted = AtomicBoolean()
    private data class Packet(val epoch: Int, val bytes: ByteArray)
    private val packets = Channel<Packet>(32)
    private val session = Ddj200Session(target, channelFadersControlPads = false)
    private val mixer = DdjMixerMidi { AndroidDdjPerformance.publish(this, it) }
    private val mutableState = MutableStateFlow(DdjConnectionState())
    val state = mutableState.asStateFlow()
    private var manager: MidiManager? = null
    private var device: MidiDevice? = null
    private var output: MidiOutputPort? = null
    private var input: MidiInputPort? = null
    private var ledWorker: Job? = null
    private var feedbackTick: Job? = null
    private var ledFrames: Channel<DdjLedSnapshot>? = null
    private var bluetoothConnection = false
    private var radioRegistered = false
    private val radioReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (closed || intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            val radio = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (radio == BluetoothAdapter.STATE_OFF || radio == BluetoothAdapter.STATE_TURNING_OFF) {
                bluetooth.clear(); choices()
                if (bluetoothConnection || state.value.scanning) {
                    disconnect("Bluetoothがオフになりました。再生を停止しました。オンにして再検索してください")
                }
            }
        }
    }
    private var openTimeout: Job? = null
    private var scanTimeout: Job? = null
    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val usb = linkedMapOf<String, MidiDeviceInfo>()
    private val bluetooth = linkedMapOf<String, BluetoothDevice>()
    private var closed = false
    private var received = 0L
    private var lastCountUpdate = 0L

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) { refreshUsb() }
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (device?.info?.id == info.id) disconnect("DDJ-200が取り外されました。再生を停止しました")
            refreshUsb()
        }
    }

    init {
        scope.launch {
            for (packet in packets) {
                if (closed || packet.epoch != epoch.get()) continue
                try {
                    mixer.stream.accept(packet.bytes)
                    session.stream.accept(packet.bytes)
                    received++
                    val now = SystemClock.uptimeMillis()
                    if (received == 1L || now - lastCountUpdate >= 100L) {
                        lastCountUpdate = now
                        mutableState.value = mutableState.value.copy(receivedPackets = received)
                    }
                } catch (_: RuntimeException) {
                    disconnect("MIDI入力を処理できませんでした。安全のため再生を停止しました")
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun service(): MidiManager? {
        if (closed) return null
        manager?.let { return it }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI)) {
            message("この端末はAndroid MIDIに対応していません")
            return null
        }
        return try {
            context.getSystemService(MidiManager::class.java)?.also {
                it.registerDeviceCallback(deviceCallback, main)
                manager = it
            } ?: run { message("MIDIサービスを利用できません"); null }
        } catch (_: RuntimeException) {
            message("MIDIサービスを開始できません"); null
        }
    }

    @Suppress("DEPRECATION")
    fun refreshUsb() {
        val midi = service() ?: return
        try {
            usb.clear()
            midi.devices.filter {
                it.type == MidiDeviceInfo.TYPE_USB && it.outputPortCount > 0 &&
                    (isDdj200Name(it.properties.getString(MidiDeviceInfo.PROPERTY_NAME)) ||
                        isDdj200Name(it.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)))
            }.take(8).forEach { usb["usb:${it.id}"] = it }
            choices()
            if (usb.isEmpty() && bluetooth.isEmpty() && !state.value.connected && !state.value.opening) {
                message("USB機器なし。データ対応USBケーブルとUSBホスト接続を確認してください")
            }
        } catch (_: RuntimeException) { message("USB MIDI機器を取得できませんでした") }
    }

    fun bluetoothPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    fun permissionsGranted() = bluetoothPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission") // Checked here, not just in the permission launcher.
    fun scanBluetooth(wideSearch: Boolean = false) {
        if (closed || state.value.opening || state.value.connected) return
        if (!permissionsGranted()) { message("Bluetooth検索には付近のデバイス権限（Android 10/11は位置情報権限）が必要です"); return }
        if (service() == null) return
        if (Build.VERSION.SDK_INT < 31 &&
            context.getSystemService(LocationManager::class.java)?.isLocationEnabled != true) {
            message("Android 10/11ではBluetooth検索のため位置情報設定もオンにしてください。位置は収集しません")
            return
        }
        stopScan()
        try {
            if (!radioRegistered) {
                // Only after BLE permission admission; USB discovery never requires Bluetooth permission.
                ContextCompat.registerReceiver(context, radioReceiver,
                    IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), ContextCompat.RECEIVER_EXPORTED)
                radioRegistered = true
            }
            val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            if (adapter == null || !adapter.isEnabled) { message("Bluetoothをオンにしてから検索してください"); return }
            val activeScanner = adapter.bluetoothLeScanner
            if (activeScanner == null) { message("Bluetooth LEを利用できません"); return }
            bluetooth.clear(); choices()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    main.post {
                        if (closed || scanCallback !== this) return@post
                        try {
                            if (!isDdj200Name(result.scanRecord?.deviceName) && !isDdj200Name(result.device.name)) return@post
                            val key = "ble:${result.device.address}"
                            if (key !in bluetooth && bluetooth.size < 8) {
                                bluetooth[key] = result.device
                                choices()
                            }
                        } catch (_: SecurityException) { stopScan(); message("Bluetooth権限が取り消されました") }
                    }
                }
                override fun onBatchScanResults(results: MutableList<ScanResult>) {
                    results.take(64).forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
                }
                override fun onScanFailed(errorCode: Int) {
                    main.post {
                        if (scanCallback !== this) return@post
                        stopScan(); message("Bluetooth検索に失敗しました ($errorCode)。少し待って再検索してください")
                    }
                }
            }
            scanner = activeScanner; scanCallback = callback
            mutableState.value = state.value.copy(scanning = true, message = "DDJ-200を検索中（10秒）。見つかった機器を選択してください")
            val filter = ScanFilter.Builder().setServiceUuid(
                ParcelUuid(UUID.fromString("03b80e5a-ede8-4b33-a751-6ce34ec4c700")),
            ).build()
            // Filters are ORed. Name filters cover controllers that omit the MIDI service
            // in their advertisement. Wide search is a separate explicit 10-second action.
            val filters = if (wideSearch) emptyList() else listOf(filter,
                ScanFilter.Builder().setDeviceName("DDJ-200").build(),
                ScanFilter.Builder().setDeviceName("DDJ200").build())
            activeScanner.startScan(filters, ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(), callback)
            scanTimeout = scope.launch {
                delay(10_000)
                stopScan()
                message(if (bluetooth.isEmpty()) "DDJ-200なし。電源と他のDJアプリの切断を確認し、見つからない場合は追加検索を試してください" else "機器を選択して接続してください")
            }
        } catch (_: RuntimeException) { stopScan(); message("Bluetooth検索を開始できませんでした") }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanTimeout?.cancel(); scanTimeout = null
        val callback = scanCallback
        val activeScanner = scanner
        scanCallback = null; scanner = null // reject queued results before asking Android to stop.
        if (callback != null && activeScanner != null) runCatching { activeScanner.stopScan(callback) }
        mutableState.value = state.value.copy(scanning = false)
    }

    @SuppressLint("MissingPermission")
    fun connect(id: String) {
        if (closed || state.value.opening) return
        val midi = service() ?: return
        val usbInfo = usb[id]
        val bleDevice = bluetooth[id]
        if (usbInfo == null && bleDevice == null) { message("機器が見つかりません。再検索してください"); return }
        if (bleDevice != null && !permissionsGranted()) { message("Bluetooth権限を許可して再検索してください"); return }
        disconnect()
        val ticket = epoch.incrementAndGet()
        bluetoothConnection = bleDevice != null
        mutableState.value = state.value.copy(opening = true, message = "DDJ-200に接続中…", receivedPackets = 0)
        val listener = MidiManager.OnDeviceOpenedListener { opened ->
            if (closed || ticket != epoch.get()) { runCatching { opened?.close() }; return@OnDeviceOpenedListener }
            openTimeout?.cancel(); openTimeout = null
            if (opened == null) { disconnect("接続できませんでした。他のDJアプリを閉じて再試行してください"); return@OnDeviceOpenedListener }
            try {
                val portNumber = opened.info.ports.firstOrNull {
                    it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT
                }?.portNumber ?: error("No MIDI output port")
                val port = opened.openOutputPort(portNumber) ?: error("MIDI port unavailable")
                device = opened; output = port
                port.connect(object : MidiReceiver() {
                    override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        if (ticket != epoch.get() || count == 0) return
                        if (count < 0 || count > 4096 || offset < 0 || offset > data.size - count ||
                            packets.trySend(Packet(ticket, data.copyOfRange(offset, offset + count))).isFailure) {
                            if (faultPosted.compareAndSet(false, true)) main.post {
                                if (ticket == epoch.get()) disconnect("MIDI入力が上限を超えたため再生を停止しました。再接続してください")
                            }
                        }
                    }
                })
                received = 0L; lastCountUpdate = 0L
                AndroidDdjPerformance.acquire(this) {
                    target.stopAll()
                    mixer.setSplitCue(false)
                    message("音声出力先が変わったため分離出力を解除し、再生を停止しました")
                }
                mixer.reset(enabled = true)
                mutableState.value = state.value.copy(connected = true, opening = false,
                    message = if (bleDevice == null) "DDJ-200 · USB接続" else "DDJ-200 · Bluetooth接続")
                startFeedback(opened, ticket)
            } catch (_: Exception) {
                runCatching { opened.close() }
                disconnect("MIDIポートを開けませんでした。他のアプリの接続を解除してください")
            }
        }
        openTimeout = scope.launch { delay(12_000); if (ticket == epoch.get()) disconnect("接続がタイムアウトしました。再検索してください") }
        try {
            if (usbInfo != null) midi.openDevice(usbInfo, listener, main)
            else midi.openBluetoothDevice(requireNotNull(bleDevice), listener, main)
        } catch (_: RuntimeException) { disconnect("接続を開始できませんでした。権限と機器の電源を確認してください") }
    }

    fun disconnect(reason: String = "DDJ-200を切断しました") {
        epoch.incrementAndGet() // invalidate queued packets and asynchronous opens FIRST.
        stopScan()
        openTimeout?.cancel(); openTimeout = null
        val wasConnected = device != null
        val oldPort = output; val oldDevice = device; val oldInput = input
        output = null; device = null; input = null; bluetoothConnection = false
        feedbackTick?.cancel(); feedbackTick = null
        ledFrames?.close(); ledFrames = null
        ledWorker?.cancel(); ledWorker = null
        runCatching { oldPort?.close() }; runCatching { oldInput?.close() }; runCatching { oldDevice?.close() }
        while (packets.tryReceive().isSuccess) { /* bounded queue, discard stale stream fragments */ }
        faultPosted.set(false)
        session.reset(stopPlayback = wasConnected)
        mixer.reset()
        AndroidDdjPerformance.release(this)
        received = 0L
        mutableState.value = state.value.copy(connected = false, opening = false, message = reason,
            receivedPackets = 0, ledStatus = "LED: 未接続")
    }

    @SuppressLint("MissingPermission")
    fun bluetoothEnabled(): Boolean = try {
        context.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
    } catch (_: SecurityException) { false }

    fun setSplitCue(enabled: Boolean) {
        if (!state.value.connected) return
        if (enabled && !AndroidDdjPerformance.route.value.splitCapable) {
            message("分離出力には実際に選択された有線ステレオ出力が必要です。音を一度再生し、出力先を確認してください")
            return
        }
        target.stopAll() // route matrix switches only after a deliberate stop request
        mixer.setSplitCue(enabled)
        message(if (enabled) "分離出力: L=スピーカー / R=ヘッドホン。再生を再開してください"
            else "通常ステレオ出力に戻しました。再生を再開してください")
    }

    private fun startFeedback(opened: MidiDevice, ticket: Int) {
        val port = runCatching {
            opened.info.ports.firstOrNull { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }
                ?.let { opened.openInputPort(it.portNumber) }
        }.getOrNull()
        input = port
        if (port == null) {
            mutableState.value = state.value.copy(ledStatus = "LED: 送信ポートなし（操作入力は利用可能）")
        } else {
            val frames = Channel<DdjLedSnapshot>(Channel.CONFLATED)
            ledFrames = frames
            mutableState.value = state.value.copy(ledStatus = "LED: 同期開始中")
            ledWorker = scope.launch(Dispatchers.IO) {
                val feedback = DdjLedFeedback()
                var announced = false
                try {
                    for (snapshot in frames) {
                        if (ticket != epoch.get()) break
                        val bytes = feedback.messages(snapshot)
                        // Platform MIDI bounds are small; complete 3-byte messages in each send.
                        for (offset in bytes.indices step 96) {
                            if (ticket != epoch.get()) break
                            port.send(bytes, offset, minOf(96, bytes.size - offset))
                        }
                        if (!announced && bytes.isNotEmpty()) {
                            announced = true
                            main.post { if (ticket == epoch.get()) {
                                mutableState.value = state.value.copy(ledStatus = "LED: 状態送信中（本体で点灯を確認）")
                            } }
                        }
                    }
                } catch (_: Exception) {
                    main.post { if (ticket == epoch.get()) {
                        frames.close(); ledFrames = null
                        mutableState.value = state.value.copy(ledStatus = "LED: 送信失敗（操作入力は継続）")
                    } }
                }
            }
        }
        feedbackTick = scope.launch {
            while (!closed && ticket == epoch.get()) {
                if (bluetoothConnection && !permissionsGranted()) {
                    disconnect("Bluetooth権限が取り消されました。再生を停止しました")
                    break
                }
                val mix = mixer.settings
                val snapshot = ledSnapshot().copy(cueLeft = mix.cueLeft,
                    cueRight = mix.cueRight, cueMaster = mix.cueMaster)
                ledFrames?.trySend(snapshot)
                mutableState.value = state.value.copy(receivedPackets = received)
                delay(50) // full snapshots conflated, deltas computed only on the output worker
            }
        }
    }

    fun message(text: String) { mutableState.value = state.value.copy(message = text) }
    private fun choices() {
        mutableState.value = state.value.copy(choices =
            usb.keys.map { DdjDeviceChoice(it, "DDJ-200 · USB") } +
                bluetooth.keys.map { DdjDeviceChoice(it, "DDJ-200 · Bluetooth") })
    }

    @Suppress("DEPRECATION")
    override fun close() {
        if (closed) return
        closed = true
        disconnect()
        runCatching { manager?.unregisterDeviceCallback(deviceCallback) }
        manager = null
        if (radioRegistered) runCatching { context.unregisterReceiver(radioReceiver) }
        radioRegistered = false
        packets.close(); scope.cancel()
        // Do NOT remove Handler callbacks: late open callbacks must close their returned device.
    }
}
