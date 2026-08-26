package com.choplab.sampler.audio

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import org.junit.Assert.assertEquals
import org.junit.Test

class PadLoopSessionCommandTest {
    private val audio = PcmAudio(
        name = "session.wav",
        samples = ShortArray(400),
        sampleRate = 48_000,
    )

    @Test
    fun snapshotsForceOneLoopOwnerAndKeepAssignedCompanionOrder() {
        val session = preparePadLoopSessionSnapshots(
            loopPad = pad(4),
            companionPads = listOf(
                pad(96),
                PadModel(97),
                pad(98),
                pad(4),
            ),
        )

        assertEquals(4, session.loopPad.padIndex)
        assertEquals(PadPlayMode.LOOP, session.loopPad.playMode)
        assertEquals(listOf(96, 98), session.companionPads.map { it.padIndex })
    }

    @Test
    fun realtimeApplicationStopsPriorOwnershipBeforePublishingAndStartingTheSession() {
        val session = preparePadLoopSessionSnapshots(
            loopPad = pad(4),
            companionPads = listOf(pad(96), pad(98)),
        )
        val events = mutableListOf<String>()

        applyExclusivePadLoopSession(
            session = session,
            stopPriorPlayback = { events += "stop" },
            publishLoop = { padIndex, frame -> events += "publish:$padIndex:$frame" },
            startVoice = { snapshot -> events += "start:${snapshot.padIndex}" },
        )

        assertEquals(
            listOf("stop", "publish:4:0", "start:4", "start:96", "start:98"),
            events,
        )
    }

    private fun pad(index: Int): PadModel = PadModel(
        globalIndex = index,
        audio = audio,
        startFrame = 0,
        endFrame = audio.frameCount,
    )
}
