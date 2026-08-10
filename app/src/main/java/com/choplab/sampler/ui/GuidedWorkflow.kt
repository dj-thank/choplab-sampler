package com.choplab.sampler.ui

enum class WorkflowStage(
    val label: String,
    val caption: String,
    val guidance: String,
) {
    CAPTURE("入れる", "CAPTURE", "まず曲を読み込むか、マイクで録音します"),
    SLICE("切る", "SLICE", "必要なら波形を区切って、音の範囲を整えます"),
    PLAY("叩く", "PLAY", "曲を再生し、ここだと思ったらPADを押します"),
    ARRANGE("並べる", "ARRANGE", "PADを選び、反復と波形を見ながらBANKを重ねます"),
    FINISH("完成", "FINISH", "ビートを確認して、4小節WAVを書き出します"),
    ;

    fun next(): WorkflowStage? = entries.getOrNull(ordinal + 1)
}

fun restoreWorkflowStage(savedName: String?): WorkflowStage =
    WorkflowStage.entries.firstOrNull { it.name == savedName }
        ?: when (savedName) {
            "SOURCE" -> WorkflowStage.SLICE
            "SEQ" -> WorkflowStage.ARRANGE
            "CHOP", "PAD" -> WorkflowStage.PLAY
            else -> WorkflowStage.PLAY
        }

fun initialWorkflowStage(hasAudio: Boolean): WorkflowStage =
    if (hasAudio) WorkflowStage.PLAY else WorkflowStage.CAPTURE

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

fun arrangeRepeatPrompt(isAssigned: Boolean, padLabel: String): String =
    if (isAssigned) {
        "2 反復を選ぶ  ${padLabel}を何拍ごとに鳴らす？"
    } else {
        "2 反復を選ぶ  先に音の入ったPADを選んでください"
    }

private val arrangeQuickStepLabels =
    listOf("1 PADを選ぶ", "2 反復を選ぶ", "3 ビートを聴く")

fun arrangeQuickSteps(): List<String> = arrangeQuickStepLabels

val ARRANGE_QUICK_GUIDANCE = arrangeQuickStepLabels.joinToString("  →  ")
