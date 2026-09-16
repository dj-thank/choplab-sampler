package com.choplab.sampler.midi

import android.media.AudioDeviceInfo
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean

/** Actual AudioTrack route, not merely a plugged-in/paired device. All queries run on Main. */
internal class AndroidDdjAudioRoute(private val track: AudioTrack) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private val listener = AudioRouting.OnRoutingChangedListener { refresh() }

    init {
        main.post {
            if (!closed.get()) {
                AndroidDdjPerformance.bindRoute(this)
                runCatching { track.addOnRoutingChangedListener(listener, main) }
                refresh()
            }
        }
    }

    private fun refresh() {
        if (closed.get()) return
        val route = runCatching {
            val devices = if (Build.VERSION.SDK_INT >= 36) track.routedDevices
                else listOfNotNull(track.routedDevice)
            val device = devices.singleOrNull()
            val wired = device?.type in setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
            )
            // Empty channel counts mean unspecified capabilities; the user still confirms
            // the physical splitter. Explicit mono-only endpoints are rejected.
            val stereo = device != null && (device.channelCounts.isEmpty() || device.channelCounts.any { it >= 2 })
            DdjAudioRoute(device?.id ?: -1, wired && stereo)
        }.getOrDefault(DdjAudioRoute())
        AndroidDdjPerformance.updateRoute(this, route)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        main.post {
            runCatching { track.removeOnRoutingChangedListener(listener) }
            AndroidDdjPerformance.unbindRoute(this)
        }
    }
}
