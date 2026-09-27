package com.choplab.jvm.ai

import com.choplab.core.ai.*
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import com.choplab.jvm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Production preparation: device PCM → normalization/WSOLA → verified float Asset, before explicit Apply. */
class VocalTtsService(private val provider: TtsProvider, private val cache: TtsCache, private val assets: FileAssetStore) : VocalSynthesisPort {
    private val serial = Mutex()
    private val closed = AtomicBoolean()
    override suspend fun voices(): TtsResult<FrozenList<TtsVoice>> = if (closed.get()) ttsFailure(TtsProblem.CLOSED) else provider.voices()

    override suspend fun prepare(row: FlowRow, tempo: Tempo, voice: TtsVoice, settings: TtsSettings, regenerate: Boolean): TtsResult<PreparedVocalLine> = serial.withLock {
        if (closed.get()) return@withLock ttsFailure(TtsProblem.CLOSED)
        if (row.reading.text != row.line.text) return@withLock ttsFailure(TtsProblem.STALE_DOCUMENT)
        if (!voice.offline) return@withLock ttsFailure(TtsProblem.NO_OFFLINE_VOICE)
        val request = TtsRequest(row.line.text, row.reading.reading, voice, settings)
        withContext(Dispatchers.IO) {
            val context = coroutineContext
            val check = { context.ensureActive(); if (closed.get()) throw CancellationException("TTS session closed") }
            try {
                val rawKey = TtsCache.rawKey(request)
                val frames = VocalGuideProcessor.targetFrames(row, tempo)
                val cached = if (regenerate) null else cache.get(rawKey, check)
                val source = cached ?: when (val result = provider.synthesize(request)) {
                    is TtsResult.Failure -> return@withContext result
                    is TtsResult.Success -> {
                        check()
                        val entry = CachedSpeech(result.value)
                        entry.copy(contentHash = cache.put(rawKey, entry, check))
                    }
                }
                val fittedKey = TtsCache.fittedKey(rawKey, requireNotNull(source.contentHash), row, tempo)
                val fitted = if (regenerate) null else cache.get(fittedKey, check)?.takeIf { it.audio.frames == frames && it.audio.sampleRate == 48_000 && it.audio.channels == 2 }
                val prepared = fitted ?: run {
                    check()
                    when (val result = withContext(Dispatchers.Default) { VocalGuideProcessor.fit(source.audio, request, frames, check) }) {
                        is TtsResult.Failure -> return@withContext result
                        is TtsResult.Success -> result.value.also { cache.put(fittedKey, it, check) }
                    }
                }
                check()
                val bytes = ByteArrayOutputStream().also { WavCodec.writeFloat(it, prepared.audio.copySamples()) }.toByteArray()
                val asset = Asset(sha256(bytes), "wav", bytes.size.toLong(), 48_000, 2, frames.toLong(), "Vocal guide.wav", AssetRole.RENDERED)
                assets.publish(asset, ByteArrayInputStream(bytes)) { !context.isActive || closed.get() }
                check()
                TtsResult.Success(PreparedVocalLine(row.line.copy(words = VocalGuideProcessor.lyricWords(row, prepared.audio)), asset,
                    prepared.speed, prepared.trimmedFrames, cached != null, fitted != null))
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: TtsCacheCapacityException) { ttsFailure(TtsProblem.CACHE_FULL) }
            catch (_: Exception) { ttsFailure(TtsProblem.FAILED) }
        }
    }
    override fun close() { if (closed.compareAndSet(false, true)) provider.close() }
}
