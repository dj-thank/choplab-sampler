package com.choplab.ui.vocal

import com.choplab.core.DocumentState
import com.choplab.core.ProgramCompiler
import com.choplab.core.model.*
import com.choplab.core.vocal.*
import com.choplab.jvm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.*

class VocalPracticeControllerTest {
    private val asset = Asset("a".repeat(64),"wav",44L+48_000*8,48_000,2,48_000,"Practice",AssetRole.RENDERED)
    private class Ports(override val renderer: VocalPracticeRenderer) : VocalPracticePorts {
        override val previewState = MutableStateFlow(PracticePreviewState())
        var guards = 0; var starts = 0; var stops = 0
        var loop: Boolean? = null; var revision: Long? = null
        var failRestore = false
        override suspend fun prepareAllowed(expectedRevision: Long): PracticeResult<Unit> { guards++; return PracticeResult.Success(Unit) }
        override suspend fun preview(asset: Asset, loop: Boolean, expectedRevision: Long): PracticeResult<Unit> {
            starts++; this.loop=loop; revision=expectedRevision
            previewState.value=PracticePreviewState(true,true); return PracticeResult.Success(Unit)
        }
        override fun requestStopPreview() {}
        override suspend fun stopPreview(): PracticeResult<Unit> {
            stops++
            if (failRestore) return PracticeResult.Failure(PracticeProblem.RESTORE_FAILED)
            previewState.value=PracticePreviewState(); return PracticeResult.Success(Unit)
        }
    }
    private fun renderer(block: suspend () -> PracticeResult<Asset>) = object : VocalPracticeRenderer {
        override suspend fun render(project: Project, revision: Long, request: VocalPracticeRequest, progress: (PracticeProgress) -> Unit) = block()
    }
    @Test fun repeatedStopAndCloseAndCancellationBeforeWorkerDispatchAlwaysFinishRestoration() = runBlocking<Unit> {
        for (beforeDispatch in listOf(false, true)) {
            val ports = Ports(renderer { PracticeResult.Success(asset) })
            val controller = VocalPracticeController(MutableStateFlow(DocumentState(Project(), 0)),
                MutableStateFlow(PracticeAvailability.EDITABLE), ports, this, 0, 48_000)
            try {
                if (beforeDispatch) {
                    val pending = async(start = CoroutineStart.UNDISPATCHED) { controller.preview() }
                    assertEquals(PracticePhase.PREPARING, controller.state.value.phase)
                    controller.close(); controller.close()
                    assertFalse(withTimeout(5000) { pending.await() })
                } else {
                    assertTrue(controller.preview())
                    controller.stop(); controller.stop()
                    controller.close(); controller.close()
                }
                withTimeout(5000) { controller.closeAndJoin() }
                assertEquals(PracticePhase.CLOSED, controller.state.value.phase)
                assertFalse(ports.previewState.value.ownsSource)
                assertEquals(if (beforeDispatch) 0 else 1, ports.stops)
            } finally { controller.closeAndJoin() }
        }
    }
    @Test fun productionWorkerToLoopPreviewLeavesDocumentUndoAndOriginalAudioUnchanged() = runBlocking<Unit> {
        val root=Files.createTempDirectory("practice-controller-")
        val store=FileAssetStore(root.resolve("assets")); val pcm=WavPcmPort(store)
        val bytes=ByteArrayOutputStream().also { WavCodec.writeFloat(it,FloatArray(4800*2){i->if(i%2==0).1f else -.2f}) }.toByteArray()
        val original=Asset(sha256(bytes),"wav",bytes.size.toLong(),48_000,2,4800,"Original")
        store.write(original,bytes)
        val project=Project(assets=frozenListOf(original),tracks=frozenListOf(Track("v","Voice",TrackKind.VOCAL)),
            clips=frozenListOf(Clip("c","v",original.hash,FrameRange(0,4800),timelineStartFrame=0)))
        val document=MutableStateFlow(DocumentState(project,7,true,true))
        val before=document.value
        val ports=Ports(VocalPracticeWorker(ProgramCompiler(pcm),store,root.resolve("temporary")))
        val controller=VocalPracticeController(document,MutableStateFlow(PracticeAvailability.EDITABLE),ports,this,0,4800)
        try {
            assertTrue(controller.preview()); assertEquals(PracticePhase.PLAYING,controller.state.value.phase)
            assertEquals(true,ports.loop); assertEquals(7L,ports.revision); assertEquals(2,ports.guards)
            assertEquals(before,document.value); assertContentEquals(bytes,store.read(original))
            controller.stop(); until { controller.state.value.phase==PracticePhase.EDITING }
            assertFalse(ports.previewState.value.ownsSource); assertEquals(1,ports.stops)
        } finally { controller.closeAndJoin(); pcm.close(); root.toFile().deleteRecursively() }
    }
    @Test fun cancellingAWaiterDoesNotAbandonTheSessionAndCloseWaitsForRestoration() = runBlocking<Unit> {
        val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
        val ports=Ports(renderer { entered.complete(Unit); release.await(); PracticeResult.Success(asset) })
        val controller=VocalPracticeController(MutableStateFlow(DocumentState(Project(),0)),MutableStateFlow(PracticeAvailability.EDITABLE),ports,this,0,48_000)
        try {
            val waiter=async { controller.preview() }; entered.await(); waiter.cancelAndJoin()
            assertEquals(PracticePhase.PREPARING,controller.state.value.phase); assertFalse(controller.preview())
            release.complete(Unit); until { controller.state.value.phase==PracticePhase.PLAYING }
            ports.failRestore=true; controller.closeAndJoin()
            assertEquals(PracticePhase.STOPPING,controller.state.value.phase); assertTrue(ports.previewState.value.ownsSource)
            assertEquals(PracticeProblem.RESTORE_FAILED,controller.state.value.problem)
            ports.failRestore=false; controller.closeAndJoin()
            assertEquals(PracticePhase.CLOSED,controller.state.value.phase); assertFalse(ports.previewState.value.ownsSource)
        } finally { release.complete(Unit); ports.failRestore=false; controller.closeAndJoin() }
    }
    @Test fun lateNonCooperativeRenderCannotStartAfterStopRevisionRecordingOrClose() = runBlocking<Unit> {
        for(kind in listOf("stop","revision","recording","close","late-failure")) {
            val entered=CompletableDeferred<Unit>(); val release=CompletableDeferred<Unit>()
            val document=MutableStateFlow(DocumentState(Project(),0))
            val availability=MutableStateFlow(PracticeAvailability.EDITABLE)
            val ports=Ports(renderer { withContext(NonCancellable) { entered.complete(Unit); release.await() }
                if(kind=="late-failure") PracticeResult.Failure(PracticeProblem.FAILED) else PracticeResult.Success(asset) })
            val controller=VocalPracticeController(document,availability,ports,this,0,48_000)
            try {
                val work=async { controller.preview() }; entered.await()
                when(kind) {
                    "revision" -> document.value=document.value.copy(revision=1)
                    "recording" -> availability.value=PracticeAvailability.RECORDING
                    "close" -> controller.close()
                    else -> controller.stop()
                }
                until { controller.state.value.phase==PracticePhase.CANCELLING }
                assertFalse(controller.preview()); assertFalse(work.isCompleted)
                release.complete(Unit); assertFalse(work.await())
                assertEquals(0,ports.starts); assertEquals(0,ports.stops,"Preparation never owns or stops SOURCE")
                assertEquals(if(kind=="close") PracticePhase.CLOSED else PracticePhase.EDITING,controller.state.value.phase)
                assertEquals(when(kind) { "revision"->PracticeProblem.STALE; "recording"->PracticeProblem.RECORDING; else->PracticeProblem.CANCELLED },controller.state.value.problem)
            } finally { release.complete(Unit); controller.closeAndJoin() }
        }
    }
    private suspend fun until(ready:()->Boolean)=withTimeout(5000){while(!ready())delay(1)}
}
