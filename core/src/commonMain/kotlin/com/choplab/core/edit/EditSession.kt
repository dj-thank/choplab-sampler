package com.choplab.core.edit

import com.choplab.core.model.FrozenList
import com.choplab.core.model.Project
import com.choplab.core.model.frozen

internal enum class Direction { EDIT, UNDO, REDO, REPLACE }

class EditPlan internal constructor(
    internal val owner: Any,
    internal val epoch: Long,
    internal val before: Project,
    internal val reduction: Reduction,
    internal val direction: Direction,
    val revision: Long,
) {
    val project: Project get() = reduction.project
    val effects: FrozenList<Effect> get() = reduction.effects
    val mutation: Mutation get() = reduction.mutation
    internal var acknowledged = 0
    internal var resolved = false
}

/** Serialized by Studio. Planning never consumes history or advances the revision. */
class EditSession(initial: Project = Project(), initialRevision: Long = 0, val historyLimit: Int = 100) {
    private val owner = Any()
    private var epoch = 0L
    private val undo = mutableListOf<Project>()
    private val redo = mutableListOf<Project>()
    private var lastMergeKey: String? = null
    var project: Project = initial
        private set
    var revision: Long = initialRevision
        private set
    init { require(initialRevision >= 0 && historyLimit in 1..100) }
    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()
    val undoCount: Int get() = undo.size
    val redoCount: Int get() = redo.size

    fun plan(intent: Intent): EditPlan { invalidate(); return makePlan(Reducer.reduce(project, intent), Direction.EDIT) }
    fun planUndo(): EditPlan? {
        invalidate()
        return undo.lastOrNull()?.let { makePlan(Reducer.reduction(project, it), Direction.UNDO) }
    }
    fun planRedo(): EditPlan? {
        invalidate()
        return redo.lastOrNull()?.let { makePlan(Reducer.reduction(project, it), Direction.REDO) }
    }
    fun planReplace(next: Project): EditPlan {
        val effects = buildList {
            val sounding = project.pads.filter { it.assetHash != null }.map { it.id }.frozen()
            if (sounding.isNotEmpty()) add(Effect.StopPads(sounding))
            add(Effect.PublishProject)
        }.frozen()
        return makePlan(Reduction(next, Mutation.PROJECT, effects), Direction.REPLACE)
    }

    /** An adapter acknowledges each successful effect in plan order, never a failed effect. */
    fun acknowledge(plan: EditPlan, effectIndex: Int) {
        requireCurrent(plan)
        require(effectIndex == plan.acknowledged && effectIndex in plan.effects.indices)
        plan.acknowledged++
    }
    fun cancel(plan: EditPlan) {
        requireCurrent(plan)
        plan.resolved = true
        lastMergeKey = null
    }
    fun commit(plan: EditPlan): Project {
        requireCurrent(plan)
        require(plan.acknowledged == plan.effects.size) { "Effects have not all succeeded" }
        if (plan.direction == Direction.REPLACE) {
            undo.clear(); redo.clear(); lastMergeKey = null
        } else if (plan.mutation == Mutation.PROJECT) when (plan.direction) {
            Direction.EDIT -> {
                val merge = plan.reduction.mergeKey
                if (merge == null || merge != lastMergeKey || undo.isEmpty() || redo.isNotEmpty()) {
                    undo.add(project)
                    if (undo.size > historyLimit) undo.removeAt(0)
                }
                redo.clear()
                lastMergeKey = merge
            }
            Direction.UNDO -> {
                check(undo.last() == plan.project)
                undo.removeAt(undo.lastIndex); redo.add(project); lastMergeKey = null
            }
            Direction.REDO -> {
                check(redo.last() == plan.project)
                redo.removeAt(redo.lastIndex); undo.add(project); lastMergeKey = null
            }
            Direction.REPLACE -> Unit
        }
        project = plan.project
        revision = plan.revision
        plan.resolved = true
        return project
    }
    fun breakCoalescing() { lastMergeKey = null }

    /** Asset lifetime roots include both history directions. GC is intentionally outside this class. */
    fun protectedAssets(): Set<String> = (listOf(project) + undo + redo).flatMap { p -> p.assets.map { it.hash } }.toSet()

    private fun makePlan(reduction: Reduction, direction: Direction): EditPlan {
        invalidate()
        val changes = reduction.mutation == Mutation.PROJECT || direction == Direction.REPLACE
        if (changes) check(revision < Long.MAX_VALUE) { "Revision exhausted" }
        return EditPlan(owner, epoch, project, reduction, direction, if (changes) revision + 1 else revision)
    }
    private fun invalidate() { check(epoch < Long.MAX_VALUE); epoch++ }
    private fun requireCurrent(plan: EditPlan) {
        require(plan.owner === owner && !plan.resolved && plan.epoch == epoch && plan.before == project) { "Foreign, resolved or stale edit plan" }
    }
}
