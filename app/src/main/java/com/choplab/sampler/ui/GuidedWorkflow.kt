package com.choplab.sampler.ui

import kotlin.math.roundToInt

enum class WorkflowStage(
    val label: String,
    val caption: String,
    val guidance: String,
) {
    CAPTURE("入れる", "CAPTURE", "まず曲を読み込むか、マイクで録音します"),
    SLICE("切る", "SLICE", "必要なら波形を区切って、音の範囲を整えます"),
    PLAY("叩く", "PLAY", "曲を再生し、ここだと思ったらPADを押します"),
    ARRANGE("並べる", "ARRANGE", "PADを選び、光るマスで鳴らす場所を決めます"),
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

fun semitoneToKeyName(semitones: Float, baseMidiNote: Int = 48): String {
    val rounded = semitones.roundToInt().coerceIn(-24, 24)
    val midiNote = (baseMidiNote + rounded).coerceIn(0, 127)
    val names = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    return "${names[midiNote % 12]}${midiNote / 12 - 1}"
}
