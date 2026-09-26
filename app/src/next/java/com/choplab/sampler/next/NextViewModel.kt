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
import com.choplab.jvm.DriverFault
import com.choplab.jvm.DriverPhase
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
    private var recovery: OutputRecovery? = null
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
            recovery = OutputRecovery(application, opened.backend.engine, viewModelScope)
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
 * While the editor is visible, reopens output lost to a route change or a missing device: a few spaced
 * attempts after each loss, and again whenever the set of audio devices changes. Playback never resumes;
 * a lost output already stopped every voice.
 */
class OutputRecovery(context: Context, private val engine: StreamingEnginePort, private val scope: CoroutineScope) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private var watcher: Job? = null
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) { retry() }
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) { retry() }
    }

    fun start() {
        if (watcher != null) return
        audioManager?.registerAudioDeviceCallback(devices, Handler(Looper.getMainLooper()))
        watcher = scope.launch {
            engine.status.collectLatest { status ->
                if (status.phase != DriverPhase.EDITING_ONLY || status.fault == DriverFault.NONE) return@collectLatest
                for (wait in RETRY_DELAYS_MS) {
                    delay(wait)
                    engine.reattach()
                }
            }
        }
    }

    fun stop() {
        watcher?.cancel()
        watcher = null
        audioManager?.unregisterAudioDeviceCallback(devices)
    }

    private fun retry() {
        val status = engine.status.value
        if (watcher != null && status.phase == DriverPhase.EDITING_ONLY && status.fault != DriverFault.NONE) engine.reattach()
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(300, 1_000, 3_000)
    }
}
