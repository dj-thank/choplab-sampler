package com.choplab.jvm.ai

import com.choplab.core.ai.*
import com.choplab.core.model.*
import com.choplab.jvm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.nio.file.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

enum class DesktopSpeechPlatform { MAC, WINDOWS, UNSUPPORTED }

/** Thin installed-voice adapter. It never downloads voices or invokes a cloud API. */
class DesktopTtsProvider(
    private val temporaryDirectory: Path,
    private val platform: DesktopSpeechPlatform = when {
        System.getProperty("os.name").startsWith("Mac", true) -> DesktopSpeechPlatform.MAC
        System.getProperty("os.name").startsWith("Windows", true) -> DesktopSpeechPlatform.WINDOWS
        else -> DesktopSpeechPlatform.UNSUPPORTED
    },
    private val runner: SpeechProcessRunner = DeviceTtsProcess(),
    private val osVersion: String = System.getProperty("os.version"),
    // Device APIs don't report the installed voice-data version. Do not reuse an unknown version across sessions.
    private val voiceSessionVersion: String = "unreported-session-${UUID.randomUUID()}",
    private val memory: PcmMemoryBudget = PcmMemoryBudget.shared,
) : TtsProvider {
    private val closed = AtomicBoolean()
    private val serial = Mutex()
    private var installed: FrozenList<TtsVoice>? = null
    private val engine = TtsEngine("device-${platform.name.lowercase()}", osVersion, "system", osVersion)

    override suspend fun voices(): TtsResult<FrozenList<TtsVoice>> = serial.withLock {
        if (closed.get()) return@withLock ttsFailure(TtsProblem.CLOSED)
        if (platform == DesktopSpeechPlatform.UNSUPPORTED) return@withLock ttsFailure(TtsProblem.UNAVAILABLE)
        installed?.let { return@withLock TtsResult.Success(it) }
        withContext(Dispatchers.IO) {
            inTemporary { directory ->
                val result = when (platform) {
                    DesktopSpeechPlatform.MAC -> runner.run(listOf("/usr/bin/say", "-v", "?"), directory)
                    DesktopSpeechPlatform.WINDOWS -> windows(directory, "voices", null)
                }
                if (closed.get()) return@inTemporary ttsFailure(TtsProblem.CLOSED)
                if (result.exitCode != 0) return@inTemporary ttsFailure(TtsProblem.UNAVAILABLE)
                val voices = if (platform == DesktopSpeechPlatform.MAC) macVoices(result.output) else windowsVoices(result.output)
                if (voices.isEmpty()) ttsFailure(TtsProblem.NO_OFFLINE_VOICE)
                else { installed = voices.frozen(); TtsResult.Success(requireNotNull(installed)) }
            }
        }
    }

    override suspend fun synthesize(request: TtsRequest): TtsResult<TtsAudio> {
        val available = when (val result = voices()) { is TtsResult.Success -> result.value; is TtsResult.Failure -> return result }
        return serial.withLock {
            if (closed.get()) return@withLock ttsFailure(TtsProblem.CLOSED)
            if (request.voice !in available) return@withLock ttsFailure(TtsProblem.VOICE_CHANGED)
            if (!request.voice.offline) return@withLock ttsFailure(TtsProblem.NO_OFFLINE_VOICE)
            if (request.settings.style != "neutral" || request.settings.pitchPermille != 1000 ||
                platform == DesktopSpeechPlatform.MAC && request.settings.volumePermille != 1000) return@withLock ttsFailure(TtsProblem.UNSUPPORTED_SETTINGS)
            // say interprets embedded speech commands; plain lyric input must not silently change voice/settings.
            if (platform == DesktopSpeechPlatform.MAC && ("[[" in request.spokenText || "]]" in request.spokenText)) return@withLock ttsFailure(TtsProblem.INVALID_INPUT)
            var delivering: TtsAudio? = null
            try { withContext(Dispatchers.IO) {
                inTemporary { directory ->
                    val output = directory.resolve("speech.wav")
                    val result = if (platform == DesktopSpeechPlatform.MAC) {
                        val input = directory.resolve("text.txt")
                        Files.writeString(input, request.spokenText, Charsets.UTF_8)
                        runner.run(listOf("/usr/bin/say", "-v", request.voice.id, "-r", (175.0 * request.settings.ratePermille / 1000).roundToInt().toString(),
                            "-f", input.toString(), "-o", output.toString(), "--file-format=WAVE", "--data-format=LEF32"), directory)
                    } else windows(directory, "synthesize", request)
                    coroutineContext.ensureActive()
                    if (closed.get()) return@inTemporary ttsFailure(TtsProblem.CLOSED)
                    if (result.exitCode != 0 || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) return@inTemporary ttsFailure(TtsProblem.FAILED)
                    if (Files.size(output) > TtsLimits.MAX_WAV_BYTES) return@inTemporary ttsFailure(TtsProblem.TOO_LARGE)
                    val context = coroutineContext
                    val audio = SpeechPcmIo.read(output, memory) { context.ensureActive(); check(!closed.get()) }.also { delivering = it }
                    if (closed.get() || !context.isActive) { audio.close(); context.ensureActive(); ttsFailure(TtsProblem.CLOSED) }
                    else TtsResult.Success(audio)
                }
            }.also { delivering = null } } finally { delivering?.close() }
        }
    }

    private suspend fun <T> inTemporary(work: suspend (Path) -> TtsResult<T>): TtsResult<T> {
        Files.createDirectories(temporaryDirectory)
        require(!Files.isSymbolicLink(temporaryDirectory))
        val directory = Files.createTempDirectory(temporaryDirectory, "speech-")
        return try { work(directory) }
        catch (_: TimeoutCancellationException) { ttsFailure(TtsProblem.TIMEOUT) }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: PcmMemoryLimit) { ttsFailure(TtsProblem.MEMORY_LIMIT) }
        catch (_: IllegalArgumentException) { ttsFailure(TtsProblem.INVALID_AUDIO) }
        catch (_: Exception) { ttsFailure(TtsProblem.FAILED) }
        finally { Files.list(directory).use { files -> files.forEach { Files.deleteIfExists(it) } }; Files.deleteIfExists(directory) }
    }

    private fun macVoices(bytes: ByteArray): List<TtsVoice> {
        val pattern = Regex("^(.+?)\\s+([a-z]{2}[_-][A-Za-z_0-9-]+)\\s+#.*$")
        return bytes.toString(Charsets.UTF_8).lineSequence().mapNotNull { line ->
            val match = pattern.matchEntire(line.trim()) ?: return@mapNotNull null
            val name = match.groupValues[1].trim(); val locale = match.groupValues[2].replace('_', '-')
            val language = language(locale) ?: return@mapNotNull null
            TtsVoice(engine, name, name, locale, voiceSessionVersion, language)
        }.take(256).distinctBy { it.id }.toList()
    }
    private fun windowsVoices(bytes: ByteArray): List<TtsVoice> {
        val values = ProjectJson.parse(bytes) as? JsonArray ?: throw IllegalArgumentException()
        require(values.size <= 256)
        return values.mapNotNull { element ->
            val item = element.obj().fields("name", "locale")
            val locale = item.string("locale"); val language = language(locale) ?: return@mapNotNull null
            val name = item.string("name")
            TtsVoice(engine, name, name, locale, voiceSessionVersion, language)
        }.distinctBy { it.id }
    }
    private fun language(locale: String) = when (locale.substringBefore('-').lowercase()) { "ja" -> LyricLanguage.JAPANESE; "en" -> LyricLanguage.ENGLISH; else -> null }

    private suspend fun windows(directory: Path, operation: String, request: TtsRequest?): SpeechProcessResult {
        val input = directory.resolve("request.json")
        Files.write(input, ProjectJson.encodeElement(obj("operation" to str(operation), "text" to str(request?.spokenText ?: ""),
            "voice" to str(request?.voice?.id ?: ""), "rate" to num(request?.settings?.ratePermille?.let { (log2(it / 1000.0) * 10).roundToInt().coerceIn(-10, 10) } ?: 0),
            "volume" to num((request?.settings?.volumePermille ?: 1000) / 10))))
        // A fixed command works with the default no-script-file policy. No policy override, user text, voice or
        // path is interpolated into PowerShell code; all data comes from fixed names in the private process cwd.
        return runner.run(listOf("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", WINDOWS_SCRIPT), directory)
    }
    override fun close() { if (closed.compareAndSet(false, true)) runner.close() }

    private companion object {
        val WINDOWS_SCRIPT = """
            ${'$'}ErrorActionPreference = 'Stop'
            ${'$'}RequestFile = [System.IO.Path]::Combine([Environment]::CurrentDirectory, 'request.json')
            ${'$'}OutputFile = [System.IO.Path]::Combine([Environment]::CurrentDirectory, 'speech.wav')
            [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
            Add-Type -AssemblyName System.Speech
            ${'$'}request = [System.IO.File]::ReadAllText(${'$'}RequestFile, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
            ${'$'}synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
            try {
                if (${'$'}request.operation -eq 'voices') {
                    ${'$'}voices = @(${'$'}synth.GetInstalledVoices() | Where-Object { ${'$'}_.Enabled } | ForEach-Object {
                        @{ name = ${'$'}_.VoiceInfo.Name; locale = ${'$'}_.VoiceInfo.Culture.Name }
                    })
                    ConvertTo-Json -InputObject ${'$'}voices -Compress
                } elseif (${'$'}request.operation -eq 'synthesize') {
                    ${'$'}synth.SelectVoice(${'$'}request.voice)
                    ${'$'}synth.Rate = [int]${'$'}request.rate
                    ${'$'}synth.Volume = [int]${'$'}request.volume
                    ${'$'}synth.SetOutputToWaveFile(${'$'}OutputFile)
                    ${'$'}synth.Speak([string]${'$'}request.text)
                    ${'$'}synth.SetOutputToNull()
                } else { exit 2 }
            } finally { ${'$'}synth.Dispose() }
        """.trimIndent()
    }
}
