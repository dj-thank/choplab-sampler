package com.choplab.desktop.next

import com.choplab.core.model.Project
import com.choplab.jvm.MicInput
import com.choplab.jvm.sha256
import com.choplab.ui.*
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Real file/Presenter/Studio transactions using isolated files and silent output, never a user's project. */
object NextDesktopFileSelfTest {
    @JvmStatic fun main(args: Array<String>) = runBlocking {
        require(args.size == 1)
        run(Path.of(args.single()))
        println("""{"status":"LOCAL_PASS","scope":"desktop-selected-file-production","originalBytesPreserved":true,"recordingRejected":true,"archiveReopened":true,"nativeAudio":false}""")
    }

    suspend fun run(directory: Path) {
        Files.createDirectories(directory)
        val input = directory.resolve("Dropped audio.WAV").also(NextSelfTest::writeDemo)
        val originalBytes = Files.readAllBytes(input)
        val hash = sha256(originalBytes)
        val profile = directory.resolve("profile")
        check(!Files.exists(profile))
        val microphone = object : MicInput {
            override val sampleRate = 48_000
            @Volatile var stopped = false
            override fun read(buffer: FloatArray): Int { while (!stopped) Thread.sleep(5); return -1 }
            override fun stop() { stopped = true }
            override fun close() { stopped = true }
        }
        val backend = NextBackend.create(profile, sinkFactory = { error("Silent file workflow") }, microphone = { microphone })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ports = DesktopEditorPorts(backend) { null }
        val presenter = ContinuousEditorPresenter(backend.studio, scope, ports)
        lateinit var saved: Project
        try {
            val original = backend.studio.document.value.project
            val drop = requireNotNull(nextDroppedFile(listOf(input.toFile())))
            check(!drop.project)
            check(presenter.dispatch(drop.action(backend.files)))
            idle(backend)
            withTimeout(5_000) { presenter.state.first { it.original != null } }
            val imported = backend.studio.document.value.project
            check(imported.source?.assetHash == hash)
            check(backend.assets.read(imported.assets.single()).contentEquals(originalBytes))
            check(presenter.dispatch(ContinuousEditorAction.Undo))
            check(backend.studio.document.value.project == original)
            check(presenter.dispatch(ContinuousEditorAction.Redo))
            check(backend.studio.document.value.project == imported)
            check(presenter.dispatch(ContinuousEditorAction.AssignSourceRange(0)))
            check(presenter.dispatch(ContinuousEditorAction.PlacePad(0, null, 0)))
            saved = backend.studio.document.value.project
            val archive = directory.resolve("Dropped project.choplab")
            check(backend.saveProject(archive).accepted); idle(backend)
            val projectDrop = requireNotNull(nextDroppedFile(listOf(archive.toFile())))
            check(projectDrop.project)

            // Files cannot replace the document or source while recording, including direct host dispatch.
            check(presenter.dispatch(ContinuousEditorAction.RecordSource))
            check(!presenter.dispatch(drop.action(backend.files)))
            check(!presenter.dispatch(projectDrop.action(backend.files)))
            check(backend.studio.document.value.project == saved)
            check(presenter.dispatch(ContinuousEditorAction.DiscardSourceRecording))
            check(presenter.dispatch(ContinuousEditorAction.Undo))
            check(backend.studio.document.value.project != saved)
            check(presenter.dispatch(projectDrop.action(backend.files))); idle(backend)
            check(backend.studio.document.value.project == saved)

            val broken = directory.resolve("Broken.wav").also { Files.writeString(it, "invalid audio fixture") }
            check(presenter.dispatch(requireNotNull(nextDroppedFile(listOf(broken.toFile()))).action(backend.files)))
            idle(backend)
            check(backend.studio.document.value.project == saved)
            check(backend.assets.read(saved.assets.single()).contentEquals(originalBytes))
            backend.flushAutosave()
        } finally { presenter.close(); ports.close(); backend.shutdown(); scope.cancel() }
        val reopened = NextBackend.create(profile, sinkFactory = { error("Silent reopen") }, microphone = { null })
        try {
            check(reopened.studio.document.value.project == saved)
            check(reopened.assets.read(saved.assets.single()).contentEquals(originalBytes))
        } finally { reopened.shutdown() }
        check(Files.readAllBytes(input).contentEquals(originalBytes))
    }

    private suspend fun idle(backend: NextBackend) = withTimeout(10_000) {
        while (backend.studio.work.value.jobId != null || backend.studio.work.value.preparationId != null) delay(5)
    }
}
