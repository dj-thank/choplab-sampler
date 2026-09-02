package com.choplab.desktop.audio

import com.choplab.sampler.audio.RenderedPcm
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JavaSoundWavPlayerTest {
    @Test
    fun sourceRenderDoesNotHoldTheEngineMonitor() {
        val probe = ClipProbe()
        val renderEntered = CountDownLatch(1)
        val releaseRender = CountDownLatch(1)
        val loadFailure = AtomicReference<Throwable?>(null)
        val player = JavaSoundWavPlayer(
            clipFactory = DesktopClipFactory { probe.clip },
            sourceRenderer = { audio, _ ->
                renderEntered.countDown()
                check(releaseRender.await(2L, TimeUnit.SECONDS)) {
                    "Timed out holding the detached source render"
                }
                RenderedPcm(audio.samples.copyOf(), audio.channelCount)
            },
        )
        val loadThread = Thread({
            loadFailure.set(
                runCatching { player.loadPcm(testAudio(), pitchSemitones = 5f) }
                    .exceptionOrNull(),
            )
        }, "ChopLab-Test-Detached-Source-Render")
        val stopCompleted = CountDownLatch(1)
        try {
            loadThread.start()
            assertTrue(renderEntered.await(2L, TimeUnit.SECONDS))
            Thread {
                player.stopAll()
                stopCompleted.countDown()
            }.start()

            assertTrue(
                stopCompleted.await(1L, TimeUnit.SECONDS),
                "Audio actions must not wait for the whole source render",
            )
            releaseRender.countDown()
            loadThread.join(5_000L)

            assertTrue(!loadThread.isAlive)
            assertEquals(null, loadFailure.get())
            assertEquals(1, probe.openCount)
        } finally {
            releaseRender.countDown()
            loadThread.join(5_000L)
            player.close()
        }
    }

    @Test
    fun failedClipOpenClosesTheUnpublishedLineExactlyOnce() {
        val probe = ClipProbe(failOn = "open")
        val player = JavaSoundWavPlayer(DesktopClipFactory { probe.clip })

        val failure = assertFailsWith<IllegalStateException> {
            player.loadPcm(testAudio(), pitchSemitones = 0f)
        }

        assertEquals("open failed", failure.message)
        assertEquals(1, probe.openCount)
        assertEquals(1, probe.closeCount)
        player.close()
        assertEquals(1, probe.closeCount)
    }

    @Test
    fun failedListenerRegistrationDoesNotRetainTheVoice() {
        val probe = ClipProbe(failOn = "listener")
        val player = JavaSoundWavPlayer(DesktopClipFactory { probe.clip })

        val failure = assertFailsWith<IllegalStateException> {
            player.triggerPad(testPad(), forceLoop = false)
        }

        assertEquals("listener failed", failure.message)
        assertEquals(1, probe.openCount)
        assertEquals(0, probe.startCount)
        assertEquals(1, probe.stopCount)
        assertEquals(1, probe.closeCount)

        player.stopAll()
        assertEquals(1, probe.stopCount)
        assertEquals(1, probe.closeCount)
    }

    @Test
    fun synchronousTerminalStopBeforeStartFailureClosesTheVoiceExactlyOnce() {
        val probe = ClipProbe(failOn = "start-after-stop")
        val player = JavaSoundWavPlayer(DesktopClipFactory { probe.clip })

        val failure = assertFailsWith<IllegalStateException> {
            player.triggerPad(testPad(), forceLoop = false)
        }

        assertEquals("start failed", failure.message)
        assertEquals(1, probe.openCount)
        assertEquals(1, probe.startCount)
        assertEquals(1, probe.stopCount)
        assertEquals(1, probe.closeCount)

        player.stopAll()
        assertEquals(1, probe.stopCount)
        assertEquals(1, probe.closeCount)
    }

    @Test
    fun failedLoopStartDoesNotRetainOrCloseTheVoiceTwice() {
        val probe = ClipProbe(failOn = "loop")
        val player = JavaSoundWavPlayer(DesktopClipFactory { probe.clip })

        val failure = assertFailsWith<IllegalStateException> {
            player.triggerPad(testPad(playMode = PadPlayMode.LOOP), forceLoop = false)
        }

        assertEquals("loop failed", failure.message)
        assertEquals(1, probe.openCount)
        assertEquals(1, probe.loopCount)
        assertEquals(1, probe.stopCount)
        assertEquals(1, probe.closeCount)

        player.stopAll()
        assertEquals(1, probe.stopCount)
        assertEquals(1, probe.closeCount)
    }

    @Test
    fun retirementFailureAfterOrdinaryCandidateStartClosesTheCandidateBeforeThrowing() {
        val prior = ClipProbe(failOn = "close-once", label = "prior")
        val candidate = ClipProbe(label = "candidate")
        val clips = ArrayDeque(listOf(prior, candidate))
        val player = JavaSoundWavPlayer(DesktopClipFactory { clips.removeFirst().clip })
        try {
            player.triggerPad(testPad(playMode = PadPlayMode.GATE, globalIndex = 4), forceLoop = false)

            val failure = assertFailsWith<IllegalStateException> {
                player.triggerPad(testPad(playMode = PadPlayMode.GATE, globalIndex = 4), forceLoop = false)
            }

            assertEquals("close failed", failure.message)
            assertEquals(1, candidate.startCount)
            assertEquals(1, candidate.stopCount)
            assertEquals(1, candidate.closeCount)

            player.stopAll()
            assertEquals(2, prior.closeCount)
        } finally {
            runCatching { player.close() }
        }
    }

    @Test
    fun candidateCleanupFailureAfterRetirementFailureRemainsOwnedForStopAllRetry() {
        val prior = ClipProbe(failOn = "close-once", label = "prior")
        val candidate = ClipProbe(failOn = "close-once", label = "candidate")
        val clips = ArrayDeque(listOf(prior, candidate))
        val player = JavaSoundWavPlayer(DesktopClipFactory { clips.removeFirst().clip })
        try {
            player.triggerPad(testPad(playMode = PadPlayMode.GATE, globalIndex = 4), forceLoop = false)

            val failure = assertFailsWith<IllegalStateException> {
                player.triggerPad(testPad(playMode = PadPlayMode.GATE, globalIndex = 4), forceLoop = false)
            }

            assertEquals("close failed", failure.message)
            assertEquals(listOf("close failed"), failure.suppressed.map { it.message })
            assertEquals(1, prior.closeCount)
            assertEquals(1, candidate.closeCount)

            player.stopAll()

            assertEquals(2, prior.closeCount)
            assertEquals(2, candidate.closeCount)
        } finally {
            runCatching { player.close() }
        }
    }

    @Test
    fun exclusiveLoopSessionStartsEveryCandidateBeforeRetiringSourceAndPadPlayback() {
        val events = mutableListOf<String>()
        val source = ClipProbe(label = "source", events = events)
        val oldPad = ClipProbe(label = "old-pad", events = events)
        val loop = ClipProbe(label = "loop", events = events)
        val companion = ClipProbe(label = "companion", events = events)
        val clips = ArrayDeque(listOf(source, oldPad, loop, companion))
        val player = JavaSoundWavPlayer(DesktopClipFactory { clips.removeFirst().clip })
        try {
            player.loadPcm(testAudio(), pitchSemitones = 0f)
            player.playFrom(0)
            player.triggerPad(testPad(globalIndex = 6), forceLoop = false)
            events.clear()

            player.prepareExclusiveLoopSession(
                loopPad = testPad(globalIndex = 7),
                companionPads = listOf(testPad(globalIndex = 8)),
            ).startCandidates().retirePriorPlayback()

            assertEquals(
                listOf(
                    "loop:open",
                    "companion:open",
                    "loop:loop",
                    "companion:start",
                    "source:stop",
                    "old-pad:stop",
                    "old-pad:close",
                ),
                events,
            )
        } finally {
            player.close()
        }
    }

    @Test
    fun lateTransportHitCannotSupersedeAStartedCandidateBeforeHandoff() {
        val loop = ClipProbe(label = "loop")
        val lateTransport = ClipProbe(label = "late-transport")
        val clips = ArrayDeque(listOf(loop, lateTransport))
        val player = JavaSoundWavPlayer(DesktopClipFactory { clips.removeFirst().clip })
        try {
            val started = player.prepareExclusiveLoopSession(
                loopPad = testPad(globalIndex = 7),
                companionPads = emptyList(),
            ).startCandidates()

            player.triggerPad(testPad(globalIndex = 7), forceLoop = false)
            started.retirePriorPlayback()

            assertEquals(0, loop.closeCount)
            assertEquals(1, lateTransport.closeCount)
            assertEquals(0, player.padFramePosition(7))
        } finally {
            player.close()
        }
    }

    @Test
    fun slowCandidateStartDoesNotHoldTheEngineMonitorAgainstTransportHits() {
        val loopEntered = CountDownLatch(1)
        val releaseLoop = CountDownLatch(1)
        val prior = ClipProbe(label = "prior")
        val loop = ClipProbe(
            label = "loop",
            operationHook = { operation ->
                if (operation == "loop") {
                    loopEntered.countDown()
                    check(releaseLoop.await(2, TimeUnit.SECONDS))
                }
            },
        )
        val concurrentTransport = ClipProbe(label = "concurrent-transport")
        val clips = ArrayDeque(listOf(prior, loop, concurrentTransport))
        val player = JavaSoundWavPlayer(DesktopClipFactory { clips.removeFirst().clip })
        val executor = Executors.newFixedThreadPool(2)
        try {
            player.triggerPad(testPad(globalIndex = 6), forceLoop = false)
            val start = executor.submit<DesktopStartedLoopSession> {
                player.prepareExclusiveLoopSession(
                    loopPad = testPad(globalIndex = 7),
                    companionPads = emptyList(),
                ).startCandidates()
            }

            assertTrue(loopEntered.await(2, TimeUnit.SECONDS))
            val transportAttempted = CountDownLatch(1)
            val transportFinished = CountDownLatch(1)
            val transport = executor.submit<Long> {
                transportAttempted.countDown()
                try {
                    player.triggerPad(testPad(globalIndex = 9), forceLoop = false)
                } finally {
                    transportFinished.countDown()
                }
            }
            assertTrue(transportAttempted.await(2, TimeUnit.SECONDS))
            val transportAdmittedWhileCandidateBlocked = transportFinished.await(1, TimeUnit.SECONDS)
            releaseLoop.countDown()
            val started = start.get(2, TimeUnit.SECONDS)
            transport.get(2, TimeUnit.SECONDS)
            started.retirePriorPlayback()

            assertTrue(transportAdmittedWhileCandidateBlocked)
            assertEquals(1, concurrentTransport.closeCount)
            assertEquals(0, loop.closeCount)
        } finally {
            releaseLoop.countDown()
            executor.shutdownNow()
            player.close()
        }
    }

    @Test
    fun exclusiveLoopCompanionFailureAbandonsCandidatesAndPreservesPriorPlayback() {
        val events = mutableListOf<String>()
        val source = ClipProbe(label = "source", events = events)
        val oldPad = ClipProbe(label = "old-pad", events = events)
        val loop = ClipProbe(label = "loop", events = events)
        val companion = ClipProbe(failOn = "start", label = "companion", events = events)
        val clips = ArrayDeque(listOf(source, oldPad, loop, companion))
        val player = JavaSoundWavPlayer(DesktopClipFactory { clips.removeFirst().clip })
        try {
            player.loadPcm(testAudio(), pitchSemitones = 0f)
            player.playFrom(0)
            player.triggerPad(testPad(globalIndex = 6), forceLoop = false)
            val sourceStopsBefore = source.stopCount
            events.clear()

            val failure = assertFailsWith<DesktopLoopSessionStartupException> {
                player.prepareExclusiveLoopSession(
                    loopPad = testPad(globalIndex = 7),
                    companionPads = listOf(testPad(globalIndex = 8)),
                ).startCandidates().retirePriorPlayback()
            }

            assertEquals("start failed", failure.message)
            assertEquals(sourceStopsBefore, source.stopCount)
            assertEquals(0, oldPad.stopCount)
            assertEquals(0, oldPad.closeCount)
            assertEquals(
                listOf(
                    "loop:open",
                    "companion:open",
                    "loop:loop",
                    "companion:start",
                    "loop:stop",
                    "loop:close",
                    "companion:stop",
                    "companion:close",
                ),
                events,
            )
        } finally {
            player.close()
        }
    }

    @Test
    fun exclusiveLoopPreparationFailureClosesPreparedCandidatesAndPreservesPriorPlayback() {
        val events = mutableListOf<String>()
        val source = ClipProbe(label = "source", events = events)
        val oldPad = ClipProbe(label = "old-pad", events = events)
        val loop = ClipProbe(label = "loop", events = events)
        val companion = ClipProbe(failOn = "open", label = "companion", events = events)
        val clips = ArrayDeque(listOf(source, oldPad, loop, companion))
        val player = JavaSoundWavPlayer(DesktopClipFactory { clips.removeFirst().clip })
        try {
            player.loadPcm(testAudio(), pitchSemitones = 0f)
            player.playFrom(0)
            player.triggerPad(testPad(globalIndex = 6), forceLoop = false)
            val sourceStopsBefore = source.stopCount
            events.clear()

            val failure = assertFailsWith<DesktopLoopSessionStartupException> {
                player.prepareExclusiveLoopSession(
                    loopPad = testPad(globalIndex = 7),
                    companionPads = listOf(testPad(globalIndex = 8)),
                )
            }

            assertEquals("open failed", failure.message)
            assertEquals(sourceStopsBefore, source.stopCount)
            assertEquals(0, oldPad.stopCount)
            assertEquals(0, oldPad.closeCount)
            assertEquals(
                listOf(
                    "loop:open",
                    "companion:open",
                    "companion:close",
                    "loop:close",
                ),
                events,
            )
        } finally {
            player.close()
        }
    }

    @Test
    fun retirementFailureAfterCandidateStartupPropagatesUnchanged() {
        val source = ClipProbe(failOn = "stop-second", label = "source")
        val oldPad = ClipProbe(label = "old-pad")
        val loop = ClipProbe(label = "loop")
        val clips = ArrayDeque(listOf(source, oldPad, loop))
        val player = JavaSoundWavPlayer(DesktopClipFactory { clips.removeFirst().clip })
        try {
            player.loadPcm(testAudio(), pitchSemitones = 0f)
            player.playFrom(0)
            player.triggerPad(testPad(globalIndex = 6), forceLoop = false)
            val started = player.prepareExclusiveLoopSession(
                loopPad = testPad(globalIndex = 7),
                companionPads = emptyList(),
            ).startCandidates()

            val failure = assertFailsWith<IllegalStateException> {
                started.retirePriorPlayback()
            }

            started.abandonCandidates()

            assertEquals("stop failed", failure.message)
            assertEquals(0, oldPad.stopCount)
            assertEquals(1, loop.loopCount)
            assertEquals(1, loop.stopCount)
            assertEquals(1, loop.closeCount)
        } finally {
            player.close()
        }
    }

    @Test
    fun failedCandidateCleanupRetainsOwnershipUntilStopAllCanRetryClose() {
        val loop = ClipProbe(failOn = "close-once", label = "loop")
        val companion = ClipProbe(failOn = "start", label = "companion")
        val clips = ArrayDeque(listOf(loop, companion))
        val player = JavaSoundWavPlayer(DesktopClipFactory { clips.removeFirst().clip })
        try {
            val failure = runCatching {
                player.prepareExclusiveLoopSession(
                    loopPad = testPad(globalIndex = 7),
                    companionPads = listOf(testPad(globalIndex = 8)),
                ).startCandidates().retirePriorPlayback()
            }.exceptionOrNull()

            assertEquals(IllegalStateException::class, failure?.let { it::class })
            assertEquals("close failed", failure?.message)
            assertEquals(1, loop.closeCount)

            player.stopAll()

            assertEquals(2, loop.closeCount)
        } finally {
            runCatching { player.close() }
        }
    }

    @Test
    fun forceLoopKeepsTheLegacyNonIntegralRenderBoundaryWhenThePadIsOneShot() {
        val audio = PcmAudio(
            name = "short",
            samples = shortArrayOf(8_000, 16_000),
            sampleRate = 48_000,
        )
        val pad = PadModel(
            globalIndex = 0,
            audio = audio,
            startFrame = 0,
            endFrame = 2,
            pitchSemitones = -5f,
            reverse = true,
        )

        val oneShot = renderDesktopPadPcm(pad, PadPlayMode.ONE_SHOT)
        val forcedLoop = renderDesktopPadPcm(pad, PadPlayMode.LOOP)

        assertEquals(2, oneShot.size)
        assertEquals(3, forcedLoop.size)
        assertEquals(0.toShort(), forcedLoop.last())
    }

    private fun testAudio() = PcmAudio(
        name = "clip-start",
        samples = shortArrayOf(4_000, -4_000, 2_000, -2_000),
        sampleRate = 48_000,
    )

    private fun testPad(
        playMode: PadPlayMode = PadPlayMode.ONE_SHOT,
        globalIndex: Int = 7,
    ): PadModel {
        val audio = testAudio()
        return PadModel(
            globalIndex = globalIndex,
            audio = audio,
            startFrame = 0,
            endFrame = audio.frameCount,
            playMode = playMode,
        )
    }

    private class ClipProbe(
        private val failOn: String = "",
        private val label: String = "clip",
        private val events: MutableList<String>? = null,
        private val operationHook: ((String) -> Unit)? = null,
    ) {
        var openCount = 0
        var startCount = 0
        var loopCount = 0
        var stopCount = 0
        var closeCount = 0
        private var framePosition = 0
        private var listener: LineListener? = null

        val clip: Clip = Proxy.newProxyInstance(
            Clip::class.java.classLoader,
            arrayOf(Clip::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "open" -> {
                    events?.add("$label:open")
                    openCount++
                    if (failOn == "open") error("open failed")
                    null
                }
                "start" -> {
                    operationHook?.invoke("start")
                    events?.add("$label:start")
                    startCount++
                    if (failOn == "start") error("start failed")
                    if (failOn == "start-after-stop") {
                        framePosition = 4
                        listener?.update(LineEvent(proxy as Clip, LineEvent.Type.STOP, framePosition.toLong()))
                        error("start failed")
                    }
                    null
                }
                "loop" -> {
                    operationHook?.invoke("loop")
                    events?.add("$label:loop")
                    loopCount++
                    if (failOn == "loop") error("loop failed")
                    null
                }
                "stop" -> {
                    events?.add("$label:stop")
                    stopCount++
                    if (failOn == "stop-second" && stopCount == 2) error("stop failed")
                    null
                }
                "close" -> {
                    events?.add("$label:close")
                    closeCount++
                    if (failOn == "close-once" && closeCount == 1) error("close failed")
                    null
                }
                "addLineListener" -> {
                    if (failOn == "listener") error("listener failed")
                    listener = arguments?.single() as LineListener
                    null
                }
                "getFrameLength" -> 4
                "getFramePosition" -> framePosition
                "getLongFramePosition", "getMicrosecondLength", "getMicrosecondPosition" -> 0L
                "getLevel" -> 0f
                "isActive", "isControlSupported", "isOpen", "isRunning" -> false
                "getControls" -> emptyArray<javax.sound.sampled.Control>()
                "hashCode" -> System.identityHashCode(this)
                "toString" -> "ClipProbe"
                else -> null
            }
        } as Clip
    }
}
