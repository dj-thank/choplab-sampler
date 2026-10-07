package com.choplab.desktop.next

import com.choplab.core.ExportRequest
import com.choplab.core.StemExportRequest
import com.choplab.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Packaged Java entry: real isolated SAVE/WAV/STEM transactions, with a recorded browser boundary. */
object NextOutputRevealSelfTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 1)
        val directory = Path.of(args.single()); Files.createDirectories(directory)
        val profile = directory.resolve("profile"); check(!Files.exists(profile))
        val source = directory.resolve("Example.wav").also(NextSelfTest::writeDemo)
        val originalBytes = Files.readAllBytes(source)
        val shown = java.util.concurrent.CopyOnWriteArrayList<Path>()
        var browserWorks = true
        val revealer = NextOutputRevealer(available = { true }, browse = { file ->
            check(browserWorks) { "Synthetic browser failure" }; shown.add(file); true
        })
        val backend = NextBackend.create(profile, sinkFactory = { error("Audio must stay closed") }, microphone = { null })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var save: Path? = directory.resolve("Example.choplab")
        val wav = directory.resolve("Song.wav")
        val stems = directory.resolve("Parts.zip")
        val native = DesktopEditorPorts(backend, outputRevealer = revealer) { null }
        val ports = object : ContinuousEditorPorts by native {
            override suspend fun chooseSave() = save?.let(backend.files::register)
            override suspend fun chooseExport(frames: Long) = ExportRequest(backend.files.register(wav), Math.toIntExact(frames))
            override suspend fun chooseStems(frames: Long) = StemExportRequest(backend.files.register(stems), Math.toIntExact(frames))
        }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        suspend fun until(predicate: (ContinuousEditorState) -> Boolean) = withTimeout(20_000) { presenter.state.first(predicate) }
        try {
            check(presenter.dispatch(ContinuousEditorAction.ImportAudioFile(backend.files.register(source))))
            until { it.original != null }
            check(presenter.dispatch(ContinuousEditorAction.AssignSourceRange(0)))
            check(presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            val before = backend.studio.document.value
            for ((action, file) in listOf(ContinuousEditorAction.SaveProject to requireNotNull(save),
                    ContinuousEditorAction.ExportWav to wav, ContinuousEditorAction.ExportStems to stems)) {
                check(presenter.dispatch(action))
                until { it.completedOutputName == file.fileName.toString() }
                check(Files.isRegularFile(file))
                check(presenter.dispatch(ContinuousEditorAction.RevealCompletedOutput))
                check(shown.last() == file)
                check(backend.studio.document.value.project == before.project)
                check(backend.studio.document.value.revision == before.revision)
                check(backend.studio.document.value.canUndo == before.canUndo)
            }
            browserWorks = false
            check(!presenter.dispatch(ContinuousEditorAction.RevealCompletedOutput))
            until { it.status == ContinuousStatus.OUTPUT_UNAVAILABLE }
            browserWorks = true
            Files.delete(stems)
            check(!presenter.dispatch(ContinuousEditorAction.RevealCompletedOutput))
            check(shown.size == 3)
            save = null
            check(!presenter.dispatch(ContinuousEditorAction.SaveProject))
            until { it.status == ContinuousStatus.CANCELLED }
            save = directory.resolve("blocked").also { Files.writeString(it, "keep") }.resolve("failed.choplab")
            check(presenter.dispatch(ContinuousEditorAction.SaveProject))
            until { it.status == ContinuousStatus.SAVE_FAILED }
            check(presenter.state.value.completedOutputName == null)
            check(!presenter.dispatch(ContinuousEditorAction.RevealCompletedOutput))
            check(backend.studio.document.value.project == before.project)
            check(Files.readAllBytes(source).contentEquals(originalBytes))
            println("""{"status":"LOCAL_PASS","scope":"successful-output-receipts-and-reveal-routing","outputs":3,"failureCases":4,"projectUnchanged":true,"originalBytesPreserved":true,"nativeBrowserOpened":false,"audioStarted":false}""")
        } finally { presenter.close(); native.close(); backend.shutdown(); scope.cancel() }
    }
}
