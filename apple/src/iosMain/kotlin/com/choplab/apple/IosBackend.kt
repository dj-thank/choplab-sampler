package com.choplab.apple

import com.choplab.core.*
import com.choplab.core.model.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

/**
 * The iPadOS composition root, as the JVM hosts' EditorBackend: one Studio, one audio route, assets and three-slot
 * autosave under one profile directory in the app container. The autosave is recovered before anything starts.
 */
internal class IosBackend private constructor(
    val studio: Studio,
    val engine: IosEnginePort,
    val assets: IosAssetStore,
    val pcm: IosPcmPort,
    val rendering: IosRendering,
    val audition: IosSourceAudition,
    val files: IosLocations,
    val directory: String,
    private val autosave: IosAutosave,
    private val scope: CoroutineScope,
    /** Unreadable autosave files moved aside at startup, kept for diagnosis; null when recovery succeeded. */
    val preservedUnreadableAutosave: String?,
) {
    private val persistenceFailed = MutableStateFlow(false)
    val persistenceFailure: StateFlow<Boolean> = persistenceFailed.asStateFlow()

    init {
        scope.launch(Dispatchers.Default) {
            studio.document.map { it.revision to it.project }.distinctUntilChanged().collectLatest { (revision, project) ->
                delay(300)
                save(project, revision)
            }
        }
        // The original is heard at the document's song key however the document changed.
        scope.launch {
            studio.document.map { it.project.source?.pitchSemitones ?: 0.0 }.distinctUntilChanged().collect { semitones ->
                audition.pitch(semitones.toFloat())
            }
        }
    }

    private suspend fun save(project: Project, revision: Long) = withContext(Dispatchers.Default) {
        try { autosave.save(project, revision); persistenceFailed.value = false }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { persistenceFailed.value = true }
    }

    suspend fun flushAutosave() { val document = studio.document.value; save(document.project, document.revision) }

    suspend fun shutdown(flush: Boolean = true) {
        try { if (flush) flushAutosave(); studio.dispatch(Action.Stop); studio.dispatch(Action.Close) }
        finally {
            audition.close()
            try { engine.close() } finally { pcm.close(); scope.cancel() }
        }
    }

    companion object {
        fun create(directory: String = IosPaths.applicationSupport() + "/ChopLab/next-v10",
                   openOutput: () -> IosAudioOutput? = { IosAudioOutput.open() }): IosBackend {
            ensureDirectory(directory)
            val assets = IosAssetStore("$directory/assets")
            var autosave = IosAutosave("$directory/autosave", assets)
            var recovered = autosave.recover()
            var preserved: String? = null
            if (recovered == null && autosave.hasSavedDocument()) {
                // Never discard what could not be read: move it aside intact and start a new document.
                preserved = "$directory/autosave-unreadable-${NSDate().timeIntervalSince1970.toLong()}"
                renameReplacing(autosave.directory, preserved)
                autosave = IosAutosave("$directory/autosave", assets)
                recovered = null
            }
            val pcm = IosPcmPort(assets)
            val compiler = ProgramCompiler(pcm)
            val engine = IosEnginePort(compiler, openOutput)
            val files = IosLocations()
            val jobs = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val services = Services(assets, IosImportPort(assets, files), IosProjectPort(assets, files), IosExportPort(compiler, files), engine)
                val studio = Studio(jobs, services, recovered?.project ?: Project(), recovered?.revision ?: 0)
                return IosBackend(studio, engine, assets, pcm, IosRendering(assets, pcm), IosSourceAudition(engine, pcm, jobs), files,
                    directory, autosave, jobs, preserved)
            } catch (failure: Throwable) {
                jobs.cancel()
                try { engine.close() } finally { pcm.close() }
                throw failure
            }
        }
    }
}
