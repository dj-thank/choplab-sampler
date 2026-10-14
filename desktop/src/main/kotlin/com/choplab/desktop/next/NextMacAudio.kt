package com.choplab.desktop.next

import com.choplab.desktop.audio.MacMicrophonePermission
import com.choplab.jvm.MicInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.sound.sampled.*

/** These opaque IDs live only in this host session and never enter a project or preference. */
internal data class MacAudioDevice(val id: Int, val label: String, val systemDefault: Boolean = false)
internal data class MacAudioSelection(val inputId: Int = 0, val outputId: Int = 0)
internal data class MacAudioDevices(val inputs: List<MacAudioDevice>, val outputs: List<MacAudioDevice>)
internal data class MacAudioEndpoint(val key: String, val label: String,
    val openInput: (suspend () -> MicInput?)? = null, val openOutput: (() -> AudioSink)? = null)

/** Explicit Java Sound mixer selection backed by the actual input/output line factories. */
internal class NextMacAudio(
    private val enumerate: () -> List<MacAudioEndpoint> = ::javaSoundEndpoints,
    private val permissions: MacMicrophonePermission = MacMicrophonePermission(),
    private val permission: (() -> Boolean) -> MacMicrophonePermission.Status = permissions::request,
) {
    private val gate = Any()
    private val selected = MutableStateFlow(MacAudioSelection())
    val selection = selected.asStateFlow()
    private val ids = mutableMapOf<String, Int>()
    private var nextId = 1
    private var endpoints = mapOf<Int, MacAudioEndpoint>()
    private var opening = 0
    private var active = 0
    private var changing = false
    @Volatile var microphonePermission = MacMicrophonePermission.Status.UNKNOWN
        private set

    suspend fun devices(): MacAudioDevices = withContext(Dispatchers.IO) {
        val found = enumerate()
        synchronized(gate) {
            endpoints = found.associate { (if (it.key == DEFAULT) 0 else ids.getOrPut(it.key) { nextId++ }) to it }
            fun choice(id: Int, endpoint: MacAudioEndpoint) = MacAudioDevice(id, endpoint.label, id == 0)
            MacAudioDevices(endpoints.filterValues { it.openInput != null }.map { choice(it.key, it.value) },
                endpoints.filterValues { it.openOutput != null }.map { choice(it.key, it.value) })
        }
    }
    fun cancelOpening() = permissions.cancel()
    fun openOutput(): AudioSink {
        val id = selection.value.outputId
        val open = synchronized(gate) { endpoints[id]?.openOutput }
            ?: if (id == 0) ({ JavaSoundSink.open() }) else null
        return checkNotNull(open) { "Selected output is unavailable" }.invoke()
    }
    suspend fun openMicrophone(): MicInput? {
        synchronized(gate) { if (changing) return null; opening++ }
        return try {
            microphonePermission = permission(com.choplab.jvm.inputOpeningActive())
            if (microphonePermission != MacMicrophonePermission.Status.AUTHORIZED) return null
            val id = selection.value.inputId
            val open = synchronized(gate) { endpoints[id]?.openInput }
                ?: if (id == 0) (suspend { JavaSoundMicInput.open() }) else null
            val input = try { open?.invoke() } catch (_: SecurityException) {
                microphonePermission = MacMicrophonePermission.Status.DENIED; null
            } ?: return null
            synchronized(gate) { active++ }
            object : MicInput {
                private val released = AtomicBoolean()
                override val sampleRate get() = input.sampleRate
                override val channels get() = input.channels
                override val bufferFrames get() = input.bufferFrames
                override val routeRevision get() = input.routeRevision
                override val terminationReason get() = input.terminationReason
                override fun onCaptureThread() = input.onCaptureThread()
                override fun read(buffer: FloatArray) = input.read(buffer)
                override fun stop() = input.stop()
                override fun close() {
                    if (released.compareAndSet(false, true)) try { input.close(); synchronized(gate) { active-- } }
                    catch (failure: Throwable) { released.set(false); throw failure }
                }
            }
        } finally { synchronized(gate) { opening-- } }
    }
    suspend fun select(next: MacAudioSelection, releaseOutput: suspend () -> Boolean): Boolean {
        devices()
        synchronized(gate) {
            if (changing || opening != 0 || active != 0 || endpoints[next.inputId]?.openInput == null || endpoints[next.outputId]?.openOutput == null) return false
            changing = true
        }
        try {
            if (!releaseOutput()) return false
            selected.value = next
            return true
        } finally { synchronized(gate) { changing = false } }
    }
    companion object {
        private const val DEFAULT = "default"
        private fun javaSoundEndpoints(): List<MacAudioEndpoint> = buildList {
            add(MacAudioEndpoint(DEFAULT, "System default", { JavaSoundMicInput.open() }, { JavaSoundSink.open() }))
            for (info in AudioSystem.getMixerInfo()) {
                val mixer = AudioSystem.getMixer(info)
                val input: (suspend () -> MicInput?)? = if (mixer.targetLineInfo.isNotEmpty()) ({
                    JavaSoundMicInput.open(supported = mixer::isLineSupported, create = { mixer.getLine(it) as TargetDataLine })
                }) else null
                val output: (() -> AudioSink)? = if (mixer.sourceLineInfo.isNotEmpty()) ({
                    JavaSoundSink.open(mixer::isLineSupported) { mixer.getLine(it) as SourceDataLine }
                }) else null
                if (input != null || output != null) add(MacAudioEndpoint(
                    listOf(info.name, info.vendor, info.description, info.version).joinToString("\u0000"), info.name, input, output))
            }
        }
    }
}
