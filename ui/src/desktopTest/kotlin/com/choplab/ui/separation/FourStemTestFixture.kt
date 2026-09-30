package com.choplab.ui.separation

import com.choplab.core.DocumentState
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.core.separation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

internal class FourStemTestFixture {
    val original = Asset("a".repeat(64), "wav", 384044, 48_000, 2, 48_000, "Original")
    val project = Project(assets = frozenListOf(original), source = Source(original.hash, FrameRange(0, original.frames)))
    val document = MutableStateFlow(DocumentState(project, 8))
    val availability = MutableStateFlow(FourStemAvailability.EDITABLE)
    val calls = AtomicInteger(); val cancels = AtomicInteger(); val closes = AtomicInteger(); val applies = AtomicInteger()
    val entered = CompletableDeferred<Unit>(); val returned = CompletableDeferred<Unit>(); val applyEntered = CompletableDeferred<Unit>()
    @Volatile var release: CompletableDeferred<Unit>? = null
    @Volatile var applyRelease: CompletableDeferred<Unit>? = null
    @Volatile var guardProblem: SeparationProblem? = null
    @Volatile var liveRecording = false
    @Volatile var failure: SeparationProblem? = null
    @Volatile var downloaded = false
    @Volatile var applied: Intent.SetArrangement? = null
    @Volatile var memoryReceipt: SeparationMemoryReceipt? = SeparationMemoryReceipt(
        SeparationMemorySource.MAC_FREE_AND_FILE_BACKED, 16L shl 30, 3L shl 30, false, 1_800_000_000_000L)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = FourStemController(document, availability, object : FourStemPort {
        override fun memoryReceipt() = this@FourStemTestFixture.memoryReceipt
        override suspend fun prepare(source: Asset, allowModelDownload: Boolean, progress: (SeparationProgress) -> Unit): SeparationResult<PreparedFourStems> {
            calls.incrementAndGet(); downloaded = allowModelDownload
            progress(SeparationProgress(0, 44_100)); entered.complete(Unit)
            release?.let { withContext(NonCancellable) { it.await() } }
            progress(SeparationProgress(44_100, 44_100)); returned.complete(Unit)
            failure?.let { return separationFailure(it) }
            return SeparationResult.Success(PreparedFourStems(source.hash, "f".repeat(64), StemPart.entries.mapIndexed { index, part ->
                SeparatedStem(part, Asset(('b' + index).toString().repeat(64), "wav", 352844, 44_100, 2, 44_100,
                    part.name, AssetRole.RENDERED, derivedFrom = source.hash)) }.frozen()))
        }
        override fun cancel() { cancels.incrementAndGet() }
        override fun close() { closes.incrementAndGet() }
    }, object : FourStemActions {
        override suspend fun prepareAllowed(expectedRevision: Long): SeparationResult<Unit> =
            guardProblem?.let(::separationFailure) ?: SeparationResult.Success(Unit)
        override suspend fun apply(intent: Intent.SetArrangement, expectedRevision: Long): Boolean {
            applyEntered.complete(Unit); applyRelease?.await()
            if (liveRecording || document.value.revision != expectedRevision) return false
            applied = intent; applies.incrementAndGet()
            document.value = DocumentState(Reducer.reduce(document.value.project, intent).project, expectedRevision + 1)
            return true
        }
    }, scope)
    fun close() { controller.close(); scope.cancel() }
    companion object {
        suspend fun waitUntil(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }
    }
}
