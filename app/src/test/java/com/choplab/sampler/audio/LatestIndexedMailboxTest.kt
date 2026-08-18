package com.choplab.sampler.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestIndexedMailboxTest {
    @Test
    fun newestValueWinsWithoutAQueueSlotPerEdit() {
        val mailbox = LatestIndexedMailbox<String>(128)

        mailbox.publish(3, "old A01")
        mailbox.publish(3, "new source")

        assertTrue(mailbox.takeDirtyWord(0) and (1L shl 3) != 0L)
        assertEquals("new source", mailbox.take(3)?.value)
        assertNull(mailbox.take(3))
    }

    @Test
    fun explicitNullIsAConsumableClearAndIndexesStayIndependent() {
        val mailbox = LatestIndexedMailbox<String>(128)

        mailbox.publish(1, null)
        mailbox.publish(65, "bank C")

        assertTrue(mailbox.takeDirtyWord(0) and (1L shl 1) != 0L)
        assertNull(mailbox.take(1)?.value)
        assertTrue(mailbox.takeDirtyWord(1) and (1L shl 1) != 0L)
        assertEquals("bank C", mailbox.take(65)?.value)
    }
}
