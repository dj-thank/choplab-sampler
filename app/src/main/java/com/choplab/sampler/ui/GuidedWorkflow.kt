package com.choplab.sampler.ui

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.SamplerUiState

enum class WorkflowStage(
    val label: String,
    val caption: String,
    val guidance: String,
) {
    CAPTURE("入れる", "CAPTURE", "曲や声を読み込んで、素材を用意します"),
    CHOP("チョップ", "CHOP", "曲を頭から流し、空PADを叩いて切ります"),
    BEAT("ビート", "BEAT", "選んだ音をループするか16ステップに並べます"),
    FINISH("保存", "SAVE", "ビートを確認して、WAVやプロジェクトを保存します"),
    ;

    fun next(): WorkflowStage? = entries.getOrNull(ordinal + 1)
}

fun restoreWorkflowStage(savedName: String?): WorkflowStage =
    WorkflowStage.entries.firstOrNull { it.name == savedName }
        ?: when (savedName) {
            "SOURCE", "SLICE", "PLAY", "PAD" -> WorkflowStage.CHOP
            "SEQ", "ARRANGE" -> WorkflowStage.BEAT
            else -> WorkflowStage.CHOP
        }

fun initialWorkflowStage(hasAudio: Boolean): WorkflowStage =
    if (hasAudio) WorkflowStage.CHOP else WorkflowStage.CAPTURE

fun workflowStageKeepsSourcePlayback(stage: WorkflowStage): Boolean =
    stage == WorkflowStage.CHOP

fun compactMachineButtonFontSizeSp(fontScale: Float): Float =
    if (usesLargeTextDeckMode(fontScale)) 7f else 8f

fun compactMachineButtonLineHeightSp(fontScale: Float): Float =
    if (usesLargeTextDeckMode(fontScale)) 8f else 9f

fun machineHeaderShowsCaption(fontScale: Float): Boolean =
    !usesLargeTextDeckMode(fontScale)

fun beatLoopButtonLabel(looping: Boolean, fontScale: Float): String =
    if (usesLargeTextDeckMode(fontScale)) {
        if (looping) "ループ停止" else "選択音ループ"
    } else {
        if (looping) "選択音ループ停止\nSTOP" else "選択音をループ\nSTART"
    }

private fun usesLargeTextDeckMode(fontScale: Float): Boolean =
    fontScale.isFinite() && fontScale >= 1.2f

fun requiresNewProjectConfirmation(state: SamplerUiState): Boolean =
    state.currentAudio != null ||
        state.pads.any(PadModel::isAssigned) ||
        state.activeSteps.isNotEmpty()

fun chopQuickGuidance(assignedPadCount: Int): String =
    if (assignedPadCount <= 0) {
        "波形タップ＝そこから再生 → 空PAD＝チョップ"
    } else {
        "PAD＝試聴／長押し＝微調整 → ビートへ"
    }

fun pitchDirectionLabel(value: Float): String = when {
    value < -0.49f -> "低い"
    value > 0.49f -> "高い"
    else -> "原キー"
}

fun toneCharacterLabel(tone: Float): String = when {
    tone < 0.5f -> "暗い"
    tone < 0.85f -> "なじむ"
    else -> "原音"
}

fun nextTonePreset(tone: Float): Float = when {
    tone < 0.5f -> 0.65f
    tone < 0.85f -> 1f
    else -> 0.35f
}

fun nextLevelPreset(gain: Float): Float =
    if (gain >= 1.49f) 0f else (gain + 0.1f).coerceAtMost(1.5f)

fun arrangeBeatLoopPrompt(
    isAssigned: Boolean,
    padLabel: String,
    loopingPadLabel: String? = null,
): String =
    if (loopingPadLabel != null) {
        "2 選択音をループ  ${loopingPadLabel}の音声全体を繰り返し中"
    } else if (isAssigned) {
        "2 選択音をループ  ${padLabel}の音声全体を繰り返す"
    } else {
        "2 選択音をループ  先に音の入ったPADを選んでください"
    }

fun placementPresetPrompt(): String = "配置プリセット  鳴らす場所を選ぶ"

private val arrangeQuickStepLabels =
    listOf("1 音ありPADを選ぶ", "2 ループ／並べる", "3 足す／スクラッチ")

fun arrangeQuickSteps(): List<String> = arrangeQuickStepLabels

val ARRANGE_QUICK_GUIDANCE = arrangeQuickStepLabels.joinToString("  →  ")

val ARRANGE_QUICK_GUIDANCE_COMPACT =
    listOf("1 PAD", "2 ループ／並べる", "3 足す／擦る").joinToString("  →  ")

fun arrangeQuickGuidance(pad: PadModel, compact: Boolean): String = when {
    pad.playMode == PadPlayMode.LOOP -> "ループは音声全体を反復。配置は別PAD"
    pad.contentKind == PadContentKind.VOCAL -> "VOICEは開始時に一度再生"
    compact -> ARRANGE_QUICK_GUIDANCE_COMPACT
    else -> ARRANGE_QUICK_GUIDANCE
}
