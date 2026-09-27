package com.choplab.jvm.ai

import com.choplab.core.ai.*
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import com.choplab.jvm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.DigestOutputStream
import java.security.MessageDigest
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
            var source: CachedSpeech? = null
            var prepared: CachedSpeech? = null
            var foreignSource: PcmMemoryBudget.Reservation? = null
            val memory = cache.memory
            try {
                val rawKey = TtsCache.rawKey(request)
                val frames = VocalGuideProcessor.targetFrames(row, tempo)
                val cached = if (regenerate) null else cache.get(rawKey, check)
                source = cached ?: when (val result = provider.synthesize(request)) {
                    is TtsResult.Failure -> return@withContext result
                    is TtsResult.Success -> {
                        // Keep the returned ownership even if cancellation happens immediately after the provider returns.
                        val entry = CachedSpeech(result.value)
                        source = entry
                        if (entry.audio.reservationOwner !== memory) foreignSource = memory.reserve(entry.audio.bytes)
                        check()
                        entry.copy(contentHash = cache.put(rawKey, entry, check))
                    }
                }
                val raw = requireNotNull(source)
                val fittedKey = TtsCache.fittedKey(rawKey, requireNotNull(raw.contentHash), row, tempo)
                val fitted = if (regenerate) null else cache.get(fittedKey, check)?.let {
                    if (it.audio.frames == frames && it.audio.sampleRate == 48_000 && it.audio.channels == 2) it else { it.audio.close(); null }
                }
                prepared = fitted
                prepared = fitted ?: run {
                    check()
                    when (val result = withContext(Dispatchers.Default) {
                        VocalGuideProcessor.fit(raw.audio, request, frames, memory, check).also { if (it is TtsResult.Success) prepared = it.value }
                    }) {
                        is TtsResult.Failure -> return@withContext result
                        is TtsResult.Success -> result.value.also { prepared = it; cache.put(fittedKey, it, check) }
                    }
                }
                raw.audio.close(); source = null
                foreignSource?.close(); foreignSource = null
                check()
                val ready = requireNotNull(prepared)
                val asset = publish(ready.audio, frames, check)
                check()
                TtsResult.Success(PreparedVocalLine(row.line.copy(words = VocalGuideProcessor.lyricWords(row, ready.audio)), asset,
                    ready.speed, ready.trimmedFrames, cached != null, fitted != null))
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: TtsCacheCapacityException) { ttsFailure(TtsProblem.CACHE_FULL) }
            catch (_: PcmMemoryLimit) { ttsFailure(TtsProblem.MEMORY_LIMIT) }
            catch (_: Exception) { ttsFailure(TtsProblem.FAILED) }
            finally { source?.audio?.close(); prepared?.audio?.close(); foreignSource?.close() }
        }
    }
    private suspend fun publish(audio: TtsAudio, frames: Int, check: () -> Unit): Asset =
        cache.memory.reserve(SpeechPcmIo.WRITE_BYTES + 64 * 1024).use {
            val temporary = Files.createTempFile(assets.directory.parent, ".guide-", ".wav")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                FileOutputStream(temporary.toFile()).use { output ->
                    SpeechPcmIo.write(DigestOutputStream(output, digest), audio, check)
                    output.fd.sync()
                }
                check()
                val asset = Asset(digest.digest().hex(), "wav", Files.size(temporary), 48_000, 2, frames.toLong(), "Vocal guide.wav", AssetRole.RENDERED)
                assets.adopt(asset, temporary) { check(); false }
                asset
            } finally { Files.deleteIfExists(temporary) }
        }
    override fun close() { if (closed.compareAndSet(false, true)) provider.close() }
}
