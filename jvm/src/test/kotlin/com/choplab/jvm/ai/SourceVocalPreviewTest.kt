package com.choplab.jvm.ai

import com.choplab.core.*
import com.choplab.core.ai.*
import com.choplab.core.model.*
import com.choplab.engine.*
import com.choplab.jvm.*
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.LockSupport
import kotlin.test.*

/** Real engine and audition commands with in-memory decoded PCM and a synthetic output endpoint. */
class SourceVocalPreviewTest {
    @Test fun chopRangeClaimsOneOwnerStopsAtItsExclusiveEndAndRestoresFullSource() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open()
            val before = f.studio.document.value
            val range = FrameRange(12_003, 24_011)
            assertIs<TtsResult.Success<Unit>>(f.preview.startRange(f.source, range, before.revision, VocalPreviewOwner.CHOP))
            waitUntil { f.preview.state.value.phase == VocalPreviewPhase.PLAYING }
            val region = (f.commands.last { it is EngineCommand.SetOriginalSource } as EngineCommand.SetOriginalSource).source!!
            assertEquals(range.start.toInt(), region.startFrame); assertEquals(range.end.toInt(), region.endFrame)
            assertFalse(region.loop)
            f.preview.requestStop(VocalPreviewOwner.GUIDE)
            assertIs<TtsResult.Success<Unit>>(f.preview.stop(VocalPreviewOwner.PITCH))
            assertEquals(VocalPreviewOwner.CHOP, f.preview.state.value.owner)
            assertEquals(TtsProblem.BUSY, assertIs<TtsResult.Failure>(f.preview.start(f.guide, before.revision)).failure.problem)
            waitUntil { !f.preview.state.value.ownsSource }
            assertEquals(5_000L, f.audition.nativeFrame())
            assertEquals(.25f, f.engine.originalPlayback().gain); assertEquals(6f, f.pitch())
            val restored = (f.commands.last { it is EngineCommand.SetOriginalSource } as EngineCommand.SetOriginalSource).source!!
            assertEquals(0, restored.startFrame); assertEquals(f.source.frames.toInt(), restored.endFrame)
            assertFalse(f.engine.originalPlayback().playing)
            assertEquals(before, f.studio.document.value)
            assertEquals(TtsProblem.INVALID_AUDIO, assertIs<TtsResult.Failure>(f.preview.startRange(f.guide, range, before.revision, VocalPreviewOwner.CHOP)).failure.problem)
        } finally { f.close() }
    }
    @Test fun unityPreviewRestoresPositionPitchGainOnceAndChangedSourceWins() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open()
            assertTrue(f.audition.play(f.source))
            val before = f.studio.document.value
            assertIs<TtsResult.Success<Unit>>(f.preview.start(f.guide, before.revision))
            waitUntil { f.preview.state.value.phase == VocalPreviewPhase.PLAYING }
            val originalFrame = f.preview.state.value.originalFrame
            assertEquals(0f, f.pitch())
            assertEquals(1f, f.engine.originalPlayback().gain)
            assertIs<TtsResult.Success<Unit>>(f.preview.stop())
            assertEquals(originalFrame, f.audition.nativeFrame())
            assertEquals(6f, f.pitch())
            assertEquals(.25f, f.engine.originalPlayback().gain)
            assertFalse(f.engine.originalPlayback().playing)
            val commands = f.commands.size
            assertIs<TtsResult.Success<Unit>>(f.preview.stop())
            assertEquals(commands, f.commands.size, "A second stop does not restore or restart twice")
            assertEquals(before, f.studio.document.value)

            assertIs<TtsResult.Success<Unit>>(f.preview.start(f.guide, before.revision))
            waitUntil { f.preview.state.value.phase == VocalPreviewPhase.PLAYING }
            val changed = Project(assets = frozenListOf(f.other), source = Source(f.other.hash, FrameRange(72_000, 96_000), pitchSemitones = -3.0))
            assertTrue(f.studio.dispatch(Action.New(changed)).accepted)
            waitUntil { !f.preview.state.value.ownsSource && f.preview.state.value.failure?.problem == TtsProblem.STALE_DOCUMENT }
            assertEquals(changed, f.studio.document.value.project)
            assertEquals(72_000L, f.audition.nativeFrame())
            assertEquals(-3f, f.pitch())
            assertEquals(f.other.hash, f.loaded.last())
            assertFalse(f.engine.originalPlayback().playing)
        } finally { f.close() }
    }

    @Test fun cancelledDecodeCannotAdoptOrPlayItsLateResult() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open(); f.delayGuide.set(true)
            val before = f.studio.document.value
            assertIs<TtsResult.Success<Unit>>(f.preview.start(f.guide, before.revision))
            withTimeout(3_000) { f.decodeEntered.await() }
            assertIs<TtsResult.Success<Unit>>(f.preview.stop())
            val plays = f.commands.count { it is EngineCommand.PlayOriginalSource }
            f.decodeRelease.complete(Unit)
            withTimeout(3_000) { f.decodeReturned.await() }
            // Drain a real owner command after the late producer has returned.
            assertTrue(f.audition.originalGain(.25f))
            assertFalse(f.preview.state.value.ownsSource)
            assertFalse(f.engine.originalPlayback().playing)
            assertEquals(5_000L, f.audition.nativeFrame())
            assertEquals(plays, f.commands.count { it is EngineCommand.PlayOriginalSource })
            assertEquals(before, f.studio.document.value)
        } finally { f.decodeRelease.complete(Unit); f.close() }
    }

    @Test fun refusedRestorationAndOutputLossStayOwnedUntilAConfirmedRetry() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open()
            val before = f.studio.document.value
            assertIs<TtsResult.Success<Unit>>(f.preview.start(f.guide, before.revision))
            waitUntil { f.preview.state.value.phase == VocalPreviewPhase.PLAYING }
            f.refuseRestoredGain.set(true)
            assertIs<TtsResult.Failure>(f.preview.stop())
            assertTrue(f.preview.state.value.ownsSource)
            assertEquals(VocalPreviewPhase.FAILED, f.preview.state.value.phase)
            f.refuseRestoredGain.set(false)
            assertIs<TtsResult.Success<Unit>>(f.preview.stop())
            assertFalse(f.preview.state.value.ownsSource)

            assertIs<TtsResult.Success<Unit>>(f.preview.start(f.guide, before.revision))
            waitUntil { f.preview.state.value.phase == VocalPreviewPhase.PLAYING }
            f.lost.set(true)
            waitUntil { f.engine.status.value.phase == DriverPhase.EDITING_ONLY }
            assertEquals(OriginalPlaybackProbe.Unavailable, f.engine.originalPlaybackProbe())
            waitUntil { f.preview.state.value.phase == VocalPreviewPhase.FAILED }
            assertEquals(TtsProblem.FAILED, f.preview.state.value.failure?.problem,
                "Unavailable output must not be mistaken for successful preview completion")
            f.lost.set(false); f.engine.reattach()
            waitUntil { f.engine.status.value.phase == DriverPhase.ATTACHED && !f.preview.state.value.ownsSource }
            assertEquals(before, f.studio.document.value)
            assertEquals(.25f, f.engine.originalPlayback().gain)
            assertEquals(6f, f.pitch())
            assertFalse(f.engine.originalPlayback().playing)
        } finally { f.close() }
    }

    @Test fun queuedStopFromAnOlderPreviewCannotRestoreOverTheNewExplicitPreview() = runBlocking<Unit> {
        val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { queued.add(block) }
        }
        val previewScope = CoroutineScope(SupervisorJob() + dispatcher)
        val f = Fixture(previewScope)
        suspend fun drainUntil(done: () -> Boolean) = withTimeout(5_000) {
            while (!done()) { repeat(100) { queued.poll()?.run() }; delay(1) }
        }
        try {
            f.open()
            assertIs<TtsResult.Success<Unit>>(f.preview.start(f.guide, f.studio.document.value.revision))
            drainUntil { f.preview.state.value.phase == VocalPreviewPhase.PLAYING }
            val offset = f.commands.size
            f.preview.requestStop()
            assertIs<TtsResult.Success<Unit>>(f.preview.start(f.guide, f.studio.document.value.revision))
            drainUntil { f.preview.state.value.phase == VocalPreviewPhase.PLAYING }
            assertTrue(f.preview.state.value.ownsSource)
            assertEquals(1f, f.engine.originalPlayback().gain)
            assertFalse(f.commands.drop(offset).any { it is EngineCommand.SetOriginalPitch && it.semitones == 6f },
                "A delayed previous cancellation cannot restore the old SOURCE into a newly requested guide")
        } finally { f.close(); previewScope.cancel(); repeat(100) { queued.poll()?.run() } }
    }

    @Test fun practiceLoopsThePreparedPeriodAndCannotStopOrReplaceAnotherFeatureClaim() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open()
            val before = f.studio.document.value
            val short = f.guide.copy(frames = 481)
            assertIs<TtsResult.Success<Unit>>(f.preview.start(short, before.revision, true, VocalPreviewOwner.PRACTICE))
            waitUntil { f.preview.state.value.phase == VocalPreviewPhase.PLAYING }
            val start = f.engine.snapshot().frame
            waitUntil { f.engine.snapshot().frame >= start + 481 * 5 }
            assertTrue(f.engine.originalPlayback().playing)
            assertTrue(f.audition.nativeFrame() in 0..480)
            val loaded = (f.commands.last { it is EngineCommand.SetOriginalSource } as EngineCommand.SetOriginalSource).source!!
            assertTrue(loaded.loop); assertEquals(480, loaded.loopCrossfadeFrames)
            assertEquals(TtsProblem.BUSY, assertIs<TtsResult.Failure>(f.preview.start(f.guide, before.revision)).failure.problem)
            assertIs<TtsResult.Success<Unit>>(f.preview.stop())
            f.preview.requestStop()
            for (other in listOf(VocalPreviewOwner.GUIDE, VocalPreviewOwner.PITCH)) {
                assertEquals(TtsProblem.BUSY, assertIs<TtsResult.Failure>(f.preview.start(short, before.revision, false, other)).failure.problem)
                assertIs<TtsResult.Success<Unit>>(f.preview.stop(other))
                f.preview.requestStop(other)
            }
            assertTrue(f.preview.state.value.ownsSource)
            assertIs<TtsResult.Success<Unit>>(f.preview.stop(VocalPreviewOwner.PRACTICE))
            assertFalse(f.preview.state.value.ownsSource)
            assertFalse(f.engine.originalPlayback().playing)
            assertEquals(5_000L, f.audition.nativeFrame())
            assertFalse((f.commands.last { it is EngineCommand.SetOriginalSource } as EngineCommand.SetOriginalSource).source!!.loop)
            assertEquals(before, f.studio.document.value)
            assertIs<TtsResult.Success<Unit>>(f.preview.start(short, before.revision))
            f.preview.requestStop(VocalPreviewOwner.PRACTICE)
            waitUntil { !f.preview.state.value.ownsSource }
            assertEquals(VocalPreviewPhase.IDLE, f.preview.state.value.phase)
        } finally { f.close() }
    }

    @Test fun pitchCanAuditionTheUnchanged48kStereoOriginalWithoutRelabellingItsAsset() = runBlocking<Unit> {
        val f = Fixture()
        try {
            f.open()
            val before = f.studio.document.value
            for (owner in listOf(VocalPreviewOwner.GUIDE, VocalPreviewOwner.PRACTICE))
                assertEquals(TtsProblem.INVALID_AUDIO, assertIs<TtsResult.Failure>(f.preview.start(f.source, before.revision, false, owner)).failure.problem)
            assertEquals(TtsProblem.INVALID_AUDIO, assertIs<TtsResult.Failure>(f.preview.start(f.source.copy(sampleRate=44_100), before.revision, false, VocalPreviewOwner.PITCH)).failure.problem)
            assertEquals(TtsProblem.INVALID_AUDIO, assertIs<TtsResult.Failure>(f.preview.start(f.source.copy(channels=1), before.revision, false, VocalPreviewOwner.PITCH)).failure.problem)
            assertIs<TtsResult.Success<Unit>>(f.preview.start(f.source, before.revision, false, VocalPreviewOwner.PITCH))
            waitUntil { f.preview.state.value.phase == VocalPreviewPhase.PLAYING }
            assertEquals(f.source.hash, f.preview.state.value.assetHash)
            assertEquals(0f, f.pitch())
            assertIs<TtsResult.Success<Unit>>(f.preview.stop(VocalPreviewOwner.PITCH))
            assertEquals(5_000L, f.audition.nativeFrame())
            assertEquals(before, f.studio.document.value)
        } finally { f.close() }
    }

    private class Fixture(private val previewScope: CoroutineScope? = null) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val source = Asset("a".repeat(64), "wav", 44, 48_000, 2, 480_000, "Original")
        val other = source.copy(hash = "b".repeat(64), name = "Changed original")
        val guide = source.copy(hash = "c".repeat(64), frames = 480_000, role = AssetRole.RENDERED, name = "Guide")
        val loaded = CopyOnWriteArrayList<String>()
        val commands = CopyOnWriteArrayList<EngineCommand>()
        val delayGuide = AtomicBoolean(); val lost = AtomicBoolean(); val refuseRestoredGain = AtomicBoolean()
        val decodeEntered = CompletableDeferred<Unit>(); val decodeRelease = CompletableDeferred<Unit>(); val decodeReturned = CompletableDeferred<Unit>()
        private val pcm = object : PcmPort {
            override suspend fun load(asset: Asset): PcmAsset {
                if (asset == guide && delayGuide.get()) withContext(NonCancellable) { decodeEntered.complete(Unit); decodeRelease.await() }
                loaded += asset.hash
                return PcmAsset.fromInterleaved(FloatArray(asset.frames.toInt() * 2) { if (it % 2 == 0) .1f else -.03f }).also {
                    if (asset == guide && delayGuide.get()) decodeReturned.complete(Unit)
                }
            }
        }
        val engine = StreamingEnginePort(ProgramCompiler(pcm), { object : AudioSink {
            override val encoding = SinkEncoding.FLOAT32
            override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
                check(!lost.get())
                LockSupport.parkNanos(length.toLong() / 8 * 1_000_000_000L / 48_000)
                return length
            }
            override fun close() = Unit
        } })
        val audition = SourceAuditionController(engine, pcm, scope, send = { command ->
            commands += command
            if (command is EngineCommand.SetOriginalMonitorGain && command.gain == .25f && refuseRestoredGain.get()) false else engine.applyMonitoring(command)
        })
        private val project = Project(assets = frozenListOf(source), source = Source(source.hash, FrameRange(0, 480_000), pitchSemitones = 6.0))
        val studio = Studio(scope, Services(object : AssetStore {
            override suspend fun containsVerified(asset: Asset) = true
            override suspend fun write(asset: Asset, bytes: ByteArray) = Unit
            override suspend fun read(asset: Asset) = byteArrayOf()
        }, object : ImportPort { override suspend fun import(location: Location) = source }, object : ProjectPort {
            override suspend fun save(project: Project, revision: Long, location: Location) = Unit
            override suspend fun open(location: Location) = project
        }, object : ExportPort { override suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt = error("Not used") }, engine), project)
        val preview = SourceVocalPreview(studio, engine, audition, previewScope ?: scope)
        suspend fun open() {
            waitUntil { engine.status.value.phase == DriverPhase.ATTACHED }
            assertTrue(studio.dispatch(Action.New(project)).accepted)
            assertTrue(audition.seek(source, 5_000)); assertTrue(audition.pitch(6f)); assertTrue(audition.originalGain(.25f))
        }
        fun pitch() = (commands.last { it is EngineCommand.SetOriginalPitch } as EngineCommand.SetOriginalPitch).semitones
        suspend fun close() { preview.close(); audition.close(); studio.dispatch(Action.Close); engine.close(); scope.cancel() }
    }
    private companion object { suspend fun waitUntil(condition: () -> Boolean) = withTimeout(10_000) { while (!condition()) delay(5) } }
}
