package com.choplab.sampler.audio

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoInfo
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Opt-in software codec execution. It never opens a microphone, endpoint, project or provider. */
@RunWith(AndroidJUnit4::class)
class AndroidNativeCodecTest {
    @Test(timeout = 300_000)
    fun packagedToolsRetainAudioPrecisionSeekResamplingAndYtDlpConversion() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("choplabCodecFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "native-codec-${System.nanoTime()}").apply { mkdirs() }
        val processId = directory.name
        try {
            val native = File(context.applicationInfo.nativeLibraryDir)
            val expectedRuntime = InstrumentationRegistry.getArguments().getString("choplabCodecExpectedFfmpegSha256")
            check(expectedRuntime?.matches(Regex("[0-9a-f]{64}")) == true) { "Supply the reviewed candidate FFmpeg hash" }
            check(hash(File(native, "libffmpeg.so")) == expectedRuntime) { "Installed runtime differs from the candidate under test" }
            val expectedPython = InstrumentationRegistry.getArguments().getString("choplabCodecExpectedPythonSha256")
            if (expectedPython != null) {
                check(expectedPython.matches(Regex("[0-9a-f]{64}"))) { "Supply the reviewed Python linkage hash" }
                check(hash(File(native, "libpython.zip.so")) == expectedPython) { "Installed Python differs from the reviewed linkage candidate" }
            }
            val packages = File(context.noBackupFilesDir, "youtubedl-android/packages")
            val migration = seedLegacyPythonCache(context, native, packages, expectedPython)
            // The migration fixture must run in a fresh instrumentation process:
            // use the real public initializer, not reflection or initPython directly.
            YoutubeDL.getInstance().init(context)
            FFmpeg.getInstance().init(context)
            if (migration != null) {
                val version = context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
                    .getString("pythonLibVersion", null)
                check(version == "41890175") { "Python cache version did not migrate: expected 41890175, actual $version" }
                check(hash(File(packages, "python/usr/lib/liblzma.so.5.8.1")) == LEGACY_LZMA_SHA256) {
                    "Migration changed the versioned Python codec bytes"
                }
                check(!java.nio.file.Files.exists(File(packages, "python/usr/lib/liblzma.so").toPath(),
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "Migration retained the old unversioned alias" }
                migration.put("status", "LOCAL_PASS").put("versionAfter", version)
                    .put("versionedSha256After", LEGACY_LZMA_SHA256).put("aliasAbsentAfter", true)
            }
            if (expectedPython != null) check(!java.nio.file.Files.exists(
                File(packages, "python/usr/lib/liblzma.so").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                "Private Python cache retained the unversioned system-library collision"
            }
            val libraries = listOf(File(packages, "python/usr/lib"), File(packages, "ffmpeg/usr/lib"), native).joinToString(":") { it.path }
            var sequence = 0
            fun command(tool: String, arguments: List<String>, accepted: Boolean = true): String {
                val output = File(directory, "command-${sequence++}.out")
                val error = File(directory, "command-${sequence++}.err")
                val builder = ProcessBuilder(listOf(File(native, tool).path) + arguments)
                    .redirectOutput(output).redirectError(error)
                builder.environment()["LD_LIBRARY_PATH"] = libraries
                builder.environment()["SSL_CERT_FILE"] = File(packages, "python/usr/etc/tls/cert.pem").path
                val process = builder.start()
                try {
                    check(process.waitFor(45, TimeUnit.SECONDS)) { "Native codec timed out" }
                    check((process.exitValue() == 0) == accepted) { error.inputStream().use { String(it.readNBytes(16_384)) } }
                    check(output.length() <= 1_048_576) { "Unexpected native response size" }
                    return output.readText()
                } finally { if (process.isAlive) process.destroyForcibly() }
            }
            fun ffmpeg(vararg arguments: String) = command("libffmpeg.so", listOf("-nostdin", "-v", "error", "-y") + arguments)
            fun decode(source: File, target: File, extra: List<String> = emptyList()) {
                command("libffmpeg.so", listOf("-nostdin", "-v", "error", "-y") + extra +
                    listOf("-protocol_whitelist", "file,pipe", "-i", source.path, "-map", "0:a:0", "-vn", "-c:a", "pcm_f32le", "-f", "f32le", target.path))
            }
            val protocols = command("libffmpeg.so", listOf("-hide_banner", "-protocols"))
            for (required in listOf("file", "pipe", "http", "https", "tls", "tcp", "udp", "srt", "sftp", "zmq")) {
                check(Regex("(?m)^\\s*$required\\s*$").containsMatchIn(protocols)) { "Missing protocol $required" }
            }
            val filters = command("libffmpeg.so", listOf("-hide_banner", "-filters"))
            for (required in listOf("aresample", "atempo", "rubberband", "loudnorm", "amix", "afade", "atrim", "concat")) {
                check(Regex("\\s$required\\s").containsMatchIn(filters)) { "Missing audio filter $required" }
            }
            val input = File(directory, "reference.wav")
            val expected = wave24(input)
            val sourceHash = hash(input)
            val decoded = File(directory, "decoded.f32le")
            data class Fixture(val extension: String, val codec: String, val extra: List<String> = emptyList(), val exact: Boolean = false)
            val fixtures = listOf(
                Fixture("flac", "flac", listOf("-sample_fmt", "s32"), true), Fixture("mp3", "libmp3lame"),
                Fixture("m4a", "aac"), Fixture("aac", "aac"), Fixture("ogg", "libvorbis", listOf("-q:a", "5")),
                Fixture("opus", "libopus"), Fixture("alac.m4a", "alac", exact = true),
                Fixture("aiff", "pcm_s24be", exact = true), Fixture("aif", "pcm_s24be", exact = true),
                Fixture("mp4", "aac"), Fixture("webm", "libopus"),
            )
            val frames = mutableMapOf<String, Int>()
            for (fixture in fixtures) {
                val encoded = File(directory, "precision.${fixture.extension}")
                command("libffmpeg.so", listOf("-nostdin", "-v", "error", "-y", "-i", input.path, "-c:a", fixture.codec) + fixture.extra + encoded.path)
                val original = hash(encoded)
                val info = JSONObject(command("libffprobe.so", listOf("-v", "error", "-select_streams", "a:0", "-show_streams", "-of", "json", encoded.path)))
                    .getJSONArray("streams").getJSONObject(0)
                check(info.getInt("sample_rate") == 48_000 && info.getInt("channels") == 2)
                decode(encoded, decoded)
                val pcm = decoded.readBytes()
                val count = pcm.size / 8
                check(count in 96_000 until 100_000)
                if (fixture.exact) check(pcm.contentEquals(expected)) { "24-bit/first-last/stereo/polarity changed: ${fixture.extension}" }
                if (fixture.extension in setOf("mp3", "opus", "webm")) check(count == 96_000) { "Gapless delay/end padding changed" }
                val samples = FloatArray(count * 2).also { ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(it) }
                fun amplitude(channel: Int, frequency: Int): Double {
                    var real = 0.0
                    var imaginary = 0.0
                    for (frame in 24_000 until 72_000) {
                        val phase = 2 * PI * frequency * frame / 48_000
                        real += samples[frame * 2 + channel] * cos(phase)
                        imaginary += samples[frame * 2 + channel] * sin(phase)
                    }
                    return 2 * hypot(real, imaginary) / 48_000
                }
                check(amplitude(0, 701) > .23 && amplitude(1, 1703) > .11 && amplitude(0, 1703) < .01 && amplitude(1, 701) < .01)
                val reference = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val delay = if (fixture.extension == "aac") 1024 else 0
                for (channel in 0..1) {
                    var dot = 0.0
                    var originalEnergy = 0.0
                    var decodedEnergy = 0.0
                    for (frame in 24_000 until 72_000) {
                        val originalValue = reference[frame * 2 + channel].toDouble()
                        val decodedValue = samples[(frame + delay) * 2 + channel].toDouble()
                        dot += originalValue * decodedValue
                        originalEnergy += originalValue * originalValue
                        decodedEnergy += decodedValue * decodedValue
                    }
                    check(dot / sqrt(originalEnergy * decodedEnergy) > .98) { "Channel polarity/timing changed" }
                }
                frames[fixture.extension] = count
                // Same command contract as the legacy converter, with owned files.
                val legacy = File(directory, "legacy.wav")
                ffmpeg("-protocol_whitelist", "file,pipe", "-i", encoded.path, "-map", "0:a:0", "-vn", "-c:a", "pcm_s16le", "-ar", "48000", "-ac", "2", legacy.path)
                check(legacy.length() >= count * 4L + 44)
                val converted = JSONObject(command("libffprobe.so", listOf("-v", "error", "-select_streams", "a:0", "-show_streams", "-of", "json", legacy.path)))
                    .getJSONArray("streams").getJSONObject(0)
                check(converted.getString("codec_name") == "pcm_s16le" && converted.getInt("channels") == 2 && converted.getInt("sample_rate") == 48_000)
                check(hash(encoded) == original)
            }
            val flac = File(directory, "precision.flac")
            for ((start, count) in listOf(6000 to 1000, 95_520 to 480)) {
                ffmpeg("-ss", (start / 48000.0).toString(), "-i", flac.path, "-t", (count / 48000.0).toString(),
                    "-map", "0:a:0", "-vn", "-c:a", "pcm_f32le", "-f", "f32le", decoded.path)
                check(decoded.readBytes().contentEquals(expected.copyOfRange(start * 8, (start + count) * 8))) { "Range seek changed" }
            }
            val mono = File(directory, "mono44100.wav")
            wave24(mono, rate = 44_100, frames = 44_101, channels = 1)
            ffmpeg("-i", mono.path, "-ar", "48000", "-ac", "2", "-c:a", "pcm_f32le", "-f", "f32le", decoded.path)
            val resampled = decoded.readBytes()
            check(resampled.size == 48_002 * 8)
            val floats = ByteBuffer.wrap(resampled).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            while (floats.hasRemaining()) check(floats.get() == floats.get())
            val broken = File(directory, "broken.flac").apply { writeText("not audio") }
            command("libffmpeg.so", listOf("-nostdin", "-v", "error", "-i", broken.path, "-f", "null", "-"), accepted = false)
            // Exercise the shipped Python/yt-dlp/FFmpeg command bridge using our
            // local synthetic file; this is deliberately not provider acceptance.
            // getInfo exercises the production Jackson bean mapping after R8.
            fun metadataRequest() = YoutubeDLRequest(flac.toURI().toString()).addOption("--enable-file-urls")
            fun rawMetadata(request: YoutubeDLRequest): JSONObject {
                // library:0.18.1 getInfo uses this exact flag, then its ObjectMapper.
                val output = YoutubeDL.getInstance().execute(request.addOption("--dump-json"), processId = processId).out
                check(output.length <= 1_048_576) { "Unexpected metadata response size" }
                return JSONObject(output)
            }
            fun checkMetadata(raw: JSONObject, mapped: VideoInfo) {
                for ((key, value) in listOf("id" to mapped.id, "title" to mapped.title, "url" to mapped.url,
                    "ext" to mapped.ext, "format_id" to mapped.formatId)) {
                    check(!raw.isNull(key) && raw.getString(key).isNotBlank() && value == raw.getString(key)) {
                        "Production metadata differs from raw field $key"
                    }
                }
                // A direct file may only have the top-level format. Do not demand
                // a formats array which the actual Android yt-dlp did not return.
                val rawFormats = raw.optJSONArray("formats")
                val mappedFormats = mapped.formats
                if (rawFormats == null) check(mappedFormats == null) { "Unexpected mapped formats" }
                else {
                    check(mappedFormats != null && mappedFormats.size == rawFormats.length()) { "Formats count differs from raw" }
                    for (index in 0 until rawFormats.length()) {
                        val format = rawFormats.getJSONObject(index)
                        check(mappedFormats[index].formatId == format.getString("format_id") &&
                            mappedFormats[index].url == format.getString("url") &&
                            mappedFormats[index].ext == format.getString("ext")) { "Nested format differs from raw" }
                    }
                }
                val rawThumbnails = raw.optJSONArray("thumbnails")
                val mappedThumbnails = mapped.thumbnails
                if (rawThumbnails == null) check(mappedThumbnails == null) { "Unexpected mapped thumbnails" }
                else {
                    check(mappedThumbnails != null && mappedThumbnails.size == rawThumbnails.length()) { "Thumbnails count differs from raw" }
                    for (index in 0 until rawThumbnails.length()) {
                        val thumbnail = rawThumbnails.getJSONObject(index)
                        check(mappedThumbnails[index].id == thumbnail.getString("id") &&
                            mappedThumbnails[index].url == thumbnail.getString("url")) { "Nested thumbnail differs from raw" }
                    }
                }
            }
            fun shape(value: JSONObject): JSONObject {
                val keys = java.util.ArrayList<String>()
                val iterator = value.keys()
                while (iterator.hasNext()) keys.add(iterator.next())
                java.util.Collections.sort(keys)
                return JSONObject().put("keys", JSONArray(keys)).put("formatsPresent", value.has("formats"))
                    .put("formatsNull", value.isNull("formats")).put("formatsLength", value.optJSONArray("formats")?.length() ?: -1)
                    .put("ytDlpVersion", value.optJSONObject("_version")?.optString("version") ?: "unknown")
            }
            val metadataDiagnostic = JSONObject()
                .put("ytDlpSha256", hash(File(context.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")))
            val flacHash = hash(flac)
            try {
                val raw = rawMetadata(metadataRequest())
                val metadata = YoutubeDL.getInstance().getInfo(metadataRequest())
                metadataDiagnostic.put("direct", shape(raw)).put("mappedFormatsLength", metadata.formats?.size ?: -1)
                checkMetadata(raw, metadata)
                check(File(java.net.URI(requireNotNull(metadata.url))).canonicalFile == flac.canonicalFile) { "Direct metadata URL changed" }
                check(metadata.ext == "flac")

                // Require nested arrays independently of a platform's direct-file
                // shape. Owned info JSON still passes through the shipped yt-dlp
                // and the very same production getInfo/ObjectMapper after R8.
                val nested = JSONObject().put("id", "owned-nested-metadata").put("title", "Owned nested metadata")
                    .put("extractor", "generic").put("formats", JSONArray().put(JSONObject()
                        .put("format_id", "owned-flac").put("url", flac.toURI()).put("ext", "flac").put("vcodec", "none").put("acodec", "flac"))
                        .put(JSONObject().put("format_id", "owned-wave").put("url", input.toURI()).put("ext", "wav")
                            .put("vcodec", "none").put("acodec", "pcm_s24le")))
                    .put("thumbnails", JSONArray().put(JSONObject().put("id", "owned-small").put("url", File(directory, "small.png").toURI()))
                        .put(JSONObject().put("id", "owned-large").put("url", File(directory, "large.png").toURI())))
                    // VideoInfo:0.18.1 has no subtitles member; confirm that the
                    // supported nested fields survive this unknown property too.
                    .put("subtitles", JSONObject().put("ja", JSONArray().put(JSONObject()
                        .put("url", File(directory, "owned.vtt").toURI()).put("ext", "vtt"))))
                val infoFile = File(directory, "owned.info.json").apply { writeText(nested.toString()) }
                fun nestedRequest() = metadataRequest().addCommands(listOf("--load-info-json", infoFile.path))
                val nestedRaw = rawMetadata(nestedRequest())
                val nestedMapped = YoutubeDL.getInstance().getInfo(nestedRequest())
                metadataDiagnostic.put("nested", shape(nestedRaw)).put("nestedMappedFormatsLength", nestedMapped.formats?.size ?: -1)
                check(nestedRaw.getJSONArray("formats").length() == 2 && nestedRaw.getJSONArray("thumbnails").length() == 2)
                check(nestedRaw.getJSONObject("subtitles").getJSONArray("ja").getJSONObject(0).getString("ext") == "vtt")
                check(nestedMapped.id == "owned-nested-metadata" && nestedMapped.title == "Owned nested metadata")
                checkMetadata(nestedRaw, nestedMapped)
                for (index in 0 until nested.getJSONArray("formats").length()) {
                    val supplied = nested.getJSONArray("formats").getJSONObject(index)
                    check(requireNotNull(nestedMapped.formats).any { it.formatId == supplied.getString("format_id") &&
                        it.url == supplied.getString("url") && it.ext == supplied.getString("ext") }) { "Owned format lost" }
                }
                for (index in 0 until nested.getJSONArray("thumbnails").length()) {
                    val supplied = nested.getJSONArray("thumbnails").getJSONObject(index)
                    check(requireNotNull(nestedMapped.thumbnails).any { it.id == supplied.getString("id") &&
                        it.url == supplied.getString("url") }) { "Owned thumbnail lost" }
                }
            } catch (failure: Exception) {
                // Only bounded key names/counts/version/hash: no URLs or full JSON.
                InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply {
                    putString("choplabCodecMetadataDiagnostic", metadataDiagnostic.toString())
                })
                throw failure
            }
            val request = YoutubeDLRequest(flac.toURI().toString()).addCommands(listOf(
                "--enable-file-urls", "--no-mtime", "--max-filesize", "256M", "-f", "bestaudio/best", "-x",
                "--audio-format", "wav", "-o", File(directory, "yt-dlp.%(ext)s").path,
            ))
            YoutubeDL.getInstance().execute(request, processId = processId)
            check(File(directory, "yt-dlp.wav").length() > 44)
            check(hash(input) == sourceHash && hash(flac) == flacHash)
            val receipt = JSONObject().put("status", "LOCAL_PASS").put("scope", "Android-native-codec-software")
                .put("cacheMigration", migration ?: JSONObject().put("status", "NOT_RUN"))
                .put("pythonZipSha256", hash(File(native, "libpython.zip.so"))).put("privateLzmaAliasAbsent", !java.nio.file.Files.exists(File(packages, "python/usr/lib/liblzma.so").toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                .put("formats", JSONObject(frames as Map<*, *>)).put("ffmpegSha256", hash(File(native, "libffmpeg.so")))
                .put("lossless24BitExact", true).put("rangeSeekExact", true).put("resample44100To48000", true)
                .put("localYtDlpConversion", true).put("jacksonMetadata", true)
                .put("metadataDirectRawMatched", true).put("metadataOwnedFormats", 2).put("metadataOwnedThumbnails", 2)
                .put("providerVerified", false).put("audioDeviceVerified", false)
            InstrumentationRegistry.getInstrumentation().sendStatus(0, android.os.Bundle().apply { putString("choplabCodecReceipt", receipt.toString()) })
        } finally {
            YoutubeDL.getInstance().destroyProcessById(processId)
            check(directory.deleteRecursively())
        }
    }

    /** Simulate only the reviewed stale cache condition in an explicitly owned Preview profile. */
    private fun seedLegacyPythonCache(context: Context, native: File, packages: File, expectedPython: String?): JSONObject? {
        if (InstrumentationRegistry.getArguments().getString("choplabCodecSeedLegacyPythonCache") != "true") return null
        check(context.packageName == "com.choplab.sampler.preview") { "Legacy cache fixture requires the isolated Preview target" }
        check(expectedPython == "3cfe0ce408f8459b4c6fb22c5465c26633ef49b29141338aff84fa6a3ca1abed") {
            "Legacy cache fixture requires the reviewed alias-only Python candidate"
        }
        val archive = File(native, "libpython.zip.so")
        check(archive.length() == 41_890_175L)
        val library = File(packages, "python/usr/lib/liblzma.so.5.8.1")
        check(library.parentFile!!.isDirectory || library.parentFile!!.mkdirs())
        // The derived ZIP retains this exact upstream ELF. Do not ship the old
        // ZIP or call any native executable before YoutubeDL replaces the cache.
        ZipFile(archive).use { zip ->
            val entry = requireNotNull(zip.getEntry("usr/lib/liblzma.so.5.8.1"))
            check(entry.size == 159_272L)
            zip.getInputStream(entry).use { input -> library.outputStream().use { input.copyTo(it) } }
        }
        check(hash(library) == LEGACY_LZMA_SHA256) { "Legacy cache fixture codec identity differs" }
        val alias = File(library.parentFile, "liblzma.so")
        // Remove a pre-existing link before writing so the fixture cannot write
        // through it. The removal is limited to this owned, simulated alias.
        java.nio.file.Files.deleteIfExists(alias.toPath())
        library.inputStream().use { input -> alias.outputStream().use { input.copyTo(it) } }
        check(hash(alias) == LEGACY_LZMA_SHA256)
        val preferences = context.getSharedPreferences("youtubedl-android", Context.MODE_PRIVATE)
        check(preferences.edit().putString("pythonLibVersion", "14305904").commit())
        check(preferences.getString("pythonLibVersion", null) == "14305904")
        return JSONObject().put("status", "SEEDED").put("scope", "owned-simulated-old-python-cache")
            .put("versionBefore", "14305904").put("versionedSha256Before", LEGACY_LZMA_SHA256)
            .put("aliasSha256Before", LEGACY_LZMA_SHA256).put("originalUpstreamZipInstalled", false)
    }

    private companion object {
        const val LEGACY_LZMA_SHA256 = "e391529fe9964ae3ce5ae6da670ebff43c8fcc886d268347a80862996134d6f0"
    }

    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun wave24(file: File, rate: Int = 48_000, frames: Int = 96_000, channels: Int = 2): ByteArray {
        val pcm = ByteArray(frames * channels * 3)
        val expected = ByteBuffer.allocate(frames * channels * 4).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        for (frame in 0 until frames) for (channel in 0 until channels) {
            val value = ((if (channel == 0) .28 else -.14) * sin(2 * PI * (if (channel == 0) 701 else 1703) * frame / rate) * 8_388_608).roundToInt() + frame % 5 - 2
            repeat(3) { byte -> pcm[offset++] = (value shr (byte * 8)).toByte() }
            expected.putFloat(value / 8_388_608f)
        }
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(channels.toShort()).putInt(rate).putInt(rate * channels * 3).putShort((channels * 3).toShort()).putShort(24)
            .put("data".toByteArray()).putInt(pcm.size)
        file.outputStream().use { it.write(header.array()); it.write(pcm) }
        return expected.array()
    }
}
