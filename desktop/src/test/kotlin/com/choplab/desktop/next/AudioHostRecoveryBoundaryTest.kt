package com.choplab.desktop.next

import com.choplab.core.vocal.PunchProblem
import com.choplab.core.vocal.VocalPunchRequest
import com.choplab.desktop.audio.FakeSystemAudioHelper
import com.choplab.desktop.audio.MacMicrophonePermission
import com.choplab.jvm.*
import com.choplab.ui.*
import com.choplab.ui.RecordingInterruption
import com.choplab.ui.SystemAudioCapture
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class AudioHostRecoveryBoundaryTest {
    @Test fun resetBothMissingMacEndpointsReleasesTheOldOutputBeforeOpeningDefaults() = runBlocking<Unit> {
        val events = CopyOnWriteArrayList<String>()
        val default = MacAudioEndpoint("default", "Default", { null }, { Sink("default", events) })
        var endpoints = listOf(default, MacAudioEndpoint("old-input", "Input", { null }),
            MacAudioEndpoint("old-output", "Output", openOutput = { Sink("old", events) }))
        val audio = NextMacAudio({ endpoints })
        val devices = audio.devices()
        val oldInput = devices.inputs.last().id; val oldOutput = devices.outputs.last().id
        val backend = NextBackend.createWithRoutes(Files.createTempDirectory("mac-route-reset-"), mac = audio)
        try {
            await { backend.engine.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(backend.chooseMacAudioDevices(oldInput, oldOutput))
            await { backend.engine.status.value.phase == DriverPhase.ATTACHED && "opened:old" in events }
            val before = backend.studio.document.value
            endpoints = listOf(default)
            assertFalse(backend.chooseMacAudioDevices(0, oldOutput))
            assertFalse(backend.chooseMacAudioDevices(oldInput, 0))
            events.clear()
            assertTrue(backend.chooseMacAudioDevices(0, 0), "The same explicit pair used by the reset menu recovers both endpoints")
            assertEquals(MacAudioSelection(), audio.selection.value)
            await { backend.engine.status.value.phase == DriverPhase.ATTACHED && "opened:default" in events }
            assertTrue(events.indexOf("closed:old") < events.indexOf("opened:default"), events.toString())
            assertFalse(backend.studio.transport.value.playing)
            assertEquals(before, backend.studio.document.value)
        } finally { backend.shutdown() }
    }

    @Test fun autosavedVoiceRestartsAtCleanupEvenWhenNoAcknowledgementCodeRan() = runBlocking<Unit> {
        val profile = Files.createTempDirectory("voice-commit-crash-")
        val backend = NextBackend.create(profile, sinkFactory = { Sink("synthetic") }, microphone = { ToneInput() })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ports = DesktopEditorPorts(backend) { null }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, object : ContinuousEditorPorts by ports {
            override suspend fun acknowledgeVoiceTake() {
                backend.flushAutosave()
                error("Synthetic process boundary after autosave and before acknowledgement")
            }
        })
        val accepted: com.choplab.core.DocumentState
        try {
            withTimeout(5000) { presenter.state.first { it.permits(ContinuousCapability.RECORD_VOICE) } }
            assertTrue(presenter.dispatch(ContinuousEditorAction.RecordVoice))
            await { backend.voice.recordedMillis >= 200 }
            assertFalse(presenter.dispatch(ContinuousEditorAction.StopVoice))
            accepted = backend.studio.document.value
            assertEquals(1, accepted.project.takes.size)
            assertNull(accepted.project.source)
            assertTrue(backend.voice.pendingSave)
        } finally { presenter.close(); ports.close(); backend.shutdown(flush = false); scope.cancel() }
        val restored = NextBackend.create(profile, sinkFactory = { Sink("restored") }, microphone = { error("No new microphone") })
        val recoveredScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val recoveredPorts = DesktopEditorPorts(restored) { null }
        val recovered = ContinuousEditorPresenter(restored.studio, recoveredScope, recoveredPorts)
        try {
            withTimeout(5000) { recovered.state.first { it.pendingRecordingApplied } }
            assertEquals(accepted.project, restored.studio.document.value.project)
            val before = restored.studio.document.value
            assertTrue(recovered.dispatch(ContinuousEditorAction.RetryRecordingSave))
            await { !restored.voice.pendingSave }
            assertEquals(before, restored.studio.document.value)
            assertNull(restored.studio.document.value.project.source)
        } finally { recovered.close(); recoveredPorts.close(); restored.shutdown(); recoveredScope.cancel() }
    }

    @Test fun outerInputDeadlineTerminatesTheHelperAndTheSameCaptureCanRetry() = runBlocking<Unit> {
        val child = AtomicReference<Process>()
        var mode = "late"
        val input = MacSystemInput({ File("synthetic") }, { FakeSystemAudioHelper.launcher(mode, "10000")(it).also(child::set) }, headerTimeoutMillis = 10_000)
        val root = Files.createTempDirectory("system-deadline-")
        val capture = NextSystemAudioCapture(FileAssetStore(root.resolve("assets")), root.resolve("scratch"), input = input, openTimeoutMillis = 500)
        try {
            assertEquals(SystemAudioCapture.Start.TIMEOUT, capture.start(5))
            await { !capture.inputBusy && !child.get().isAlive }
            mode = "float"
            assertEquals(SystemAudioCapture.Start.STARTED, capture.start(5))
            capture.discard()
            await { !capture.inputBusy && !child.get().isAlive }
        } finally { capture.close() }
    }

    @Test fun nativeTerminationReportsOnlyKnownReasonsAndRetainsPartialStereoAudio() = runBlocking<Unit> {
        for ((mode, expected) in listOf("float-permission" to RecordingInterruption.PERMISSION,
            "float-error" to RecordingInterruption.READ_FAILED, "float-dies" to RecordingInterruption.UNKNOWN)) {
            val root = Files.createTempDirectory("system-end-reason-")
            val input = MacSystemInput({ File("synthetic") }, FakeSystemAudioHelper.launcher(mode))
            val capture = NextSystemAudioCapture(FileAssetStore(root.resolve("assets")), root.resolve("scratch"), input = input)
            try {
                assertEquals(SystemAudioCapture.Start.STARTED, capture.start(5))
                await { capture.interrupted }
                assertEquals(expected, capture.inputReadout.interruption)
                val asset = assertNotNull(capture.stop("partial"))
                assertEquals(48_000, asset.frames); assertEquals(2, asset.channels)
                assertTrue(capture.inputReadout.pendingSave)
                capture.acknowledgeTake()
            } finally { capture.close() }
        }
    }

    @Test fun punchUsesTheSamePermissionClassificationAndCancelsItsOwnedHelper() = runBlocking<Unit> {
        var mode = "mic-denied"
        var opens = 0
        val child = AtomicReference<Process>()
        val permission = MacMicrophonePermission({ File("synthetic") }, { FakeSystemAudioHelper.launcher(mode)(it).also(child::set) })
        val audio = NextMacAudio({ listOf(MacAudioEndpoint("default", "Default", { opens++; null }, { Sink("default") })) }, permission)
        audio.devices()
        val backend = NextBackend.createWithRoutes(Files.createTempDirectory("punch-permission-"), mac = audio)
        val ports = DesktopEditorPorts(backend) { null }
        val request = VocalPunchRequest(0, 48_000, 0, 0)
        try {
            await { backend.engine.status.value.phase == DriverPhase.ATTACHED }
            val doc = backend.studio.document.value
            assertEquals(PunchProblem.PERMISSION, ports.vocalPunch.capture(doc.project, doc.revision, request).problem)
            assertEquals(0, opens)
            mode = "mic-late"; child.set(null)
            val opening = async(Dispatchers.Default) { ports.vocalPunch.capture(doc.project, doc.revision, request) }
            await { child.get() != null }
            ports.vocalPunch.requestStop()
            assertEquals(PunchProblem.CANCELLED, withTimeout(2000) { opening.await() }.problem)
            await { !child.get().isAlive && !backend.voice.inputBusy }
            mode = "mic-denied"
            assertEquals(PunchProblem.PERMISSION, ports.vocalPunch.capture(doc.project, doc.revision, request).problem)
            assertEquals(0, opens)
            var cancelledWithResult = false
            val cancellationRace = VocalPunchCapture(backend.studio, backend.engine, backend.voice,
                permission = { cancelledWithResult = true; false })
            assertEquals(PunchProblem.CANCELLED, cancellationRace.capture(doc.project, doc.revision, request) {
                cancelledWithResult
            }.problem, "A completed opening result must not replace the user's concurrent cancellation")
        } finally { ports.close(); backend.shutdown() }
    }

    private class ToneInput : MicInput {
        override val sampleRate = 48_000
        @Volatile private var stopped = false
        override fun read(buffer: FloatArray): Int {
            if (stopped) return -1
            Thread.sleep(10)
            val count = minOf(buffer.size, 480)
            for (i in 0 until count) buffer[i] = .25f
            return count
        }
        override fun stop() { stopped = true }
        override fun close() { stopped = true }
    }
    private class Sink(private val name: String, private val events: MutableList<String> = mutableListOf()) : AudioSink {
        init { events += "opened:$name" }
        override val encoding = SinkEncoding.FLOAT32
        override fun pendingFrames() = 0L
        override fun write(bytes: ByteArray, offset: Int, length: Int): Int { Thread.sleep(1); return length }
        override fun close() { events += "closed:$name" }
    }
    private suspend fun await(condition: () -> Boolean) = withTimeout(5000) { while (!condition()) delay(5) }
}
