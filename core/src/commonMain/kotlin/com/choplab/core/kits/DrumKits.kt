package com.choplab.core.kits

import com.choplab.core.model.Asset
import com.choplab.core.model.AssetRole
import com.choplab.core.model.FrameRange
import com.choplab.core.model.Pad
import com.choplab.engine.PlayMode
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** One of the original synthesized kits. No third-party recording ships; sounds render on demand. */
class DrumKit internal constructor(val id: String, val name: String, internal val tuning: Float, internal val noise: Float)

/** A kit sound: its kit and its slot, 0..15 (four families of four variations). */
data class KitSound(val kit: DrumKit, val slot: Int)

/**
 * The five built-in kits of the earlier app, rendered with the same deterministic synthesis: 16 mono
 * 16-bit one-shots at 48 kHz per kit, families kick, snare, hat and percussion in slots 0-3, 4-7, 8-11, 12-15.
 * Hosts store each rendered sound as an app-rendered WAV [Asset] named by [soundName].
 */
object DrumKits {
    const val SAMPLE_RATE = 48_000
    const val SOUNDS = 16
    /** The earlier app kept its kit in BANK B; the linked editor installs there too. */
    const val BANK = 1

    val catalog: List<DrumKit> = listOf(
        DrumKit("dusty-jazz", "DUSTY JAZZ", 0.92f, 0.34f),
        DrumKit("boom-bap", "BOOM BAP", 0.82f, 0.18f),
        DrumKit("vinyl-soul", "VINYL SOUL", 1.02f, 0.46f),
        DrumKit("lofi-tape", "LO-FI TAPE", 0.74f, 0.62f),
        DrumKit("clean-studio", "CLEAN STUDIO", 1.12f, 0.06f),
    )

    fun kit(id: String): DrumKit = requireNotNull(catalog.firstOrNull { it.id == id }) { "Unknown drum kit" }

    /** Durable name of a kit sound's asset, as the earlier app named it; also how [identify] knows it. */
    fun soundName(kit: DrumKit, slot: Int): String = "${kit.name} ${padName(slot)}"

    /** Short PAD label for a slot, such as "KICK 1" or "OPEN HAT 3". */
    fun padName(slot: Int): String {
        requireSlot(slot)
        val variation = slot % 4
        val label = when (slot / 4) {
            0 -> "KICK"
            1 -> "SNARE"
            2 -> if (variation < 2) "CLOSED HAT" else "OPEN HAT"
            else -> listOf("CLAP", "RIM", "SHAKER", "PERC")[variation]
        }
        return "$label ${variation + 1}"
    }

    /** Length of a slot's sound; the same in every kit, so a placed clip fits any kit's sound. */
    fun frames(slot: Int): Int {
        requireSlot(slot)
        val seconds = when (slot / 4) {
            0 -> 0.42
            1 -> 0.30
            2 -> if (slot % 4 < 2) 0.10 else 0.34
            else -> 0.24
        }
        return (SAMPLE_RATE * seconds).toInt()
    }

    /** Recognizes an app-rendered kit sound by its metadata; imported or other audio is never a kit sound. */
    fun identify(asset: Asset): KitSound? {
        if (asset.role != AssetRole.RENDERED || asset.extension != "wav" || asset.sampleRate != SAMPLE_RATE || asset.channels != 1) return null
        return soundsByName[asset.name]?.takeIf { asset.frames == frames(it.slot).toLong() }
    }
    /** Every kit sound by its stored name, built once: recognizing an asset builds no names. */
    private val soundsByName: Map<String, KitSound> by lazy {
        buildMap { for (kit in catalog) for (slot in 0 until SOUNDS) put(soundName(kit, slot), KitSound(kit, slot)) }
    }

    /** The PAD the earlier app made for a slot: whole sound, one-shot, hats sharing one choke group. */
    fun pad(padId: Int, slot: Int, asset: Asset): Pad {
        val sound = requireNotNull(identify(asset)) { "Not a kit sound" }
        require(sound.slot == slot)
        val hat = slot / 4 == 2
        return Pad(padId, asset.hash, FrameRange(0, asset.frames), padName(slot), PlayMode.ONE_SHOT,
            gain = if (hat) 0.72f else 0.9f, chokeGroup = if (hat) 1 else 0, attackFrames = 0)
    }

    /** Deterministic mono PCM-16 for one slot, identical to the earlier app's synthesis. */
    fun render(kit: DrumKit, slot: Int): ShortArray {
        val family = slot / 4
        val variation = slot % 4
        val output = ShortArray(frames(slot))
        val random = Random(stableId("noise:${kit.id}:$family:$variation"))
        var phase = 0.0
        var previousNoise = 0.0
        for (frame in output.indices) {
            val time = frame.toDouble() / SAMPLE_RATE
            val progress = frame.toDouble() / output.size
            val noise = random.nextDouble(-1.0, 1.0)
            val highNoise = noise - previousNoise * 0.84
            previousNoise = noise
            val value = when (family) {
                0 -> {
                    val frequency = (42.0 + 92.0 * exp(-time * 24.0)) * kit.tuning
                    phase += 2.0 * PI * frequency / SAMPLE_RATE
                    sin(phase) * exp(-time * (8.0 + variation)) + noise * kit.noise * exp(-time * 45.0)
                }
                1 -> {
                    val body = sin(2.0 * PI * (165.0 + variation * 13.0) * time) * exp(-time * 18.0)
                    body * 0.42 + highNoise * exp(-time * (11.0 + variation)) * (0.72 + kit.noise)
                }
                2 -> {
                    val decay = if (variation < 2) 52.0 - variation * 7.0 else 12.0 + variation
                    highNoise * exp(-time * decay) * (0.72 + kit.noise * 0.22)
                }
                else -> {
                    val burst = when (variation) {
                        0 -> if ((time % 0.026) < 0.009) 1.0 else 0.22
                        1 -> 0.38
                        2 -> if ((frame / 180) % 2 == 0) 0.8 else 0.35
                        else -> 0.5
                    }
                    val tonal = sin(2.0 * PI * (410.0 + variation * 170.0) * time) * 0.28
                    (highNoise * burst + tonal) * exp(-time * (15.0 + variation * 2.0))
                }
            }
            val edgeFade = (1.0 - progress).coerceIn(0.0, 1.0)
            output[frame] = (value * edgeFade * 22_000.0).coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()
        }
        return output
    }

    private fun requireSlot(slot: Int) = require(slot in 0 until SOUNDS)

    private fun stableId(value: String): Long {
        var hash = 1_125_899_906_842_597L
        value.forEach { char -> hash = hash * 31L + char.code }
        return hash and Long.MAX_VALUE
    }
}
