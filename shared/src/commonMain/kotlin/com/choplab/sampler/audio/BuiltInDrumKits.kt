package com.choplab.sampler.audio

import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.PatternArrangement
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.materializedPatternArrangement
import com.choplab.sampler.model.starterDrumKitInstallationAllowed
import com.choplab.sampler.model.stepKey
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

data class DrumKitDefinition(
    val id: String,
    val name: String,
    val character: String,
    val accent: String,
    internal val tuning: Float,
    internal val noiseAmount: Float,
)

/**
 * Original, deterministic PCM drum synthesis bundled without third-party recordings.
 * Sounds are rendered only when a kit is selected, keeping the APK small.
 */
object BuiltInDrumKits {
    private const val SAMPLE_RATE = 48_000
    private const val DEFAULT_BPM = 92f
    private const val DEFAULT_SWING = 54f
    const val DEFAULT_STARTER_KIT_ID = "dusty-jazz"

    val catalog = listOf(
        DrumKitDefinition("dusty-jazz", "DUSTY JAZZ", "丸いキック / 乾いたスネア", "WARM", 0.92f, 0.34f),
        DrumKitDefinition("boom-bap", "BOOM BAP", "太い低音 / 強いバックビート", "PUNCH", 0.82f, 0.18f),
        DrumKitDefinition("vinyl-soul", "VINYL SOUL", "柔らかい輪郭 / 古い盤の質感", "SOUL", 1.02f, 0.46f),
        DrumKitDefinition("lofi-tape", "LO-FI TAPE", "暗いハット / 揺れた質感", "DARK", 0.74f, 0.62f),
        DrumKitDefinition("clean-studio", "CLEAN STUDIO", "明瞭 / 現代的", "HI-FI", 1.12f, 0.06f),
    )

    private val defaultStarterPattern: Set<Int> by lazy {
        starterPattern(DEFAULT_STARTER_KIT_ID, SamplerConfig.DRUM_BANK_INDEX)
    }
    private val untouchedStarterArrangement: PatternArrangement by lazy {
        PatternArrangement(storedStepsBySlot = listOf(defaultStarterPattern, emptySet()))
    }

    fun createBankPads(kitId: String, bankIndex: Int): List<PadModel> {
        require(bankIndex in 0 until SamplerConfig.BANK_COUNT)
        val kit = catalog.firstOrNull { it.id == kitId } ?: error("Unknown drum kit: $kitId")
        return List(SamplerConfig.DRUM_KIT_PAD_COUNT) { index ->
            val family = index / 4
            val variation = index % 4
            val samples = renderOneShot(kit, family, variation)
            val label = when (family) {
                0 -> "KICK"
                1 -> "SNARE"
                2 -> if (variation < 2) "CLOSED HAT" else "OPEN HAT"
                else -> listOf("CLAP", "RIM", "SHAKER", "PERC")[variation]
            }
            val audio = PcmAudio(
                id = stableId("${kit.id}:$index"),
                name = "${kit.name} $label ${variation + 1}",
                samples = samples,
                sampleRate = SAMPLE_RATE,
            )
            PadModel(
                globalIndex = bankIndex * SamplerConfig.PADS_PER_BANK + index,
                audio = audio,
                startFrame = 0,
                endFrame = audio.frameCount,
                gain = if (family == 2) 0.72f else 0.9f,
                playMode = PadPlayMode.ONE_SHOT,
                contentKind = PadContentKind.DRUM,
                chokeGroup = if (family == 2) 1 else 0,
            )
        }
    }

    fun starterPattern(kitId: String, bankIndex: Int): Set<Int> {
        require(catalog.any { it.id == kitId }) { "Unknown drum kit: $kitId" }
        require(bankIndex in 0 until SamplerConfig.BANK_COUNT)
        val base = bankIndex * SamplerConfig.PADS_PER_BANK
        return buildSet {
            listOf(0, 6, 10).forEach { add(stepKey(base, it)) }
            listOf(4, 12).forEach { add(stepKey(base + 4, it)) }
            (0 until SamplerConfig.STEP_COUNT step 2).forEach { add(stepKey(base + 8, it)) }
            add(stepKey(base + 14, 15))
        }
    }

    fun installStarterKit(state: SamplerUiState): SamplerUiState {
        if (!starterDrumKitInstallationAllowed(state)) return state
        val bankIndex = SamplerConfig.DRUM_BANK_INDEX
        val starterPads = createBankPads(DEFAULT_STARTER_KIT_ID, bankIndex)
        val pads = state.pads.toMutableList()
        starterPads.forEach { pads[it.globalIndex] = it }
        return state.copy(
            pads = pads,
            activeSteps = defaultStarterPattern,
            selectedDrumKitId = DEFAULT_STARTER_KIT_ID,
        )
    }

    fun isPristineStarterProduction(state: SamplerUiState): Boolean =
        state.currentAudio == null && hasUntouchedStarterDrums(state)

    fun hasUntouchedStarterDrums(state: SamplerUiState): Boolean {
        if (
            state.selectedDrumKitId != DEFAULT_STARTER_KIT_ID ||
            state.bpm != DEFAULT_BPM ||
            state.swing != DEFAULT_SWING ||
            state.activeSteps != defaultStarterPattern ||
            state.materializedPatternArrangement() != untouchedStarterArrangement
        ) {
            return false
        }
        val firstDrum = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
        return state.pads.all { pad ->
            val starterIndex = pad.globalIndex - firstDrum
            if (starterIndex in 0 until SamplerConfig.DRUM_KIT_PAD_COUNT) {
                val family = starterIndex / 4
                pad.isAssigned &&
                    pad.contentKind == PadContentKind.DRUM &&
                    pad.audio?.id == stableId("$DEFAULT_STARTER_KIT_ID:$starterIndex") &&
                    pad.startFrame == 0 &&
                    pad.endFrame == pad.audio.frameCount &&
                    pad.pitchSemitones == 0f &&
                    pad.tone == 1f &&
                    pad.gain == (if (family == 2) 0.72f else 0.9f) &&
                    !pad.reverse &&
                    pad.playMode == PadPlayMode.ONE_SHOT &&
                    pad.chokeGroup == (if (family == 2) 1 else 0)
            } else {
                !pad.isAssigned
            }
        }
    }

    private fun renderOneShot(kit: DrumKitDefinition, family: Int, variation: Int): ShortArray {
        val duration = when (family) {
            0 -> 0.42
            1 -> 0.30
            2 -> if (variation < 2) 0.10 else 0.34
            else -> 0.24
        }
        val output = ShortArray((SAMPLE_RATE * duration).toInt())
        val random = Random(stableId("noise:${kit.id}:$family:$variation"))
        var phase = 0.0
        var previousNoise = 0.0
        output.indices.forEach { frame ->
            val time = frame.toDouble() / SAMPLE_RATE
            val progress = frame.toDouble() / output.size
            val noise = random.nextDouble(-1.0, 1.0)
            val highNoise = noise - previousNoise * 0.84
            previousNoise = noise
            val value = when (family) {
                0 -> {
                    val frequency = (42.0 + 92.0 * exp(-time * 24.0)) * kit.tuning
                    phase += 2.0 * PI * frequency / SAMPLE_RATE
                    sin(phase) * exp(-time * (8.0 + variation)) + noise * kit.noiseAmount * exp(-time * 45.0)
                }
                1 -> {
                    val body = sin(2.0 * PI * (165.0 + variation * 13.0) * time) * exp(-time * 18.0)
                    body * 0.42 + highNoise * exp(-time * (11.0 + variation)) * (0.72 + kit.noiseAmount)
                }
                2 -> {
                    val decay = if (variation < 2) 52.0 - variation * 7.0 else 12.0 + variation
                    highNoise * exp(-time * decay) * (0.72 + kit.noiseAmount * 0.22)
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
            output[frame] = (value * edgeFade * 22_000.0)
                .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                .toInt()
                .toShort()
        }
        return output
    }

    private fun stableId(value: String): Long {
        var hash = 1_125_899_906_842_597L
        value.forEach { char -> hash = hash * 31L + char.code }
        return hash and Long.MAX_VALUE
    }
}
