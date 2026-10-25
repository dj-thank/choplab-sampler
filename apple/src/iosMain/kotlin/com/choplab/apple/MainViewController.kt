package com.choplab.apple

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import com.choplab.core.Action
import com.choplab.core.ai.GoogleLyricSession
import com.choplab.ui.*
import com.choplab.ui.inception.InceptionController
import kotlinx.coroutines.*
import platform.Foundation.*
import platform.UIKit.*
import platform.AVFAudio.*
import kotlinx.serialization.json.*
import com.choplab.core.model.ProjectLimits
import com.choplab.apple.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.flow.first

/** Public Swift entry. Backend initialization is off-main; it recovers before exposing editable state. */
fun MainViewController(): UIViewController {
    lateinit var controller: UIViewController
    controller = ComposeUIViewController {
        val scope = rememberCoroutineScope()
        val ownership = remember { ResourceOwner<IosUiHost>() }
        var backend by remember { mutableStateOf<IosUiHost?>(null) }
        var failed by remember { mutableStateOf(false) }
        DisposableEffect(Unit) {
            onDispose {
                ownership.close()?.let { owned ->
                    CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                        try { owned.shutdown() } finally { cancel() }
                    }
                }
            }
        }
        LaunchedEffect(Unit) {
            try {
                createAndPublishOwnedResource(
                    create = { IosUiHost(IosBackend.create()) },
                    release = { it.shutdown() },
                    publish = { check(ownership.install(it)); backend = it },
                )
            }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed = true }
        }
        val owned = backend
        val host = owned?.backend
        if (host == null) {
            MaterialTheme { if (failed) Text(stringResource(Res.string.startup_failed)) else CircularProgressIndicator() }
        } else {
            val picker = remember(host) { IosFilePicker { controller } }
            val ports = remember(host) { IosEditorPorts(host, picker, scope) }
            val presenter = remember(host) { ContinuousEditorPresenter(host.studio, scope, ports) }
            val inception = remember(host) {
                InceptionController(IosInceptionStore(host.directory + "/inception-v1"), scope, host.studio,
                    GoogleLyricSession { (NSDate().timeIntervalSince1970 * 1000).toLong() },
                    canApply = { presenter.state.value.permits(ContinuousCapability.LYRICS_EDIT) })
            }
            val state by presenter.state.collectAsState()
            val autoChop by presenter.autoChop.collectAsState()
            val patterns by presenter.stepPatterns.collectAsState()
            LaunchedEffect(host) {
                val token = NSProcessInfo.processInfo.environment["CHOPLAB_SIMULATOR_SMOKE_TOKEN"] as? String
                if (token != null && token.length <= 64) {
                    val ready = presenter.state.first { it.pads.size == 128 && it.status != ContinuousStatus.LOADING }
                    val driver = withTimeout(15_000) { host.engine.status.first { it.phase != DriverPhase.STARTING } }
                    withFrameNanos { }
                    val receipt = buildJsonObject {
                        put("token", token); put("stage", ready.stage.name); put("padCount", ready.pads.size)
                        put("schemaVersion", ProjectLimits.SCHEMA); put("outputAttached", host.engine.snapshot().outputAttached)
                        put("driverPhase", driver.phase.name); put("driverFault", driver.fault.name)
                        host.engine.lastOutputCapability()?.let { put("audioCapability", it.json()) }
                        put("livePlayback", "not-exercised"); put("physicalSoundVerified", false)
                    }.toString().encodeToByteArray()
                    withContext(Dispatchers.Default) { writeFileAtomically(IosPaths.documents() + "/.simulator-smoke.json", receipt) }
                }
            }
            var refresh by remember { mutableLongStateOf(0L) }
            LaunchedEffect(host) { while (isActive) { delay(33); host.studio.dispatch(Action.RefreshTransport); refresh++ } }
            DisposableEffect(host) {
                requireNotNull(owned).beforeShutdown = {
                    try { inception.close() } finally { try { presenter.close() } finally { ports.sourcePreview.close() } }
                }
                val center = NSNotificationCenter.defaultCenter
                val observers = listOf(
                    center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) {
                        host.engine.releaseOutput()
                        scope.launch { inception.hostChanged(); host.studio.dispatch(Action.Silence); host.flushAutosave() }
                    },
                    center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { host.engine.reattach() },
                    center.addObserverForName(AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue) { host.engine.releaseOutput() },
                    center.addObserverForName(AVAudioSessionRouteChangeNotification, null, NSOperationQueue.mainQueue) { host.engine.releaseOutput() },
                )
                onDispose {
                    observers.forEach(center::removeObserver)
                }
            }
            Column(Modifier.fillMaxSize()) {
                if (host.preservedUnreadableAutosave != null) Text(stringResource(Res.string.autosave_preserved))
                val driver by host.engine.status.collectAsState()
                if (driver.phase == DriverPhase.EDITING_ONLY) TextButton(onClick = { host.engine.reattach() }) {
                    Text(stringResource(Res.string.reconnect_audio))
                }
                ContinuousEditor(state, { action ->
                    if (action == ContinuousEditorAction.StopAll) scope.launch { inception.stop() }
                    presenter.onAction(action)
                }, presenter::readout, refresh,
                    modifier = Modifier.weight(1f), diagnostics = presenter::diagnostics, mixerReadout = presenter::readMixer,
                    autoChop = autoChop, stepPatterns = patterns, inception = inception)
            }
        }
    }
    return controller
}

private class IosUiHost(val backend: IosBackend) {
    var beforeShutdown: suspend () -> Unit = {}
    suspend fun shutdown() { try { beforeShutdown() } finally { backend.shutdown() } }
}
