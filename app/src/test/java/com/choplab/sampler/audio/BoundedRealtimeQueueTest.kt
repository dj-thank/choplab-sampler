package com.choplab.sampler.audio

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedRealtimeQueueTest {
    @Test
    fun clearDiscardsAnOfferThatWasReservedBeforeTheClearBoundary() {
        val queue = BoundedRealtimeQueue<String>(capacity = 1)
        val factoryEntered = CountDownLatch(1)
        val releaseFactory = CountDownLatch(1)
        val accepted = AtomicBoolean(true)
        val producer = Thread {
            accepted.set(
                queue.offerPrepared {
                    factoryEntered.countDown()
                    releaseFactory.await()
                    "stale"
                },
            )
        }.apply { start() }

        try {
            assertTrue(factoryEntered.await(1, TimeUnit.SECONDS))
            queue.clear()
            releaseFactory.countDown()
            producer.join(1_000L)

            assertFalse(accepted.get())
            assertEquals(0, queue.size)
            assertNull(queue.poll())
            assertTrue(queue.offer("fresh"))
            assertEquals("fresh", queue.poll())
        } finally {
            releaseFactory.countDown()
            producer.join(1_000L)
        }
    }

    @Test
    fun queueRejectsOverflowAndPreservesAcceptedCommandOrder() {
        val queue = BoundedRealtimeQueue<String>(capacity = 2)

        assertTrue(queue.offer("first"))
        assertTrue(queue.offer("second"))
        assertFalse(queue.offer("overflow"))
        assertEquals(2, queue.size)

        assertEquals("first", queue.poll())
        assertEquals("second", queue.poll())
        assertNull(queue.poll())
        assertEquals(0, queue.size)
    }

    @Test
    fun clearReleasesEveryReservedSlot() {
        val queue = BoundedRealtimeQueue<Int>(capacity = 2)
        assertTrue(queue.offer(1))
        assertTrue(queue.offer(2))

        queue.clear()

        assertEquals(0, queue.size)
        assertTrue(queue.offer(3))
        assertEquals(3, queue.poll())
    }

    @Test
    fun preparedMailboxCommandDoesNotRunItsSideEffectWhenCapacityIsFull() {
        val mailbox = RealtimeCommandMailbox<String, Int>(capacity = 1)
        val sourceState = SourcePlaybackState()
        val playingGeneration = sourceState.issuePlay()
        assertTrue(mailbox.offer("occupied"))

        val accepted = mailbox.offerPrepared {
            sourceState.issueStop()
            "loop"
        }

        assertFalse(accepted)
        assertTrue(sourceState.isCurrent(playingGeneration))
    }

    @Test
    fun preparedMailboxCommandRunsOnceAfterCapacityIsReserved() {
        val mailbox = RealtimeCommandMailbox<String, Int>(capacity = 1)
        val sourceState = SourcePlaybackState()
        val playingGeneration = sourceState.issuePlay()
        var preparationCount = 0

        val accepted = mailbox.offerPrepared {
            preparationCount++
            sourceState.issueStop()
            "loop"
        }

        assertTrue(accepted)
        assertEquals(1, preparationCount)
        assertFalse(sourceState.isCurrent(playingGeneration))
        assertEquals("loop", mailbox.pollEntry()?.value)
    }

    @Test
    fun urgentStopInvalidatesOlderCommandsButKeepsCommandsIssuedAfterIt() {
        val mailbox = RealtimeCommandMailbox<String, Int>(capacity = 4)
        assertTrue(mailbox.offer("old"))
        mailbox.requestStop(7)
        assertTrue(mailbox.offer("new"))

        val stop = requireNotNull(mailbox.takeLatestStop())
        assertEquals(7, stop.payload)

        val old = requireNotNull(mailbox.pollEntry())
        val new = requireNotNull(mailbox.pollEntry())
        assertFalse(mailbox.shouldProcess(old))
        assertTrue(mailbox.shouldProcess(new))
    }

    @Test
    fun newestUrgentStopWinsWhenSeveralStopsArePending() {
        val mailbox = RealtimeCommandMailbox<String, String>(capacity = 2)

        mailbox.requestStop("first")
        mailbox.requestStop("latest")

        assertEquals("latest", mailbox.takeLatestStop()?.payload)
        assertNull(mailbox.takeLatestStop())
    }
}
