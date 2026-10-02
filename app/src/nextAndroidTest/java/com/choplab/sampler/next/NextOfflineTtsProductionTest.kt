package com.choplab.sampler.next

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.choplab.core.*
import com.choplab.core.ai.*
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import com.choplab.jvm.PcmMemoryBudget
import com.choplab.jvm.WavCodec
import com.choplab.jvm.ai.TtsCache
import com.choplab.jvm.ai.VocalTtsService
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class NextOfflineTtsProductionTest {
    @Test fun installedOfflineJapaneseVoiceThroughGuideAndArchive() = runBlocking { exercise(LyricLanguage.JAPANESE) }
    @Test fun installedOfflineEnglishVoiceThroughGuideAndArchive() = runBlocking { exercise(LyricLanguage.ENGLISH) }

    private suspend fun exercise(language: LyricLanguage) = OfflineRuntimeFixture.run("choplabOfflineTtsFixture", "android-offline-tts-${language.name.lowercase()}") { fixture ->
        val backend = fixture.backend
        val provider = AndroidTtsProvider(fixture.context)
        val service = VocalTtsService(provider, TtsCache(fixture.directory.resolve("tts-cache")), backend.assets)
        // The live backend owns its bounded output workspace until shutdown. TTS must return
        // exactly to this baseline; the existing post-shutdown check still requires total zero.
        val backendRetainedBytes = PcmMemoryBudget.shared.statistics().usedBytes
        val previousNativeScratch = fixture.context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("next-speech-") }.map { it.name }.toSet()
        try {
            val voices = when (val result = service.voices()) {
                is TtsResult.Success -> result.value
                is TtsResult.Failure -> {
                    check(result.failure.problem in setOf(TtsProblem.UNAVAILABLE, TtsProblem.NO_OFFLINE_VOICE)) { "Voice enumeration: ${result.failure.problem}" }
                    fixture.unavailable(result.failure.problem.name)
                }
            }
            // The production adapter excludes network-required and not-installed Android voices.
            val voice = voices.firstOrNull { it.language == language && it.offline }
                ?: fixture.unavailable(TtsProblem.NO_OFFLINE_VOICE.name)
            val original = fixture.original(); val originalBytes = backend.assets.read(original)
            val text = if (language == LyricLanguage.JAPANESE) "川の音に合わせて歌います" else "We sing beside the quiet river"
            val reading = if (language == LyricLanguage.JAPANESE) "かわのおとにあわせてうたいます" else ""
            val placement = LyricProposal("Offline fixture", language, frozenListOf(ProposalSection("Verse", LyricSectionKind.VERSE, 1,
                frozenListOf(ProposalLine.create(text, reading, language))))).placeStructured(0, 4, "line")
            var before = Project(assets = frozenListOf(original), source = Source(original.hash, FrameRange(0, original.frames)),
                lyrics = placement.lines, lyricStructure = placement.structure)
            check(backend.studio.dispatch(Action.New(before)).accepted)
            val plan = (FlowPlanner.plan(before, 0, FlowMode.ONE_BAR) as FlowResult.Ready).plan
            var result = service.prepare(plan.rows.single(), before.tempo, voice, TtsSettings(), false)
            check(backend.studio.document.value.project == before)
            if (result is TtsResult.Failure && result.failure.problem == TtsProblem.CANNOT_FIT) {
                val tempo = (before.tempo.milliBpm / requireNotNull(result.failure.speedRequired)).roundToInt().coerceIn(40_000, 240_000)
                before = before.copy(tempo = Tempo(tempo))
                check(backend.studio.dispatch(Action.New(before)).accepted)
                result = service.prepare(plan.rows.single(), before.tempo, voice, TtsSettings(), false)
            }
            val prepared = success(result); val revision = backend.studio.document.value.revision
            check(PcmMemoryBudget.shared.statistics().usedBytes == backendRetainedBytes) { "TTS retained PCM beyond the live backend workspace" }
            check(backend.studio.document.value.project == before)
            val audio = backend.assets.read(prepared.asset).inputStream().use(WavCodec::read)
            check(audio.info.sampleRate == 48_000 && audio.info.channels == 2 && audio.info.floatingPoint)
            check(audio.samples.all { it.isFinite() } && audio.samples.any { abs(it) > .00001f })
            check(prepared.speed in .6..1.6 && prepared.line.words.all { it.timingOrigin == WordTimingOrigin.ESTIMATED })
            check(prepared.line.words.joinToString("") { it.text } == text)

            var nativeReturned = false
            val stored = backend.assets.storedBytes()
            val cancelProvider = object : TtsProvider by provider {
                override suspend fun synthesize(request: TtsRequest): TtsResult<TtsAudio> {
                    val actual = provider.synthesize(request)
                    if (actual is TtsResult.Success) { nativeReturned = true; currentCoroutineContext().cancel() }
                    return actual
                }
                override fun close() = Unit
            }
            val cancelService = VocalTtsService(cancelProvider, TtsCache(fixture.directory.resolve("cancel-cache")), backend.assets)
            try {
                supervisorScope {
                    val pending = async { cancelService.prepare(plan.rows.single(), before.tempo, voice, TtsSettings(), true) }
                    val failure = runCatching { withTimeout(65_000) { pending.await() } }.exceptionOrNull()
                    check(failure is CancellationException && failure !is TimeoutCancellationException && nativeReturned)
                }
            } finally { cancelService.close() }
            check(backend.assets.storedBytes() == stored && backend.studio.document.value.project == before)
            check(PcmMemoryBudget.shared.statistics().usedBytes == backendRetainedBytes) { "TTS retained PCM beyond the live backend workspace" }
            val edit = success(plan.guideEdit(before, listOf(prepared), "guide"))
            check(backend.studio.dispatch(Action.Edit(edit, expectedRevision = revision)).accepted)
            val after = backend.studio.document.value.project
            check(backend.studio.document.value.revision == revision + 1 && after.source == before.source)
            check(after.tracks.single().kind == TrackKind.GUIDE && after.clips.single().range == FrameRange(0, prepared.asset.frames))
            check(backend.studio.dispatch(Action.Undo).accepted && backend.studio.document.value.project == before)
            check(backend.studio.dispatch(Action.Redo).accepted && backend.studio.document.value.project == after)
            check(!backend.studio.dispatch(Action.Edit(edit, expectedRevision = revision)).accepted)
            fixture.exportAndReopen(after, original, originalBytes, prepared.asset.frames.toInt())
            mapOf("language" to language.name, "realNativeSpeech" to true, "guideFrames" to prepared.asset.frames,
                "milliBpm" to before.tempo.milliBpm, "originalBytesExact" to true, "explicitApply" to true,
                "oneUndoRedo" to true, "staleRejected" to true, "cancelAtNativeReturn" to true,
                "cancelPublishedAssets" to 0, "export24" to true, "archiveReopen" to true, "autosaveReopen" to true)
        } finally {
            service.close()
            // Android shutdown is posted to Main; wait for it without operating the UI or permissions.
            withContext(Dispatchers.Main.immediate) { }
            check(fixture.context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("next-speech-") && it.name !in previousNativeScratch })
        }
    }

    private fun <T> success(result: TtsResult<T>): T = when (result) {
        is TtsResult.Success -> result.value
        is TtsResult.Failure -> error("Native TTS preparation failed: ${result.failure.problem}")
    }
}
