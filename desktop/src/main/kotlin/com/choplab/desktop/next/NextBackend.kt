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
    private val titles = ConcurrentHashMap<String, String>()
    private val hashes = ConcurrentHashMap<String, String>()
    fun expectedHash(location: Location): String? = hashes[location.handle]
    fun title(location: Location): String? = titles[location.handle]
    fun registerNamed(path: Path, title: String, expectedHash: String? = null): Location = register(path).also {
        titles[it.handle] = title.filter { ch -> ch.code >= 32 }.take(240).ifBlank { "Audio" }
        expectedHash?.let { hash -> hashes[it.handle] = hash }
    }
    fun register(path: Path): Location = Location(UUID.randomUUID().toString()).also {
        values[it.handle] = path.toAbsolutePath().normalize()
    }
    fun resolve(location: Location): Path = requireNotNull(values[location.handle]) { "Unknown host file" }
}

/** Desktop face of the shared [EditorBackend]: explicit Windows WASAPI/Java Sound routes and path-backed file services.
 * The caller selects Preview/next-v10 (or a test temporary directory), never the legacy data root.
 */
class NextBackend private constructor(private val shared: EditorBackend, val files: NextFileLocations, val voice: VoiceTakes,
    val systemAudio: com.choplab.ui.SystemAudioCapture?, private val decoder: DesktopOriginalAudioDecoder,
    internal val windowsAudio: NextWindowsAudio? = null) : AutoCloseable {
    val studio: Studio get() = shared.studio
    val engine: StreamingEnginePort get() = shared.engine
    val assets: FileAssetStore get() = shared.assets
    val audition: SourceAuditionController get() = shared.audition
    val persistenceFailure: StateFlow<Boolean> get() = shared.persistenceFailure

    /** Explicit Windows selection: keep the document, silence voices and release the old route before another opens. */
    internal suspend fun chooseAudioRoute(route: NextAudioRoute): Boolean {
        val audio = windowsAudio ?: return false
        if (studio.work.value.let { it.jobId != null || it.preparationId != null }) return false
        val changed = audio.select(route) {
            if (!studio.dispatch(Action.Silence).accepted) return@select false
            engine.releaseOutput()
            withTimeoutOrNull(5_000) {
                while (engine.status.value.phase != DriverPhase.EDITING_ONLY || engine.diagnostics().openingDevice) delay(10)
                true
            } == true
        }
        if (!changed) return false
        return engine.reattach()
    }

    /** Worker-only library validation uses the same bounded decoder handoff as original import and playback. */
    fun validateLibraryFile(file: java.io.File) {
        require(file.isFile && file.length() in 1..com.choplab.core.model.ProjectLimits.MAX_ASSET_BYTES)
        val extension = file.extension.lowercase()
        require(extension in Asset.EXTENSIONS)
        if (extension == "wav") { file.inputStream().use { WavCodec.inspect(it) }; return }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(64 * 1024)
            while (true) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                val count = input.read(bytes)
                if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        decoder.inspect(file.toPath(), hash) { Thread.currentThread().isInterrupted }
    }

    fun createFourStemWorker(factory: com.choplab.jvm.separation.FourStemSessionFactory,
                            memoryProbe: () -> com.choplab.jvm.separation.SeparationMemory) = shared.createFourStemWorker(factory, memoryProbe)

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
    suspend fun prepareDrumKit(kitId: String): List<Asset> = shared.prepareDrumKit(kitId)
    suspend fun renderPad(pad: com.choplab.core.model.Pad, source: Asset): Asset = shared.renderPad(pad, source)
    suspend fun renderPerformance(pad: com.choplab.core.model.Pad, source: Asset, releaseAt: Int?, limitFrames: Int, stopAt: Int? = null): Asset =
        shared.renderPerformance(pad, source, releaseAt, limitFrames, stopAt)

    /** Caller owns and closes the job; the captured source is verified again on its worker. */
    internal fun separation(source: Asset, library: Path, title: String): NextSeparation = NextSeparation(
        library, ::validateLibraryFile, load = { cancelled ->
            val path = assets.verifiedPath(source, cancelled)
            if (source.extension == "wav") java.nio.file.Files.newInputStream(path).use { WavCodec.read(it) }
            else decoder.decode(path, source.hash, cancelled)
        }, render = { audio, output, progress, cancelled ->
            val model = com.choplab.desktop.separation.defaultSeparatorModelsDir()
                .resolve(com.choplab.sampler.separation.SeparatorSpec.MODEL_FILE).toPath()
            NextDrumSeparation.renderWithModel(audio, output, model, progress, cancelled)
        }, title = title)

    /** [flush] is false only after the user chose to close without the final autosave. A take still recording is dropped. */
    suspend fun shutdown(flush: Boolean = true) {
        try { systemAudio?.close() } finally { try { voice.close() } finally {
            try { shared.shutdown(flush) } finally { windowsAudio?.close() }
        } }
    }
    override fun close() = runBlocking { shutdown() }

    companion object {
        fun create(directory: Path, sinkFactory: (() -> AudioSink)? = null, microphone: (() -> MicInput?)? = null): NextBackend {
            val windows = if (sinkFactory == null && System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) NextWindowsAudio() else null
            return createWithRoutes(directory, sinkFactory, microphone, windows)
        }

        internal fun createWithRoutes(directory: Path, sinkFactory: (() -> AudioSink)? = null,
            microphone: (() -> MicInput?)? = null, windows: NextWindowsAudio? = null): NextBackend {
            val files = NextFileLocations()
            val decoder = DesktopOriginalAudioDecoder()
            val shared = try { EditorBackend.create(directory,
                engine = { compiler -> JavaSoundEnginePort(compiler, sinkFactory ?: windows?.let { it::openOutput } ?: { JavaSoundSink.open() }) },
                files = { assets, compiler ->
                    val original = OriginalAudioImportPort(assets, files::resolve, decoder)
                    val named = object : ImportPort {
                        override suspend fun import(location: Location): Asset = original.import(location).let { asset ->
                            require(files.expectedHash(location)?.let { it == asset.hash } != false) { "Library audio changed after selection" }
                            files.title(location)?.let { asset.copy(name = it) } ?: asset
                        }
                    }
                    HostFileServices(named,
                    FileProjectPort(assets, files::resolve), WavExportPort(compiler, files::resolve)) }, decoder = decoder) }
                catch (failure: Throwable) { try { windows?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }; throw failure }
            val voice = try { VoiceTakes(shared.assets, directory.resolve("voice-scratch"),
                microphone = microphone ?: windows?.let { it::openMicrophone } ?: { JavaSoundMicInput.open() }) }
                catch (failure: Throwable) {
                    try { runBlocking { shared.shutdown(flush = false) } } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    try { windows?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                    throw failure
                }
            val system = try { when {
                windows != null -> NextWasapiSystemAudioCapture(shared.assets, directory.resolve("system-scratch"), windows)
                com.choplab.desktop.isMacOsHost() -> NextSystemAudioCapture(shared.assets, directory.resolve("system-scratch"))
                else -> null
            } } catch (failure: Throwable) {
                try { runBlocking { voice.close() } } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                try { runBlocking { shared.shutdown(flush = false) } } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                try { windows?.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
            return NextBackend(shared, files, voice, system, decoder, windows)
        }

        fun patternFrames(project: Project, pattern: Pattern): Int = EditorBackend.patternFrames(project, pattern)
    }
}
