package com.choplab.sampler.next

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bridge between the editor's microphone port and the Activity's permission screen. */
class MicrophonePermissionTest {
    private var asked = 0
    private val launch: () -> Unit = { asked++ }

    @Test
    fun alreadyAllowedAsksNothing() = runBlocking {
        val permission = MicrophonePermission({ true }, Dispatchers.Unconfined).apply { attach(launch) }
        assertTrue(permission.request())
        assertEquals(0, asked)
    }

    @Test
    fun theAnswerReachesTheWaitingPortAlsoAfterRotation() = runBlocking {
        val permission = MicrophonePermission({ false }, Dispatchers.Unconfined).apply { attach(launch) }
        val answer = async(Dispatchers.Unconfined) { permission.request() }
        assertEquals(1, asked)
        // The old Activity goes away and a new one registers its own launcher; the answer still arrives.
        permission.detach(launch)
        permission.attach { error("No second screen for the same request") }
        permission.complete(true)
        assertTrue(answer.await())

        val refused = async(Dispatchers.Unconfined) { permission.request() }
        permission.complete(false)
        assertFalse(refused.await())
    }

    @Test
    fun withoutAnActivityAfterCloseOrWhenReplacedNothingIsAllowed() = runBlocking {
        val permission = MicrophonePermission({ false }, Dispatchers.Unconfined)
        assertFalse(permission.request())
        permission.attach(launch)
        val first = async(Dispatchers.Unconfined) { permission.request() }
        val second = async(Dispatchers.Unconfined) { permission.request() }
        yield()
        assertFalse("A newer request replaces the older one", first.await())
        permission.close()
        assertFalse(second.await())
        permission.attach(launch)
        assertFalse(permission.request())
        assertEquals(2, asked)
    }
}
