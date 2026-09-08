package com.choplab.sampler

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import com.choplab.sampler.source.SourceImportViewModel
import com.choplab.sampler.ui.AudioSourceHub
import com.choplab.sampler.ui.SpotifySourcePicker
import com.choplab.sampler.ui.externalDocumentActionsEnabled
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.choplab.sampler.audio.PlaybackInterruption
import com.choplab.sampler.ui.DocumentAction
import com.choplab.sampler.ui.SamplerScreen
import com.choplab.sampler.ui.documentPickerCanceledMessage
import com.choplab.sampler.ui.theme.ChopLabTheme

class MainActivity : ComponentActivity() {
    private val samplerViewModel: SamplerViewModel by viewModels()
    private val sourceViewModel: SourceImportViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(savedInstanceState==null) sourceViewModel.handleIntent(intent)
        setContent {
            ChopLabTheme {
                val context = LocalContext.current
                val state by samplerViewModel.uiState.collectAsStateWithLifecycle()
                var pendingAction by rememberSaveable { mutableStateOf(PendingPermissionAction.NONE) }

                val importState by sourceViewModel.hub.state.collectAsStateWithLifecycle()
                val spotifyState by sourceViewModel.spotify.state.collectAsStateWithLifecycle()
                val sourceVisible by sourceViewModel.visible.collectAsStateWithLifecycle()
                val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    if(uris.isNotEmpty()) sourceViewModel.importUris(uris)
                }
                fun useLibrary(id:String) {
                    if(!externalDocumentActionsEnabled(samplerViewModel.uiState.value)) return
                    val item=sourceViewModel.hub.state.value.library.firstOrNull { it.id==id }?:return
                    samplerViewModel.addLibrarySource(android.net.Uri.fromFile(sourceViewModel.hub.file(id)),item.title)
                    sourceViewModel.hub.consumed(id);sourceViewModel.used()
                }
                LaunchedEffect(importState.pendingUseId) { importState.pendingUseId?.let(::useLibrary) }
                BackHandler(enabled=sourceVisible) { sourceViewModel.hide() }

                val exportLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("audio/wav"),
                ) { uri ->
                    if (uri == null) {
                        samplerViewModel.setStatus(documentPickerCanceledMessage(DocumentAction.EXPORT_WAV))
                        return@rememberLauncherForActivityResult
                    }
                    samplerViewModel.exportPattern(uri)
                }

                val openProjectLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                ) { uri ->
                    if (uri == null) {
                        samplerViewModel.setStatus(documentPickerCanceledMessage(DocumentAction.OPEN_PROJECT))
                        return@rememberLauncherForActivityResult
                    }
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    samplerViewModel.loadProject(uri)
                }

                val saveProjectLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.CreateDocument("application/vnd.choplab.project"),
                ) { uri ->
                    if (uri == null) {
                        samplerViewModel.setStatus(documentPickerCanceledMessage(DocumentAction.SAVE_PROJECT))
                        return@rememberLauncherForActivityResult
                    }
                    samplerViewModel.saveProject(uri)
                }

                val projectionManager = remember {
                    requireNotNull(context.getSystemService(MediaProjectionManager::class.java)) {
                        "MediaProjectionManager is unavailable on this device"
                    }
                }
                val projectionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.StartActivityForResult(),
                ) { result ->
                    val data = result.data
                    if (result.resultCode == Activity.RESULT_OK && data != null) {
                        samplerViewModel.startSystemAudioCapture(result.resultCode, data)
                    } else {
                        samplerViewModel.setStatus("端末音声録音はキャンセルされました")
                    }
                }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) {
                    // A foreground media-projection service may still run when notifications
                    // are denied, but the stop action remains available inside the app.
                    projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                }

                fun requestSystemAudioProjection() {
                    val needsNotificationPermission =
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.POST_NOTIFICATIONS,
                            ) != PackageManager.PERMISSION_GRANTED

                    if (needsNotificationPermission) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        projectionLauncher.launch(projectionManager.createScreenCaptureIntent())
                    }
                }

                val recordPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                ) { granted ->
                    if (granted) {
                        when (pendingAction) {
                            PendingPermissionAction.MICROPHONE -> samplerViewModel.startMicrophoneRecording()
                            PendingPermissionAction.VOCAL -> samplerViewModel.startVocalOverdubRecording()
                            PendingPermissionAction.SYSTEM_AUDIO -> requestSystemAudioProjection()
                            PendingPermissionAction.NONE -> Unit
                        }
                    } else {
                        samplerViewModel.setStatus(recordPermissionDeniedMessage(pendingAction))
                    }
                    pendingAction = PendingPermissionAction.NONE
                }

                fun hasRecordPermission(): Boolean =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED

                SamplerScreen(
                    state = state,
                    onImportAudio = { sourceViewModel.show() },
                    onToggleMicrophoneRecording = {
                        if (state.microphoneRecording) {
                            samplerViewModel.stopMicrophoneRecording()
                        } else if (hasRecordPermission()) {
                            samplerViewModel.startMicrophoneRecording()
                        } else {
                            pendingAction = PendingPermissionAction.MICROPHONE
                            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onToggleVocalRecording = {
                        if (state.vocalOverdubRecording) {
                            samplerViewModel.stopVocalOverdubRecording()
                        } else if (hasRecordPermission()) {
                            samplerViewModel.startVocalOverdubRecording()
                        } else {
                            pendingAction = PendingPermissionAction.VOCAL
                            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onExportBeat = {
                        exportLauncher.launch("ChopLab_${System.currentTimeMillis()}.wav")
                    },
                    onOpenProject = {
                        openProjectLauncher.launch(
                            arrayOf(
                                "application/vnd.choplab.project",
                                "application/zip",
                                "application/octet-stream",
                            ),
                        )
                    },
                    onSaveProject = {
                        saveProjectLauncher.launch("ChopLab_${System.currentTimeMillis()}.choplab")
                    },
                    onToggleSystemAudioRecording = {
                        if (state.systemAudioRecording) {
                            samplerViewModel.stopSystemAudioCapture()
                        } else if (hasRecordPermission()) {
                            requestSystemAudioProjection()
                        } else {
                            pendingAction = PendingPermissionAction.SYSTEM_AUDIO
                            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    viewModel = samplerViewModel,
                )
                if(sourceVisible) AudioSourceHub(
                    state=importState,canUseAudio=externalDocumentActionsEnabled(state),
                    onSection=sourceViewModel.hub::section,onQuery=sourceViewModel.hub::query,onSearch=sourceViewModel.hub::search,
                    onDownload=sourceViewModel.hub::download,
                    onPickFiles={importLauncher.launch(arrayOf("audio/*","video/mp4","video/webm","application/zip","application/octet-stream"))},
                    onUse=::useLibrary,onCancel=sourceViewModel.hub::cancel,onClose=sourceViewModel::hide,
                    spotifyContent={SpotifySourcePicker(spotifyState,importState.busy,SourceImportViewModel.REDIRECT_URI,
                        sourceViewModel.spotify::login,sourceViewModel.spotify::disconnect,sourceViewModel.spotify::loadMore,
                        sourceViewModel.hub::importFavorite,{link->startActivity(Intent(Intent.ACTION_VIEW,android.net.Uri.parse(link)))})},
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sourceViewModel.handleIntent(intent)
        setIntent(Intent(this,MainActivity::class.java))
    }

    override fun onStop() {
        if (shouldInterruptPlaybackOnActivityStop(isChangingConfigurations)) {
            samplerViewModel.handlePlaybackInterruption(PlaybackInterruption.APP_BACKGROUND)
            samplerViewModel.flushAutosave()
        }
        super.onStop()
    }
}

internal fun shouldInterruptPlaybackOnActivityStop(
    isChangingConfigurations: Boolean,
): Boolean = !isChangingConfigurations

internal enum class PendingPermissionAction {
    NONE,
    MICROPHONE,
    VOCAL,
    SYSTEM_AUDIO,
}

internal fun recordPermissionDeniedMessage(action: PendingPermissionAction): String = when (action) {
    PendingPermissionAction.MICROPHONE -> "マイク素材を録音するにはマイク権限が必要です"
    PendingPermissionAction.VOCAL -> "ビートに声を重ねるにはマイク権限が必要です"
    PendingPermissionAction.SYSTEM_AUDIO -> "端末音声を録音するにはマイク権限が必要です"
    PendingPermissionAction.NONE -> "録音するにはマイク権限が必要です"
}
