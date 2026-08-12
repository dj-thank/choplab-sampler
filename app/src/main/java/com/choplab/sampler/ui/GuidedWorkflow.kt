package com.choplab.sampler.ui

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.SourceUiPhase
import com.choplab.sampler.model.bankRoleFor

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

enum class WorkflowNavigationAction {
    STOP_SOURCE,
    ENSURE_PLAYABLE_PAD,
}

fun workflowNavigationActions(
    current: WorkflowStage,
    target: WorkflowStage,
): Set<WorkflowNavigationAction> {
    if (current == target) return emptySet()
    return buildSet {
        if (!workflowStageKeepsSourcePlayback(target)) add(WorkflowNavigationAction.STOP_SOURCE)
        if (target == WorkflowStage.BEAT) add(WorkflowNavigationAction.ENSURE_PLAYABLE_PAD)
    }
}

data class StartChopPolicy(
    val enabled: Boolean,
    val prepareMelodyDestination: Boolean,
    val startSource: Boolean,
)

fun startChopPolicy(sourcePhase: SourceUiPhase): StartChopPolicy = StartChopPolicy(
    enabled = sourcePhase != SourceUiPhase.STOPPING,
    prepareMelodyDestination = sourcePhase != SourceUiPhase.STOPPING,
    startSource = sourcePhase == SourceUiPhase.STOPPED,
)

fun captureSourceStatusLabel(
    sourcePhase: SourceUiPhase,
    isLoading: Boolean,
    audioLoaded: Boolean,
): String = when {
    sourcePhase == SourceUiPhase.STARTING -> "STARTING"
    sourcePhase == SourceUiPhase.PLAYING -> "SAMPLING"
    sourcePhase == SourceUiPhase.STOPPING -> "STOPPING"
    isLoading -> "LOADING"
    audioLoaded -> "READY"
    else -> "NO SOURCE"
}

fun emptySourceWaveformLabel(isLoading: Boolean): String =
    if (isLoading) "音声を読込中\nPLEASE WAIT" else "NO SOURCE\nLOAD OR RECORD AUDIO"

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

data class ChopSessionPresentation(
    val captureMode: Boolean,
    val primaryActionLabel: String,
    val primaryEnabled: Boolean,
    val guidance: String,
)

fun chopSessionPresentation(
    sourcePhase: SourceUiPhase,
    assignedPadCount: Int,
): ChopSessionPresentation = when (sourcePhase) {
    SourceUiPhase.STOPPED -> ChopSessionPresentation(
        captureMode = false,
        primaryActionLabel = "チョップ開始\nSTART CHOP",
        primaryEnabled = true,
        guidance = "「チョップ開始」→ 音が鳴ったら空PADを叩く",
    )
    SourceUiPhase.STARTING -> ChopSessionPresentation(
        captureMode = false,
        primaryActionLabel = "再生準備中\nTAP TO CANCEL",
        primaryEnabled = true,
        guidance = "再生を準備中。音が鳴るまで空PADは選択のみ",
    )
    SourceUiPhase.PLAYING -> ChopSessionPresentation(
        captureMode = true,
        primaryActionLabel = "元曲を止める\nSTOP SOURCE",
        primaryEnabled = true,
        guidance = if (assignedPadCount <= 0) {
            "空PADを叩くと、その瞬間からチョップ"
        } else {
            "空PAD＝追加／音ありPAD＝試聴・長押し微調整"
        },
    )
    SourceUiPhase.STOPPING -> ChopSessionPresentation(
        captureMode = false,
        primaryActionLabel = "停止中\nPLEASE WAIT",
        primaryEnabled = false,
        guidance = "停止処理中。割当済みPADは上書きされません",
    )
}

fun chopSessionPresentation(
    sourcePlaying: Boolean,
    assignedPadCount: Int,
): ChopSessionPresentation = chopSessionPresentation(
    sourcePhase = if (sourcePlaying) SourceUiPhase.PLAYING else SourceUiPhase.STOPPED,
    assignedPadCount = assignedPadCount,
)

fun bankSwitchLabel(bankIndex: Int, selected: Boolean, compact: Boolean): String {
    val role = bankRoleFor(bankIndex)
    val marker = if (selected) "● " else ""
    return if (compact) {
        "$marker${role.letter}\n${role.englishLabel}"
    } else {
        "$marker${role.letter} ${role.japaneseLabel}\n${role.englishLabel}"
    }
}

fun padPageSwitchLabel(
    pageIndex: Int,
    selected: Boolean,
    assignedCount: Int,
    selectedPadLabel: String,
): String {
    val first = pageIndex * SamplerConfig.PAD_PAGE_SIZE + 1
    val last = first + SamplerConfig.PAD_PAGE_SIZE - 1
    val occupancy = if (assignedCount <= 0) "空" else "${assignedCount}音"
    val marker = if (selected) "● " else ""
    val detail = if (selected) "選択 $selectedPadLabel / $occupancy" else "切替 / $occupancy"
    return "$marker${"PAD %02d–%02d".format(first, last)}\n$detail"
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
