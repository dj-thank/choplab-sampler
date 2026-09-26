package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.model.Asset
import com.choplab.core.model.Pattern
import com.choplab.core.model.Project
import com.choplab.engine.SequenceClock
import com.choplab.jvm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Host-private mapping. Neither absolute paths nor opaque handles enter a Project. */
class NextFileLocations {
    private val values = ConcurrentHashMap<String, Path>()
    fun register(path: Path): Location = Location(UUID.randomUUID().toString()).also {
        values[it.handle] = path.toAbsolutePath().normalize()
    }
    fun resolve(location: Location): Path = requireNotNull(values[location.handle]) { "Unknown host file" }
}

/** UI-independent composition root, usable by the preserved continuous editor. No window/dialog.
 * The caller selects Preview/next-v10 (or a test temporary directory), never the legacy data root.
 */
class NextBackend private constructor(
    val studio: Studio,
    val engine: JavaSoundEnginePort,
    val files: NextFileLocations,
    val assets: FileAssetStore,
    private val pcm: WavPcmPort,
    private val scope: CoroutineScope,
    val audition: SourceAuditionController,
    private val autosave: AutosaveStore,
) : AutoCloseable {
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
    suspend fun importAudio(path: Path): ActionResult = studio.dispatch(Action.Import(files.register(path)))
    suspend fun openProject(path: Path): ActionResult = studio.dispatch(Action.Open(files.register(path)))
    suspend fun saveProject(path: Path): ActionResult = studio.dispatch(Action.Save(files.register(path)))
    suspend fun exportPattern(path: Path, bits: Int = 24, frames: Int? = null): ActionResult {
        val project = studio.document.value.project
        val pattern = project.patterns.first { it.id == studio.selection.value.patternId }
        return studio.dispatch(Action.Export(ExportRequest(files.register(path), frames ?: patternFrames(project, pattern), bits = bits)))
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
    override fun close() = runBlocking { shutdown() }

    companion object {
        fun create(directory: Path, sinkFactory: (() -> AudioSink)? = null): NextBackend {
            Files.createDirectories(directory)
            require(!Files.isSymbolicLink(directory)) { "Profile directory must not be a symbolic link" }
            val files = NextFileLocations()
            val assets = FileAssetStore(directory.toRealPath().resolve("assets"))
            val autosave = AutosaveStore(directory.toRealPath().resolve("autosave"), assets)
            val recovered = autosave.recover()
            val hadSavedDocument = (0..2).any { Files.exists(autosave.directory.resolve("autosave.$it.json")) }
            check(recovered != null || !hadSavedDocument) { "Autosave recovery failed; existing files were preserved" }
            // Owned resources start only after recovery, and a failed composition releases them again.
            val pcm = WavPcmPort(assets)
            var engine: JavaSoundEnginePort? = null
            try {
                val compiler = ProgramCompiler(pcm)
                val output = if (sinkFactory == null) JavaSoundEnginePort(compiler) else JavaSoundEnginePort(compiler, sinkFactory)
                engine = output
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val studio = Studio(scope, Services(assets, WavImportPort(assets, files::resolve), FileProjectPort(assets, files::resolve),
                    WavExportPort(compiler, files::resolve), output), recovered?.project ?: Project(), recovered?.revision ?: 0)
                return NextBackend(studio, output, files, assets, pcm, scope, SourceAuditionController(output, pcm, scope), autosave)
            } catch (failure: Throwable) {
                try { engine?.close() } finally { pcm.close() }
                throw failure
            }
        }

        fun patternFrames(project: Project, pattern: Pattern): Int =
            ((pattern.lengthTicks.toLong() * SequenceClock.UNITS_PER_TICK + project.tempo.milliBpm - 1) / project.tempo.milliBpm).toInt()
    }
}
