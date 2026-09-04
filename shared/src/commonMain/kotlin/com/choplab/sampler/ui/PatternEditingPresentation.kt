package com.choplab.sampler.ui

import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.canUsePatternSteps
import com.choplab.sampler.model.selectedPadModel
import com.choplab.sampler.model.shiftPadSteps

data class PatternEditingPresentation(
    val fillEnabled: Boolean,
    val clearEnabled: Boolean,
    val shiftEnabled: Boolean,
    val guidance: String,
)

fun patternEditingPresentation(state: SamplerUiState): PatternEditingPresentation {
    val editable = externalDocumentActionsEnabled(state)
    val pad = state.selectedPadModel()
    val hasSteps = state.activeSteps.any { it / SamplerConfig.STEP_COUNT == state.selectedPad }
    val eligible = pad.canUsePatternSteps()
    val shiftChangesPattern = hasSteps && state.activeSteps.shiftPadSteps(state.selectedPad, 1) != state.activeSteps
    return PatternEditingPresentation(
        fillEnabled = editable && eligible,
        clearEnabled = editable && hasSteps,
        shiftEnabled = editable && eligible && shiftChangesPattern,
        guidance = when {
            !editable -> if (state.isLoading) "処理が終わるまでお待ちください" else "録音をSTOPすると配置を編集できます"
            !pad.isAssigned -> "音の入ったPADを選んでください"
            !eligible -> "LOOP・VOICEは自動で重なります。配置には通常のPADを選んでください"
            !hasSteps -> "間隔を選ぶと、この音をビートへ配置できます"
            else -> "選択した音だけを配置・移動します。他の音はそのままです"
        },
    )
}
