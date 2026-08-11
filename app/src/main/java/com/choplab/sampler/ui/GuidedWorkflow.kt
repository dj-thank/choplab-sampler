package com.choplab.sampler.ui

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
        "2 ビートをループ  ${loopingPadLabel}の音声全体を繰り返し中"
    } else if (isAssigned) {
        "2 ビートをループ  ${padLabel}の音声全体を繰り返す"
    } else {
        "2 ビートをループ  先に音の入ったPADを選んでください"
    }

fun placementPresetPrompt(): String = "配置プリセット  鳴らす場所を選ぶ"

private val arrangeQuickStepLabels =
    listOf("1 PADを選ぶ", "2 ループ／並べる", "3 足す／スクラッチ")

fun arrangeQuickSteps(): List<String> = arrangeQuickStepLabels

val ARRANGE_QUICK_GUIDANCE = arrangeQuickStepLabels.joinToString("  →  ")
