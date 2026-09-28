package com.choplab.jvm.ai

import com.choplab.core.ai.*
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import com.choplab.jvm.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.*

class VocalTtsMemoryTest {
    private val voice = TtsVoice(TtsEngine("device", "1", "system", "1"), "voice", "Voice", "ja-JP", "1", LyricLanguage.JAPANESE)
    private fun row(): FlowRow {
        val placement = LyricProposal("川", LyricLanguage.JAPANESE, frozenListOf(ProposalSection("A", LyricSectionKind.VERSE, 1,
            frozenListOf(ProposalLine.create("川の歌", "かわのうた", LyricLanguage.JAPANESE))))).placeStructured(0, 4, "line")
        return assertIs<FlowResult.Ready>(FlowPlanner.plan(Project(lyrics = placement.lines, lyricStructure = placement.structure), 0, FlowMode.ONE_BAR)).plan.rows.single()
    }

    @Test fun activePcmSharesNativeDecodeResamplerWsolaCacheAndAssetAdmissionThenIdleEvictionAllowsRetry() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("tts-global-budget-")
        val memory = PcmMemoryBudget(12L * 1024 * 1024)
        val assets = FileAssetStore(directory.resolve("assets"))
        val pcm = WavPcmPort(assets, memory = memory)
        val bytes = ByteArrayOutputStream().also { WavCodec.writeFloat(it, FloatArray(500_000 * 2) { .05f }) }.toByteArray()
        val original = Asset(sha256(bytes), "wav", bytes.size.toLong(), 48_000, 2, 500_000, "Original")
        assets.publish(original, ByteArrayInputStream(bytes))
        var syntheses = 0
        val runner = object : SpeechProcessRunner {
            override suspend fun run(arguments: List<String>, directory: Path): SpeechProcessResult {
                if ("?" in arguments) return SpeechProcessResult(0, "Local ja_JP # voice".toByteArray())
                syntheses++
                // This fixture stands in for OS process memory, outside the app's owned PCM ledger.
                Files.newOutputStream(directory.resolve("speech.wav")).use { WavCodec.writeFloat(it,
                    FloatArray(44_100) { (.15 * sin(2 * PI * 200 * it / 22_050)).toFloat() }, 22_050, 1) }
                return SpeechProcessResult(0, byteArrayOf())
            }
            override fun close() = Unit
        }
        val provider = DesktopTtsProvider(directory.resolve("native"), DesktopSpeechPlatform.MAC, runner, "test", "voice", memory)
        val service = VocalTtsService(provider, TtsCache(directory.resolve("cache"), memory = memory), assets)
        val lease = pcm.acquire(original)
        try {
            val retained = memory.statistics().usedBytes
            assertEquals(4_000_000L, retained)
            val installed = assertIs<TtsResult.Success<FrozenList<TtsVoice>>>(service.voices()).value.single()
            val rejected = assertIs<TtsResult.Failure>(service.prepare(row(), Tempo(), installed, TtsSettings(), false))
            assertEquals(TtsProblem.MEMORY_LIMIT, rejected.failure.problem)
            assertEquals(retained, memory.statistics().usedBytes, "Every refused TTS stage returns its own charge")
            assertEquals(1L, Files.list(assets.directory).use { it.count() }, "Only the original remains; refusal cannot publish a guide")
            lease.close()
            val first = assertIs<TtsResult.Success<PreparedVocalLine>>(service.prepare(row(), Tempo(), installed, TtsSettings(), false)).value
            val again = assertIs<TtsResult.Success<PreparedVocalLine>>(service.prepare(row(), Tempo(), installed, TtsSettings(), false)).value
            assertTrue(first.rawCacheHit && again.processedCacheHit)
            assertEquals(first.asset, again.asset); assertEquals(1, syntheses)
            assertTrue(assets.containsVerified(first.asset))
            assertContentEquals(bytes, assets.read(original))
            assertEquals(0L, memory.statistics().usedBytes)
            assertTrue(memory.statistics().peakBytes <= memory.limitBytes)
            println("TTS_SHARED_PCM peakBytes=${memory.statistics().peakBytes} limitBytes=${memory.limitBytes} remainingBytes=${memory.statistics().usedBytes}")
            assertEquals(0L, Files.list(directory.resolve("native")).use { it.count() })
            assertEquals(0L, Files.list(directory).use { paths -> paths.filter { it.fileName.toString().startsWith(".guide-") }.count() })
        } finally { lease.close(); service.close(); pcm.close(); directory.toFile().deleteRecursively() }
        assertEquals(0L, memory.statistics().usedBytes)
    }

    @Test fun aCancelledLateProviderResultIsClosedOnceWithoutPublishingOrRetainingPcm() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("tts-late-budget-")
        val memory = PcmMemoryBudget(4L * 1024 * 1024)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var disposed = 0; var closes = 0
        val provider = object : TtsProvider {
            override suspend fun voices() = TtsResult.Success(frozenListOf(voice))
            override suspend fun synthesize(request: TtsRequest): TtsResult<TtsAudio> = withContext(NonCancellable) {
                val charge = memory.reserve(48_000 * 4L)
                val audio = TtsAudio.takeOwnership(FloatArray(48_000) { .1f }, 48_000, 1, reservationOwner = memory,
                    release = { disposed++; charge.close() })
                entered.complete(Unit); release.await(); TtsResult.Success(audio)
            }
            override fun close() { closes++ }
        }
        val assets = FileAssetStore(directory.resolve("assets"))
        val cache = TtsCache(directory.resolve("cache"), memory = memory)
        val service = VocalTtsService(provider, cache, assets)
        try {
            val job = launch { service.prepare(row(), Tempo(), voice, TtsSettings(), true) }
            withTimeout(5_000) { entered.await() }
            assertEquals(192_000L, memory.statistics().usedBytes)
            job.cancel(); release.complete(Unit); withTimeout(5_000) { job.join() }
            assertEquals(1, disposed); assertEquals(0L, memory.statistics().usedBytes)
            assertEquals(0L, Files.list(assets.directory).use { it.count() })
            assertEquals(0L, Files.list(directory.resolve("cache")).use { it.count() })
        } finally { release.complete(Unit); service.close(); service.close(); directory.toFile().deleteRecursively() }
        assertEquals(1, closes)
    }

    @Test fun callbackChunksReserveBeforeDecodeAndReleaseOnClearOrMemoryRefusal() = runBlocking<Unit> {
        val memory = PcmMemoryBudget(128)
        val capture = DeviceSpeechPcm(memory)
        capture.begin(48_000, 2, DevicePcmEncoding.FLOAT_32)
        val values = floatArrayOf(.25f, -.5f, .75f, -1f)
        val bytes = ByteArray(values.size * 4)
        values.forEachIndexed { i, sample -> repeat(4) { bytes[i * 4 + it] = (sample.toRawBits() ushr (it * 8)).toByte() } }
        for (part in bytes.asList().chunked(3)) capture.append(part.toByteArray())
        assertEquals(16L, memory.statistics().usedBytes)
        val audio = assertIs<TtsResult.Success<TtsAudio>>(capture.finish()).value
        assertEquals(16L, memory.statistics().usedBytes); assertEquals(32L, memory.statistics().peakBytes)
        assertContentEquals(values, audio.copySamples())
        capture.clear(); assertEquals(16L, memory.statistics().usedBytes, "The returned audio owns its storage independently of callback cleanup")
        audio.close(); audio.close(); assertEquals(0L, memory.statistics().usedBytes)
        assertFailsWith<IllegalArgumentException> { audio.copySamples() }
        memory.reserve(120).use {
            val denied = DeviceSpeechPcm(memory).apply { begin(48_000, 2, DevicePcmEncoding.FLOAT_32); append(bytes) }
            assertEquals(TtsProblem.MEMORY_LIMIT, assertIs<TtsResult.Failure>(denied.finish()).failure.problem)
            assertEquals(120L, memory.statistics().usedBytes)
        }
        val cancelled = DeviceSpeechPcm(memory).apply { begin(48_000, 2, DevicePcmEncoding.FLOAT_32); append(bytes) }
        assertEquals(16L, memory.statistics().usedBytes)
        cancelled.clear(); cancelled.append(bytes)
        assertEquals(0L, memory.statistics().usedBytes)
        assertIs<TtsResult.Failure>(cancelled.finish())
    }

    @Test fun interruptedStreamCacheWritePreservesOldEntryAndReturnsEveryWindow() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("tts-cache-budget-")
        val memory = PcmMemoryBudget(1024 * 1024)
        val cache = TtsCache(directory, memory = memory)
        val reservation = memory.reserve(32_000)
        val audio = TtsAudio.takeOwnership(FloatArray(8_000) { .125f }, 8_000, 1, reservationOwner = memory, release = reservation::close)
        try {
            val key = "a".repeat(64)
            val original = cache.put(key, CachedSpeech(audio)) {}
            var checks = 0
            assertFailsWith<CancellationException> { cache.put(key, CachedSpeech(audio)) { if (++checks == 3) throw CancellationException() } }
            val restored = requireNotNull(cache.get(key) {})
            assertEquals(original, restored.contentHash)
            assertContentEquals(audio.copySamples(), restored.audio.copySamples())
            restored.audio.close()
            assertEquals(audio.bytes, memory.statistics().usedBytes)
            assertEquals(1L, Files.list(directory).use { it.count() })
            assertTrue(memory.statistics().peakBytes <= memory.limitBytes)
        } finally { audio.close(); directory.toFile().deleteRecursively() }
        assertEquals(0L, memory.statistics().usedBytes)
    }
}
