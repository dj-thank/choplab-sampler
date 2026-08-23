package com.choplab.sampler.model

/**
 * Planned command whose blocking effects must succeed before [ProductionSession.commit].
 * A plan belongs to exactly one session and can be committed or cancelled once.
 */
class ProductionCommandPlan internal constructor(
    internal val ownerToken: Any,
    internal val epoch: Long,
    internal val before: SamplerUiState,
    internal val result: ProductionCommandResult,
) {
    val mutation: ProductionMutation
        get() = result.mutation

    val effects: List<ProductionEffect>
        get() = result.effects

    internal var resolved: Boolean = false
}

data class ProductionSessionTransition(
    val state: SamplerUiState,
    val mutation: ProductionMutation,
    val revision: Long,
    val persistenceRequired: Boolean,
    val effects: List<ProductionEffect> = emptyList(),
)

/**
 * Owns project edit history, revision and persistence admission.
 *
 * Platform controllers continue to own StateFlow publication, lifecycle and effect
 * execution. Calls must be serialized by that controller's existing state owner.
 */
class ProductionSession(maxHistoryEntries: Int = 40) {
    private val history = EditHistory(maxEntries = maxHistoryEntries)
    private val ownerToken = Any()
    private var transactionEpoch = 0L

    var revision: Long = 0L
        private set

    val canUndo: Boolean
        get() = history.canUndo

    val canRedo: Boolean
        get() = history.canRedo

    fun planCommand(
        state: SamplerUiState,
        command: ProductionCommand,
    ): ProductionCommandPlan {
        invalidatePlans()
        return ProductionCommandPlan(
            ownerToken = ownerToken,
            epoch = transactionEpoch,
            before = state,
            result = reduceProductionCommand(state, command),
        )
    }

    fun cancel(plan: ProductionCommandPlan) {
        requirePlanIsCurrent(plan)
        plan.resolved = true
    }

    fun commit(plan: ProductionCommandPlan): ProductionSessionTransition {
        requirePlanIsCurrent(plan)
        plan.resolved = true
        return commitResult(
            before = plan.before,
            result = plan.result,
        )
    }

    fun applyEdit(
        before: SamplerUiState,
        after: SamplerUiState,
        mergeKey: String? = null,
    ): ProductionSessionTransition {
        invalidatePlans()
        val mutation = when {
            after == before -> ProductionMutation.NONE
            before.hasSameEditableContent(after) -> ProductionMutation.SESSION
            else -> ProductionMutation.PROJECT
        }
        if (mutation == ProductionMutation.PROJECT) {
            history.record(before, mergeKey)
            advanceRevision()
        }
        return transition(
            state = after,
            mutation = mutation,
        )
    }

    fun undo(current: SamplerUiState): ProductionSessionTransition? {
        invalidatePlans()
        val restored = history.undo(current) ?: return null
        advanceRevision()
        return transition(restored, ProductionMutation.PROJECT)
    }

    fun redo(current: SamplerUiState): ProductionSessionTransition? {
        invalidatePlans()
        val restored = history.redo(current) ?: return null
        advanceRevision()
        return transition(restored, ProductionMutation.PROJECT)
    }

    fun replaceProject(
        state: SamplerUiState,
        persistenceRequired: Boolean = true,
    ): ProductionSessionTransition {
        invalidatePlans()
        history.reset()
        advanceRevision()
        return transition(
            state = state,
            mutation = ProductionMutation.PROJECT,
            persistenceRequired = persistenceRequired,
        )
    }

    private fun decorate(state: SamplerUiState): SamplerUiState = state.copy(
        canUndo = history.canUndo,
        canRedo = history.canRedo,
    )

    private fun commitResult(
        before: SamplerUiState,
        result: ProductionCommandResult,
    ): ProductionSessionTransition {
        if (result.mutation == ProductionMutation.PROJECT) {
            history.record(before, result.mergeKey)
            advanceRevision()
        }
        return transition(
            state = result.state,
            mutation = result.mutation,
            effects = result.effects,
        )
    }

    private fun transition(
        state: SamplerUiState,
        mutation: ProductionMutation,
        effects: List<ProductionEffect> = emptyList(),
        persistenceRequired: Boolean = mutation == ProductionMutation.PROJECT,
    ): ProductionSessionTransition = ProductionSessionTransition(
        state = decorate(state),
        mutation = mutation,
        revision = revision,
        persistenceRequired = persistenceRequired,
        effects = effects,
    )

    private fun invalidatePlans() {
        check(transactionEpoch < Long.MAX_VALUE) { "Production transaction epoch exhausted" }
        transactionEpoch++
    }

    private fun advanceRevision() {
        check(revision < Long.MAX_VALUE) { "Production revision exhausted" }
        revision++
    }

    private fun requirePlanIsCurrent(plan: ProductionCommandPlan) {
        require(plan.ownerToken === ownerToken) { "Production command plan belongs to another session" }
        require(!plan.resolved) { "Production command plan was already resolved" }
        require(plan.epoch == transactionEpoch) { "Production command plan is stale" }
    }
}

fun SamplerUiState.hasSameEditableContent(other: SamplerUiState): Boolean =
    currentAudio == other.currentAudio &&
        rangeStartFrame == other.rangeStartFrame &&
        rangeEndFrame == other.rangeEndFrame &&
        sliceMarkers == other.sliceMarkers &&
        activeSliceIndex == other.activeSliceIndex &&
        manualChopEnabled == other.manualChopEnabled &&
        selectedBank == other.selectedBank &&
        selectedPad == other.selectedPad &&
        autoNextPad == other.autoNextPad &&
        pads == other.pads &&
        activeSteps == other.activeSteps &&
        bpm == other.bpm &&
        swing == other.swing &&
        sourcePlayheadFrame == other.sourcePlayheadFrame &&
        masterPitchSemitones == other.masterPitchSemitones &&
        selectedDrumKitId == other.selectedDrumKitId
