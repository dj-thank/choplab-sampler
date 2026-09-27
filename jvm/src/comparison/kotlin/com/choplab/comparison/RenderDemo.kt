package com.choplab.comparison

import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import com.choplab.engine.OfflineRender
import com.choplab.engine.Pad
import com.choplab.engine.Pattern
import com.choplab.engine.PcmAsset
import com.choplab.engine.PlayMode
import com.choplab.engine.SequenceNote
import com.choplab.engine.Tempo
import com.choplab.jvm.WavCodec
import com.choplab.sampler.audio.PatternRenderer
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.stepKey
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val RATE = 48_000
private const val BARS = 4
private const val FRAMES = RATE * 8

private data class Sound(val data: ShortArray, val pitch: Double = 0.0, val gain: Float = .9f, val loop: Boolean = false)
private data class Scene(val id: String, val title: String, val sounds: List<Sound>, val steps: List<Int>)

/** Generated here: no user audio, artist recording, microphone, network or output device. */
private fun synth(frames: Int, frequency: Double, amplitude: Double, decay: Double = 0.0, highTone: Boolean = false): ShortArray =
    ShortArray(frames * 2) { index ->
        val frame = index / 2
        val seconds = frame.toDouble() / RATE
        val envelope = if (decay > 0) exp(-seconds / decay) else 1.0
        val signal = sin(2 * PI * frequency * seconds) +
            if (highTone) .35 * sin(2 * PI * 17_000 * seconds) else 0.0
        val channelGain = if (index % 2 == 0) 1.0 else .65
        (signal * envelope * amplitude * channelGain * 32767).toInt().coerceIn(-32768, 32767).toShort()
    }

private fun scenes() = listOf(
    Scene("pitched-chop", "+12半音のチョップ", listOf(Sound(synth(12_000, 440.0, .5, highTone = true), pitch = 12.0)), listOf(0, 4, 8, 12)),
    Scene("loop-seam", "周期を変えないループの継ぎ目", listOf(Sound(synth(6576, 220.0, .45), loop = true)), emptyList()),
    Scene("dense-drums", "16音を重ねた合成パーカッション", (0 until 16).map { Sound(synth(16_000, 70.0 + it * 47, .65, decay = .08 + it * .004), gain = 1f) }, listOf(0, 4, 8, 12)),
    Scene("quiet-fade", "小さい音量のフェード", listOf(Sound(synth(60_000, 523.25, .35, decay = .12), gain = .015f)), listOf(0, 8)),
)

private fun peak(data: FloatArray): Double = data.maxOf { abs(it.toDouble()) }
private fun rms(data: FloatArray): Double = sqrt(data.sumOf { it.toDouble() * it } / data.size)
private fun sha(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(65536)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            digest.update(buffer, 0, size)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

fun main(args: Array<String>) {
    require(args.size == 1)
    val root = Path.of(args[0]).toAbsolutePath().normalize()
    Files.createDirectories(root)
    val notes = StringBuilder("# Synthetic engine A/B\n\n")
        .append("All input is deterministic synthesis in RenderDemo.kt. Both renderers receive the same PCM16 stereo input. ")
        .append("This isolates engine/export changes; it does not prove a new decoder or online source has better quality.\n\n")
        .append("Each pair is matched to the same stereo RMS, capped so neither peak exceeds 0.85. ")
        .append("This is RMS level matching, not gated LUFS or a claim of identical perceived loudness. ")
        .append("Raw outputs are retained. No device, microphone or subjective hearing test is performed.\n\n")
    val sums = StringBuilder()
    for (scene in scenes()) {
        val oldPads = List(SamplerConfig.PAD_COUNT) { index ->
            scene.sounds.getOrNull(index)?.let { sound ->
                PadModel(index, PcmAudio(name = "synthetic-${scene.id}-$index", samples = sound.data, sampleRate = RATE, channelCount = 2),
                    endFrame = sound.data.size / 2, pitchSemitones = sound.pitch.toFloat(), gain = sound.gain,
                    playMode = if (sound.loop) PadPlayMode.LOOP else PadPlayMode.ONE_SHOT)
            } ?: PadModel(index)
        }
        val oldRaw = root.resolve("${scene.id}-legacy-raw.wav")
        val steps = scene.sounds.indices.flatMap { pad -> scene.steps.map { stepKey(pad, it) } }.toSet()
        PatternRenderer.renderToWav(oldRaw.toFile(), oldPads, steps, 120f, 50f, BARS, RATE)
        val old = Files.newInputStream(oldRaw).use { WavCodec.read(it).samples }
        val newPads = scene.sounds.mapIndexed { index, sound ->
            Pad(index, PcmAsset.fromInterleaved(FloatArray(sound.data.size) { sound.data[it] / 32768f }),
                pitchSemitones = sound.pitch, gain = sound.gain, attackFrames = 48,
                mode = if (sound.loop) PlayMode.LOOP else PlayMode.ONE_SHOT)
        }
        val pattern = Pattern(4 * 960, scene.sounds.indices.flatMap { pad -> scene.steps.map { SequenceNote(it * 240, pad) } })
        val program = EngineProgram(newPads, pattern, Tempo())
        val commands = if (scene.sounds.any { it.loop }) listOf(EngineCommand.Trigger(0, 1, 0))
            else listOf(EngineCommand.StartSequence(0, 1))
        val fresh = OfflineRender.render(program, commands, FRAMES)
        check(old.size == fresh.size && old.size == FRAMES * 2)
        check(old.all { it.isFinite() } && fresh.all { it.isFinite() })
        val oldRms = rms(old)
        val newRms = rms(fresh)
        check(oldRms > 0 && newRms > 0)
        val target = min(.1, min(oldRms * .85 / max(peak(old), 1e-12), newRms * .85 / max(peak(fresh), 1e-12)))
        val oldGain = target / oldRms
        val newGain = target / newRms
        val rawNew = root.resolve("${scene.id}-new-raw.wav")
        Files.newOutputStream(rawNew).use { WavCodec.writeFloat(it, fresh) }
        for ((name, data, gain) in listOf(Triple("legacy", old, oldGain), Triple("new", fresh, newGain))) {
            val normalized = FloatArray(data.size) { (data[it] * gain).toFloat() }
            check(abs(rms(normalized) - target) < 1e-7 && peak(normalized) <= .850001)
            val file = root.resolve("${scene.id}-$name-matched.wav")
            Files.newOutputStream(file).use { WavCodec.writePcm(it, normalized, bits = 24, seed = 1729) }
            val decoded = Files.newInputStream(file).use { WavCodec.read(it) }
            check(decoded.info.frames == FRAMES.toLong() && decoded.info.channels == 2)
            check(abs(rms(decoded.samples) - target) < 1e-6)
            sums.append(sha(file)).append("  ").append(file.fileName).append('\n')
        }
        sums.append(sha(oldRaw)).append("  ").append(oldRaw.fileName).append('\n')
        sums.append(sha(rawNew)).append("  ").append(rawNew.fileName).append('\n')
        notes.append("## ").append(scene.title).append("\n\n")
            .append("- [Legacy matched](").append(scene.id).append("-legacy-matched.wav) / [New matched](").append(scene.id).append("-new-matched.wav)\n")
            .append(String.format(Locale.ROOT, "- 48 kHz stereo, 8 s; common RMS %.8f; legacy gain %.5f / new gain %.5f.\n", target, oldGain, newGain))
            .append("- Legacy renderer is the archived 0.18.0 behavior; new render uses EngineCore with its 72-frame lookahead removed.\n\n")
        println("A/B ${scene.id}: ${FRAMES}frames stereo, level and WAV readback verified")
    }
    Files.writeString(root.resolve("README.md"), notes)
    Files.writeString(root.resolve("SHA256SUMS"), sums)
}
