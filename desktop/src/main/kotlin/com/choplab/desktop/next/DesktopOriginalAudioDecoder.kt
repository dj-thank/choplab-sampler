package com.choplab.desktop.next

import com.choplab.core.model.ProjectLimits
import com.choplab.engine.EngineFormat
import com.choplab.jvm.OriginalAudioDecoder
import com.choplab.jvm.WavAudio
import com.choplab.jvm.WavInfo
import kotlinx.coroutines.CancellationException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/** Original-rate float decoding on workers, with one bounded handoff to the shared PCM cache.
 * Only local audio/container demuxers are enabled. Tools, streams and temporary files belong to this job.
 */
class DesktopOriginalAudioDecoder(
    private val tools: () -> Pair<Path, Path> = ::localAudioTools,
    private val timeoutMillis: Long = 120_000,
    private val maxResidentBytes: Long = EngineFormat.MAX_RESIDENT_BYTES,
) : OriginalAudioDecoder {
    init { require(timeoutMillis > 0 && maxResidentBytes in 8..EngineFormat.MAX_RESIDENT_BYTES && maxResidentBytes % 8 == 0L) }
    private val gate = ReentrantLock()
    private val metadata = object : LinkedHashMap<String, WavInfo>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, WavInfo>?) = size > 256
    }
    private var handoff: Pair<String, WavAudio>? = null
    override fun close() = synchronized(metadata) { handoff = null; metadata.clear() }

    override fun inspect(path: Path, hash: String, cancelled: () -> Boolean): WavInfo {
        checkCancelled(cancelled)
        synchronized(metadata) { metadata[hash]?.let { return it } }
        return locked(cancelled) {
            synchronized(metadata) { metadata[hash] } ?: read(path, cancelled).also { audio ->
                synchronized(metadata) {
                    metadata[hash] = audio.info
                    handoff = if (audio.samples.size.toLong() * 4 <= 64L * 1024 * 1024) hash to audio else null
                }
            }.info
        }
    }

    override fun decode(path: Path, hash: String, cancelled: () -> Boolean): WavAudio = locked(cancelled) {
        val cached = synchronized(metadata) {
            handoff?.takeIf { it.first == hash }?.second.also { if (it != null) handoff = null }
        }
        cached ?: read(path, cancelled).also { synchronized(metadata) { metadata[hash] = it.info } }
    }

    private fun <T> locked(cancelled: () -> Boolean, block: () -> T): T {
        while (!gate.tryLock(25, TimeUnit.MILLISECONDS)) checkCancelled(cancelled)
        try { checkCancelled(cancelled); return block() } finally { gate.unlock() }
    }

    private fun read(path: Path, cancelled: () -> Boolean): WavAudio {
        require(Files.isRegularFile(path) && Files.size(path) in 1..ProjectLimits.MAX_ASSET_BYTES)
        val (ffmpeg, ffprobe) = tools()
        val temporary = Files.createTempDirectory("choplab-decode-")
        try {
            val probe = temporary.resolve("probe.json")
            val error = temporary.resolve("error.txt")
            val input = listOf("-protocol_whitelist", "file,pipe", "-format_whitelist", "flac,mp3,mov,ogg,aac", "-i", path.toAbsolutePath().toString())
            execute(listOf(ffprobe.toString(), "-v", "error") + input + listOf("-select_streams", "a:0",
                "-show_entries", "stream=sample_rate,channels", "-of", "default=noprint_wrappers=1"), probe, error, cancelled, 64 * 1024L)
            val fields = Files.readAllLines(probe).associate { line ->
                val parts = line.split('=', limit = 2)
                require(parts.size == 2)
                parts[0] to parts[1].toInt()
            }
            require(fields.keys == setOf("sample_rate", "channels")) { "No supported audio stream" }
            val rate = fields.getValue("sample_rate")
            val channels = fields.getValue("channels")
            require(rate in 8_000..192_000 && channels in 1..2) { "Unsupported audio rate or channels" }
            // Match the shared resident budget at both source and render rate before allocating samples.
            val maxFrames = minOf(ProjectLimits.MAX_FRAMES, maxResidentBytes / 8,
                (maxResidentBytes / 8) * rate / 48_000)
            val maximumBytes = maxFrames * channels * 4
            val raw = temporary.resolve("audio.f32")
            val stdout = temporary.resolve("stdout.txt")
            execute(listOf(ffmpeg.toString(), "-nostdin", "-v", "error", "-xerror", "-err_detect", "explode") + input +
                listOf("-map", "0:a:0", "-vn", "-sn", "-dn", "-ar", rate.toString(), "-ac", channels.toString(),
                    "-c:a", "pcm_f32le", "-f", "f32le", "-fs", (maximumBytes + 64 * 1024).toString(), raw.toString()),
                stdout, error, cancelled, 64 * 1024L)
            val size = Files.size(raw)
            require(size in (channels * 4L)..maximumBytes && size % (channels * 4) == 0L) { "Audio exceeds the resident frame limit" }
            val frames = size / (channels * 4)
            val samples = FloatArray((size / 4).toInt())
            Files.newInputStream(raw).use { inputStream ->
                val bytes = ByteArray(64 * 1024)
                var offset = 0
                while (offset < samples.size) {
                    checkCancelled(cancelled)
                    val count = minOf(bytes.size, (samples.size - offset) * 4)
                    require(inputStream.readNBytes(bytes, 0, count) == count)
                    val floats = ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    floats.get(samples, offset, count / 4)
                    for (i in offset until offset + count / 4) require(samples[i].isFinite()) { "Non-finite audio" }
                    offset += count / 4
                }
            }
            checkCancelled(cancelled)
            return WavAudio(WavInfo(rate, channels, frames, 32, true), samples)
        } finally {
            Files.list(temporary).use { entries -> entries.forEach(Files::deleteIfExists) }
            Files.deleteIfExists(temporary)
        }
    }

    private fun execute(command: List<String>, output: Path, error: Path, cancelled: () -> Boolean, limit: Long) {
        checkCancelled(cancelled)
        val process = ProcessBuilder(command).redirectOutput(output.toFile()).redirectError(error.toFile()).start()
        process.outputStream.close()
        val started = System.nanoTime()
        try {
            while (true) {
                checkCancelled(cancelled)
                require((System.nanoTime() - started) / 1_000_000 < timeoutMillis) { "Audio decode timed out" }
                require(Files.size(output) <= limit && Files.size(error) <= 64 * 1024) { "Audio decoder output exceeded limit" }
                if (process.waitFor(25, TimeUnit.MILLISECONDS)) break
            }
            checkCancelled(cancelled)
            require((System.nanoTime() - started) / 1_000_000 < timeoutMillis) { "Audio decode timed out" }
            require(process.exitValue() == 0) { "Audio could not be decoded" }
            require(Files.size(output) <= limit && Files.size(error) <= 64 * 1024)
        } finally {
            if (process.isAlive) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS) }
        }
    }

    private fun checkCancelled(cancelled: () -> Boolean) {
        if (cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Audio import cancelled")
    }
}

private fun localAudioTools(): Pair<Path, Path> {
    val suffix = if (System.getProperty("os.name").contains("Windows", true)) ".exe" else ""
    fun pair(directory: File): Pair<Path, Path>? {
        val paths = listOf("ffmpeg", "ffprobe").map { File(directory, it + suffix) }
        return if (paths.all { it.isFile && it.canExecute() }) paths[0].toPath() to paths[1].toPath() else null
    }
    val configured = System.getProperty("choplab.mediaTools") ?: System.getenv("CHOPLAB_MEDIA_TOOLS")
    if (configured != null) return requireNotNull(pair(File(configured))) { "Configured audio tools are unavailable" }
    val directories = listOf(File(System.getProperty("java.home")).parentFile.resolve("tools"), File("work/media-tools"),
        File("../work/media-tools"), File("/opt/homebrew/bin"), File("/usr/local/bin")) +
        System.getenv("PATH").orEmpty().split(File.pathSeparator).filter(String::isNotBlank).map(::File)
    return requireNotNull(directories.firstNotNullOfOrNull(::pair)) { "Local audio tools are unavailable" }
}
