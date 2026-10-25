package com.choplab.apple

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import platform.Foundation.NSProcessInfo
import kotlin.math.PI
import kotlin.math.sin

internal fun appAcceptanceMode(): AppAcceptanceMode? {
    val env = NSProcessInfo.processInfo.environment
    return AppAcceptanceMode.parse(env["CHOPLAB_APP_ACCEPTANCE_PROFILE"] as? String,
        env["CHOPLAB_APP_ACCEPTANCE_PHASE"] as? String, env["CHOPLAB_SIMULATOR_SMOKE_TOKEN"] as? String)
}

/** Runs on the existing construction worker, before ownership is handed to the UI. No user profile is opened. */
internal fun createAppBackend(): IosBackend {
    val mode = appAcceptanceMode() ?: return IosBackend.create()
    return runBlocking {
        run {
            val root = IosPaths.applicationSupport() + "/ChopLab/acceptance-" + mode.profile
            ensureDirectory(root)
            val host = IosBackend.create("$root/profile")
            var stage = "output"
            try {
                withTimeout(60_000) { runAppAcceptance(host, mode, root) { stage = it } }
                host
            } catch (error: Throwable) {
                withContext(NonCancellable) {
                    try { withTimeout(15_000) { host.shutdown() } } finally {
                        writeFileAtomically(IosPaths.documents() + "/.simulator-smoke-error.json", buildJsonObject {
                            put("token", mode.token); put("phase", mode.phase)
                            put("stage", stage); put("errorType", error::class.simpleName ?: "Unknown")
                            put("failure", "studio-acceptance-failed")
                        }.toString().encodeToByteArray())
                    }
                }
                throw error
            }
        }
    }
}

private suspend fun runAppAcceptance(host: IosBackend, mode: AppAcceptanceMode, root: String, stage: (String) -> Unit) {
    val studio = host.studio
    val status = withTimeout(15_000) { host.engine.status.first { it.phase != DriverPhase.STARTING } }
    check(status.phase == DriverPhase.ATTACHED && host.engine.lastOutputCapability()?.stage == AudioOutputStage.READY)
    val sourcePath = "$root/fixture.wav"
    val savedPath = "$root/fixture.choplab"
    if (mode.phase == "first") {
        stage("import")
        // A fresh random run ID is mandatory. Existing data is preserved rather than replaced.
        check(studio.document.value.project.assets.isEmpty() && regularFileSize(sourcePath) == null && regularFileSize(savedPath) == null)
        val fixture = ArrayOutput()
        IosWav.FloatWriter(fixture, 96_000).apply {
            write(FloatArray(192_000) { i ->
                val wave = sin(2 * PI * 440 * (i / 2) / 48_000).toFloat()
                if (i % 2 == 0) wave * .3f else wave * -.15f
            }); finish()
        }
        FileWriter(sourcePath).use { it.write(fixture.toByteArray()); it.syncAndClose() }
        completeAppOperation(studio, Action.Import(host.files.register(HostFile(sourcePath, "fixture.wav"))), Operation.IMPORT)
        check(studio.dispatch(Action.Edit(Intent.Rename("App acceptance fixture"))).accepted)
        check(studio.dispatch(Action.Edit(Intent.EqualChop(4))).accepted)
        check(studio.dispatch(Action.Edit(Intent.AssignSlice(0, 0))).accepted)
        val asset = studio.document.value.project.assets.single()
        val track = Track("song", "Song", TrackKind.BANK)
        val clip = Clip("part", track.id, asset.hash, FrameRange(0, asset.frames))
        check(studio.dispatch(Action.Edit(Intent.SetArrangement(frozenListOf(track), frozenListOf(clip), frozenListOf()))).accepted)
        stage("archive")
        completeAppOperation(studio, Action.Save(host.files.register(HostFile(savedPath, "fixture.choplab"))), Operation.SAVE)
        val saved = studio.document.value.project
        completeAppOperation(studio, Action.Open(host.files.register(HostFile(savedPath, "fixture.choplab"))), Operation.OPEN)
        check(studio.document.value.project == saved)
    } else {
        stage("restore")
        // This branch starts in a new app process and must restore the first process's autosave exactly.
        check(studio.document.value.project.title == "App acceptance fixture")
        check(!studio.document.value.canUndo)
        val restored = studio.document.value.project
        completeAppOperation(studio, Action.Open(host.files.register(HostFile(savedPath, "fixture.choplab"))), Operation.OPEN)
        check(studio.document.value.project == restored)
        check(studio.dispatch(Action.Edit(Intent.Rename("New edit after app restart"))).accepted)
        check(studio.dispatch(Action.Undo).accepted && studio.document.value.project == restored)
        check(studio.dispatch(Action.Redo).accepted && studio.document.value.project.title == "New edit after app restart")
        check(studio.dispatch(Action.Undo).accepted && studio.document.value.project == restored)
    }
    stage("original-bytes")
    val original = readFile(sourcePath, 2L * 1024 * 1024)
    val asset = studio.document.value.project.assets.single()
    check(asset.hash == sha256(original) && host.assets.read(asset).contentEquals(original))
    stage("playback")
    check(studio.dispatch(Action.SelectPlaybackTarget(PlaybackTarget.Arrangement())).accepted)
    val before = requireNotNull(host.engine.outputConsumedFrames())
    check(studio.dispatch(Action.Play).accepted)
    withTimeout(5_000) { while ((host.engine.outputConsumedFrames() ?: 0) <= before + 1024) delay(10) }
    check(host.engine.snapshot().playing && host.engine.snapshot().sequenceFrame > 0)
    check(host.engine.health().measuredBlocks > 0)
    check(host.audition.play(asset))
    check(host.audition.scratchStart(asset, 1000, 0, asset.frames))
    check(host.audition.scratchTo(2400.0, 512))
    check(host.audition.scratchEnd())
    check(host.engine.originalPlayback().playing && host.engine.snapshot().playing)
    check(host.audition.pause())
    check(studio.dispatch(Action.Stop).accepted)
    stage("export")
    val exported = "$root/${mode.phase}.wav"
    completeAppOperation(studio, Action.Export(ExportRequest(host.files.register(HostFile(exported, "export.wav")),
        96_000, bits = 24, tailMode = ExportTailMode.EXACT), PlaybackTarget.Arrangement()), Operation.EXPORT)
    val wav = FileReader(exported).use { IosWav.read(it.asInput()) }
    check(wav.info.bits == 24 && wav.info.frames == 96_000L)
    check(wav.samples.filterIndexed { i, _ -> i % 2 == 0 }.any { it > .01f })
    check(wav.samples.filterIndexed { i, _ -> i % 2 == 1 }.any { it < -.01f })
    stage("autosave")
    host.flushAutosave()
    check(!host.persistenceFailure.value)
    val report = buildJsonObject {
        put("phase", mode.phase); put("originalHash", asset.hash); put("originalBytesPreserved", true)
        put("songSourceHandIndependent", true); put("consumedFramesDelta", requireNotNull(host.engine.outputConsumedFrames()) - before)
        put("exportBits", wav.info.bits); put("exportFrames", wav.info.frames)
        put("autosaveFlushed", true); put("archiveVerified", true); put("newUndoRedoAfterRestart", mode.phase == "relaunch")
        put("livePlayback", "verified-real-output"); put("physicalSoundVerified", false)
    }
    writeFileAtomically("$root/acceptance-result.json", report.toString().encodeToByteArray())
}

internal fun appAcceptanceReport(): JsonObject? {
    val mode = appAcceptanceMode() ?: return null
    val root = IosPaths.applicationSupport() + "/ChopLab/acceptance-" + mode.profile
    return Json.parseToJsonElement(readFile("$root/acceptance-result.json", 16L * 1024).decodeToString()).jsonObject
}

private suspend fun completeAppOperation(studio: Studio, action: Action, operation: Operation) = coroutineScope {
    val result = async(start = CoroutineStart.UNDISPATCHED) {
        studio.notices.first { it == Notice.Completed(operation) || it == Notice.Failed(operation) || it == Notice.Cancelled(operation) }
    }
    check(studio.dispatch(action).accepted)
    check(result.await() == Notice.Completed(operation))
}
