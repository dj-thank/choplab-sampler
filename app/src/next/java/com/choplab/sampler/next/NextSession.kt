package com.choplab.sampler.next

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import com.choplab.core.*
import com.choplab.core.model.Asset
import com.choplab.core.model.Pad
import com.choplab.jvm.*
import com.choplab.jvm.ai.*
import com.choplab.sampler.R
import com.choplab.ui.*
import com.choplab.ui.ai.LyricProposalPort
import com.choplab.ui.ai.VocalGuidePort
import com.choplab.ui.onboarding.QuickStartController
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
    /** Frames the activity drew; declared before the presenter, whose ports read it. */
    val frames = FrameCounter()
    /** The microphone permission screen and voice takes; also declared before the presenter. */
    val microphone = MicrophonePermission({ context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED })
    private val hasMicrophone = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    private val voice = VoiceTakes(backend.assets, File(context.cacheDir, "next-voice").toPath()) { AndroidMicInput.open(context) }
    private val speechPreview = SourceVocalPreview(backend, scope)
    val presenter = ContinuousEditorPresenter(backend.studio, scope, Ports())
    private val guideStore = FileQuickStartStore(backend.assets.directory.parent.resolve("ui"))
    val quickStart = QuickStartController(scope, autoShow = backend.studio.document.value.revision == 0L,
        guideStore::completed, guideStore::complete)
    @Volatile var closedWithoutAutosave = false
        private set

    /** The editor is visible again: reopen the output it released or lost. Playback never resumes by itself. */
    fun reopenOutput() { backend.engine.reattach() }
    /** The editor is hidden: hand the output device back so nothing keeps rendering silence. */
    fun releaseOutput() { backend.engine.releaseOutput() }
    /** Stops the song and the original. Unlike the Stop button it leaves an edit, import, save or export running. */
    suspend fun stopSound() {
        speechPreview.stop()
        backend.studio.dispatch(Action.Silence)
        backend.audition.pause()
        presenter.finishRecording()
    }
    /** Saves now; a failure stays visible through [EditorBackend.persistenceFailure]. */
    suspend fun saveNow() { try { backend.flushAutosave() } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { } }

    /** Another app took audio focus or the headphones were unplugged: stop, and never resume by itself. */
    fun stopForInterruption() { scope.launch { stopSound() } }

    /** Closes the editor after the final autosave, or after the user accepted closing without it. */
    suspend fun close(confirmWithoutAutosave: suspend () -> Boolean, finish: suspend () -> Unit): Boolean =
        closeAfterAutosave({ check(presenter.finishRecording()); backend.flushAutosave() },
            { confirmWithoutAutosave().also { if (it) closedWithoutAutosave = true } }, finish)

    suspend fun shutdown() {
        quickStart.close()
        withContext(Dispatchers.Main.immediate + NonCancellable) { pickers.close(); microphone.close() }
        // Closing the presenter keeps a take still recording; anything left after that is dropped.
        try { withContext(NonCancellable) { presenter.close() } }
        finally {
            withContext(NonCancellable) { try { voice.close() } finally { backend.shutdown(flush = !closedWithoutAutosave) } }
            scope.cancel()
        }
    }

    private fun message(text: String) { hostMessages.tryEmit(text) }
    private fun suggestedName(extension: String): String =
        context.getString(R.string.next_file_base) + "-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(Date()) + ".$extension"

    private inner class Ports : ContinuousEditorPorts {
        override val vocalGuide: VocalGuidePort = object : VocalGuidePort {
            override val preview = speechPreview
            override fun createSynthesis(): com.choplab.core.ai.VocalSynthesisPort = VocalTtsService(
                AndroidTtsProvider(context), TtsCache(File(context.cacheDir, "next-tts-cache").toPath()), backend.assets)
        }
        override val onlineSource = AndroidOnlineSourceHost(context, documents)
        override val lyricProposal: LyricProposalPort = object : LyricProposalPort {
            override fun createProvider() = GeminiLyricProvider()
        }
        override val lyricFiles: LyricFiles = object : LyricFiles {
            override suspend fun importLrc(): String? {
                val uri = pickers.pick(PickerKind.IMPORT_LRC) ?: return null
                return withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openInputStream(uri)).use(LrcTextIO::read) }
            }
            override suspend fun exportLrc(text: String): Boolean {
                val uri = pickers.pick(PickerKind.EXPORT_LRC, suggestedName("lrc")) ?: return false
                withContext(Dispatchers.IO) {
                    LrcTextIO.write(text) {
                        ensureActive()
                        requireNotNull(context.contentResolver.openOutputStream(uri, "wt"))
                    }
                }
                return true
            }
        }
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
        override suspend fun setHandMonitorGain(gain: Float) = backend.audition.handGain(gain)
        override suspend fun scratchOriginalStart(asset: Asset, from: Long, start: Long, end: Long) = backend.audition.scratchStart(asset, from, start, end)
        override suspend fun scratchOriginalTo(position: Double, durationFrames: Int) = backend.audition.scratchTo(position, durationFrames)
        override suspend fun scratchOriginalCut(gain: Float) = backend.audition.scratchCut(gain)
        override suspend fun scratchOriginalEnd() = backend.audition.scratchEnd()
        override val padRenderAvailable = true
        override val stepPatternsAvailable = true
        override val sourceAnalysisAvailable = true
        override suspend fun analyseSource(asset: Asset, range: com.choplab.core.model.FrameRange) = backend.analyseSource(asset, range)
        override suspend fun renderPad(pad: Pad, source: Asset) = backend.renderPad(pad, source)
        override suspend fun renderPerformance(pad: Pad, source: Asset, releaseAt: Int?, limitFrames: Int, stopAt: Int?) =
            backend.renderPerformance(pad, source, releaseAt, limitFrames, stopAt)
        override suspend fun setSongMonitorGain(gain: Float) = backend.audition.songGain(gain)
        override fun readout() = ContinuousEditorReadout(backend.audition.nativeFrame(), backend.engine.playback().sequenceRenderFrames,
            handSourceFrame = backend.audition.nativeHandFrame(), countInBeatsRemaining = backend.engine.snapshot().countInBeatsRemaining,
            pcm = pcmReadout())
        private fun pcmReadout() = backend.engine.pcmPlayback().let { ContinuousPcmReadout(it.status, it.underrunFrames, it.droppedRequests) }
        override suspend fun peaks(asset: Asset) = backend.loadPeaks(asset)
        override val drumKitsAvailable get() = true
        override suspend fun drumKit(kitId: String) = backend.prepareDrumKit(kitId)
        override fun diagnostics(): ContinuousDiagnostics = backend.engine.health().let { health ->
            ContinuousDiagnostics(outputAttached = health.attached, floatOutput = health.encoding?.let { it == SinkEncoding.FLOAT32 },
                sampleRate = health.sampleRate, blockFrames = health.blockFrames, bufferFrames = health.bufferFrames,
                pendingFrames = health.pendingFrames, underruns = health.underruns, outputLosses = health.outputLosses,
                measuredBlocks = health.measuredBlocks, renderP99 = health.renderP99, renderMax = health.renderMax,
                drawnFrames = frames.drawn.get(), slowFrames = frames.slow.get(), pcm = pcmReadout())
        }
        override val voiceAvailable get() = hasMicrophone
        override suspend fun startVoice(maxSeconds: Int): VoiceStart = if (!microphone.request()) VoiceStart.DENIED
            else when (voice.start(maxSeconds)) {
                VoiceTakes.Start.STARTED -> VoiceStart.STARTED
                VoiceTakes.Start.NO_ROOM -> VoiceStart.NO_ROOM
                VoiceTakes.Start.NO_INPUT -> VoiceStart.UNAVAILABLE
            }
        override fun cueVoice() = voice.cue()
        override val recordingCue: RecordingCuePort = object : RecordingCuePort {
            override suspend fun startArmedVoice(maxSeconds: Int): VoiceStart = if (!microphone.request()) VoiceStart.DENIED
                else when (voice.start(maxSeconds, waitForCue = true)) {
                    VoiceTakes.Start.STARTED -> VoiceStart.STARTED
                    VoiceTakes.Start.NO_ROOM -> VoiceStart.NO_ROOM
                    VoiceTakes.Start.NO_INPUT -> VoiceStart.UNAVAILABLE
                }
            override fun cueVoiceAt(engineFrame: Long): Boolean = backend.engine.estimatedOutputNanos(engineFrame)?.let(voice::cueAt) == true
            override fun armingTimedOut() = voice.armingTimedOut
        }
        override fun voiceFull() = voice.full
        override fun voiceRecordedMillis() = voice.recordedMillis
        override fun voiceInterrupted() = voice.interrupted
        override suspend fun stopVoice(name: String) = voice.stop(name)
        override suspend fun discardVoice() = voice.discard()
        override suspend fun copyText(text: String): Boolean = withContext(Dispatchers.Main) {
            val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return@withContext false
            clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.next_diagnostics_label), text))
            true
        }

        override suspend fun chooseAudio(): Location? {
            val uri = pickers.pick(PickerKind.AUDIO) ?: return null
            return when (withContext(Dispatchers.IO) { checkSource(context, uri) }) {
                SourceCheck.ACCEPTED -> documents.opened(uri)
                SourceCheck.TOO_LONG -> null.also {
                    message(context.getString(R.string.next_source_frame_limit))
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
            val decoder = AndroidOriginalAudioDecoder(File(app.cacheDir, "next-decoded").toPath())
            val files = StreamFileServices(documents, File(app.cacheDir, "next-io").toPath(), originalDecoder = decoder)
            val backend = EditorBackend.create(File(app.filesDir, "next-v10").toPath(),
                engine = { compiler -> StreamingEnginePort(compiler, { AndroidAudioSink.open() }) },
                files = files::create, decoder = decoder)
            return NextSession(app, backend, documents, DocumentPickers(), CoroutineScope(SupervisorJob() + Dispatchers.Default))
        }
    }
}
