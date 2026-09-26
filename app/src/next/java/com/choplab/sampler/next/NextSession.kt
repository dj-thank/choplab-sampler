package com.choplab.sampler.next

import android.content.Context
import android.net.Uri
import com.choplab.core.*
import com.choplab.core.model.Asset
import com.choplab.jvm.*
import com.choplab.sampler.R
import com.choplab.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One editor session: the shared [EditorBackend] with the Android output and Storage Access Framework
 * documents, and the preserved continuous editor's presenter. Lives in a ViewModel so rotation keeps it.
 */
class NextSession private constructor(
    private val context: Context,
    val backend: EditorBackend,
    val documents: AndroidDocuments,
    val pickers: DocumentPickers<Uri>,
    private val scope: CoroutineScope,
) {
    private val hostMessages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Localized host messages (for example "too long") that the core's typed notices cannot carry. */
    val messages: SharedFlow<String> = hostMessages.asSharedFlow()
    val presenter = ContinuousEditorPresenter(backend.studio, scope, Ports())
    @Volatile var closedWithoutAutosave = false
        private set

    /** The editor is visible again: reopen the output it released or lost. Playback never resumes by itself. */
    fun reopenOutput() { backend.engine.reattach() }
    /** The editor is hidden: hand the output device back so nothing keeps rendering silence. */
    fun releaseOutput() { backend.engine.releaseOutput() }
    /** Stops the song and the original. Unlike the Stop button it leaves an import, save or export running. */
    suspend fun stopSound() {
        backend.studio.dispatch(Action.Stop)
        backend.audition.pause()
    }
    /** Saves now; a failure stays visible through [EditorBackend.persistenceFailure]. */
    suspend fun saveNow() { try { backend.flushAutosave() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { } }

    /** Another app took audio focus or the headphones were unplugged: stop, and never resume by itself. */
    fun stopForInterruption() { scope.launch { stopSound() } }

    /** Closes the editor after the final autosave, or after the user accepted closing without it. */
    suspend fun close(confirmWithoutAutosave: suspend () -> Boolean, finish: suspend () -> Unit): Boolean =
        closeAfterAutosave(backend::flushAutosave, { confirmWithoutAutosave().also { if (it) closedWithoutAutosave = true } }, finish)

    suspend fun shutdown() {
        withContext(Dispatchers.Main.immediate + NonCancellable) { pickers.close() }
        try { withContext(NonCancellable) { presenter.close() } }
        finally { withContext(NonCancellable) { backend.shutdown(flush = !closedWithoutAutosave) }; scope.cancel() }
    }

    private fun message(text: String) { hostMessages.tryEmit(text) }
    private fun suggestedName(extension: String): String =
        context.getString(R.string.next_file_base) + "-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + ".$extension"

    private inner class Ports : ContinuousEditorPorts {
        override val originalAvailable get() = backend.engine.status.value.phase == DriverPhase.ATTACHED
        override fun originalPlaying() = backend.engine.originalPlayback().playing
        override fun playingPads() = backend.engine.playingPads()
        override fun cancelOriginalPreparation() = backend.audition.cancelPreparation()
        override suspend fun playOriginal(asset: Asset) = backend.audition.play(asset)
        override suspend fun stopOriginal() = backend.audition.pause()
        override suspend fun resetOriginal() = backend.audition.clear()
        override suspend fun seekOriginal(frame: Long): Boolean {
            val project = backend.studio.document.value.project
            val asset = project.source?.assetHash?.let(project::asset) ?: return false
            return backend.audition.seek(asset, frame)
        }
        override suspend fun setOriginalMonitorGain(gain: Float) = backend.audition.originalGain(gain)
        override suspend fun setSongMonitorGain(gain: Float) = backend.audition.songGain(gain)
        override fun readout() = ContinuousEditorReadout(backend.audition.nativeFrame(), backend.engine.playback().sequenceRenderFrames)
        override suspend fun peaks(asset: Asset) = backend.loadPeaks(asset)

        override suspend fun chooseAudio(): Location? {
            val uri = pickers.pick(PickerKind.AUDIO) ?: return null
            return when (withContext(Dispatchers.IO) { checkSource(context, uri) }) {
                SourceCheck.ACCEPTED -> documents.opened(uri)
                SourceCheck.TOO_LONG -> null.also {
                    message(context.getString(R.string.next_too_long, maximumSourceSeconds / 60, maximumSourceSeconds % 60))
                }
                SourceCheck.UNREADABLE -> null.also { message(context.getString(R.string.next_unreadable)) }
            }
        }
        override suspend fun chooseOpen(): Location? = pickers.pick(PickerKind.PROJECT)?.let(documents::opened)
        override suspend fun chooseSave(): Location? = pickers.pick(PickerKind.SAVE_PROJECT, suggestedName("choplab"))?.let(documents::created)
        override suspend fun chooseExport(frames: Long): ExportRequest? {
            val uri = pickers.pick(PickerKind.EXPORT_WAV, suggestedName("wav")) ?: return null
            return ExportRequest(documents.created(uri), Math.toIntExact(frames), bits = 24)
        }
    }

    companion object {
        /** Blocking: recovers the autosave and starts the output. Call off the main thread. */
        fun open(context: Context): NextSession {
            val app = context.applicationContext
            val documents = AndroidDocuments(app.contentResolver)
            val files = StreamFileServices(documents, File(app.cacheDir, "next-io").toPath(), AndroidAudioDecoding(app, documents))
            val backend = EditorBackend.create(File(app.filesDir, "next-v10").toPath(),
                engine = { compiler -> StreamingEnginePort(compiler, { AndroidAudioSink.open() }) },
                files = files::create)
            return NextSession(app, backend, documents, DocumentPickers(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        }
    }
}
