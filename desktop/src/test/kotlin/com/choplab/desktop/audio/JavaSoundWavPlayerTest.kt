package com.choplab.desktop.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import java.lang.reflect.Proxy
import java.util.ArrayDeque
import javax.sound.sampled.Clip
import javax.sound.sampled.LineEvent
import javax.sound.sampled.LineListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JavaSoundWavPlayerTest {
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

            player.startExclusiveLoopSession(
                loopPad = testPad(globalIndex = 7),
                companionPads = listOf(testPad(globalIndex = 8)),
            )

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
                player.startExclusiveLoopSession(
                    loopPad = testPad(globalIndex = 7),
                    companionPads = listOf(testPad(globalIndex = 8)),
                )
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
                    events?.add("$label:loop")
                    loopCount++
                    if (failOn == "loop") error("loop failed")
                    null
                }
                "stop" -> {
                    events?.add("$label:stop")
                    stopCount++
                    null
                }
                "close" -> {
                    events?.add("$label:close")
                    closeCount++
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
