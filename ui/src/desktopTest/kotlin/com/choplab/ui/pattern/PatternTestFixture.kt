package com.choplab.ui.pattern

import com.choplab.core.*
import com.choplab.core.edit.*
import com.choplab.core.model.*
import com.choplab.core.pattern.PatternVoiceRender
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicInteger

internal class PatternTestFixture(
    initial: Project = project(),
    selectedPad: Int = 0,
    val render: suspend (Int, Pad, Asset, PatternVoiceRender) -> Asset? = { _, pad, source, request -> rendered(pad, source, request) },
    val beforeApply: suspend () -> Unit = {},
) {
    val document = MutableStateFlow(DocumentState(initial, 0))
    val selection = MutableStateFlow(SelectionState(padId = selectedPad))
    val availability = MutableStateFlow(PatternAvailability.EDITABLE)
    val edits = mutableListOf<Intent>()
    val renderCalls = AtomicInteger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val controller = StepPatternController(document, selection, availability, object : StepPatternPorts {
        override suspend fun apply(intent: Intent, expectedRevision: Long): Boolean {
            beforeApply()
            if (document.value.revision != expectedRevision || availability.value != PatternAvailability.EDITABLE) return false
            val after = Reducer.reduce(document.value.project, intent).project
            edits += intent
            document.value = DocumentState(after, expectedRevision + 1, canUndo = true)
            return true
        }
        override suspend fun render(pad: Pad, source: Asset, request: PatternVoiceRender) = render(renderCalls.incrementAndGet(), pad, source, request)
        override suspend fun selectPad(padId: Int): Boolean { selection.value = selection.value.copy(padId = padId); return true }
    }, scope)
    suspend fun action(action: PatternAction) = controller.dispatch(action)
    fun close() { controller.close(); scope.cancel() }
    companion object {
        fun project(): Project {
            val assets = (0..1).map { Asset((it + 1).toString().padStart(64, '0'), "wav", 38444, 48_000, 2, 4800, "sound$it") }.frozen()
            return Project(assets = assets, pads = (0..127).map { id -> if (id < 2) Pad(id, assets[id].hash, FrameRange(0, 4800)) else Pad(id) }.frozen())
        }
        fun rendered(pad: Pad, source: Asset, request: PatternVoiceRender): Asset = Asset(
            "f".repeat(56) + request.hashCode().toUInt().toString(16).padStart(8, '0'), "wav", request.limitFrames * 8L + 44,
            48_000, 2, request.limitFrames.toLong(), "render-${pad.id}", AssetRole.RENDERED, derivedFrom = source.hash)
        suspend fun waitUntil(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(5) }
    }
}
