package com.choplab.jvm.ai

import com.choplab.core.ai.*
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import com.choplab.jvm.*
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.FileTime
import java.util.UUID

/** Private disposable cache. Request text is represented only by a digest; no credentials are accepted. */
class TtsCache(directory: Path, val maximumBytes: Long = 128L * 1024 * 1024) {
    private val directory: Path
    private val lock: Any
    init {
        require(maximumBytes in 1..(512L * 1024 * 1024))
        Files.createDirectories(directory); require(!Files.isSymbolicLink(directory))
        this.directory = directory.toRealPath(); lock = StoreLocks.forPath(this.directory)
    }

    internal fun get(key: String, checkCancelled: () -> Unit): CachedSpeech? = synchronized(lock) {
        val file = path(key)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return@synchronized null
        checkCancelled()
        try {
            require(Files.size(file) <= minOf(maximumBytes, MAX_ENTRY_BYTES))
            val bytes = Files.newInputStream(file).use { readBounded(it, MAX_ENTRY_BYTES) }
            checkCancelled()
            val input = DataInputStream(ByteArrayInputStream(bytes))
            require(input.readInt() == MAGIC)
            val digest = ByteArray(64).also(input::readFully).toString(Charsets.US_ASCII)
            val body = input.readBytes()
            require(sha256(body) == digest)
            val content = DataInputStream(ByteArrayInputStream(body))
            val size = content.readInt().also { require(it in 1..MAX_META_BYTES) }
            val meta = ProjectJson.parse(ByteArray(size).also(content::readFully)).obj().fields("sampleRate", "channels", "words", "speed", "trimmedFrames")
            val audio = WavCodec.read(content, maxBytes = TtsLimits.MAX_WAV_BYTES, maxDecodedBytes = TtsLimits.MAX_DECODED_BYTES)
            require(audio.info.sampleRate == meta.int("sampleRate") && audio.info.channels == meta.int("channels"))
            val words = meta.list("words", 256) { entry ->
                val word = entry.obj().fields("text", "startFrame", "endFrame", "origin")
                TtsWord(word.string("text"), word.long("startFrame"), word.long("endFrame"), WordTimingOrigin.valueOf(word.string("origin")))
            }
            val speed = meta.double("speed").also { require(it in .6..1.6) }
            val trimmed = meta.long("trimmedFrames").also { require(it in 0..(48_000L * TtsLimits.MAX_SECONDS)) }
            checkCancelled()
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()))
            CachedSpeech(TtsAudio.fromPcm(audio.samples, audio.info.sampleRate, audio.info.channels, words), speed, trimmed, digest)
        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel }
        catch (_: IOException) { null }
        catch (_: IllegalArgumentException) { null }
    }

    internal fun put(key: String, value: CachedSpeech, checkCancelled: () -> Unit) = synchronized(lock) {
        checkCancelled()
        val audio = value.audio
        val metadata = ProjectJson.encodeElement(obj("sampleRate" to num(audio.sampleRate), "channels" to num(audio.channels),
            "speed" to num(value.speed), "trimmedFrames" to num(value.trimmedFrames), "words" to arr(audio.words.map { word ->
                obj("text" to str(word.text), "startFrame" to num(word.startFrame), "endFrame" to num(word.endFrame), "origin" to str(word.origin.name))
            })))
        require(metadata.size <= MAX_META_BYTES)
        val body = ByteArrayOutputStream().also { output ->
            DataOutputStream(output).writeInt(metadata.size); output.write(metadata)
            WavCodec.writeFloat(output, audio.copySamples(), audio.sampleRate, audio.channels)
        }.toByteArray()
        val bytes = body.size.toLong() + 68
        if (bytes > maximumBytes || bytes > MAX_ENTRY_BYTES) throw TtsCacheCapacityException()
        val digest = sha256(body)
        val target = path(key)
        val files = Files.list(directory).use { stream -> stream.filter { cacheFile(it) && it != target }.toList() }
        var stored = files.sumOf { Files.size(it) }
        for (file in files.sortedBy { Files.getLastModifiedTime(it).toMillis() }) {
            if (stored + bytes <= maximumBytes) break
            checkCancelled(); val size = Files.size(file); Files.delete(file); stored -= size
        }
        val pending = directory.resolve(".pending-${UUID.randomUUID()}")
        try {
            FileOutputStream(pending.toFile()).use { raw ->
                val output = DataOutputStream(raw)
                output.writeInt(MAGIC); output.write(digest.toByteArray(Charsets.US_ASCII))
                var offset = 0
                while (offset < body.size) {
                    checkCancelled(); val count = minOf(8192, body.size - offset)
                    output.write(body, offset, count); offset += count
                }
                raw.fd.sync()
            }
            checkCancelled()
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(pending) }
        digest
    }
    fun clear() = synchronized(lock) {
        Files.list(directory).use { stream -> stream.filter(::cacheFile).forEach { Files.delete(it) } }
    }
    private fun path(key: String): Path { requireHash(key); return directory.resolve("$key.speech") }
    private fun cacheFile(path: Path) = path.fileName.toString().matches(Regex("[0-9a-f]{64}\\.speech")) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    companion object {
        private const val MAGIC = 0x54545331
        private const val MAX_META_BYTES = 256 * 1024
        private const val MAX_ENTRY_BYTES = TtsLimits.MAX_WAV_BYTES + MAX_META_BYTES + 128

        fun rawKey(request: TtsRequest): String {
            val voice = request.voice; val engine = voice.engine; val settings = request.settings
            return sha256(ProjectJson.encodeElement(obj("format" to str("tts-native-float-1"), "text" to str(request.text), "reading" to str(request.reading),
                "provider" to str(engine.provider), "providerVersion" to str(engine.providerVersion), "model" to str(engine.model), "modelVersion" to str(engine.modelVersion),
                "voice" to str(voice.id), "voiceVersion" to str(voice.version), "locale" to str(voice.locale), "language" to str(voice.language.name),
                "offline" to bool(voice.offline), "pitchSupported" to bool(voice.supportsPitch), "rate" to num(settings.ratePermille), "pitch" to num(settings.pitchPermille),
                "volume" to num(settings.volumePermille), "style" to str(settings.style))))
        }
        internal fun fittedKey(rawKey: String, contentHash: String, row: FlowRow, tempo: Tempo): String = sha256(ProjectJson.encodeElement(obj(
            "processing" to str(VocalGuideProcessor.VERSION), "rawKey" to str(rawKey), "contentHash" to str(contentHash), "startTick" to num(row.line.startTick), "endTick" to num(row.line.endTick),
            "milliBpm" to num(tempo.milliBpm), "swingPermille" to num(tempo.swingPermille), "mode" to str(row.mode.name))))
    }
}

internal class TtsCacheCapacityException : IOException()
