package com.choplab.desktop.next

import com.choplab.jvm.WavCodec
import com.choplab.jvm.PcmMemoryBudget
import com.choplab.ui.ContinuousEditorAction
import com.choplab.ui.ContinuousEditorPresenter
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlinx.coroutines.*

/** Explicit device probe only; the caller owns and removes the private temporary recording directory. */
object NextMicrophoneDeviceSelfTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 2 && args[0] == "--record-microphone") { "Use --record-microphone <new-private-directory>" }
        val directory = Path.of(args[1])
        require(!Files.exists(directory)) { "A new private directory is required" }
        val backend = NextBackend.create(directory, sinkFactory = { error("No speaker output in microphone probe") })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ports = DesktopEditorPorts(backend) { null }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        var rate = 0
        var frames = 0L
        var peak = 0f
        try {
            check(presenter.dispatch(ContinuousEditorAction.RecordSource)) { "Native microphone could not open; check permission and input route" }
            withTimeout(10_000) { while (backend.voice.recordedMillis < 2_000) delay(20) }
            check(!backend.voice.interrupted) { "Microphone ended before the requested stop" }
            check(presenter.finishRecording())
            val project = backend.studio.document.value.project
            val asset = project.assets.single()
            check(asset.channels == 1 && asset.frames >= asset.sampleRate * 2L)
            val audio = backend.assets.openVerified(asset).use { WavCodec.read(it) }
            check(audio.samples.all { it.isFinite() })
            rate = asset.sampleRate; frames = asset.frames; peak = audio.samples.maxOf(::abs)
            check(presenter.dispatch(ContinuousEditorAction.Undo))
            check(backend.studio.document.value.project.source == null)
            check(presenter.dispatch(ContinuousEditorAction.Redo))
            check(backend.studio.document.value.project == project)
            backend.flushAutosave()
        } finally { presenter.close(); ports.close(); backend.shutdown(); scope.cancel() }
        val reopened = NextBackend.create(directory, sinkFactory = { error("No speaker output") }, microphone = { null })
        try {
            val asset = reopened.studio.document.value.project.assets.single()
            check(asset.sampleRate == rate && asset.frames == frames)
            reopened.assets.openVerified(asset).close()
        } finally { reopened.shutdown() }
        withTimeout(5_000) { while (PcmMemoryBudget.shared.statistics().usedBytes != 0L) delay(10) }
        println("""{"status":"DEVICE_CAPTURE_PASS","scope":"packaged-java-native-microphone-source","sampleRate":$rate,"channels":1,"frames":$frames,"peak":$peak,"undoRedo":true,"autosaveReopen":true,"listeningAndLatencyVerified":false}""")
    }
}
