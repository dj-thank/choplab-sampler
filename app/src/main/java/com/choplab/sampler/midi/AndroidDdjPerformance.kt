package com.choplab.sampler.midi

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DdjAudioRoute(val id: Int = -1, val splitCapable: Boolean = false)

/** Main-only control publication, one immutable volatile snapshot per audio block. */
internal object AndroidDdjPerformance {
    @Volatile var snapshot = DdjMixerSettings()
        private set
    private val mutableState = MutableStateFlow(snapshot)
    val state = mutableState.asStateFlow()
    private val mutableRoute = MutableStateFlow(DdjAudioRoute())
    val route = mutableRoute.asStateFlow()
    private var owner: Any? = null
    private var routeOwner: Any? = null
    private var splitRoute = -1
    private var routeLost: (() -> Unit)? = null

    fun acquire(token: Any, onRouteLost: () -> Unit) {
        owner = token; routeLost = onRouteLost
        publish(token, DdjMixerSettings(enabled = true))
    }
    fun publish(token: Any, value: DdjMixerSettings) {
        if (owner !== token) return
        val safe = if (value.splitCue && !mutableRoute.value.splitCapable) value.copy(splitCue = false) else value
        if (safe.splitCue && !snapshot.splitCue) splitRoute = mutableRoute.value.id
        snapshot = safe; mutableState.value = safe
    }
    fun release(token: Any) {
        if (owner !== token) return
        snapshot = DdjMixerSettings(); mutableState.value = snapshot
        owner = null; routeLost = null; splitRoute = -1
    }
    fun bindRoute(token: Any) { routeOwner = token; updateRoute(token, DdjAudioRoute()) }
    fun updateRoute(token: Any, value: DdjAudioRoute) {
        if (routeOwner !== token) return
        mutableRoute.value = value
        if (snapshot.splitCue && (!value.splitCapable || value.id != splitRoute)) {
            // Fail closed as soon as Android reports a route change. In-flight AudioTrack
            // buffers/OS notification delay still require physical unplug testing.
            snapshot = snapshot.copy(splitCue = false)
            mutableState.value = snapshot
            routeLost?.invoke()
        }
    }
    fun unbindRoute(token: Any) {
        if (routeOwner !== token) return
        updateRoute(token, DdjAudioRoute()); routeOwner = null
    }
}
