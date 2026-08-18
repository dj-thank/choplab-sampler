package com.choplab.sampler.audio

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCommandAdmissionTest {
    @Test
    fun commandSubmittedAfterShutdownCannotSurviveIntoNextStart() {
        val lifecycleLock = Any()
        val running = AtomicBoolean(true)
        val accepted = mutableListOf<String>()
        val admission = RuntimeCommandAdmission(lifecycleLock, running::get)

        synchronized(lifecycleLock) {
            running.set(false)
            accepted.clear()
        }

        assertFalse(admission.offer {
            accepted += "stale trigger"
            true
        })

        synchronized(lifecycleLock) { running.set(true) }
        assertTrue(accepted.isEmpty())
        assertTrue(admission.offer {
            accepted += "fresh trigger"
            true
        })
        assertTrue(accepted == listOf("fresh trigger"))
    }

    @Test
    fun shutdownAndAdmissionAreSerializedByTheSameLifecycleBoundary() {
        val lifecycleLock = Any()
        val running = AtomicBoolean(true)
        val accepted = mutableListOf<String>()
        val admission = RuntimeCommandAdmission(lifecycleLock, running::get)

        val producer = thread(start = false) {
            admission.offer {
                accepted += "trigger"
                true
            }
        }
        synchronized(lifecycleLock) {
            producer.start()
            running.set(false)
            accepted.clear()
        }
        producer.join()

        assertTrue(accepted.isEmpty())
    }
}
