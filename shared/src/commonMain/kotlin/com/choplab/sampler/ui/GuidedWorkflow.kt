package com.choplab.sampler.ui

import com.choplab.sampler.audio.BuiltInDrumKits
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.ProjectLaunchTarget
import com.choplab.sampler.model.RecordingKind
import com.choplab.sampler.model.RecordingPhase
import com.choplab.sampler.model.RecordingSession
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.SourceUiPhase
import com.choplab.sampler.model.activePhaseFor
import com.choplab.sampler.model.bankRoleFor
import com.choplab.sampler.model.hasAudiblePatternContent
import com.choplab.sampler.model.inferProjectLaunchTarget

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

fun initialWorkflowStage(state: SamplerUiState): WorkflowStage = when (
    state.projectLaunchTarget ?: inferProjectLaunchTarget(state)
) {
    ProjectLaunchTarget.CAPTURE -> WorkflowStage.CAPTURE
    ProjectLaunchTarget.CHOP -> WorkflowStage.CHOP
    ProjectLaunchTarget.BEAT -> WorkflowStage.BEAT
}

data class WorkflowStageAvailability(
    val enabled: Boolean,
    val blockedReason: String?,
)

fun workflowStageAvailability(
    stage: WorkflowStage,
    state: SamplerUiState,
): WorkflowStageAvailability = when (stage) {
    WorkflowStage.CAPTURE -> WorkflowStageAvailability(enabled = true, blockedReason = null)
    WorkflowStage.CHOP -> if (state.currentAudio != null) {
        WorkflowStageAvailability(enabled = true, blockedReason = null)
    } else {
        WorkflowStageAvailability(
            enabled = false,
            blockedReason = "曲を読み込むか録音すると使えます",
        )
    }
    WorkflowStage.BEAT -> if (state.pads.any(PadModel::isAssigned)) {
        WorkflowStageAvailability(enabled = true, blockedReason = null)
    } else {
        WorkflowStageAvailability(
            enabled = false,
            blockedReason = "チョップでPADに音を入れると使えます",
        )
    }
    WorkflowStage.FINISH -> if (state.currentAudio != null || state.pads.any(PadModel::isAssigned)) {
        WorkflowStageAvailability(enabled = true, blockedReason = null)
    } else {
        WorkflowStageAvailability(
            enabled = false,
            blockedReason = "音源か音の入ったPADを用意すると使えます",
        )
    }
}

fun workflowStageEnabled(stage: WorkflowStage, state: SamplerUiState): Boolean =
    workflowStageAvailability(stage, state).enabled

fun workflowStageStateDescription(availability: WorkflowStageAvailability): String =
    if (availability.enabled) {
        "利用できます"
    } else {
        "まだ使えません。${availability.blockedReason ?: "準備が必要です"}"
    }

data class WorkflowNextActionPresentation(
    val stage: WorkflowStage?,
    val title: String,
    val guidance: String,
)

fun workflowNextActionPresentation(state: SamplerUiState): WorkflowNextActionPresentation {
    if (state.isLoading) {
        return WorkflowNextActionPresentation(
            stage = null,
            title = "NEXT 待つ",
            guidance = "音声の読込が終わるまで待ちます",
        )
    }
    val recording = state.recordingSession as? RecordingSession.Active
    if (recording != null) {
        return if (recording.phase == RecordingPhase.STOPPING) {
            WorkflowNextActionPresentation(
                stage = null,
                title = "NEXT 待つ",
                guidance = "録音の停止と保存が終わるまで待ちます",
            )
        } else {
            WorkflowNextActionPresentation(
                stage = null,
                title = "NEXT 録音を止める",
                guidance = "上の停止ボタンで録音を保存します",
            )
        }
    }

    val anyAssignedPad = state.pads.any(PadModel::isAssigned)
    val sourceHasChop = state.currentAudio?.let { source ->
        state.pads.any { pad -> pad.isAssigned && pad.audio?.id == source.id }
    } ?: false
    return when {
        state.currentAudio == null && (
            !anyAssignedPad || BuiltInDrumKits.isPristineStarterProduction(state)
        ) -> WorkflowNextActionPresentation(
            stage = WorkflowStage.CAPTURE,
            title = "NEXT 1 入れる",
            guidance = "曲を読み込むか録音します",
        )
        state.currentAudio != null && !sourceHasChop -> WorkflowNextActionPresentation(
            stage = WorkflowStage.CHOP,
            title = "NEXT 2 チョップ",
            guidance = "曲を流し、空PADを押します",
        )
        !state.activeSteps.hasAudiblePatternContent(state.pads) -> WorkflowNextActionPresentation(
            stage = WorkflowStage.BEAT,
            title = "NEXT 3 ビート",
            guidance = "音ありPADをループするか、鳴るマスを置きます",
        )
        else -> WorkflowNextActionPresentation(
            stage = WorkflowStage.FINISH,
            title = "NEXT 4 保存",
            guidance = "再生で確認し、制作またはWAVを保存します",
        )
    }
}

fun reconcileWorkflowStage(stage: WorkflowStage, state: SamplerUiState): WorkflowStage =
    if (workflowStageEnabled(stage, state)) stage else initialWorkflowStage(state)

data class BeatWorkspaceSurface(
    val showPadGrid: Boolean,
    val showDetailedSequencer: Boolean,
)

fun beatWorkspaceSurface(showFineControls: Boolean): BeatWorkspaceSurface = BeatWorkspaceSurface(
    showPadGrid = !showFineControls,
    showDetailedSequencer = showFineControls,
)

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

data class CaptureEntryPresentation(
    val focused: Boolean,
    val starterDemoAvailable: Boolean,
    val title: String,
    val guidance: String,
)

fun captureEntryPresentation(state: SamplerUiState): CaptureEntryPresentation {
    val focused = state.currentAudio == null &&
        !state.isLoading &&
        state.recordingSession == RecordingSession.Idle
    return CaptureEntryPresentation(
        focused = focused,
        starterDemoAvailable = focused && BuiltInDrumKits.isPristineStarterProduction(state),
        title = "まず、自分の音を入れる",
        guidance = "曲を読み込むか、前の制作を開きます",
    )
}

data class FinishReadinessPresentation(
    val title: String,
    val guidance: String,
)

fun finishReadinessPresentation(readyForWav: Boolean): FinishReadinessPresentation =
    if (readyForWav) {
        FinishReadinessPresentation(
            title = "ビートを書き出せます",
            guidance = "操作は端末内へ自動保存。再生で確認し、4小節WAVにもできます。",
        )
    } else {
        FinishReadinessPresentation(
            title = "制作は保存できます",
            guidance = "WAVはまだ準備中です。『ビート』で鳴らすマスを光らせてください。",
        )
    }

data class CaptureInputPolicy(
    val fileEnabled: Boolean,
    val microphoneEnabled: Boolean,
    val systemAudioEnabled: Boolean,
)

fun captureInputPolicy(state: SamplerUiState): CaptureInputPolicy {
    val session = state.recordingSession
    val microphonePhase = session.activePhaseFor(RecordingKind.SOURCE_MICROPHONE)
    val systemAudioPhase = session.activePhaseFor(RecordingKind.SOURCE_SYSTEM_AUDIO)
    return CaptureInputPolicy(
        fileEnabled = !state.isLoading && session == RecordingSession.Idle,
        microphoneEnabled = microphonePhase?.let { it != RecordingPhase.STOPPING }
            ?: (!state.isLoading && session == RecordingSession.Idle),
        systemAudioEnabled = systemAudioPhase?.let { it != RecordingPhase.STOPPING }
            ?: (!state.isLoading && session == RecordingSession.Idle),
    )
}

fun externalDocumentActionsEnabled(state: SamplerUiState): Boolean =
    !state.isLoading && state.recordingSession == RecordingSession.Idle

data class RecordingControlPresentation(
    val label: String,
    val enabled: Boolean,
    val active: Boolean,
)

fun recordingControlPresentation(
    session: RecordingSession,
    kind: RecordingKind,
    idleLabel: String,
    stopLabel: String,
): RecordingControlPresentation {
    val active = session as? RecordingSession.Active
        ?: return RecordingControlPresentation(idleLabel, enabled = true, active = false)
    if (active.kind != kind) {
        return RecordingControlPresentation("別の録音中\nWAIT", enabled = false, active = false)
    }
    return if (active.phase == RecordingPhase.STOPPING) {
        RecordingControlPresentation("停止・保存中\nPLEASE WAIT", enabled = false, active = true)
    } else {
        RecordingControlPresentation(stopLabel, enabled = true, active = true)
    }
}

data class RecordingHeaderPresentation(
    val statusLabel: String,
    val stopLabel: String,
    val stopEnabled: Boolean,
    val accessibilityLabel: String,
)

fun recordingHeaderPresentation(session: RecordingSession): RecordingHeaderPresentation? {
    val active = session as? RecordingSession.Active ?: return null
    val kindLabel = when (active.kind) {
        RecordingKind.SOURCE_MICROPHONE -> "MIC"
        RecordingKind.SOURCE_SYSTEM_AUDIO -> "DEVICE"
        RecordingKind.VOCAL_OVERDUB -> "VOICE"
    }
    val phaseLabel = when (active.phase) {
        RecordingPhase.STARTING -> "START"
        RecordingPhase.RECORDING -> "REC"
        RecordingPhase.STOPPING -> "STOPPING"
    }
    val accessibilityKind = when (active.kind) {
        RecordingKind.SOURCE_MICROPHONE -> "マイク素材"
        RecordingKind.SOURCE_SYSTEM_AUDIO -> "端末音声"
        RecordingKind.VOCAL_OVERDUB -> "ボーカル"
    }
    val accessibilityPhase = when (active.phase) {
        RecordingPhase.STARTING -> "録音を準備中"
        RecordingPhase.RECORDING -> "録音中"
        RecordingPhase.STOPPING -> "録音を停止して保存中"
    }
    val stopLabel = when {
        active.phase == RecordingPhase.STOPPING -> "停止・保存中\nPLEASE WAIT"
        active.kind == RecordingKind.SOURCE_MICROPHONE -> "録音を停止\nMIC STOP"
        active.kind == RecordingKind.SOURCE_SYSTEM_AUDIO -> "録音を停止\nDEVICE STOP"
        else -> "声を保存\nVOICE STOP"
    }
    return RecordingHeaderPresentation(
        statusLabel = "$kindLabel  $phaseLabel",
        stopLabel = stopLabel,
        stopEnabled = active.phase != RecordingPhase.STOPPING,
        accessibilityLabel = "$accessibilityKind${if (active.phase == RecordingPhase.RECORDING) "を" else "の"}$accessibilityPhase",
    )
}

fun compactMachineButtonFontSizeSp(@Suppress("UNUSED_PARAMETER") fontScale: Float): Float = 8f

fun compactMachineButtonLineHeightSp(@Suppress("UNUSED_PARAMETER") fontScale: Float): Float = 9f

fun machineHeaderShowsCaption(fontScale: Float): Boolean =
    !usesLargeTextDeckMode(fontScale)

fun machineHeaderShowsBankStatus(fontScale: Float): Boolean =
    !usesLargeTextDeckMode(fontScale)

fun workflowStageRows(fontScale: Float): Int =
    if (usesLargeTextDeckMode(fontScale)) 2 else 1

fun beatLoopButtonLabel(looping: Boolean, fontScale: Float): String =
    if (usesLargeTextDeckMode(fontScale)) {
        if (looping) "ループ停止" else "選択音ループ"
    } else {
        if (looping) "選択音ループ停止\nSTOP" else "選択音をループ\nSTART"
    }

fun usesLargeTextDeckMode(fontScale: Float): Boolean =
    fontScale.isFinite() && fontScale >= 1.2f

fun requiresNewProjectConfirmation(state: SamplerUiState): Boolean =
    !BuiltInDrumKits.isPristineStarterProduction(state) && (
        state.currentAudio != null ||
            state.pads.any(PadModel::isAssigned) ||
            state.activeSteps.isNotEmpty()
        )

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
            "空PAD＝追加／音ありPAD＝タップ上書き・長押し微調整"
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
