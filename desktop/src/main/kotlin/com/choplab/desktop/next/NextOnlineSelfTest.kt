package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.model.Project
import com.choplab.jvm.WavCodec
import com.choplab.ui.*
import com.choplab.sampler.source.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Isolated candidate lookup, explicit acquisition, editor production, archive and restart; no native devices. */
object NextOnlineSelfTest {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1)
        runBlocking { run(Path.of(args[0])) }
        println("""{"status":"LOCAL_PASS","scope":"online-source-production","originalBytesExact":true,"titlePreserved":true,"syntheticProvider":true,"undoRedo":true,"export24":true,"saveRestart":true,"nativeDialogs":false,"humanAcceptance":false}""")
    }
    suspend fun run(directory: Path) {
        Files.createDirectories(directory)
        val profile = directory.resolve("profile")
        check(!Files.exists(profile))
        val input = directory.resolve("Online original.wav")
        Files.newOutputStream(input).use { WavCodec.writePcm(it, FloatArray(48_000 * 2) {
            (kotlin.math.sin(it / 31.0) * if (it % 2 == 0) .25 else -.12).toFloat()
        }, bits = 24, dither = false) }
        suspend fun idle(library: NextOnline) = withTimeout(10_000) { while (library.state.value.busy) delay(5) }
        val backend = NextBackend.create(profile, sinkFactory = { error("No native audio in library self-test") }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val presenter = ContinuousEditorPresenter(backend.studio, scope, DesktopEditorPorts(backend) { null })
        val saved: Project
        suspend fun ready() = withTimeout(10_000) {
            while (backend.studio.work.value.jobId != null || backend.studio.work.value.preparationId != null) delay(5)
        }
        try {
            val candidate = YoutubeSource("abcdefghijk", "Online original", "Synthetic provider", 1.0)
            var downloads = 0
            val provider = object : YoutubeSourceBackend {
                override fun search(query: String, jobId: String) = listOf(candidate)
                override fun info(url: String, jobId: String) = candidate
                override fun cancel(jobId: String) {}
                override fun download(source: YoutubeSource, folder: java.io.File, jobId: String, progress: (Float) -> Unit): java.io.File {
                    downloads++
                    return folder.resolve("audio.wav").also { Files.copy(input, it.toPath()); progress(100f) }
                }
            }
            val selected = NextOnline(directory.resolve("library"), backend::validateLibraryFile, provider).use { online ->
                check(online.search(candidate.url)); idle(online)
                check(online.state.value.candidates == listOf(candidate) && downloads == 0 && online.state.value.selection == null)
                check(online.acquire(candidate.id)); idle(online)
                check(online.state.value.status == NextOnline.Status.SELECTED && downloads == 1)
                requireNotNull(online.state.value.selection).also {
                    check(it.title == candidate.title && Files.readAllBytes(it.path).contentEquals(Files.readAllBytes(input)))
                }
            }
            val before = backend.studio.document.value.project
            check(backend.studio.dispatch(Action.Import(backend.files.registerNamed(selected.path, selected.title, selected.hash))).accepted)
            ready()
            val original = backend.studio.document.value.project
            check(original.assets.single().name == selected.title && original.source?.assetHash == selected.hash)
            check(presenter.dispatch(ContinuousEditorAction.Undo)); check(backend.studio.document.value.project == before)
            check(presenter.dispatch(ContinuousEditorAction.Redo)); check(backend.studio.document.value.project == original)
            check(presenter.dispatch(ContinuousEditorAction.AssignSourceRange(0)))
            check(presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            val output = directory.resolve("online-song.wav")
            check(backend.studio.dispatch(Action.Export(ExportRequest(backend.files.register(output), 48_000, bits = 24), PlaybackTarget.Arrangement())).accepted)
            ready()
            val audio = Files.newInputStream(output).use { WavCodec.read(it) }
            check(audio.info.bits == 24 && audio.info.frames == 48_000L && audio.samples.any { kotlin.math.abs(it) > .05f })
            val archive = directory.resolve("online.choplab")
            check(backend.saveProject(archive).accepted); ready()
            ZipFile(archive.toFile()).use { zip ->
                check(zip.getInputStream(requireNotNull(zip.getEntry("assets/${selected.hash}.wav"))).use { it.readBytes() }
                    .contentEquals(Files.readAllBytes(input)))
            }
            saved = backend.studio.document.value.project
        } finally { presenter.close(); backend.shutdown(); scope.cancel() }
        NextBackend.create(profile, sinkFactory = { error("No native audio in library self-test") }, microphone = { null }).use { reopened ->
            check(reopened.studio.document.value.project == saved)
            check(reopened.assets.read(saved.assets.single()).contentEquals(Files.readAllBytes(input)))
        }
    }
}
