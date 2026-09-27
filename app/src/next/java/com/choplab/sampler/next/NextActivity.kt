package com.choplab.sampler.next

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.choplab.sampler.R
import com.choplab.ui.ContinuousEditor
import com.choplab.ui.ContinuousStatus

/** おとひろい NEXT: the preserved four-stage editor on the new core. The existing app stays the default entry. */
class NextActivity : ComponentActivity() {
    private val model: NextViewModel by viewModels()

    private val openAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { answer(PickerKind.AUDIO, it) }
    private val openProject = registerForActivityResult(ActivityResultContracts.OpenDocument()) { answer(PickerKind.PROJECT, it) }
    private val saveProject = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) {
        answer(PickerKind.SAVE_PROJECT, it)
    }
    private val exportWav = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/x-wav")) { answer(PickerKind.EXPORT_WAV, it) }
    private val launch: (PickerKind, String?) -> Unit = { kind, name ->
        when (kind) {
            PickerKind.AUDIO -> openAudio.launch(arrayOf("audio/*", "application/ogg", "video/mp4"))
            PickerKind.PROJECT -> openProject.launch(arrayOf("*/*"))
            PickerKind.SAVE_PROJECT -> saveProject.launch(name ?: "project.choplab")
            PickerKind.EXPORT_WAV -> exportWav.launch(name ?: "export.wav")
        }
    }

    private fun answer(kind: PickerKind, uri: Uri?) {
        (model.state.value as? NextViewModel.Startup.Ready)?.session?.pickers?.complete(kind, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            val startup by model.state.collectAsState()
            val closed by model.closed.collectAsState()
            val question by model.askingToClose.collectAsState()
            LaunchedEffect(closed) { if (closed) finish() }
            BackHandler { model.requestClose() }
            Box(Modifier.fillMaxSize().background(Background)) {
                when (val current = startup) {
                    NextViewModel.Startup.Loading -> Notice(stringResource(R.string.next_loading))
                    NextViewModel.Startup.Failed -> Notice(stringResource(R.string.next_start_failed))
                    is NextViewModel.Startup.Ready -> Editor(current.session)
                }
            }
            question?.let { pending ->
                AlertDialog(
                    onDismissRequest = { pending.complete(false) },
                    title = { Text(stringResource(R.string.next_close_title)) },
                    text = { Text(stringResource(R.string.next_close_message)) },
                    confirmButton = { TextButton({ pending.complete(true) }) { Text(stringResource(R.string.next_close_confirm)) } },
                    dismissButton = { TextButton({ pending.complete(false) }) { Text(stringResource(R.string.next_close_cancel)) } },
                )
            }
        }
    }

    @Composable
    private fun Editor(session: NextSession) {
        LaunchedEffect(session) { session.pickers.attach(launch) }
        LaunchedEffect(session) { session.messages.collect { Toast.makeText(this@NextActivity, it, Toast.LENGTH_LONG).show() } }
        val state by session.presenter.state.collectAsState()
        val refresh by session.presenter.refreshKey.collectAsState()
        val failed by session.backend.persistenceFailure.collectAsState()
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("next-editor")) {
            ContinuousEditor(if (failed) state.copy(status = ContinuousStatus.FAILED) else state,
                session.presenter::onAction, session.presenter::readout, refresh)
        }
    }

    @Composable
    private fun Notice(text: String) {
        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp), contentAlignment = Alignment.Center) {
            Text(text, color = Cream, fontSize = 16.sp, textAlign = TextAlign.Center)
        }
    }

    override fun onStart() {
        super.onStart()
        model.onVisible()
    }

    override fun onStop() {
        model.onHidden()
        super.onStop()
    }

    override fun onDestroy() {
        (model.state.value as? NextViewModel.Startup.Ready)?.session?.pickers?.detach(launch)
        super.onDestroy()
    }

    private companion object {
        val Background = Color(0xFF14110A)
        val Cream = Color(0xFFEFE6D0)
    }
}
