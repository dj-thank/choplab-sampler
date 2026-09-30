package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.kits.DrumKits
import com.choplab.core.model.Asset
import com.choplab.core.model.AssetRole
import com.choplab.core.model.Pad
import com.choplab.core.model.Pattern
import com.choplab.core.model.Project
import com.choplab.engine.PadRender
import com.choplab.engine.SequenceClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/** Import, project and export ports a host builds on the backend's asset store and compiler.
 * Paths or content URIs stay host-private behind opaque [Location] handles.
 */
class HostFileServices(val importer: ImportPort, val projects: ProjectPort, val exporter: ExportPort, val stems: StemExportPort? = null)

/**
 * UI-independent composition root shared by the desktop and Android editor hosts: one [Studio], one
 * streaming output, assets and autosave under one profile directory. No window, dialog or device choice.
 * The caller selects an isolated Preview profile (or a test directory), never the legacy data root.
 */
class EditorBackend private constructor(
    val studio: Studio,
    val engine: StreamingEnginePort,
    val assets: FileAssetStore,
    private val pcm: WavPcmPort,
    private val scope: CoroutineScope,
    val audition: SourceAuditionController,
    val stemsAvailable: Boolean,
    private val autosave: AutosaveStore,
    private val decoder: OriginalAudioDecoder?,
) {
    private val persistenceFailed = MutableStateFlow(false)
    val persistenceFailure: StateFlow<Boolean> = persistenceFailed.asStateFlow()
    init {
        scope.launch(Dispatchers.IO) {
            studio.document.map { it.revision to it.project }.distinctUntilChanged().collectLatest { (revision, project) ->
                delay(300)
                try { autosave.save(project, revision); persistenceFailed.value = false }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { persistenceFailed.value = true }
            }
        }
        // The original is heard at the document's song key however the document changed: edit, Undo, open or import.
        scope.launch {
            studio.document.map { it.project.source?.pitchSemitones ?: 0.0 }.distinctUntilChanged().collect { semitones ->
                audition.pitch(semitones.toFloat())
            }
        }
    }

    suspend fun flushAutosave() = withContext(Dispatchers.IO) {
        val document = studio.document.value
        try { autosave.save(document.project, document.revision); persistenceFailed.value = false }
        catch (error: Exception) { persistenceFailed.value = true; throw error }
    }

    suspend fun loadPeaks(asset: Asset, maximumBuckets: Int = 512): List<Float> = withContext(Dispatchers.Default) {
        require(maximumBuckets in 16..2048)
        pcm.acquire(asset).use { lease ->
            pcm.memory.reserve(com.choplab.engine.PagedPcm.PAGE_FRAMES * 8L + maximumBuckets * 4L).use {
            val audio = lease.pcm
            val bucketSize = ((audio.frameCount + maximumBuckets - 1) / maximumBuckets).coerceAtLeast(1)
            val peaks = FloatArray((audio.frameCount + bucketSize - 1) / bucketSize)
            var first = 0
            while (first < audio.frameCount) {
                currentCoroutineContext().ensureActive()
                val end = minOf(audio.frameCount, first + com.choplab.engine.PagedPcm.PAGE_FRAMES)
                val window = pcm.readWindow(audio, first, end)
                for (frame in first until end) {
                    val sample = (frame - first) * 2
                    val bucket = frame / bucketSize
                    peaks[bucket] = maxOf(peaks[bucket], abs(window[sample]), abs(window[sample + 1]))
                }
                first = end
            }
            peaks.toList()
            }
        }
    }

    private val vocalCompRenderer = VocalCompRenderer(assets, pcm, assets.directory.parent.resolve("vocal-comp"))
    suspend fun renderVocalComp(project: Project, draft: com.choplab.core.vocal.VocalCompDraft, name: String): Asset =
        try { vocalCompRenderer.render(project, draft, name) }
        catch (_: PcmMemoryLimit) { throw com.choplab.core.vocal.VocalEditException(com.choplab.core.vocal.VocalProblem.LIMIT) }
    /** Offline practice uses the production compiler and the same PCM leases/budget as playback/export. */
    fun practiceRenderer(): com.choplab.core.vocal.VocalPracticeRenderer = VocalPracticeWorker(
        ProgramCompiler(pcm), assets, assets.directory.parent.resolve("practice-scratch"), memory = pcm.memory)

    /** The worker borrows the shared PCM; closing it never closes playback's cache or decoder. */
    fun createFourStemWorker(factory: com.choplab.jvm.separation.FourStemSessionFactory,
                            memoryProbe: () -> com.choplab.jvm.separation.SeparationMemory): com.choplab.core.separation.FourStemPort =
        com.choplab.jvm.separation.FourStemService(assets, pcm, assets.directory.parent.resolve("four-stem-temporary"), factory, memoryProbe)

    /** Renders and stores a built-in kit's 16 sounds in slot order, ready for an InstallKit edit. */
    suspend fun prepareDrumKit(kitId: String): List<Asset> = DrumKitAssets.publish(DrumKits.kit(kitId), assets)

    suspend fun analyseSource(asset: Asset, range: com.choplab.core.model.FrameRange): com.choplab.core.analysis.SourceMusicResult =
        analyseSourceMusic(pcm, asset, range)

    /**
     * Renders [pad] from [source] with its pitch, reverse, tone and own pan into a 48 kHz float WAV in the store.
     * Placement retains the PAD gain and applies the destination BANK mix once; neither is baked here.
     * The same PAD renders to the same bytes, so placing it again adds nothing.
     */
    suspend fun renderPad(pad: Pad, source: Asset): Asset = withContext(Dispatchers.Default) {
        pcm.acquire(source).use { lease ->
            val prepared = ProgramCompiler.enginePad(pad, source, lease.pcm)
            pcm.memory.reserve(PadRender.frames(prepared) * 8L + 256 * 1024).use {
                val context = currentCoroutineContext()
                val samples = PadRender.render(prepared, bakePan = true) { windows, render -> runBlocking(context) { pcm.prepared(windows, render) } }
                val marks = buildList {
                    if (pad.pitchSemitones != 0.0) add("%+d".format(kotlin.math.round(pad.pitchSemitones).toInt()))
                    if (pad.reverse) add("rev")
                    if (pad.tone < com.choplab.engine.Pad.TONE_BYPASS) add("tone ${kotlin.math.round(pad.tone * 100).toInt()}%")
                }
                publishRendered(samples, (listOf(source.name.take(200)) + marks).joinToString(" "), source.hash)
            }
        }
    }

    /** A complete performed voice. Gain/pan/envelope are baked; placement uses unity gain and center pan. */
    suspend fun renderPerformance(pad: Pad, source: Asset, releaseAt: Int?, limitFrames: Int, stopAt: Int? = null): Asset = withContext(Dispatchers.Default) {
        pcm.acquire(source).use { lease ->
            val prepared = ProgramCompiler.enginePad(pad, source, lease.pcm)
            val frames = com.choplab.engine.PadPerformanceRender.frames(prepared, releaseAt, limitFrames, stopAt)
            pcm.memory.reserve(frames * 8L + 256 * 1024).use {
                val context = currentCoroutineContext()
                val samples = com.choplab.engine.PadPerformanceRender.render(prepared, releaseAt, limitFrames, stopAt) { windows, render ->
                    runBlocking(context) { pcm.prepared(windows, render) }
                }
                publishRendered(samples, source.name.take(200) + " performance", source.hash)
            }
        }
    }

    suspend fun renderNoteRepeat(pad: Pad, source: Asset, tempo: com.choplab.engine.Tempo, ticks: Int,
                                 releaseAt: Int, limitFrames: Int, stopAt: Int? = null): Asset = withContext(Dispatchers.Default) {
        pcm.acquire(source).use { lease ->
            val prepared = ProgramCompiler.enginePad(pad, source, lease.pcm)
            val frames = com.choplab.engine.NoteRepeatRender.frames(prepared, releaseAt, limitFrames, stopAt)
            pcm.memory.reserve(frames * 8L + 256 * 1024).use {
                val context = currentCoroutineContext()
                val samples = com.choplab.engine.NoteRepeatRender.render(prepared, tempo, ticks, releaseAt, limitFrames, stopAt) { windows, render ->
                    runBlocking(context) { pcm.prepared(windows, render) }
                }
                publishRendered(samples, source.name.take(200) + " repeat", source.hash)
            }
        }
    }

    /** Stream float bytes: no second/third full-sized byte-array copy next to the rendered PCM. */
    private suspend fun publishRendered(samples: FloatArray, name: String, sourceHash: String): Asset = withContext(Dispatchers.IO) {
        val temporary = Files.createTempFile("choplab-render-", ".wav")
        try {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            java.io.FileOutputStream(temporary.toFile()).use { file ->
                val writer = WavCodec.FloatWriter(java.security.DigestOutputStream(file, hash), samples.size / 2L)
                var first = 0
                while (first < samples.size / 2) {
                    ensureActive()
                    val count = minOf(4096, samples.size / 2 - first)
                    writer.write(samples, first, count)
                    first += count
                }
                writer.finish(); file.fd.sync()
            }
            val asset = Asset(hash.digest().hex(), "wav", Files.size(temporary), 48_000, 2, samples.size / 2L,
                name, AssetRole.RENDERED, derivedFrom = sourceHash)
            val context = currentCoroutineContext()
            ensureActive()
            assets.adopt(asset, temporary) { !context.isActive }
            asset
        } finally { Files.deleteIfExists(temporary) }
    }

    /** [flush] is false only after the user chose to close without the final autosave. */
    suspend fun shutdown(flush: Boolean = true) {
        try { if (flush) flushAutosave(); studio.dispatch(Action.Stop); studio.dispatch(Action.Close) }
        finally {
            audition.close()
            try { withContext(Dispatchers.IO) { engine.close() } }
            finally { pcm.close(); decoder?.close(); scope.cancel() }
        }
    }

    companion object {
        /**
         * Recovers the profile's autosave first; the output, decoders and coroutines start only after that
         * succeeds, and a failed composition releases whatever it had already started.
         */
        fun create(
            directory: Path,
            engine: (ProgramCompiler) -> StreamingEnginePort,
            files: (FileAssetStore, ProgramCompiler) -> HostFileServices,
            decoder: OriginalAudioDecoder? = null,
        ): EditorBackend {
            Files.createDirectories(directory)
            require(!Files.isSymbolicLink(directory)) { "Profile directory must not be a symbolic link" }
            val assets = FileAssetStore(directory.toRealPath().resolve("assets"), decoder = decoder)
            val autosave = AutosaveStore(directory.toRealPath().resolve("autosave"), assets)
            val recovered = autosave.recover()
            val hadSavedDocument = (0..2).any { Files.exists(autosave.directory.resolve("autosave.$it.json")) }
            check(recovered != null || !hadSavedDocument) { "Autosave recovery failed; existing files were preserved" }
            val pcm = WavPcmPort(assets, decoder = decoder)
            var output: StreamingEnginePort? = null
            var scope: CoroutineScope? = null
            try {
                val compiler = ProgramCompiler(pcm)
                output = engine(compiler)
                val services = files(assets, compiler)
                val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
                val studio = Studio(jobs, Services(assets, services.importer, services.projects, services.exporter, output, services.stems),
                    recovered?.project ?: Project(), recovered?.revision ?: 0)
                return EditorBackend(studio, output, assets, pcm, jobs, SourceAuditionController(output, pcm, jobs), services.stems != null, autosave, decoder)
            } catch (failure: Throwable) {
                scope?.cancel()
                try { output?.close() } finally { pcm.close(); decoder?.close() }
                throw failure
            }
        }

        fun patternFrames(project: Project, pattern: Pattern): Int =
            ((pattern.lengthTicks.toLong() * SequenceClock.UNITS_PER_TICK + project.tempo.milliBpm - 1) / project.tempo.milliBpm).toInt()
    }
}

/**
 * A failing autosave (full or read-only storage) must never trap the editor open. After a failed final
 * save the user decides; declining keeps the editor and its unsaved work. Returns whether it finished.
 */
suspend fun closeAfterAutosave(flush: suspend () -> Unit, confirmWithoutAutosave: suspend () -> Boolean,
                               finish: suspend () -> Unit): Boolean {
    val saved = try { flush(); true } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { false }
    if (!saved && !confirmWithoutAutosave()) return false
    finish()
    return true
}
