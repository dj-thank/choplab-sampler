package com.choplab.core

import com.choplab.core.edit.*
import com.choplab.core.model.Project
import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

data class DocumentState(val project: Project, val revision: Long, val canUndo: Boolean = false, val canRedo: Boolean = false, val savedRevision: Long? = null, val audiblePending: Boolean = true)
data class SelectionState(val padId: Int = 0, val patternId: String = "pattern-1", val slice: Int? = null,
                          val playbackTarget: PlaybackTarget = PlaybackTarget.Pattern(patternId))
data class WorkState(val jobId: Long? = null, val operation: Operation? = null, val basedOnRevision: Long? = null, val preparationId: Long? = null)

sealed interface Action {
    data class Edit(val intent: Intent) : Action
    data object Undo : Action
    data object Redo : Action
    data class SelectPad(val id: Int) : Action
    data class SelectPattern(val id: String) : Action
    data class SelectPlaybackTarget(val target: PlaybackTarget) : Action
    data class SelectSlice(val index: Int?) : Action
    data class Import(val location: Location) : Action
    data class Open(val location: Location) : Action
    data class New(val project: Project = Project()) : Action
    data class Save(val location: Location) : Action
    data class Export(val request: ExportRequest, val target: PlaybackTarget? = null) : Action
    data object CancelWork : Action
    data class Trigger(val padId: Int, val velocity: Float = 1f) : Action
    data class Release(val padId: Int) : Action
    data object Play : Action
    data object Pause : Action
    data object Resume : Action
    data class Seek(val sequenceFrame: Long) : Action { init { require(sequenceFrame in 0..com.choplab.core.model.ProjectLimits.MAX_TIMELINE_FRAMES) } }
    data object Stop : Action
    data object RefreshTransport : Action
    data object Close : Action
}
data class ActionResult(val accepted: Boolean, val notice: Notice? = null)

/**
 * One actor owns edits, selection, jobs and engine command production. Hosts never write flows.
 * Worker completions carry job, project-generation and revision fences; cancellation need not cooperate.
 */
class Studio(scope: CoroutineScope, private val services: Services, initial: Project = Project(), initialRevision: Long = 0,
             private val preparationDispatcher: CoroutineDispatcher = Dispatchers.Default) {
    private val session = EditSession(initial, initialRevision)
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val ownedScope = CoroutineScope(scope.coroutineContext + ownerJob)
    private val mailbox = Channel<Message>(64)
    private val _document = MutableStateFlow(DocumentState(initial, initialRevision))
    private val _selection = MutableStateFlow(SelectionState(patternId = initial.patterns.first().id))
    private val _work = MutableStateFlow(WorkState())
    private val _transport = MutableStateFlow(TransportState())
    private val _notices = MutableSharedFlow<Notice>(extraBufferCapacity = 32)
    val document: StateFlow<DocumentState> = _document.asStateFlow()
    val selection: StateFlow<SelectionState> = _selection.asStateFlow()
    val work: StateFlow<WorkState> = _work.asStateFlow()
    val transport: StateFlow<TransportState> = _transport.asStateFlow()
    val notices: SharedFlow<Notice> = _notices.asSharedFlow()
    private var worker: Job? = null
    private var preparation: Preparation? = null
    private var generation = 0L
    private var nextJob = 0L
    private var orderId = 0L
    private var commandFrame = 0L
    private var closed = false

    private enum class Purpose { EDIT, NEW, OPEN, IMPORT, SELECT }
    private class Preparation(
        val id: Long, val generation: Long, val revision: Long, val plan: EditPlan?,
        val pattern: String, val target: PlaybackTarget, val purpose: Purpose, val answer: CompletableDeferred<ActionResult>?, val workJobId: Long?,
    ) { lateinit var job: Job }

    private sealed interface Message {
        data class Request(val action: Action, val answer: CompletableDeferred<ActionResult>) : Message
        data class Complete(val id: Long, val generation: Long, val revision: Long, val operation: Operation, val result: Result<Any>) : Message
        data class Prepared(val id: Long, val generation: Long, val revision: Long, val result: Result<EngineProgram>) : Message
        data class CancelRequest(val answer: CompletableDeferred<ActionResult>) : Message
    }
    init {
        ownedScope.launch {
            try {
                for (message in mailbox) when (message) {
                    is Message.Request -> {
                        if (message.answer.isCancelled) continue
                        val result = try { handle(message.action, message.answer) }
                        catch (cancel: CancellationException) { message.answer.cancel(cancel); throw cancel }
                        catch (_: IllegalArgumentException) { rejected(Rejection.INVALID_INPUT) }
                        catch (_: Exception) { failed(Operation.EDIT) }
                        if (result != null) message.answer.complete(result)
                        if (closed) break
                    }
                    is Message.Complete -> try { complete(message) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) {
                            if (_work.value.jobId == message.id) _work.value = _work.value.copy(jobId = null, operation = null, basedOnRevision = null)
                            failed(message.operation)
                        }
                    is Message.Prepared -> prepared(message)
                    is Message.CancelRequest -> if (preparation?.answer === message.answer) cancelPreparation()
                }
            } finally {
                closed = true
                worker?.cancel()
                cancelPreparation()
                mailbox.close()
                while (true) {
                    val pending = mailbox.tryReceive().getOrNull() ?: break
                    if (pending is Message.Request) pending.answer.complete(ActionResult(false, Notice.Rejected(Rejection.CLOSED)))
                }
                ownerJob.cancel()
            }
        }
    }
    suspend fun dispatch(action: Action): ActionResult {
        val answer = CompletableDeferred<ActionResult>(currentCoroutineContext()[Job])
        try { mailbox.send(Message.Request(action, answer)) }
        catch (_: kotlinx.coroutines.channels.ClosedSendChannelException) {
            val result = ActionResult(false, Notice.Rejected(Rejection.CLOSED)); answer.complete(result); return result
        }
        return try { answer.await() } catch (cancel: CancellationException) {
            mailbox.trySend(Message.CancelRequest(answer)); throw cancel
        }
    }

    private suspend fun handle(action: Action, answer: CompletableDeferred<ActionResult>): ActionResult? = when (action) {
        is Action.Edit -> { beforeEdit(); begin(session.plan(action.intent), answer) }
        Action.Undo -> { beforeEdit(); val plan = session.planUndo(); if (plan == null) rejected(Rejection.NO_HISTORY) else begin(plan, answer) }
        Action.Redo -> { beforeEdit(); val plan = session.planRedo(); if (plan == null) rejected(Rejection.NO_HISTORY) else begin(plan, answer) }
        is Action.SelectPad -> { require(action.id in 0..127); session.breakCoalescing(); _selection.value = _selection.value.copy(padId = action.id); ActionResult(true) }
        is Action.SelectSlice -> { require(action.index == null || action.index in requireNotNull(session.project.source).slices().indices); _selection.value = _selection.value.copy(slice = action.index); ActionResult(true) }
        is Action.SelectPattern -> {
            require(session.project.patterns.any { it.id == action.id })
            beforeEdit()
            begin(null, answer, Purpose.SELECT, PlaybackTarget.Pattern(action.id))
        }
        is Action.SelectPlaybackTarget -> {
            validateTarget(session.project, action.target)
            beforeEdit()
            begin(null, answer, Purpose.SELECT, action.target)
        }
        is Action.Import -> start(Operation.IMPORT) { services.importer.import(action.location).also { require(services.assets.containsVerified(it)) } }
        is Action.Open -> {
            cancelAllWork()
            start(Operation.OPEN) { services.projects.open(action.location) }
        }
        is Action.New -> {
            cancelAllWork()
            begin(session.planReplace(action.project), answer, Purpose.NEW)
        }
        is Action.Save -> {
            val project = session.project; val revision = session.revision
            start(Operation.SAVE) { services.projects.save(project, revision, action.location); Unit }
        }
        is Action.Export -> {
            val project = session.project; val target = action.target ?: _selection.value.playbackTarget
            validateTarget(project, target)
            start(Operation.EXPORT) { services.exporter.export(project, target, action.request) }
        }
        Action.CancelWork -> {
            val operation = _work.value.operation
            cancelAllWork()
            operation?.let { notice(Notice.Cancelled(it)) }; ActionResult(true)
        }
        is Action.Trigger -> {
            require(action.padId in 0..127 && session.project.pads[action.padId].assetHash != null)
            playback { frame, id -> EngineCommand.Trigger(frame, id, action.padId, action.velocity) }
        }
        is Action.Release -> playback { frame, id -> EngineCommand.Release(frame, id, action.padId) }
        Action.Play -> playback { frame, id -> EngineCommand.StartSequence(frame, id) }
        Action.Pause -> playback { frame, id -> EngineCommand.Pause(frame, id) }
        Action.Resume -> playback { frame, id -> EngineCommand.Resume(frame, id) }
        is Action.Seek -> playback { frame, id -> EngineCommand.Seek(frame, id, action.sequenceFrame) }
        Action.Stop -> { cancelPreparation(); playback { frame, id -> EngineCommand.Stop(frame, id) } }
        Action.RefreshTransport -> { _transport.value = services.engine.snapshot(); ActionResult(true) }
        Action.Close -> {
            cancelAllWork()
            val stopped = apply { frame, id -> EngineCommand.Stop(frame, id) }
            if (!stopped) rejected(Rejection.ENGINE_REFUSED) else {
                closed = true; ActionResult(true)
            }
        }
    }

    private fun beforeEdit() {
        cancelPreparation()
        if (_work.value.operation == Operation.IMPORT || _work.value.operation == Operation.OPEN) {
            worker?.cancel(); worker = null; _work.value = WorkState()
        }
    }

    /** Returns null while dispatch waits; the actor remains available for Stop, Cancel and replacement. */
    private suspend fun begin(plan: EditPlan?, answer: CompletableDeferred<ActionResult>?, purpose: Purpose = Purpose.EDIT,
                              selectedTarget: PlaybackTarget? = null, originJobId: Long? = null): ActionResult? {
        val project = plan?.project ?: session.project
        val target = selectedTarget ?: normalizeTarget(project, _selection.value.playbackTarget, purpose == Purpose.NEW || purpose == Purpose.OPEN)
        val pattern = (target as? PlaybackTarget.Pattern)?.id ?: _selection.value.patternId.takeIf { id -> project.patterns.any { it.id == id } } ?: project.patterns.first().id
        if (plan != null && plan.effects.isEmpty()) return commitPrepared(plan, pattern, target, null, purpose)
        check(nextJob < Long.MAX_VALUE)
        val id = ++nextJob
        val ownsWork = originJobId ?: id.takeIf { _work.value.jobId == null }
        val pending = Preparation(id, generation, session.revision, plan, pattern, target, purpose, answer, ownsWork)
        preparation = pending
        _work.value = if (_work.value.jobId == null) WorkState(id, Operation.EDIT, session.revision, id) else _work.value.copy(preparationId = id)
        pending.job = ownedScope.launch(preparationDispatcher) {
            val result = try { Result.success(services.engine.prepare(project, target, plan?.revision ?: pending.revision)) }
            catch (cancel: CancellationException) { if (!isActive) return@launch else Result.failure(cancel) }
            catch (failure: Exception) { Result.failure(failure) }
            mailbox.send(Message.Prepared(id, pending.generation, pending.revision, result))
        }
        return null
    }

    private suspend fun prepared(message: Message.Prepared) {
        val pending = preparation
        if (pending == null || pending.id != message.id) return
        if (pending.answer?.isCancelled == true || message.generation != generation || message.revision != session.revision) {
            cancelPreparation(); notice(Notice.StaleCompletion); return
        }
        preparation = null
        val result = try {
            if (message.result.isFailure) {
                pending.plan?.let(session::cancel); failed(Operation.EDIT)
            } else commitPrepared(pending.plan, pending.pattern, pending.target, message.result.getOrThrow(), pending.purpose)
        } catch (cancel: CancellationException) { pending.answer?.cancel(cancel); throw cancel }
        catch (_: Exception) { failed(Operation.EDIT) }
        clearPreparationWork(pending)
        pending.answer?.complete(result)
        if (result.accepted && pending.purpose == Purpose.IMPORT) notice(Notice.Completed(Operation.IMPORT))
        if (result.accepted && pending.purpose == Purpose.OPEN) notice(Notice.Completed(Operation.OPEN))
    }

    private suspend fun commitPrepared(plan: EditPlan?, pattern: String, target: PlaybackTarget, program: EngineProgram?, purpose: Purpose): ActionResult {
        if (plan == null) {
            return if (apply { frame, id -> EngineCommand.SwapProgram(frame, id, requireNotNull(program)) }) {
                session.breakCoalescing(); _selection.value = _selection.value.copy(patternId = pattern, playbackTarget = target); ActionResult(true)
            } else rejected(Rejection.ENGINE_REFUSED)
        }
        try {
            for ((index, effect) in plan.effects.withIndex()) {
                val success = when (effect) {
                    is Effect.StopPads -> {
                        var accepted = true
                        for (pad in effect.ids) if (!apply { frame, id -> EngineCommand.Release(frame, id, pad) }) { accepted = false; break }
                        accepted
                    }
                    Effect.PublishProject -> apply { frame, id -> EngineCommand.SwapProgram(frame, id, requireNotNull(program)) }
                }
                if (!success) { session.cancel(plan); _transport.value = services.engine.snapshot(); return rejected(Rejection.ENGINE_REFUSED) }
                session.acknowledge(plan, index)
            }
            session.commit(plan)
            _selection.value = _selection.value.copy(patternId = pattern, playbackTarget = target, slice = _selection.value.slice?.takeIf { it in (session.project.source?.slices()?.indices ?: IntRange.EMPTY) })
            publishDocument()
            if (purpose == Purpose.NEW || purpose == Purpose.OPEN) {
                check(generation < Long.MAX_VALUE); generation++
                _document.value = _document.value.copy(savedRevision = if (purpose == Purpose.OPEN) session.revision else null)
            }
            _transport.value = services.engine.snapshot()
            return ActionResult(true)
        } catch (cancel: CancellationException) { session.cancel(plan); throw cancel }
        catch (_: Exception) { session.cancel(plan); return failed(Operation.EDIT) }
    }

    private fun cancelPreparation() {
        val pending = preparation ?: return
        preparation = null
        pending.job.cancel()
        pending.plan?.let(session::cancel)
        clearPreparationWork(pending)
        val result = ActionResult(false, Notice.Cancelled(Operation.EDIT))
        pending.answer?.complete(result)
        notice(requireNotNull(result.notice))
    }
    private fun clearPreparationWork(pending: Preparation) {
        _work.value = if (_work.value.jobId == pending.workJobId) WorkState() else _work.value.copy(preparationId = null)
    }
    private fun cancelAllWork() { cancelPreparation(); worker?.cancel(); worker = null; _work.value = WorkState() }

    private fun validateTarget(project: Project, target: PlaybackTarget) {
        when (target) {
            is PlaybackTarget.Pattern -> require(project.patterns.any { it.id == target.id })
            is PlaybackTarget.Arrangement -> require(target.takeIds.all { id -> project.takes.any { it.id == id } })
        }
    }
    private fun normalizeTarget(project: Project, target: PlaybackTarget, replacing: Boolean): PlaybackTarget = when (target) {
        is PlaybackTarget.Pattern -> PlaybackTarget.Pattern(target.id.takeIf { id -> project.patterns.any { it.id == id } } ?: project.patterns.first().id)
        is PlaybackTarget.Arrangement -> PlaybackTarget.Arrangement(if (replacing) com.choplab.core.model.frozenListOf() else
            com.choplab.core.model.FrozenList.from(target.takeIds.filter { id -> project.takes.any { it.id == id } }))
    }

    private fun start(operation: Operation, block: suspend () -> Any): ActionResult {
        if (_work.value.jobId != null) return rejected(Rejection.BUSY)
        check(nextJob < Long.MAX_VALUE)
        val id = ++nextJob; val capturedGeneration = generation; val revision = session.revision
        _work.value = WorkState(id, operation, revision)
        worker = ownedScope.launch {
            val result = try { Result.success(block()) }
            catch (cancel: CancellationException) { if (!isActive) return@launch else Result.failure(cancel) }
            catch (failure: Exception) { Result.failure(failure) }
            mailbox.send(Message.Complete(id, capturedGeneration, revision, operation, result))
        }
        return ActionResult(true)
    }
    private suspend fun complete(message: Message.Complete) {
        if (_work.value.jobId != message.id) { notice(Notice.StaleCompletion); return }
        worker = null
        if (message.generation != generation || message.revision != session.revision) { _work.value = _work.value.copy(jobId = null, operation = null, basedOnRevision = null); notice(Notice.StaleCompletion); return }
        if (message.result.isFailure) { _work.value = _work.value.copy(jobId = null, operation = null, basedOnRevision = null); failed(message.operation); return }
        when (message.operation) {
            Operation.IMPORT -> {
                cancelPreparation()
                val result = begin(session.plan(Intent.ImportAsset(message.result.getOrThrow() as com.choplab.core.model.Asset)), null, Purpose.IMPORT, originJobId = message.id)
                if (result != null) { _work.value = WorkState(); if (result.accepted) notice(Notice.Completed(Operation.IMPORT)) }
            }
            Operation.OPEN -> {
                cancelPreparation()
                begin(session.planReplace(message.result.getOrThrow() as Project), null, Purpose.OPEN, originJobId = message.id)
            }
            else -> {
                _work.value = _work.value.copy(jobId = null, operation = null, basedOnRevision = null)
                if (message.operation == Operation.SAVE) _document.value = _document.value.copy(savedRevision = message.revision)
                notice(Notice.Completed(message.operation))
            }
        }
    }
    private suspend fun apply(command: (Long, Long) -> EngineCommand): Boolean {
        check(orderId < Long.MAX_VALUE)
        commandFrame = maxOf(commandFrame, services.engine.snapshot().frame)
        return withTimeoutOrNull(2_000) { services.engine.apply(command(commandFrame, ++orderId)) } ?: false
    }
    private suspend fun playback(command: (Long, Long) -> EngineCommand): ActionResult {
        val accepted = apply(command); _transport.value = services.engine.snapshot()
        return if (accepted) ActionResult(true) else rejected(Rejection.ENGINE_REFUSED)
    }
    private fun publishDocument() { _document.value = DocumentState(session.project, session.revision, session.canUndo, session.canRedo, _document.value.savedRevision, !services.engine.snapshot().outputAttached) }
    private fun notice(value: Notice) { _notices.tryEmit(value) }
    private fun rejected(reason: Rejection): ActionResult = ActionResult(false, Notice.Rejected(reason)).also { notice(requireNotNull(it.notice)) }
    private fun failed(operation: Operation): ActionResult = ActionResult(false, Notice.Failed(operation)).also { notice(requireNotNull(it.notice)) }
}
