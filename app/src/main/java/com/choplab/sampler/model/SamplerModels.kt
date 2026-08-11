package com.choplab.sampler.model

import kotlin.math.max

object SamplerConfig {
    const val BANK_COUNT = 4
    const val PADS_PER_BANK = 16
    const val PAD_COUNT = BANK_COUNT * PADS_PER_BANK
    const val STEP_COUNT = 16
    const val DRUM_BANK_INDEX = 1
    const val VOCAL_BANK_INDEX = 3
}

enum class BankRole(
    val letter: String,
    val japaneseLabel: String,
    val englishLabel: String,
) {
    MELODY("A", "メロディー", "MELODY"),
    DRUMS("B", "ドラム", "DRUMS"),
    ONE_SHOTS("C", "ワンショット", "ONE SHOTS"),
    VOICE("D", "ボイス", "VOICE"),
}

fun bankRoleFor(bankIndex: Int): BankRole =
    BankRole.entries[bankIndex.coerceIn(0, SamplerConfig.BANK_COUNT - 1)]

enum class DrumKitApplyDecision {
    APPLY,
    CONFIRM_REPLACE,
}

fun drumKitApplyDecision(pads: List<PadModel>): DrumKitApplyDecision {
    require(pads.size == SamplerConfig.PAD_COUNT) { "Expected ${SamplerConfig.PAD_COUNT} PADs" }
    val start = SamplerConfig.DRUM_BANK_INDEX * SamplerConfig.PADS_PER_BANK
    return if (pads.subList(start, start + SamplerConfig.PADS_PER_BANK).any(PadModel::isAssigned)) {
        DrumKitApplyDecision.CONFIRM_REPLACE
    } else {
        DrumKitApplyDecision.APPLY
    }
}

enum class PadPlayMode {
    ONE_SHOT,
    GATE,
    LOOP,
}

enum class PadContentKind {
    SAMPLE,
    DRUM,
    VOCAL,
}

data class PcmAudio(
    val id: Long = System.nanoTime(),
    val name: String,
    val samples: ShortArray,
    val sampleRate: Int,
) {
    val frameCount: Int
        get() = samples.size

    val durationSeconds: Float
        get() = if (sampleRate > 0) samples.size.toFloat() / sampleRate else 0f
}

data class SliceRange(
    val startFrame: Int,
    val endFrame: Int,
) {
    val length: Int
        get() = max(0, endFrame - startFrame)
}

data class PadModel(
    val globalIndex: Int,
    val audio: PcmAudio? = null,
    val startFrame: Int = 0,
    val endFrame: Int = 0,
    val pitchSemitones: Float = 0f,
    val tone: Float = 1f,
    val gain: Float = 0.9f,
    val reverse: Boolean = false,
    val playMode: PadPlayMode = PadPlayMode.ONE_SHOT,
    val contentKind: PadContentKind = PadContentKind.SAMPLE,
    val chokeGroup: Int = 0,
) {
    val isAssigned: Boolean
        get() = audio != null && endFrame > startFrame

    val bankIndex: Int
        get() = globalIndex / SamplerConfig.PADS_PER_BANK

    val indexInBank: Int
        get() = globalIndex % SamplerConfig.PADS_PER_BANK
}

data class SamplerUiState(
    val isLoading: Boolean = false,
    val statusMessage: String = "音声を読み込むか録音してください",
    val currentAudio: PcmAudio? = null,
    val rangeStartFrame: Int = 0,
    val rangeEndFrame: Int = 0,
    val sliceMarkers: List<Int> = emptyList(),
    val activeSliceIndex: Int? = null,
    val manualChopEnabled: Boolean = false,
    val selectedBank: Int = 0,
    val selectedPad: Int = 0,
    val autoNextPad: Boolean = true,
    val pads: List<PadModel> = List(SamplerConfig.PAD_COUNT) { PadModel(it) },
    val activeSteps: Set<Int> = emptySet(),
    val bpm: Float = 92f,
    val swing: Float = 54f,
    val transportPlaying: Boolean = false,
    val recordArmed: Boolean = false,
    val currentStep: Int = -1,
    val microphoneRecording: Boolean = false,
    val vocalOverdubRecording: Boolean = false,
    val systemAudioRecording: Boolean = false,
    val sourcePlaying: Boolean = false,
    val sourcePlayheadFrame: Int = 0,
    val loopingPadIndex: Int? = null,
    val loopPlayheadFrame: Int = -1,
    val scratchingPadIndex: Int? = null,
    val scratchPlayheadFrame: Int = -1,
    val selectedDrumKitId: String = "dusty-jazz",
    val masterPitchSemitones: Float = 0f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
)

fun SamplerUiState.visiblePads(): List<PadModel> {
    val start = selectedBank * SamplerConfig.PADS_PER_BANK
    return pads.subList(start, start + SamplerConfig.PADS_PER_BANK)
}

fun SamplerUiState.selectedPadModel(): PadModel = pads[selectedPad]

fun SamplerUiState.sliceRanges(): List<SliceRange> {
    val audio = currentAudio ?: return emptyList()
    if (audio.frameCount <= 1 || rangeEndFrame <= rangeStartFrame) return emptyList()

    val points = buildList {
        add(rangeStartFrame.coerceIn(0, audio.frameCount - 1))
        addAll(
            sliceMarkers
                .asSequence()
                .filter { it > rangeStartFrame && it < rangeEndFrame }
                .distinct()
                .sorted()
                .toList(),
        )
        add(rangeEndFrame.coerceIn(1, audio.frameCount))
    }

    return points.zipWithNext { start, end -> SliceRange(start, end) }
        .filter { it.length > 1 }
}

fun SamplerUiState.activeSliceRange(): SliceRange? {
    val index = activeSliceIndex ?: return null
    return sliceRanges().getOrNull(index)
}

fun stepKey(padIndex: Int, stepIndex: Int): Int =
    padIndex * SamplerConfig.STEP_COUNT + stepIndex

fun List<PadModel>.nextVocalPadIndex(): Int? {
    require(size == SamplerConfig.PAD_COUNT) { "Expected ${SamplerConfig.PAD_COUNT} PADs" }
    val start = SamplerConfig.VOCAL_BANK_INDEX * SamplerConfig.PADS_PER_BANK
    return subList(start, start + SamplerConfig.PADS_PER_BANK)
        .firstOrNull { !it.isAssigned }
        ?.globalIndex
}
