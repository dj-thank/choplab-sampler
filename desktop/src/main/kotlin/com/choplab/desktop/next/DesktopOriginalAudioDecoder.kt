package com.choplab.desktop.next

import com.choplab.core.model.ProjectLimits
import com.choplab.engine.EngineFormat
import com.choplab.jvm.OriginalAudioDecoder
import com.choplab.jvm.WavAudio
import com.choplab.jvm.WavInfo
import com.choplab.jvm.PcmFrameSource
import com.choplab.jvm.RawFloatFrameSource
import com.choplab.jvm.PcmScratchBudget
import kotlinx.coroutines.CancellationException
import java.io.File
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
    private var handoff: Pair<String, PcmFrameSource>? = null
    override fun close() = synchronized(metadata) { handoff?.second?.close(); handoff = null; metadata.clear() }

    override fun inspect(path: Path, hash: String, cancelled: () -> Boolean): WavInfo {
        checkCancelled(cancelled)
        synchronized(metadata) { metadata[hash]?.let { return it } }
        return locked(cancelled) {
            synchronized(metadata) { metadata[hash] } ?: readSource(path, cancelled).also { audio ->
                synchronized(metadata) {
                    metadata[hash] = audio.info
                    handoff?.second?.close()
                    handoff = hash to audio
                }
            }.info
        }
    }

    override fun openPcm(path: Path, hash: String, cancelled: () -> Boolean): PcmFrameSource = locked(cancelled) {
        val cached = synchronized(metadata) {
            handoff?.takeIf { it.first == hash }?.second.also { if (it != null) handoff = null }
        }
        cached ?: readSource(path, cancelled).also { synchronized(metadata) { metadata[hash] = it.info } }
    }

    override fun decode(path: Path, hash: String, cancelled: () -> Boolean): WavAudio = openPcm(path, hash, cancelled).use { source ->
        val info = source.info
        require(info.frames * 8 <= maxResidentBytes && (info.frames * 48_000 + info.sampleRate - 1) / info.sampleRate * 8 <= maxResidentBytes) {
            "Use paged decoding for long audio"
        }
        val samples = FloatArray((info.frames * info.channels).toInt())
        var first = 0
        while (first < info.frames) {
            val count = minOf(4096L, info.frames - first).toInt()
            source.read(first, count, cancelled).copyInto(samples, first * info.channels)
            first += count
        }
        WavAudio(info, samples)
    }

    private fun <T> locked(cancelled: () -> Boolean, block: () -> T): T {
        while (!gate.tryLock(25, TimeUnit.MILLISECONDS)) checkCancelled(cancelled)
        try { checkCancelled(cancelled); return block() } finally { gate.unlock() }
    }

    private fun readSource(path: Path, cancelled: () -> Boolean): PcmFrameSource {
        require(Files.isRegularFile(path) && Files.size(path) in 1..ProjectLimits.MAX_ASSET_BYTES)
        val memory = kotlinx.coroutines.runBlocking { com.choplab.jvm.PcmMemoryBudget.shared.reserve(256 * 1024L) }
        try {
        val (ffmpeg, ffprobe) = tools()
        val temporary = Files.createTempDirectory("choplab-decode-")
        var quota: java.io.Closeable? = null
        var transferred = false
        try {
            val probe = temporary.resolve("probe.json")
            val error = temporary.resolve("error.txt")
            val input = listOf("-protocol_whitelist", "file,pipe", "-format_whitelist", "flac,mp3,mov,ogg,aac,aiff,matroska,webm", "-i", path.toAbsolutePath().toString())
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
            val maximumBytes = ProjectLimits.MAX_FRAMES * channels * 4
            quota = PcmScratchBudget.reserve(maximumBytes + 64 * 1024)
            val raw = temporary.resolve("audio.f32")
            val stdout = temporary.resolve("stdout.txt")
            execute(listOf(ffmpeg.toString(), "-nostdin", "-v", "error", "-xerror", "-err_detect", "explode") + input +
                listOf("-map", "0:a:0", "-vn", "-sn", "-dn", "-ar", rate.toString(), "-ac", channels.toString(),
                    "-c:a", "pcm_f32le", "-f", "f32le", "-fs", (maximumBytes + 64 * 1024).toString(), raw.toString()),
                stdout, error, cancelled, 64 * 1024L)
            val size = Files.size(raw)
            require(size in (channels * 4L)..maximumBytes && size % (channels * 4) == 0L) { "Audio exceeds the source frame limit" }
            val frames = size / (channels * 4)
            val info = WavInfo(rate, channels, frames, 32, true)
            val lease = requireNotNull(quota)
            val source = RawFloatFrameSource(raw, info) {
                try {
                    Files.list(temporary).use { entries -> entries.forEach(Files::deleteIfExists) }
                    Files.deleteIfExists(temporary)
                } finally { lease.close() }
            }
            // Validate all native float samples with bounded storage before admitting metadata.
            var first = 0
            while (first < frames) {
                val count = minOf(16_384L, frames - first).toInt()
                source.read(first, count, cancelled)
                first += count
            }
            checkCancelled(cancelled)
            transferred = true
            return source
        } finally {
            if (!transferred) {
                try {
                    Files.list(temporary).use { entries -> entries.forEach(Files::deleteIfExists) }
                    Files.deleteIfExists(temporary)
                } finally { quota?.close() }
            }
        }
        } finally { memory.close() }
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
