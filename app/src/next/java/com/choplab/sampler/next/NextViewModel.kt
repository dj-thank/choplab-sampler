package com.choplab.sampler.next

import android.app.Application
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.choplab.jvm.OutputRecovery
import com.choplab.jvm.StreamingEnginePort
import com.choplab.sampler.audio.AndroidPlaybackFocusAdapter
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Owns the editor session across rotation; the Activity reports visibility and back presses. */
class NextViewModel(application: Application) : AndroidViewModel(application) {
    sealed interface Startup {
        data object Loading : Startup
        data class Ready(val session: NextSession) : Startup
        data object Failed : Startup
    }

    private val startup = MutableStateFlow<Startup>(Startup.Loading)
    val state: StateFlow<Startup> = startup.asStateFlow()
    private val closeQuestion = MutableStateFlow<CompletableDeferred<Boolean>?>(null)
    /** Non-null while the editor asks whether to close without the final autosave. */
    val askingToClose: StateFlow<CompletableDeferred<Boolean>?> = closeQuestion.asStateFlow()
    private val finished = MutableStateFlow(false)
    /** Becomes true once closing may finish the Activity. */
    val closed: StateFlow<Boolean> = finished.asStateFlow()

    private var session: NextSession? = null
    private var focus: AndroidPlaybackFocusAdapter? = null
    private var recovery: AndroidOutputRecovery? = null
    private var visible = false
    private var hiding: Job? = null
    private var closing = false

    init {
        viewModelScope.launch {
            val opened = try { withContext(Dispatchers.IO) { NextSession.open(application) } }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { startup.value = Startup.Failed; return@launch }
            session = opened
            val adapter = AndroidPlaybackFocusAdapter(application) { opened.stopForInterruption() }
            focus = adapter
            recovery = AndroidOutputRecovery(application, opened.backend.engine, viewModelScope)
            // Only the song and the original hold audio focus; short PAD hits play like an instrument.
            launch {
                opened.presenter.state.map { it.songPlaying || it.originalPlaying }.distinctUntilChanged().collect { playing ->
                    if (!playing) adapter.abandonPlaybackFocus()
                    else if (!adapter.requestPlaybackFocus()) opened.stopForInterruption()
                }
            }
            startup.value = Startup.Ready(opened)
            if (visible) show(opened) else hide(opened)
        }
    }

    fun onVisible() {
        visible = true
        session?.let(::show)
    }

    fun onHidden() {
        visible = false
        session?.let(::hide)
    }

    private fun show(opened: NextSession) {
        hiding?.cancel()
        hiding = null
        opened.reopenOutput()
        recovery?.start()
    }

    private fun hide(opened: NextSession) {
        recovery?.stop()
        focus?.abandonPlaybackFocus()
        hiding?.cancel()
        hiding = viewModelScope.launch {
            // A document screen covering the editor also lands here; a running import keeps going.
            opened.stopSound()
            // Main thread: onVisible cannot interleave with this check, and cancels this job first.
            if (!visible) opened.releaseOutput()
            opened.saveNow()
        }
    }

    /** Back press: save, or ask before closing without the final autosave. */
    fun requestClose() {
        val opened = session ?: run { finished.value = true; return }
        if (closing) return
        closing = true
        viewModelScope.launch {
            val done = opened.close({ ask() }) { finished.value = true }
            if (!done) closing = false
        }
    }

    private suspend fun ask(): Boolean {
        val answer = CompletableDeferred<Boolean>()
        closeQuestion.value = answer
        return try { answer.await() } finally { closeQuestion.value = null }
    }

    override fun onCleared() {
        val opened = session ?: return
        recovery?.stop()
        focus?.close()
        // viewModelScope is already cancelled; the shutdown saves and releases on its own scope.
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try { opened.shutdown() } catch (_: Exception) { }
        }
    }
}

/**
 * The shared [OutputRecovery] plus Android's device events: plugging or unplugging headphones, USB or
 * Bluetooth output tries once more at once, while the editor is visible.
 */
class AndroidOutputRecovery(context: Context, engine: StreamingEnginePort, scope: CoroutineScope) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val policy = OutputRecovery(engine, scope)
    private var registered = false
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { policy.retry() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { policy.retry() }
    }

    fun start() {
        policy.start()
        if (!registered) { audioManager?.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper())); registered = true }
    }

    fun stop() {
        policy.stop()
        if (registered) { audioManager?.unregisterAudioDeviceCallback(devices); registered = false }
    }
}
