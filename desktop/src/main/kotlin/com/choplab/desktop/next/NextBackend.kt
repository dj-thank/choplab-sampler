package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.model.Asset
import com.choplab.core.model.Pattern
import com.choplab.core.model.Project
import com.choplab.jvm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Host-private mapping. Neither absolute paths nor opaque handles enter a Project. */
class NextFileLocations {
    private val values = ConcurrentHashMap<String, Path>()
    fun register(path: Path): Location = Location(UUID.randomUUID().toString()).also {
        values[it.handle] = path.toAbsolutePath().normalize()
    }
    fun resolve(location: Location): Path = requireNotNull(values[location.handle]) { "Unknown host file" }
}

/** Desktop face of the shared [EditorBackend]: Java Sound output and path-backed file services.
 * The caller selects Preview/next-v10 (or a test temporary directory), never the legacy data root.
 */
class NextBackend private constructor(private val shared: EditorBackend, val files: NextFileLocations) : AutoCloseable {
    val studio: Studio get() = shared.studio
    val engine: StreamingEnginePort get() = shared.engine
    val assets: FileAssetStore get() = shared.assets
    val audition: SourceAuditionController get() = shared.audition
    val persistenceFailure: StateFlow<Boolean> get() = shared.persistenceFailure

    suspend fun flushAutosave() = shared.flushAutosave()
    suspend fun importAudio(path: Path): ActionResult = studio.dispatch(Action.Import(files.register(path)))
    suspend fun openProject(path: Path): ActionResult = studio.dispatch(Action.Open(files.register(path)))
    suspend fun saveProject(path: Path): ActionResult = studio.dispatch(Action.Save(files.register(path)))
    suspend fun exportPattern(path: Path, bits: Int = 24, frames: Int? = null): ActionResult {
        val project = studio.document.value.project
        val pattern = project.patterns.first { it.id == studio.selection.value.patternId }
        return studio.dispatch(Action.Export(ExportRequest(files.register(path), frames ?: patternFrames(project, pattern), bits = bits)))
    }
    suspend fun loadPeaks(asset: Asset, maximumBuckets: Int = 512): List<Float> = shared.loadPeaks(asset, maximumBuckets)

    /** [flush] is false only after the user chose to close without the final autosave. */
    suspend fun shutdown(flush: Boolean = true) = shared.shutdown(flush)
    override fun close() = runBlocking { shutdown() }

    companion object {
        fun create(directory: Path, sinkFactory: (() -> AudioSink)? = null): NextBackend {
            val files = NextFileLocations()
            val shared = EditorBackend.create(directory,
                engine = { compiler -> if (sinkFactory == null) JavaSoundEnginePort(compiler) else JavaSoundEnginePort(compiler, sinkFactory) },
                files = { assets, compiler -> HostFileServices(WavImportPort(assets, files::resolve),
                    FileProjectPort(assets, files::resolve), WavExportPort(compiler, files::resolve)) })
            return NextBackend(shared, files)
        }

        fun patternFrames(project: Project, pattern: Pattern): Int = EditorBackend.patternFrames(project, pattern)
    }
}
