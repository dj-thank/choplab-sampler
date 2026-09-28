package com.choplab.jvm.ai

import com.choplab.core.*
import com.choplab.core.ai.*
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.engine.*
import com.choplab.jvm.*
import kotlinx.coroutines.*
import java.io.*
import java.nio.file.*
import kotlin.math.*
import kotlin.test.*

class VocalTtsServiceTest {
    private class NativeRunner : SpeechProcessRunner {
        var synthesis = 0
        var closes = 0
        var silent = false
        var malformed = false
        var frames = 44_100
        var voiceList = "Local Ja ja_JP # voice\nLocal En en_US # voice"
        var denyScriptFile = false
        var gate: CompletableDeferred<Unit>? = null
        val commands = mutableListOf<List<String>>()
        override suspend fun run(arguments: List<String>, directory: Path): SpeechProcessResult {
            commands += arguments
            if (denyScriptFile && "-File" in arguments) return SpeechProcessResult(1, "UnauthorizedAccess".toByteArray())
            if (arguments.contains("?")) return SpeechProcessResult(0, voiceList.toByteArray())
            if ("powershell.exe" == arguments.first() && Files.readString(directory.resolve("request.json")).contains("\"voices\""))
                return SpeechProcessResult(0, "[{\"name\":\"Local Ja\",\"locale\":\"ja-JP\"}]".toByteArray())
            synthesis++
            gate?.await()
            val text = if (arguments.first() == "/usr/bin/say") Files.readString(directory.resolve("text.txt")) else Files.readString(directory.resolve("request.json"))
            assertTrue(text.isNotEmpty())
            val output = directory.resolve("speech.wav")
            if (malformed) Files.write(output, byteArrayOf(1, 2, 3)) else Files.newOutputStream(output).use { out ->
                val data = FloatArray(frames) { i -> if (silent || i < 1102 || i >= frames - 1102) 0f else (.18 * sin(2 * PI * (180 + synthesis * 20) * i / 22050)).toFloat() }
                WavCodec.writeFloat(out, data, 22_050, 1)
            }
            return SpeechProcessResult(0, byteArrayOf())
        }
        override fun close() { closes++ }
    }
    private fun project(): Project {
        val placement = LyricProposal("川", LyricLanguage.JAPANESE, frozenListOf(ProposalSection("一番", LyricSectionKind.VERSE, 1,
            frozenListOf(ProposalLine.create("川の歌", "かわのうた", LyricLanguage.JAPANESE))))).placeStructured(0, 4, "line")
        return Project(lyrics = placement.lines, lyricStructure = placement.structure, tempo = Tempo(120_000, 710))
    }
    private fun plan(project: Project) = assertIs<FlowResult.Ready>(FlowPlanner.plan(project, 0, FlowMode.ONE_BAR)).plan
    private fun provider(directory: Path, runner: NativeRunner, platform: DesktopSpeechPlatform = DesktopSpeechPlatform.MAC) =
        DesktopTtsProvider(directory, platform, runner, "fixture-os", "fixture-voice-version")

    @Test fun nativeAdapterThroughAssetsStudioUndoEngineExportArchiveAndAutosave() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("vocal-tts-flow-")
        val files = mutableMapOf<String, Path>()
        val backend = EditorBackend.create(directory.resolve("profile"), { StreamingEnginePort(it, { error("No real device") }) }, { assets, compiler ->
            HostFileServices(WavImportPort(assets, { files.getValue(it.handle) }), FileProjectPort(assets, { files.getValue(it.handle) }), WavExportPort(compiler, { files.getValue(it.handle) }))
        })
        val native = NativeRunner(); val provider = provider(directory.resolve("native"), native)
        val service = VocalTtsService(provider, TtsCache(directory.resolve("cache")), backend.assets)
        try {
            val before = project()
            assertTrue(backend.studio.dispatch(Action.New(before)).accepted)
            val revision = backend.studio.document.value.revision
            val voice = assertIs<TtsResult.Success<FrozenList<TtsVoice>>>(service.voices()).value.first()
            val plan = plan(before)
            val entry = assertIs<TtsResult.Success<PreparedVocalLine>>(service.prepare(plan.rows.single(), before.tempo, voice, TtsSettings())).value
            assertEquals(96_000L, entry.asset.frames)
            assertTrue(entry.trimmedFrames > 0)
            assertTrue(entry.line.words.all { it.timingOrigin == WordTimingOrigin.ESTIMATED })
            assertEquals("川の歌", entry.line.words.joinToString("") { it.text })
            assertEquals(before, backend.studio.document.value.project, "Preparing files never commits a document")
            val intent = assertIs<TtsResult.Success<Intent.ApplyVocalGuide>>(plan.guideEdit(before, listOf(entry), "guide")).value
            assertTrue(backend.studio.dispatch(Action.Edit(intent, expectedRevision = revision)).accepted)
            val applied = backend.studio.document.value.project
            assertEquals(TrackKind.GUIDE, applied.tracks.single().kind)
            assertEquals(before.lyricStructure, applied.lyricStructure)
            assertTrue(backend.studio.dispatch(Action.Undo).accepted)
            assertEquals(before, backend.studio.document.value.project)
            assertTrue(backend.studio.dispatch(Action.Redo).accepted)
            assertEquals(applied, backend.studio.document.value.project)
            assertFalse(backend.studio.dispatch(Action.Edit(intent, expectedRevision = revision)).accepted)
            val wav = directory.resolve("song.wav"); files["out"] = wav
            assertTrue(backend.studio.dispatch(Action.SelectPlaybackTarget(PlaybackTarget.Arrangement())).accepted)
            assertTrue(backend.studio.dispatch(Action.Export(ExportRequest(Location("out"), 96_000, tailFrames = 0, bits = 24))).accepted)
            withTimeout(10_000) { while (backend.studio.work.value.jobId != null) delay(5) }
            val exported = Files.newInputStream(wav).use(WavCodec::read)
            assertEquals(24, exported.info.bits)
            assertTrue(exported.samples.any { abs(it) > .01f })
            assertEquals(96_000L, exported.info.frames)
            val archive = ByteArrayOutputStream().also { ArchiveCodec().write(applied, backend.assets, it) }.toByteArray()
            val fresh = FileAssetStore(directory.resolve("fresh"))
            assertEquals(applied, ArchiveCodec().read(ByteArrayInputStream(archive), fresh))
            assertContentEquals(backend.assets.read(entry.asset), fresh.read(entry.asset))
            backend.flushAutosave()
            assertEquals(applied, AutosaveStore(directory.resolve("profile/autosave"), fresh).recover()!!.project)
            assertEquals(1, native.synthesis)
        } finally { service.close(); service.close(); backend.shutdown(); directory.toFile().deleteRecursively() }
        assertEquals(1, native.closes)
    }

    @Test fun rawAndProcessedCachesSeparateAllSettingsTempoAndRegeneratedContent() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("tts-cache-")
        val native = NativeRunner(); val provider = provider(directory.resolve("native"), native)
        val service = VocalTtsService(provider, TtsCache(directory.resolve("cache")), FileAssetStore(directory.resolve("assets")))
        try {
            val voice = assertIs<TtsResult.Success<FrozenList<TtsVoice>>>(service.voices()).value.first()
            val row = plan(project()).rows.single()
            suspend fun prepare(tempo: Tempo = Tempo(), regenerate: Boolean = false) = assertIs<TtsResult.Success<PreparedVocalLine>>(service.prepare(row, tempo, voice, TtsSettings(), regenerate)).value
            val first = prepare()
            val cached = prepare()
            assertTrue(cached.rawCacheHit && cached.processedCacheHit)
            assertEquals(first.asset, cached.asset)
            val slower = prepare(Tempo(100_000, 710))
            assertTrue(slower.rawCacheHit); assertFalse(slower.processedCacheHit)
            val regenerated = prepare(regenerate = true)
            assertFalse(regenerated.rawCacheHit || regenerated.processedCacheHit)
            assertNotEquals(first.asset.hash, regenerated.asset.hash)
            assertNotEquals(slower.asset.hash, prepare(Tempo(100_000, 710)).asset.hash, "A new native result invalidates every BPM's processed cache")
            assertEquals(2, native.synthesis)
            val request = TtsRequest("川", "かわ", voice)
            val variants = listOf(request.copy(text = "河"), request.copy(reading = "かあわ"), request.copy(voice = voice.copy(version = "next")),
                request.copy(voice = voice.copy(id = "another")), request.copy(voice = voice.copy(locale = "ja-Other")),
                request.copy(voice = voice.copy(engine = voice.engine.copy(provider = "other"))),
                request.copy(voice = voice.copy(engine = voice.engine.copy(providerVersion = "next"))),
                request.copy(voice = voice.copy(engine = voice.engine.copy(model = "other"))),
                request.copy(voice = voice.copy(engine = voice.engine.copy(modelVersion = "next"))),
                request.copy(settings = TtsSettings(ratePermille = 900)), request.copy(settings = TtsSettings(pitchPermille = 1100)),
                request.copy(settings = TtsSettings(volumePermille = 800)), request.copy(settings = TtsSettings(style = "soft")))
            assertTrue(variants.map(TtsCache::rawKey).none { it == TtsCache.rawKey(request) })
            assertEquals(variants.size, variants.map(TtsCache::rawKey).distinct().size)
        } finally { service.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun cannotFitSilenceBadContainersCancellationAndUnsupportedSettingsStayTypedAndClean() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("tts-rejections-")
        val native = NativeRunner(); val provider = provider(directory.resolve("native"), native)
        val service = VocalTtsService(provider, TtsCache(directory.resolve("cache")), FileAssetStore(directory.resolve("assets")))
        try {
            val voice = assertIs<TtsResult.Success<FrozenList<TtsVoice>>>(service.voices()).value.first()
            val row = plan(project()).rows.single()
            native.frames = 220_500
            val fit = assertIs<TtsResult.Failure>(service.prepare(row, Tempo(), voice, TtsSettings(), true)).failure
            assertEquals(TtsProblem.CANNOT_FIT, fit.problem); assertTrue(fit.speedRequired!! > 1.6)
            native.frames = 44_100; native.silent = true
            assertEquals(TtsProblem.SILENT_AUDIO, assertIs<TtsResult.Failure>(service.prepare(row, Tempo(), voice, TtsSettings(), true)).failure.problem)
            native.silent = false; native.malformed = true
            assertEquals(TtsProblem.INVALID_AUDIO, assertIs<TtsResult.Failure>(service.prepare(row, Tempo(), voice, TtsSettings(), true)).failure.problem)
            native.malformed = false
            assertEquals(TtsProblem.UNSUPPORTED_SETTINGS, assertIs<TtsResult.Failure>(provider.synthesize(TtsRequest("川", "かわ", voice, TtsSettings(pitchPermille = 1200)))).failure.problem)
            native.gate = CompletableDeferred()
            val pending = async { service.prepare(row, Tempo(), voice, TtsSettings(), true) }
            withTimeout(3000) { while (native.synthesis < 4) delay(5) }
            pending.cancelAndJoin()
            assertEquals(0L, Files.list(directory.resolve("native")).use { it.count() })
            assertEquals(0L, Files.list(directory.resolve("assets")).use { it.count() })
        } finally { native.gate?.complete(Unit); service.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun windowsAdapterUsesPrivateJsonPlainTextAndActualWavMetadata() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("tts-windows-"); val native = NativeRunner().apply { denyScriptFile = true }
        val provider = provider(directory, native, DesktopSpeechPlatform.WINDOWS)
        try {
            val voice = assertIs<TtsResult.Success<FrozenList<TtsVoice>>>(provider.voices()).value.single()
            val audio = assertIs<TtsResult.Success<TtsAudio>>(provider.synthesize(TtsRequest("川", "かわ", voice))).value
            assertEquals(22_050, audio.sampleRate, "Decode the container, never assume the requested format was returned")
            assertEquals(1, audio.channels)
            audio.close()
            assertTrue(native.commands.all { "-NoProfile" in it && "-Command" in it && "-File" !in it &&
                "-ExecutionPolicy" !in it && it.none { arg -> "かわ" in arg || "Bypass" in arg || directory.toString() in arg } })
            assertEquals(1, native.commands.map { it.last() }.distinct().size, "Enumeration and synthesis run identical fixed code")
            assertEquals(0L, Files.list(directory).use { it.count() })
        } finally { provider.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun macEnumerationPreservesTheExactInstalledVoiceNameAsOneArgument() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("tts-mac-voice-")
        val native = NativeRunner().apply {
            voiceList = "Local Ja (日本語（日本）) ja_JP # voice\nLocal Ja (日本語（日本）) ja_JP # duplicate\nLocal En en_US # voice"
        }
        val provider = provider(directory, native)
        try {
            val voices = assertIs<TtsResult.Success<FrozenList<TtsVoice>>>(provider.voices()).value
            assertEquals(2, voices.size)
            val japanese = voices.single { it.language == LyricLanguage.JAPANESE }
            assertEquals("Local Ja (日本語（日本）)", japanese.id)
            val audio = assertIs<TtsResult.Success<TtsAudio>>(provider.synthesize(TtsRequest("川", "かわ", japanese))).value
            val command = native.commands.last()
            assertEquals(japanese.id, command[command.indexOf("-v") + 1])
            assertFalse(command.contains("かわ"), "Text is passed in its private file, not as shell syntax")
            assertEquals(22_050, audio.sampleRate)
            assertEquals(1, audio.channels)
            audio.close()
            assertEquals(0L, Files.list(directory).use { it.count() })
        } finally { provider.close(); directory.toFile().deleteRecursively() }
    }

    @Test fun returnedWordTimesStayReturnedOnlyForAnUnwarpedUnclippedSignal() = runBlocking<Unit> {
        val voice = TtsVoice(TtsEngine("test", "1", "test", "1"), "voice", "Voice", "en-US", "1", LyricLanguage.ENGLISH)
        val samples = FloatArray(48_000 * 2) { (.1 * cos(2 * PI * 220 * (it / 2) / 48_000)).toFloat() }
        val audio = TtsAudio.fromPcm(samples, 48_000, 2, listOf(TtsWord("A ", 0, 24_000, WordTimingOrigin.RETURNED), TtsWord("word", 24_000, 48_000, WordTimingOrigin.RETURNED)))
        val request = TtsRequest("A word", "", voice)
        val same = assertIs<TtsResult.Success<CachedSpeech>>(VocalGuideProcessor.fit(audio, request, 48_000) {}).value
        assertTrue(same.audio.words.all { it.origin == WordTimingOrigin.RETURNED })
        val warped = assertIs<TtsResult.Success<CachedSpeech>>(VocalGuideProcessor.fit(audio, request, 60_000) {}).value
        assertTrue(warped.audio.words.all { it.origin == WordTimingOrigin.ESTIMATED })
        audio.close(); same.audio.close(); warped.audio.close()
    }
}
