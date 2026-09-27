package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.model.Project
import com.choplab.jvm.WavCodec
import com.choplab.ui.*
import kotlinx.coroutines.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/** Isolated real library validation, explicit selection, editor production, archive and restart; no native devices. */
object NextLibrarySelfTest {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1)
        runBlocking { run(Path.of(args[0])) }
        println("""{"status":"LOCAL_PASS","scope":"library-source-production","originalBytesExact":true,"titlePreserved":true,"bundleRoundtrip":true,"undoRedo":true,"export24":true,"saveRestart":true,"nativeDialogs":false,"humanAcceptance":false}""")
    }
    suspend fun run(directory: Path) {
        Files.createDirectories(directory)
        val profile = directory.resolve("profile")
        check(!Files.exists(profile))
        val input = directory.resolve("Library original.wav")
        Files.newOutputStream(input).use { WavCodec.writePcm(it, FloatArray(48_000 * 2) {
            (kotlin.math.sin(it / 31.0) * if (it % 2 == 0) .25 else -.12).toFloat()
        }, bits = 24, dither = false) }
        suspend fun idle(library: NextLibrary) = withTimeout(10_000) { while (library.state.value.busy) delay(5) }
        val backend = NextBackend.create(profile, sinkFactory = { error("No native audio in library self-test") }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val presenter = ContinuousEditorPresenter(backend.studio, scope, DesktopEditorPorts(backend) { null })
        val saved: Project
        suspend fun ready() = withTimeout(10_000) {
            while (backend.studio.work.value.jobId != null || backend.studio.work.value.preparationId != null) delay(5)
        }
        try {
            val selected = NextLibrary(directory.resolve("library"), backend::validateLibraryFile).use { library ->
                idle(library); check(library.add(listOf(input))); idle(library)
                check(library.state.value.status == NextLibrary.Status.ADDED && library.state.value.selection == null)
                val item = library.state.value.items.single()
                check(library.select(item.id)); idle(library)
                val choice = requireNotNull(library.state.value.selection)
                check(choice.title == "Library original" && Files.readAllBytes(choice.path).contentEquals(Files.readAllBytes(input)))
                val bundle = directory.resolve("library.choplib")
                check(library.export(bundle)); idle(library)
                check(library.state.value.status == NextLibrary.Status.EXPORTED)
                NextLibrary(directory.resolve("restored-library"), backend::validateLibraryFile).use { restored ->
                    idle(restored); check(restored.add(listOf(bundle))); idle(restored)
                    check(restored.state.value.items.single().id == item.id)
                }
                choice
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
            val output = directory.resolve("library-song.wav")
            check(backend.studio.dispatch(Action.Export(ExportRequest(backend.files.register(output), 48_000, bits = 24), PlaybackTarget.Arrangement())).accepted)
            ready()
            val audio = Files.newInputStream(output).use { WavCodec.read(it) }
            check(audio.info.bits == 24 && audio.info.frames == 48_000L && audio.samples.any { kotlin.math.abs(it) > .05f })
            val archive = directory.resolve("library.choplab")
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
