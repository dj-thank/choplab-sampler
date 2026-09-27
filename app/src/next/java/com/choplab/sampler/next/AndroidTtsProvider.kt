package com.choplab.sampler.next

import android.content.Context
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.choplab.core.ai.*
import com.choplab.core.model.*
import com.choplab.jvm.ai.DevicePcmEncoding
import com.choplab.jvm.ai.DeviceSpeechPcm
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** API 29+ device voices only. File synthesis is silent; the shared EngineCore owns subsequent audition. */
class AndroidTtsProvider(context: Context) : TtsProvider {
    private val context = context.applicationContext
    private val closed = AtomicBoolean()
    private val serial = Mutex()
    private val ready = CompletableDeferred<Boolean>()
    private val engine = AtomicReference<TextToSpeech?>()
    private val active = AtomicReference<Capture?>()
    private val sessionVersion = "unreported-session-${UUID.randomUUID()}"
    private class Capture(val id: String, val pcm: DeviceSpeechPcm = DeviceSpeechPcm(), val done: CompletableDeferred<TtsResult<Unit>> = CompletableDeferred())

    private suspend fun initialized(): TextToSpeech? = withContext(Dispatchers.Main.immediate) {
        if (closed.get()) return@withContext null
        if (engine.get() == null) {
            val candidate = TextToSpeech(context) { result -> ready.complete(result == TextToSpeech.SUCCESS) }
            engine.set(candidate)
            if (closed.get()) { engine.getAndSet(null)?.shutdown(); return@withContext null }
            candidate.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { capture(utteranceId)?.done?.complete(TtsResult.Success(Unit)) }
                @Deprecated("Native compatibility callback") override fun onError(utteranceId: String?) { fail(utteranceId) }
                override fun onError(utteranceId: String?, errorCode: Int) { fail(utteranceId) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { capture(utteranceId)?.done?.complete(ttsFailure(TtsProblem.CANCELLED)) }
                override fun onBeginSynthesis(utteranceId: String?, sampleRateInHz: Int, audioFormat: Int, channelCount: Int) {
                    val current = capture(utteranceId) ?: return
                    val format = when (audioFormat) {
                        AudioFormat.ENCODING_PCM_8BIT -> DevicePcmEncoding.UNSIGNED_8
                        AudioFormat.ENCODING_PCM_16BIT -> DevicePcmEncoding.SIGNED_16
                        AudioFormat.ENCODING_PCM_FLOAT -> DevicePcmEncoding.FLOAT_32
                        else -> { fail(utteranceId); return }
                    }
                    current.pcm.begin(sampleRateInHz, channelCount, format)
                }
                override fun onAudioAvailable(utteranceId: String?, audio: ByteArray?) {
                    val current = capture(utteranceId) ?: return
                    if (audio == null) fail(utteranceId) else current.pcm.append(audio)
                }
                private fun capture(id: String?) = active.get()?.takeIf { it.id == id && !closed.get() }
                private fun fail(id: String?) { capture(id)?.done?.complete(ttsFailure(TtsProblem.FAILED)) }
            })
        }
        if (withTimeout(10_000) { ready.await() } && !closed.get()) engine.get() else null
    }

    override suspend fun voices(): TtsResult<FrozenList<TtsVoice>> = serial.withLock {
        try {
            val tts = initialized() ?: return@withLock ttsFailure(if (closed.get()) TtsProblem.CLOSED else TtsProblem.UNAVAILABLE)
            withContext(Dispatchers.Main.immediate) {
                val packageName = tts.defaultEngine ?: return@withContext ttsFailure(TtsProblem.UNAVAILABLE)
                @Suppress("DEPRECATION") val version = context.packageManager.getPackageInfo(packageName, 0).longVersionCode.toString()
                val id = TtsEngine("device-android", version, packageName, version)
                val voices = tts.voices.orEmpty().filter { !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
                    .mapNotNull { voice ->
                        val language = when (voice.locale.language) { "ja" -> LyricLanguage.JAPANESE; "en" -> LyricLanguage.ENGLISH; else -> return@mapNotNull null }
                        TtsVoice(id, voice.name, voice.name, voice.locale.toLanguageTag(), sessionVersion, language, supportsPitch = true)
                    }.sortedBy { it.id }.take(256).frozen()
                if (voices.isEmpty()) ttsFailure(TtsProblem.NO_OFFLINE_VOICE) else TtsResult.Success(voices)
            }
        } catch (_: TimeoutCancellationException) { ttsFailure(TtsProblem.TIMEOUT) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { ttsFailure(TtsProblem.UNAVAILABLE) }
    }

    override suspend fun synthesize(request: TtsRequest): TtsResult<TtsAudio> {
        val voices = when (val result = voices()) { is TtsResult.Success -> result.value; is TtsResult.Failure -> return result }
        if (request.voice !in voices) return ttsFailure(TtsProblem.VOICE_CHANGED)
        if (request.settings.style != "neutral" || request.settings.volumePermille != 1000) return ttsFailure(TtsProblem.UNSUPPORTED_SETTINGS)
        return serial.withLock {
            if (closed.get()) return@withLock ttsFailure(TtsProblem.CLOSED)
            val tts = engine.get() ?: return@withLock ttsFailure(TtsProblem.UNAVAILABLE)
            val capture = Capture(UUID.randomUUID().toString())
            val output = withContext(Dispatchers.IO) { File.createTempFile("next-speech-", ".wav", context.cacheDir) }
            active.set(capture)
            var delivering: TtsAudio? = null
            var successful = false
            try {
                val queued = withContext(Dispatchers.Main.immediate) {
                    if (closed.get()) return@withContext false
                    val voice = tts.voices.firstOrNull { it.name == request.voice.id && !it.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features }
                        ?: return@withContext false
                    if (tts.setVoice(voice) != TextToSpeech.SUCCESS || tts.setSpeechRate(request.settings.ratePermille / 1000f) != TextToSpeech.SUCCESS ||
                        tts.setPitch(request.settings.pitchPermille / 1000f) != TextToSpeech.SUCCESS) return@withContext false
                    val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, request.settings.volumePermille / 1000f) }
                    tts.synthesizeToFile(request.spokenText, params, output, capture.id) == TextToSpeech.SUCCESS
                }
                if (!queued) return@withLock ttsFailure(TtsProblem.VOICE_CHANGED)
                val result = when (val completion = withTimeout(60_000) { capture.done.await() }) {
                    is TtsResult.Failure -> completion
                    is TtsResult.Success -> withContext(Dispatchers.Default) {
                        ensureActive(); capture.pcm.finish().also { if (it is TtsResult.Success) delivering = it.value }
                    }
                }
                successful = result is TtsResult.Success
                result
            } catch (_: TimeoutCancellationException) { ttsFailure(TtsProblem.TIMEOUT) }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { ttsFailure(TtsProblem.FAILED) }
            finally {
                var cleaned = false
                try {
                    active.compareAndSet(capture, null)
                    capture.pcm.clear()
                    withContext(NonCancellable + Dispatchers.Main.immediate) { if (!closed.get()) tts.stop() }
                    withContext(NonCancellable + Dispatchers.IO) { output.delete() }
                    currentCoroutineContext().ensureActive()
                    cleaned = true
                } finally { if (!successful || !cleaned) delivering?.close() }
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.getAndSet(null)?.let { it.pcm.clear(); it.done.complete(ttsFailure(TtsProblem.CLOSED)) }
        ready.complete(false)
        val tts = engine.getAndSet(null) ?: return
        val release = Runnable { tts.stop(); tts.shutdown() }
        if (Looper.myLooper() == Looper.getMainLooper()) release.run() else Handler(Looper.getMainLooper()).post(release)
    }
}
