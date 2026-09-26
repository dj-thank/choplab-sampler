package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.model.Asset
import com.choplab.core.model.Pattern
import com.choplab.core.model.Project
import com.choplab.engine.SequenceClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs

/** Import, project and export ports a host builds on the backend's asset store and compiler.
 * Paths or content URIs stay host-private behind opaque [Location] handles.
 */
class HostFileServices(val importer: ImportPort, val projects: ProjectPort, val exporter: ExportPort)

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
    private val autosave: AutosaveStore,
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
    }

    suspend fun flushAutosave() = withContext(Dispatchers.IO) {
        val document = studio.document.value
        try { autosave.save(document.project, document.revision); persistenceFailed.value = false }
        catch (error: Exception) { persistenceFailed.value = true; throw error }
    }

    suspend fun loadPeaks(asset: Asset, maximumBuckets: Int = 512): List<Float> = withContext(Dispatchers.Default) {
        require(maximumBuckets in 16..2048)
        val audio = pcm.load(asset)
        val bucketSize = ((audio.frameCount + maximumBuckets - 1) / maximumBuckets).coerceAtLeast(1)
        val cache = WaveformCache()
        val peaks = cache.build(WaveformCache.Key(asset.hash, bucketSize), audio)
        List(peaks.buckets) { bucket -> maxOf(abs(peaks.minimum(bucket, 0)), abs(peaks.maximum(bucket, 0)),
            abs(peaks.minimum(bucket, 1)), abs(peaks.maximum(bucket, 1))) }
    }

    /** [flush] is false only after the user chose to close without the final autosave. */
    suspend fun shutdown(flush: Boolean = true) {
        try { if (flush) flushAutosave(); studio.dispatch(Action.Stop); studio.dispatch(Action.Close) }
        finally {
            audition.close()
            try { withContext(Dispatchers.IO) { engine.close() } }
            finally { pcm.close(); scope.cancel() }
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
        ): EditorBackend {
            Files.createDirectories(directory)
            require(!Files.isSymbolicLink(directory)) { "Profile directory must not be a symbolic link" }
            val assets = FileAssetStore(directory.toRealPath().resolve("assets"))
            val autosave = AutosaveStore(directory.toRealPath().resolve("autosave"), assets)
            val recovered = autosave.recover()
            val hadSavedDocument = (0..2).any { Files.exists(autosave.directory.resolve("autosave.$it.json")) }
            check(recovered != null || !hadSavedDocument) { "Autosave recovery failed; existing files were preserved" }
            val pcm = WavPcmPort(assets)
            var output: StreamingEnginePort? = null
            var scope: CoroutineScope? = null
            try {
                val compiler = ProgramCompiler(pcm)
                output = engine(compiler)
                val services = files(assets, compiler)
                val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope = it }
                val studio = Studio(jobs, Services(assets, services.importer, services.projects, services.exporter, output),
                    recovered?.project ?: Project(), recovered?.revision ?: 0)
                return EditorBackend(studio, output, assets, pcm, jobs, SourceAuditionController(output, pcm, jobs), autosave)
            } catch (failure: Throwable) {
                scope?.cancel()
                try { output?.close() } finally { pcm.close() }
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
