package com.choplab.sampler.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScratchAccessibilityPolicyTest {
    @Test
    fun exposesStartStopAndDirectionalActionsInStableOrder() {
        val calls = mutableListOf<String>()
        val actions = scratchAccessibilityActions(
            available = true,
            active = false,
            onStart = { calls += "start" },
            onStop = { calls += "stop" },
            onPrevious = { calls += "previous" },
            onNext = { calls += "next" },
        )

        assertEquals(
            listOf("スクラッチ開始", "スクラッチ停止", "左へ擦る", "右へ擦る"),
            actions.map { it.label },
        )
        assertTrue(actions[0].action())
        assertFalse(actions[1].action())
        assertFalse(actions[2].action())
        assertFalse(actions[3].action())
        assertEquals(listOf("start"), calls)
    }

    @Test
    fun activeScratchCanStopOrMoveButCannotStartAgain() {
        val calls = mutableListOf<String>()
        val actions = scratchAccessibilityActions(
            available = true,
            active = true,
            onStart = { calls += "start" },
            onStop = { calls += "stop" },
            onPrevious = { calls += "previous" },
            onNext = { calls += "next" },
        )

        assertFalse(actions[0].action())
        assertTrue(actions[1].action())
        assertTrue(actions[2].action())
        assertTrue(actions[3].action())
        assertEquals(listOf("stop", "previous", "next"), calls)
    }

    @Test
    fun unavailableScratchRejectsEveryAction() {
        val actions = scratchAccessibilityActions(
            available = false,
            active = false,
            onStart = { error("must not start") },
            onStop = { error("must not stop") },
            onPrevious = { error("must not move") },
            onNext = { error("must not move") },
        )

        assertTrue(actions.all { !it.action() })
    }
}
