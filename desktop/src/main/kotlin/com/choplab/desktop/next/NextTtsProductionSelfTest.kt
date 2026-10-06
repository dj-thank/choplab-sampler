package com.choplab.desktop.next

import com.choplab.core.*
import com.choplab.core.ai.*
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import com.choplab.jvm.*
import com.choplab.jvm.ai.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.system.exitProcess

/** Opt-in, silent native speech acceptance. No voice download, audio endpoint, user text or provider. */
object NextTtsProductionSelfTest {
    @JvmStatic fun main(args: Array<String>) {
        require(args.contentEquals(arrayOf("--choplab-offline-tts-fixture"))) { "Explicit offline TTS fixture flag required" }
        val directory = Files.createTempDirectory("choplab-tts-production-")
        var unavailable = false
        try {
            runBlocking {
                check(PcmMemoryBudget.shared.statistics().usedBytes == 0L) { "Run acceptance in its own JVM" }
                for (language in listOf(LyricLanguage.JAPANESE, LyricLanguage.ENGLISH)) {
                    val result = exercise(directory.resolve(language.name.lowercase()), language)
                    println(result)
                    unavailable = unavailable || "\"status\":\"UNAVAILABLE\"" in result
                }
            }
        } finally { check(directory.toFile().deleteRecursively()) { "Owned fixture cleanup failed" } }
        if (unavailable) exitProcess(2)
    }

    private suspend fun exercise(directory: Path, language: LyricLanguage): String {
        Files.createDirectories(directory)
        val provider = DesktopTtsProvider(directory.resolve("native"))
        val outputs = mutableMapOf<String, Path>()
        val backend = EditorBackend.create(directory.resolve("profile"),
            { StreamingEnginePort(it, { error("No audio endpoint in TTS acceptance") }) }, { assets, compiler ->
                HostFileServices(WavImportPort(assets, { outputs.getValue(it.handle) }),
                    FileProjectPort(assets, { outputs.getValue(it.handle) }), WavExportPort(compiler, { outputs.getValue(it.handle) }))
            })
        val service = VocalTtsService(provider, TtsCache(directory.resolve("cache")), backend.assets)
        // The live backend owns its bounded output workspace until shutdown. TTS must return
        // exactly to this baseline; the existing post-shutdown check still requires total zero.
        val backendRetainedBytes = PcmMemoryBudget.shared.statistics().usedBytes
        var outcome: String
        try {
            val voices = when (val result = service.voices()) {
                is TtsResult.Success -> result.value
                is TtsResult.Failure -> return unavailable(language, result.failure.problem)
            }
            val voice = voices.firstOrNull { it.language == language && it.offline }
                ?: return unavailable(language, TtsProblem.NO_OFFLINE_VOICE)
            val source = syntheticOriginal(directory, backend.assets)
            val originalBytes = backend.assets.read(source)
            val text = if (language == LyricLanguage.JAPANESE) "川の音に合わせて歌います" else "We sing beside the quiet river"
            val reading = if (language == LyricLanguage.JAPANESE) "かわのおとにあわせてうたいます" else ""
            val placement = LyricProposal("Offline fixture", language, frozenListOf(ProposalSection("Verse", LyricSectionKind.VERSE, 1,
                frozenListOf(ProposalLine.create(text, reading, language))))).placeStructured(0, 4, "line")
            var before = Project(assets = frozenListOf(source), source = Source(source.hash, FrameRange(0, source.frames)),
                lyrics = placement.lines, lyricStructure = placement.structure)
            check(backend.studio.dispatch(Action.New(before)).accepted)
            val plan = (FlowPlanner.plan(before, 0, FlowMode.ONE_BAR) as FlowResult.Ready).plan
            var preparation = service.prepare(plan.rows.single(), before.tempo, voice, TtsSettings(), false)
            check(backend.studio.document.value.project == before)
            // An installed voice can speak at a different natural rate. Choose the fixture's musical tempo
            // explicitly from the typed refusal, retaining the production 0.6–1.6 / 40–240 BPM limits.
            if (preparation is TtsResult.Failure && preparation.failure.problem == TtsProblem.CANNOT_FIT) {
                val speed = requireNotNull(preparation.failure.speedRequired)
                val tempo = (before.tempo.milliBpm / speed).roundToInt().coerceIn(40_000, 240_000)
                before = before.copy(tempo = Tempo(tempo))
                check(backend.studio.dispatch(Action.New(before)).accepted)
                preparation = service.prepare(plan.rows.single(), before.tempo, voice, TtsSettings(), false)
            }
            val prepared = success(preparation)
            check(PcmMemoryBudget.shared.statistics().usedBytes == backendRetainedBytes) { "TTS retained PCM beyond the live backend workspace" }
            val revision = backend.studio.document.value.revision
            check(backend.studio.document.value.project == before)
            val audio = backend.assets.read(prepared.asset).inputStream().use(WavCodec::read)
            check(audio.info.sampleRate == 48_000 && audio.info.channels == 2 && audio.info.floatingPoint)
            check(audio.samples.all { it.isFinite() } && audio.samples.any { abs(it) > .00001f })
            check(prepared.speed in .6..1.6 && prepared.line.words.all { it.timingOrigin == WordTimingOrigin.ESTIMATED })
            check(prepared.line.words.joinToString("") { it.text } == text)

            // Cancel after real native PCM returns, before the service can cache/publish it. The forwarding
            // observer changes only job state; it never substitutes speech, settings or returned PCM.
            val storedBeforeCancel = backend.assets.storedBytes()
            var nativeReturned = false
            val cancelProvider = object : TtsProvider by provider {
                override suspend fun synthesize(request: TtsRequest): TtsResult<TtsAudio> {
                    val result = provider.synthesize(request)
                    if (result is TtsResult.Success) { nativeReturned = true; currentCoroutineContext().cancel() }
                    return result
                }
                override fun close() = Unit // Borrowed provider; the main service owns it.
            }
            val cancelled = VocalTtsService(cancelProvider, TtsCache(directory.resolve("cancel-cache")), backend.assets)
            try {
                supervisorScope {
                    val job = async { cancelled.prepare(plan.rows.single(), before.tempo, voice, TtsSettings(), true) }
                    val failure = runCatching { withTimeout(65_000) { job.await() } }.exceptionOrNull()
                    check(failure is CancellationException && failure !is TimeoutCancellationException && nativeReturned)
                }
            } finally { cancelled.close() }
            check(backend.assets.storedBytes() == storedBeforeCancel && backend.studio.document.value.project == before)
            check(PcmMemoryBudget.shared.statistics().usedBytes == backendRetainedBytes) { "TTS retained PCM beyond the live backend workspace" }

            val intent = success(plan.guideEdit(before, listOf(prepared), "guide"))
            check(backend.studio.dispatch(Action.Edit(intent, expectedRevision = revision)).accepted)
            val after = backend.studio.document.value.project
            check(backend.studio.document.value.revision == revision + 1 && after.source == before.source)
            check(after.tracks.single().kind == TrackKind.GUIDE && after.clips.single().range == FrameRange(0, prepared.asset.frames))
            check(backend.studio.dispatch(Action.Undo).accepted && backend.studio.document.value.project == before)
            check(backend.studio.dispatch(Action.Redo).accepted && backend.studio.document.value.project == after)
            check(!backend.studio.dispatch(Action.Edit(intent, expectedRevision = revision)).accepted)
            val output = directory.resolve("guide.wav"); outputs["export"] = output
            check(backend.studio.dispatch(Action.Export(ExportRequest(Location("export"), prepared.asset.frames.toInt(), tailFrames = 0, bits = 24),
                PlaybackTarget.Arrangement())).accepted)
            withTimeout(15_000) { while (backend.studio.work.value.jobId != null) delay(5) }
            val exported = Files.newInputStream(output).use(WavCodec::read)
            check(exported.info.bits == 24 && exported.info.frames == prepared.asset.frames && exported.info.channels == 2)
            check(exported.samples.all { it.isFinite() } && exported.samples.any { abs(it) > .00001f })
            val archive = ByteArrayOutputStream().also { ArchiveCodec().write(after, backend.assets, it) }.toByteArray()
            val fresh = FileAssetStore(directory.resolve("fresh"))
            check(ArchiveCodec().read(ByteArrayInputStream(archive), fresh) == after)
            after.assets.forEach { check(fresh.read(it).contentEquals(backend.assets.read(it))) }
            backend.flushAutosave()
            check(AutosaveStore(directory.resolve("profile/autosave"), fresh).recover()?.project == after)
            check(backend.assets.read(source).contentEquals(originalBytes))
            check(Files.list(directory.resolve("native")).use { it.count() } == 0L)
            outcome = """{"status":"LOCAL_PASS","scope":"desktop-offline-tts","language":"${language.name}","realNativeSpeech":true,"guideFrames":${prepared.asset.frames},"milliBpm":${before.tempo.milliBpm},"originalBytesExact":true,"explicitApply":true,"oneUndoRedo":true,"staleRejected":true,"cancelAtNativeReturn":true,"cancelPublishedAssets":0,"export24":true,"archiveReopen":true,"autosaveReopen":true,"humanAcceptance":false}"""
        } finally {
            try { service.close() } finally { backend.shutdown() }
            withTimeout(10_000) { while (PcmMemoryBudget.shared.statistics().usedBytes != 0L) delay(5) }
        }
        val stats = PcmMemoryBudget.shared.statistics()
        check(stats.peakBytes <= stats.limitBytes)
        return outcome.dropLast(1) + ",\"pcmRetainedBytes\":0,\"pcmPeakBytes\":${stats.peakBytes},\"pcmLimitBytes\":${stats.limitBytes}}"
    }

    private fun unavailable(language: LyricLanguage, problem: TtsProblem): String {
        check(problem in setOf(TtsProblem.UNAVAILABLE, TtsProblem.NO_OFFLINE_VOICE)) { "Native voice enumeration failed: $problem" }
        return """{"status":"UNAVAILABLE","scope":"desktop-offline-tts","language":"${language.name}","problem":"${problem.name}","realNativeSpeech":false,"humanAcceptance":false}"""
    }

    private fun <T> success(result: TtsResult<T>): T = when (result) {
        is TtsResult.Success -> result.value
        is TtsResult.Failure -> error("Native TTS preparation failed: ${result.failure.problem}")
    }

    private fun syntheticOriginal(directory: Path, assets: FileAssetStore): Asset {
        val file = directory.resolve("synthetic-original.wav")
        Files.newOutputStream(file).use { out -> WavCodec.writePcm(out,
            FloatArray(24_000 * 2) { (.12 * sin((it / 2) * Math.PI * 2 * if (it % 2 == 0) 110.0 / 48_000 else 173.0 / 48_000)).toFloat() }, bits = 24, dither = false) }
        val bytes = Files.readAllBytes(file)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
        val asset = Asset(hash, "wav", bytes.size.toLong(), 48_000, 2, 24_000, "Synthetic original.wav")
        assets.adopt(asset, file)
        return asset
    }
}
