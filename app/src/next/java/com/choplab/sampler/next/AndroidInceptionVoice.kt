package com.choplab.sampler.next

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.choplab.core.inception.*
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** On-device recognizer only. Never falls back to a cloud recognizer or invents an audio recording. */
class AndroidInceptionVoice(private val context: Context, private val permission: MicrophonePermission) : InceptionVoice {
    private val epoch = AtomicLong()
    private val main = Handler(Looper.getMainLooper())
    // Access to these three fields is confined to the main looper.
    private var activeGeneration = 0L
    private var recognizer: SpeechRecognizer? = null
    private var answer: CancellableContinuation<InceptionMaterial>? = null
    override val available get() = Build.VERSION.SDK_INT >= 31 &&
        runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
    private fun releaseCurrent() {
        val oldAnswer = answer; val oldRecognizer = recognizer
        answer = null; recognizer = null
        oldAnswer?.cancel()
        oldRecognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
    }
    override suspend fun utterance(): InceptionMaterial {
        val generation = epoch.incrementAndGet()
        if (Build.VERSION.SDK_INT < 31 || !available) throw InceptionException(InceptionProblem.VOICE_UNAVAILABLE)
        if (!permission.request()) throw InceptionException(InceptionProblem.VOICE_UNAVAILABLE)
        return withContext(Dispatchers.Main.immediate) {
            ensureActive()
            if (epoch.get() != generation) throw CancellationException()
            suspendCancellableCoroutine { continuation ->
                releaseCurrent()
                val active = try { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }
                catch (_: Exception) {
                    continuation.resumeWithException(InceptionException(InceptionProblem.VOICE_UNAVAILABLE))
                    return@suspendCancellableCoroutine
                }
                activeGeneration = generation; answer = continuation; recognizer = active
                fun finish(text: String?) {
                    if (epoch.get() != generation || answer !== continuation || !continuation.isActive) return
                    answer = null; recognizer = null; runCatching { active.destroy() }
                    if (text.isNullOrBlank() || text.length > InceptionLimits.MAX_TEXT) continuation.resumeWithException(InceptionException(InceptionProblem.NO_SPEECH))
                    else runCatching { InceptionMaterial("transcript", text, kind = MaterialKind.TRANSCRIPT) }
                        .onSuccess { continuation.resume(it) }
                        .onFailure { continuation.resumeWithException(InceptionException(InceptionProblem.NO_SPEECH)) }
                }
                continuation.invokeOnCancellation {
                    epoch.compareAndSet(generation, generation + 1)
                    main.post { if (recognizer === active) releaseCurrent() }
                }
                try {
                    active.setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) { finish(null) }
                        override fun onResults(results: Bundle?) { finish(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()) }
                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                    active.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    })
                } catch (_: Exception) { finish(null) }
            }
        }
    }
    override fun stop() {
        val stoppedThrough = epoch.incrementAndGet()
        // A queued stop must not close a newer utterance that reached the main looper first.
        main.post { if (activeGeneration < stoppedThrough) releaseCurrent() }
    }
}
