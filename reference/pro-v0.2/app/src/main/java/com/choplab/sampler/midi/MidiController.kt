package com.choplab.sampler.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiDeviceStatus
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.HandlerThread
import com.choplab.sampler.model.MidiDeviceModel
import java.io.Closeable
import kotlin.math.roundToInt

/** USB/Bluetooth/virtual MIDI input with running-status and MIDI clock support. */
class MidiController(
    context: Context,
    private val listener: Listener,
) : Closeable {
    interface Listener {
        fun onDevicesChanged(devices: List<MidiDeviceModel>)
        fun onConnectionChanged(deviceId: Int?, connected: Boolean)
        fun onNoteOn(channel: Int, note: Int, velocity: Int)
        fun onNoteOff(channel: Int, note: Int, velocity: Int)
        fun onControlChange(channel: Int, controller: Int, value: Int)
        fun onClockTempo(bpm: Float)
        fun onTransportStart()
        fun onTransportContinue()
        fun onTransportStop()
        fun onError(message: String)
    }

    private val manager = context.applicationContext.getSystemService(MidiManager::class.java)
    private val thread = HandlerThread("ChopLab-MIDI").apply { start() }
    private val handler = Handler(thread.looper)
    private val parser = MidiParser(listener)
    private var device: MidiDevice? = null
    private var port: MidiOutputPort? = null
    private var selectedDeviceId: Int? = null

    private val receiver = object : MidiReceiver() {
        override fun onSend(data: ByteArray, offset: Int, count: Int, timestamp: Long) {
            parser.consume(data, offset, count, timestamp.takeIf { it > 0L } ?: System.nanoTime())
        }
    }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(info: MidiDeviceInfo) = publishDevices()
        override fun onDeviceRemoved(info: MidiDeviceInfo) {
            if (info.id == selectedDeviceId) disconnect()
            publishDevices()
        }
        override fun onDeviceStatusChanged(status: MidiDeviceStatus) = publishDevices()
    }

    init {
        if (manager == null) {
            listener.onError("この端末はAndroid MIDIサービスを利用できません")
        } else {
            manager.registerDeviceCallback(callback, handler)
            publishDevices()
        }
    }

    fun refresh() = publishDevices()

    @Synchronized
    fun connect(deviceId: Int) {
        val midiManager = manager ?: return
        val info = midiManager.devices.firstOrNull { it.id == deviceId }
        if (info == null) {
            listener.onError("MIDIデバイスが見つかりません")
            return
        }
        disconnect()
        selectedDeviceId = deviceId
        midiManager.openDevice(info, { opened ->
            if (opened == null) {
                selectedDeviceId = null
                listener.onConnectionChanged(null, false)
                listener.onError("MIDIデバイスを開けません")
                return@openDevice
            }
            val outputIndex = (0 until info.outputPortCount).firstOrNull()
            if (outputIndex == null) {
                opened.close()
                selectedDeviceId = null
                listener.onConnectionChanged(null, false)
                listener.onError("選択したMIDIデバイスに入力可能な出力ポートがありません")
                return@openDevice
            }
            val openedPort = opened.openOutputPort(outputIndex)
            if (openedPort == null) {
                opened.close()
                selectedDeviceId = null
                listener.onConnectionChanged(null, false)
                listener.onError("MIDI出力ポートを開けません")
                return@openDevice
            }
            device = opened
            port = openedPort
            openedPort.connect(receiver)
            listener.onConnectionChanged(deviceId, true)
        }, handler)
    }

    @Synchronized
    fun disconnect() {
        runCatching { port?.disconnect(receiver) }
        runCatching { port?.close() }
        runCatching { device?.close() }
        port = null
        device = null
        selectedDeviceId = null
        parser.reset()
        listener.onConnectionChanged(null, false)
    }

    override fun close() {
        disconnect()
        manager?.unregisterDeviceCallback(callback)
        thread.quitSafely()
    }

    private fun publishDevices() {
        val devices = manager?.devices.orEmpty()
            .filter { it.outputPortCount > 0 }
            .map { it.toModel() }
            .sortedWith(compareBy<MidiDeviceModel> { it.name.lowercase() }.thenBy { it.id })
        listener.onDevicesChanged(devices)
    }

    private fun MidiDeviceInfo.toModel(): MidiDeviceModel {
        val name = properties.getString(MidiDeviceInfo.PROPERTY_NAME)
            ?: properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT)
            ?: "MIDI Device $id"
        return MidiDeviceModel(
            id = id,
            name = name,
            manufacturer = properties.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER).orEmpty(),
            product = properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT).orEmpty(),
            outputPortCount = outputPortCount,
        )
    }

    private class MidiParser(private val listener: Listener) {
        private var runningStatus = -1
        private var data1 = -1
        private var inSysEx = false
        private var clockTickCount = 0
        private var lastQuarterTimestamp = 0L
        private var smoothedBpm = 0f

        fun reset() {
            runningStatus = -1
            data1 = -1
            inSysEx = false
            clockTickCount = 0
            lastQuarterTimestamp = 0L
            smoothedBpm = 0f
        }

        fun consume(bytes: ByteArray, offset: Int, count: Int, timestamp: Long) {
            val end = (offset + count).coerceAtMost(bytes.size)
            for (index in offset.coerceAtLeast(0) until end) {
                val value = bytes[index].toInt() and 0xFF
                if (value >= 0xF8) {
                    realtime(value, timestamp)
                    continue
                }
                if (inSysEx) {
                    if (value == 0xF7) inSysEx = false
                    continue
                }
                if (value and 0x80 != 0) {
                    when {
                        value == 0xF0 -> { inSysEx = true; runningStatus = -1; data1 = -1 }
                        value >= 0xF0 -> { runningStatus = -1; data1 = -1 }
                        else -> { runningStatus = value; data1 = -1 }
                    }
                    continue
                }
                val status = runningStatus
                if (status < 0) continue
                val command = status and 0xF0
                val channel = status and 0x0F
                val oneByte = command == 0xC0 || command == 0xD0
                if (oneByte) {
                    data1 = -1
                    continue
                }
                if (data1 < 0) {
                    data1 = value
                } else {
                    dispatch(command, channel, data1, value)
                    data1 = -1
                }
            }
        }

        private fun dispatch(command: Int, channel: Int, first: Int, second: Int) {
            when (command) {
                0x80 -> listener.onNoteOff(channel, first, second)
                0x90 -> if (second == 0) listener.onNoteOff(channel, first, 0)
                    else listener.onNoteOn(channel, first, second)
                0xB0 -> listener.onControlChange(channel, first, second)
            }
        }

        private fun realtime(status: Int, timestamp: Long) {
            when (status) {
                0xF8 -> clock(timestamp)
                0xFA -> { clockTickCount = 0; lastQuarterTimestamp = 0L; listener.onTransportStart() }
                0xFB -> listener.onTransportContinue()
                0xFC -> listener.onTransportStop()
            }
        }

        private fun clock(timestamp: Long) {
            clockTickCount++
            if (clockTickCount < 24) return
            clockTickCount = 0
            if (lastQuarterTimestamp > 0L && timestamp > lastQuarterTimestamp) {
                val seconds = (timestamp - lastQuarterTimestamp) / 1_000_000_000.0
                val instant = (60.0 / seconds).toFloat().coerceIn(20f, 400f)
                smoothedBpm = if (smoothedBpm == 0f) instant else smoothedBpm * 0.8f + instant * 0.2f
                listener.onClockTempo((smoothedBpm * 10f).roundToInt() / 10f)
            }
            lastQuarterTimestamp = timestamp
        }
    }
}
