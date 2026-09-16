package com.choplab.sampler

import com.choplab.sampler.audio.BuiltInDrumKits
import com.choplab.sampler.model.ProjectOperationEpoch
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.persistence.RecoveredProjectState
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupProjectPreparationTest {
    @Test
    fun restoredProjectDoesNotCreateOrReadAFreshStarter() = runBlocking {
        val recovered = RecoveredProjectState(SamplerUiState(bpm = 123f), 42L)
        val result = prepareStartupProject(
            load = { recovered },
            createStarter = { error("No drum synthesis for an existing project") },
        ).getOrThrow()
        assertSame(recovered, (result as PreparedStartupProject.Restored).project)
    }

    @Test
    fun ioAndSynthesisUseWorkersThenResumeOnCallerThread() = runBlocking {
        val caller = Thread.currentThread()
        val starter = BuiltInDrumKits.prepareStarterKit()
        val ioThread = AtomicReference<Thread>()
        val cpuThread = AtomicReference<Thread>()
        Executors.newSingleThreadExecutor {
            Thread(it, "startup-io-test").also(ioThread::set)
        }.asCoroutineDispatcher().use { io ->
            Executors.newSingleThreadExecutor {
                Thread(it, "startup-cpu-test").also(cpuThread::set)
            }.asCoroutineDispatcher().use { cpu ->
                val result = prepareStartupProject(
                    load = { assertSame(ioThread.get(), Thread.currentThread()); null },
                    createStarter = {
                        assertSame(cpuThread.get(), Thread.currentThread())
                        starter
                    },
                    ioDispatcher = io,
                    synthesisDispatcher = cpu,
                ).getOrThrow()
                assertSame(caller, Thread.currentThread())
                assertSame(starter, (result as PreparedStartupProject.Fresh).kit)
            }
        }
    }

    @Test
    fun workerGeneratedStarterHasExactlyTheSamePcmAndPattern() = runBlocking {
        val blank = SamplerUiState()
        val expected = BuiltInDrumKits.installStarterKit(blank)
        val kit = (prepareStartupProject(load = { null })
            .getOrThrow() as PreparedStartupProject.Fresh).kit
        val actual = BuiltInDrumKits.installStarterKit(blank, kit)
        assertEquals(expected.activeSteps, actual.activeSteps)
        assertEquals(expected.selectedBank, actual.selectedBank)
        assertEquals(expected.selectedPad, actual.selectedPad)
        assertEquals(expected.selectedDrumKitId, actual.selectedDrumKitId)
        expected.pads.zip(actual.pads).forEach { (before, after) ->
            assertEquals(before.globalIndex, after.globalIndex)
            assertEquals(before.audio?.id, after.audio?.id)
            assertArrayEquals(before.audio?.samples, after.audio?.samples)
        }
        assertTrue(BuiltInDrumKits.isPristineStarterProduction(actual))
    }

    @Test
    fun brokenArchiveIsAnErrorNotPermissionToReplaceUserData() = runBlocking {
        val failure = IOException("synthetic archive error")
        val result = prepareStartupProject(
            load = { throw failure },
            createStarter = { error("Corruption must not create a fresh project") },
        )
        assertPropagatedFailure(failure, result.exceptionOrNull())
    }

    @Test
    fun synthesisFailureIsReportedWithoutPublishingAPartialProject() = runBlocking {
        val failure = IllegalStateException("synthetic synthesis error")
        val result = prepareStartupProject(
            load = { null },
            createStarter = { throw failure },
        )
        assertPropagatedFailure(failure, result.exceptionOrNull())
    }

    @Test
    fun ioCancellationIsNotConvertedIntoAFailedStartupResult() = runBlocking {
        val canceled = CancellationException("synthetic cancellation")
        var propagated = false
        try {
            prepareStartupProject(load = { throw canceled }, createStarter = { error("unreachable") })
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    @Test
    fun synthesisCancellationIsNotConvertedIntoAFailedStartupResult() = runBlocking {
        var propagated = false
        try {
            prepareStartupProject(
                load = { null },
                createStarter = { throw CancellationException("synthetic cancellation") },
            )
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    @Test
    fun canceledPreparationCannotPublishAfterCpuWorkReturns() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val published = AtomicBoolean(false)
        val job = launch {
            prepareStartupProject(
                load = { null },
                    createStarter = {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    BuiltInDrumKits.prepareStarterKit()
                },
            ).getOrThrow()
            published.set(true)
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            job.cancel()
        } finally {
            release.countDown()
        }
        job.join()
        assertFalse(published.get())
    }

    @Test
    fun latestPadSelectionSurvivesWorkerPreparation() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var latest = SamplerUiState()
        var published: SamplerUiState? = null
        val job = launch {
            val result = prepareStartupProject(
                load = { null },
                createStarter = {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    BuiltInDrumKits.prepareStarterKit()
                },
            ).getOrThrow() as PreparedStartupProject.Fresh
            published = BuiltInDrumKits.installStarterKit(latest, result.kit)
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            latest = latest.copy(selectedBank = 2, selectedPad = 64)
        } finally {
            release.countDown()
        }
        job.join()
        assertEquals(2, published?.selectedBank)
        assertEquals(64, published?.selectedPad)
    }

    @Test
    fun callerCanSupersedeProjectWhileSynthesisIsBlocked() = runBlocking {
        val epoch = ProjectOperationEpoch()
        val operation = epoch.begin()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var published = false
        val job = launch {
            val prepared = prepareStartupProject(
                load = { null },
                    createStarter = {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    BuiltInDrumKits.prepareStarterKit()
                },
            )
            epoch.completeIfCurrent(operation) { prepared.getOrThrow(); published = true }
        }
        try {
            withContext(Dispatchers.IO) { assertTrue(entered.await(5, TimeUnit.SECONDS)) }
            // This runs on the caller while the CPU worker is held; a new project wins.
            epoch.begin()
        } finally {
            release.countDown()
        }
        job.join()
        assertFalse(published)
    }
    @Test
    fun fatalLoadErrorIsNotDowngradedToRecoverableUiFailure() = runBlocking {
        val fatal = LinkageError("synthetic fatal startup error")
        var caught: Throwable? = null
        try { prepareStartupProject(load = { throw fatal }) } catch (failure: Throwable) { caught = failure }
        assertPropagatedFailure(fatal, caught)
    }

    @Test
    fun fatalSynthesisErrorIsNotDowngradedToRecoverableUiFailure() = runBlocking {
        val fatal = OutOfMemoryError("synthetic exception; no memory exhaustion is performed")
        var caught: Throwable? = null
        try {
            prepareStartupProject(load = { null }, createStarter = { throw fatal })
        } catch (failure: Throwable) { caught = failure }
        assertPropagatedFailure(fatal, caught)
    }

    /** Debug stacktrace recovery may copy an exception, binding the original as its cause. */
    private fun assertPropagatedFailure(expected: Throwable, actual: Throwable?) {
        requireNotNull(actual) { "Failure was swallowed or reported as a successful preparation" }
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
        assertTrue(
            "Only the exact failure or its stacktrace-recovered copy may propagate",
            actual === expected || actual.cause === expected,
        )
    }

}
