package com.choplab.desktop.audio

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
}
