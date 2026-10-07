package com.choplab.jvm.ai

import com.choplab.core.persistence.*
import com.choplab.core.ai.*
import com.choplab.core.model.*
import com.choplab.engine.Tempo
import com.choplab.jvm.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.nio.file.*
import java.nio.file.attribute.FileTime
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

/** Private disposable disk LRU. Native PCM and every file window share the active engine's memory budget. */
class TtsCache(directory: Path, val maximumBytes: Long = 128L * 1024 * 1024,
               val memory: PcmMemoryBudget = PcmMemoryBudget.shared) {
    private val directory: Path
    private val lock: kotlinx.coroutines.sync.Mutex
    init {
        require(maximumBytes in 1..(512L * 1024 * 1024))
        Files.createDirectories(directory); require(!Files.isSymbolicLink(directory))
        this.directory = directory.toRealPath(); lock = locks.computeIfAbsent(this.directory) { kotlinx.coroutines.sync.Mutex() }
    }
    private data class Header(val words: List<TtsWord>, val sampleRate: Int, val channels: Int, val speed: Double, val trimmed: Long)
    private fun header(input: DataInputStream): Header {
        val size = input.readInt().also { require(it in 1..MAX_META_BYTES) }
        val meta = ProjectJson.parse(ByteArray(size).also(input::readFully)).obj().fields("sampleRate", "channels", "words", "speed", "trimmedFrames")
        val words = meta.list("words", 256) { entry ->
            val word = entry.obj().fields("text", "startFrame", "endFrame", "origin")
            TtsWord(word.string("text"), word.long("startFrame"), word.long("endFrame"), WordTimingOrigin.valueOf(word.string("origin")))
        }
        return Header(words, meta.int("sampleRate"), meta.int("channels"), meta.double("speed").also { require(it in .6..1.6) },
            meta.long("trimmedFrames").also { require(it in 0..(48_000L * TtsLimits.MAX_SECONDS)) })
    }
    private fun <T> read(file: Path, check: () -> Unit, block: (Header, InputStream) -> T): Pair<T, String> {
        return Files.newInputStream(file).use { raw ->
            val input = DataInputStream(SpeechPcmIo.checked(raw, check))
            require(input.readInt() == MAGIC)
            val expected = ByteArray(64).also(input::readFully).toString(Charsets.US_ASCII)
            requireHash(expected)
            val digest = MessageDigest.getInstance("SHA-256")
            val body = DigestInputStream(input, digest)
            val result = block(header(DataInputStream(body)), body)
            require(body.read() == -1)
            require(digest.digest().hex() == expected)
            result to expected
        }
    }
    internal suspend fun get(key: String, checkCancelled: () -> Unit): CachedSpeech? = lock.withLock {
        val file = path(key)
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return@withLock null
        checkCancelled()
        var reserved: PcmMemoryBudget.Reservation? = null
        try {
            require(Files.size(file) <= minOf(maximumBytes, MAX_ENTRY_BYTES))
            val inspected = memory.reserve(SpeechPcmIo.READ_BYTES + MAX_META_BYTES).use {
                read(file, checkCancelled) { meta, input ->
                    val info = WavCodec.inspect(input, TtsLimits.MAX_WAV_BYTES)
                    require(info.sampleRate == meta.sampleRate && info.channels == meta.channels)
                    require(info.frames <= info.sampleRate.toLong() * TtsLimits.MAX_SECONDS)
                    info
                }
            }
            val info = inspected.first
            val bytes = info.frames * info.channels * 4
            val charge = memory.reserve(bytes + SpeechPcmIo.READ_BYTES + MAX_META_BYTES)
            reserved = charge
            var metadata: Header? = null
            val decoded = read(file, checkCancelled) { meta, input ->
                metadata = meta
                WavCodec.read(input, TtsLimits.MAX_WAV_BYTES, bytes).also { require(it.info == info) }
            }
            require(decoded.second == inspected.second)
            val meta = requireNotNull(metadata)
            checkCancelled()
            Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis()))
            val audio = TtsAudio.takeOwnership(decoded.first.samples, info.sampleRate, info.channels, meta.words, memory, charge::close)
            charge.shrinkTo(bytes); reserved = null
            CachedSpeech(audio, meta.speed, meta.trimmed, decoded.second)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: IOException) { null }
        catch (_: IllegalArgumentException) { null }
        finally { reserved?.close() }
    }

    internal suspend fun put(key: String, value: CachedSpeech, checkCancelled: () -> Unit): String = lock.withLock {
        memory.reserve(SpeechPcmIo.WRITE_BYTES + MAX_META_BYTES).use {
            checkCancelled()
            val audio = value.audio
            val metadata = ProjectJson.encodeElement(obj("sampleRate" to num(audio.sampleRate), "channels" to num(audio.channels),
                "speed" to num(value.speed), "trimmedFrames" to num(value.trimmedFrames), "words" to arr(audio.words.map { word ->
                    obj("text" to str(word.text), "startFrame" to num(word.startFrame), "endFrame" to num(word.endFrame), "origin" to str(word.origin.name))
                })))
            require(metadata.size <= MAX_META_BYTES)
            val bytes = 68 + 4 + metadata.size + 44 + audio.bytes
            if (bytes > maximumBytes || bytes > MAX_ENTRY_BYTES) throw TtsCacheCapacityException()
            val target = path(key)
            val files = Files.list(directory).use { stream -> stream.filter { cacheFile(it) && it != target }.toList() }
            var stored = files.sumOf { Files.size(it) }
            for (file in files.sortedBy { Files.getLastModifiedTime(it).toMillis() }) {
                if (stored + bytes <= maximumBytes) break
                checkCancelled(); val size = Files.size(file); Files.delete(file); stored -= size
            }
            val pending = directory.resolve(".pending-${UUID.randomUUID()}")
            try {
                val hash = MessageDigest.getInstance("SHA-256")
                FileOutputStream(pending.toFile()).use { raw ->
                    DataOutputStream(raw).apply { writeInt(MAGIC); write(ByteArray(64)) }
                    val body = DigestOutputStream(raw, hash)
                    DataOutputStream(body).writeInt(metadata.size); body.write(metadata)
                    SpeechPcmIo.write(body, audio, checkCancelled)
                    raw.fd.sync()
                }
                val digest = hash.digest().hex()
                RandomAccessFile(pending.toFile(), "rw").use { it.seek(4); it.write(digest.toByteArray(Charsets.US_ASCII)); it.fd.sync() }
                checkCancelled()
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                digest
            } finally { Files.deleteIfExists(pending) }
        }
    }
    suspend fun clear() = lock.withLock {
        Files.list(directory).use { stream -> stream.filter(::cacheFile).forEach { Files.delete(it) } }
    }
    private fun path(key: String): Path { requireHash(key); return directory.resolve("$key.speech") }
    private fun cacheFile(path: Path) = path.fileName.toString().matches(Regex("[0-9a-f]{64}\\.speech")) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    companion object {
        private val locks = java.util.concurrent.ConcurrentHashMap<Path, kotlinx.coroutines.sync.Mutex>()
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
