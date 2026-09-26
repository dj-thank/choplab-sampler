package com.choplab.desktop.source

import com.choplab.sampler.audio.AudioResourceLimits
import com.choplab.sampler.model.PcmAudio
import java.io.File
import java.security.MessageDigest

/** One bounded, single-use handoff from import validation to opening that same audio.
 * Hashes bytes instead of trusting paths/mtime: the validated staging file is renamed on publish.
 * Only validation owns retained samples; decode consumes them, so callers never share a mutable cache.
 */
internal class ValidatedAudioHandoff(
    private val decoder: (File) -> PcmAudio,
    private val maximumBytes: Long = 64L * 1024 * 1024,
) {
    private data class Entry(val key: String, val audio: PcmAudio)
    private val lock = Any()
    private var entry: Entry? = null

    fun validate(file: File) {
        synchronized(lock) { entry = null }
        // PCM WAV already decodes faster than hashing it. Retaining it would add latency,
        // especially for the WAV returned by online import. Optimize external codecs only.
        if (file.extension.equals("wav", ignoreCase = true)) {
            decoder(file)
            return
        }
        val key = fingerprint(file)
        val audio = decoder(file)
        checkNotInterrupted()
        require(fingerprint(file) == key) { "取り込み中に音源が変更されました。もう一度追加してください" }
        if (audio.samples.size.toLong() * Short.SIZE_BYTES <= maximumBytes) {
            synchronized(lock) { entry = Entry(key, audio) }
        }
    }

    fun decode(file: File): PcmAudio {
        if (file.extension.equals("wav", ignoreCase = true)) {
            synchronized(lock) { entry = null }
            return decoder(file)
        }
        // No retained validation means no extra hash pass on normal project/source reads.
        if (synchronized(lock) { entry == null }) return decoder(file)
        val key = fingerprint(file)
        val retained = synchronized(lock) {
            val previous = entry
            entry = null
            previous?.takeIf { it.key == key }?.audio
        }
        checkNotInterrupted()
        return retained?.copy(name = file.name.take(240)) ?: decoder(file)
    }

    private fun fingerprint(file: File): String {
        AudioResourceLimits.requireImportFileSize(file.length())
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                checkNotInterrupted()
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                AudioResourceLimits.requireImportFileSize(total)
                digest.update(buffer, 0, read)
            }
        }
        return file.extension.lowercase() + ":" + digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun checkNotInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
    }
}
