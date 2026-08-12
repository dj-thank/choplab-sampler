package com.choplab.sampler.ui

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.SourceUiPhase
import com.choplab.sampler.model.selectedPadModel
import com.choplab.sampler.model.sourceUiPhase

enum class ProductionDockIntent {
    RESET_ALL,
    START_CHOP,
    OPEN_BEAT,
    OPEN_PAD_EDIT,
    OPEN_ADD,
    OPEN_SCRATCH,
    SHOW_QUICK,
    SHOW_STEPS,
}

data class ProductionDockItem(
    val intent: ProductionDockIntent,
    val label: String,
    val enabled: Boolean = true,
    val active: Boolean? = null,
    val weight: Float = 1f,
    val confirmLabel: String? = null,
)

fun captureProductionDockItems(state: SamplerUiState): List<ProductionDockItem> {
    val audioReady = state.currentAudio != null
    val sourcePhase = state.sourceUiPhase()
    val startLabel = when (sourcePhase) {
        SourceUiPhase.STOPPED -> "チョップ開始\nSTART CHOP"
        SourceUiPhase.STARTING,
        SourceUiPhase.PLAYING -> "チョップへ\nOPEN CHOP"
        SourceUiPhase.STOPPING -> "停止中\nPLEASE WAIT"
    }
    val canContinue = audioReady && sourcePhase != SourceUiPhase.STOPPING

    return buildList {
        if (audioReady) {
            add(
                ProductionDockItem(
                    intent = ProductionDockIntent.RESET_ALL,
                    label = "全部消して入れ直す\nRESET ALL",
                    weight = 0.9f,
                    confirmLabel = "もう一度で完全リセット",
                ),
            )
        }
        add(
            ProductionDockItem(
                intent = ProductionDockIntent.START_CHOP,
                label = startLabel,
                enabled = canContinue,
                active = canContinue,
                weight = 1.4f,
            ),
        )
    }
}

fun chopProductionDockItems(state: SamplerUiState): List<ProductionDockItem> {
    val hasAssignedPad = state.pads.any(PadModel::isAssigned)
    val selectedPadAssigned = state.selectedPadModel().isAssigned
    return listOf(
        ProductionDockItem(
            intent = ProductionDockIntent.OPEN_BEAT,
            label = "ビートへ\nBEAT",
            enabled = hasAssignedPad,
            active = hasAssignedPad,
            weight = 1.15f,
        ),
        ProductionDockItem(
            intent = ProductionDockIntent.OPEN_PAD_EDIT,
            label = "微調整\nPAD EDIT",
            enabled = selectedPadAssigned,
        ),
        ProductionDockItem(
            intent = ProductionDockIntent.OPEN_ADD,
            label = "音を足す\nADD",
        ),
        ProductionDockItem(
            intent = ProductionDockIntent.OPEN_SCRATCH,
            label = "スクラッチ\nSCRATCH",
            enabled = state.currentAudio != null || selectedPadAssigned,
        ),
    )
}

fun beatProductionDockItems(stepsVisible: Boolean): List<ProductionDockItem> =
    listOf(
        ProductionDockItem(
            intent = ProductionDockIntent.SHOW_QUICK,
            label = "クイック\nQUICK",
            active = !stepsVisible,
        ),
        ProductionDockItem(
            intent = ProductionDockIntent.SHOW_STEPS,
            label = "並べる詳細\nSTEPS",
            active = stepsVisible,
        ),
        ProductionDockItem(
            intent = ProductionDockIntent.OPEN_ADD,
            label = "音を足す\nADD",
        ),
        ProductionDockItem(
            intent = ProductionDockIntent.OPEN_SCRATCH,
            label = "スクラッチ\nSCRATCH",
        ),
    )
