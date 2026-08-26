package com.choplab.desktop.audio

import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PcmAudio
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JavaSoundVoiceReplacementTest {
    @Test
    fun failedCandidatePreparationClosesTheCandidateWithoutMaskingTheFailure() {
        val events = mutableListOf<String>()

        val failure = assertFailsWith<IllegalStateException> {
            prepareCandidateOrAbandon(
                candidate = "new",
                prepareCandidate = { voice ->
                    events += "prepare:$voice"
                    error("test clip open failure")
                },
                abandonCandidate = { voice -> events += "abandon:$voice" },
            )
        }

        assertEquals("test clip open failure", failure.message)
        assertEquals(listOf("prepare:new", "abandon:new"), events)
    }

    @Test
    fun failedCandidateStartupAbandonsOnlyTheCandidate() {
        val events = mutableListOf<String>()

        val failure = assertFailsWith<IllegalStateException> {
            startReplacementBeforeRetiringConflicts(
                candidate = "new",
                conflicts = listOf("old-same-pad", "old-choke-peer"),
                startCandidate = { voice ->
                    events += "start:$voice"
                    error("test output unavailable")
                },
                abandonCandidate = { voice -> events += "abandon:$voice" },
                retireConflict = { voice -> events += "retire:$voice" },
            )
        }

        assertEquals("test output unavailable", failure.message)
        assertEquals(listOf("start:new", "abandon:new"), events)
    }

    @Test
    fun successfulCandidateStartupPrecedesConflictRetirement() {
        val events = mutableListOf<String>()

        startReplacementBeforeRetiringConflicts(
            candidate = "new",
            conflicts = listOf("old-same-pad", "old-choke-peer"),
            startCandidate = { voice -> events += "start:$voice" },
            abandonCandidate = { voice -> events += "abandon:$voice" },
            retireConflict = { voice -> events += "retire:$voice" },
        )

        assertEquals(
            listOf("start:new", "retire:old-same-pad", "retire:old-choke-peer"),
            events,
        )
    }

    @Test
    fun failedCandidateSetStartupAbandonsEveryCandidateAndRetiresNoPlayback() {
        val events = mutableListOf<String>()

        val failure = assertFailsWith<DesktopLoopSessionStartupException> {
            prepareAndStartCandidatesBeforeRetiringPlayback(
                inputs = listOf("loop", "voice"),
                prepareCandidate = { input ->
                    events += "prepare:$input"
                    "candidate-$input"
                },
                startCandidate = { candidate ->
                    events += "start:$candidate"
                    if (candidate == "candidate-voice") error("test companion start failure")
                },
                abandonCandidate = { candidate -> events += "abandon:$candidate" },
                retirePlayback = { events += "retire:old-playback" },
            )
        }

        assertEquals("test companion start failure", failure.message)
        assertEquals("test companion start failure", failure.cause?.message)
        assertEquals(
            listOf(
                "prepare:loop",
                "prepare:voice",
                "start:candidate-loop",
                "start:candidate-voice",
                "abandon:candidate-loop",
                "abandon:candidate-voice",
            ),
            events,
        )
    }

    @Test
    fun failedCandidateSetPreparationAbandonsEarlierCandidatesAndRetiresNoPlayback() {
        val events = mutableListOf<String>()

        val failure = assertFailsWith<DesktopLoopSessionStartupException> {
            prepareAndStartCandidatesBeforeRetiringPlayback(
                inputs = listOf("loop", "voice"),
                prepareCandidate = { input ->
                    events += "prepare:$input"
                    if (input == "voice") error("test companion prepare failure")
                    "candidate-$input"
                },
                startCandidate = { candidate -> events += "start:$candidate" },
                abandonCandidate = { candidate -> events += "abandon:$candidate" },
                retirePlayback = { events += "retire:old-playback" },
            )
        }

        assertEquals("test companion prepare failure", failure.message)
        assertEquals(
            listOf("prepare:loop", "prepare:voice", "abandon:candidate-loop"),
            events,
        )
    }

    @Test
    fun completeCandidateSetStartsBeforePriorPlaybackRetires() {
        val events = mutableListOf<String>()

        val candidates = prepareAndStartCandidatesBeforeRetiringPlayback(
            inputs = listOf("loop", "voice"),
            prepareCandidate = { input ->
                events += "prepare:$input"
                "candidate-$input"
            },
            startCandidate = { candidate -> events += "start:$candidate" },
            abandonCandidate = { candidate -> events += "abandon:$candidate" },
            retirePlayback = { events += "retire:old-playback" },
        )

        assertEquals(listOf("candidate-loop", "candidate-voice"), candidates)
        assertEquals(
            listOf(
                "prepare:loop",
                "prepare:voice",
                "start:candidate-loop",
                "start:candidate-voice",
                "retire:old-playback",
            ),
            events,
        )
    }

    @Test
    fun retirementFailureAfterCandidateStartupPropagatesUnchanged() {
        val events = mutableListOf<String>()

        val failure = assertFailsWith<IllegalStateException> {
            prepareAndStartCandidatesBeforeRetiringPlayback(
                inputs = listOf("loop"),
                prepareCandidate = { input ->
                    events += "prepare:$input"
                    "candidate-$input"
                },
                startCandidate = { candidate -> events += "start:$candidate" },
                abandonCandidate = { candidate -> events += "abandon:$candidate" },
                retirePlayback = {
                    events += "retire:old-playback"
                    error("test retirement failure")
                },
            )
        }

        assertEquals("test retirement failure", failure.message)
        assertEquals(
            listOf("prepare:loop", "start:candidate-loop", "retire:old-playback"),
            events,
        )
    }

    @Test
    fun exclusiveLoopCompanionsPreserveSequentialChokeOwnership() {
        val audio = PcmAudio(
            name = "voice.wav",
            samples = ShortArray(400),
            sampleRate = 1_000,
        )
        fun vocal(index: Int, chokeGroup: Int) = PadModel(
            globalIndex = index,
            audio = audio,
            startFrame = 0,
            endFrame = audio.frameCount,
            chokeGroup = chokeGroup,
            contentKind = PadContentKind.VOCAL,
        )

        val survivors = exclusiveLoopCompanionPads(
            loopPad = vocal(64, 2),
            companionPads = listOf(
                vocal(96, 0),
                vocal(97, 1),
                vocal(98, 0),
                vocal(99, 1),
                vocal(100, 2),
            ),
        )

        assertEquals(listOf(96, 98, 99), survivors.map(PadModel::globalIndex))
    }
}
