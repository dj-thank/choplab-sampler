package com.choplab.sampler.audio

import java.util.concurrent.atomic.AtomicInteger

/** Audio-thread-owned transport truth with an atomically published current step. */
internal class TransportRuntimeState {
    private val currentStepValue = AtomicInteger(-1)

    var running: Boolean = false
        private set

    val currentStep: Int
        get() = currentStepValue.get()

    fun start() {
        running = true
        currentStepValue.set(-1)
    }

    fun publishStep(step: Int) {
        currentStepValue.set(step)
    }

    fun stop() {
        running = false
        currentStepValue.set(-1)
    }
}
