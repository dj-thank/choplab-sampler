package com.choplab.sampler.model

private const val DEFAULT_EMPTY_PROJECT_MESSAGE = "音声を読み込むか録音してください"
private const val AUTOSAVE_RECOVERY_MESSAGE = "前回の自動保存を復元しています…"
private const val AUTOSAVE_RECOVERY_FAILURE_MESSAGE = "前回の自動保存を復元できませんでした"

fun beginAutosaveRecovery(state: SamplerUiState = SamplerUiState()): SamplerUiState =
    state.copy(
        isLoading = true,
        statusMessage = AUTOSAVE_RECOVERY_MESSAGE,
    )

fun completeAutosaveRecoveryWithoutProject(state: SamplerUiState): SamplerUiState =
    state.copy(
        isLoading = false,
        statusMessage = DEFAULT_EMPTY_PROJECT_MESSAGE,
    )

fun failAutosaveRecovery(state: SamplerUiState, message: String?): SamplerUiState =
    state.copy(
        isLoading = false,
        statusMessage = message ?: AUTOSAVE_RECOVERY_FAILURE_MESSAGE,
    )
