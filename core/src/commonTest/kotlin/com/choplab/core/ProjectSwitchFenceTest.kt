package com.choplab.core

import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import com.choplab.engine.EngineCommand
import com.choplab.engine.EngineProgram
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectSwitchFenceTest {
    @Test fun actorRejectsConfirmedOpenAndNewAfterAnEarlierQueuedEditWithoutStartingFileWork() = runTest {
        var opens = 0
        val studio = Studio(this, services { opens++; Project(id = "opened") }, preparationDispatcher = StandardTestDispatcher(testScheduler))
        val revision = studio.document.value.revision
        val edit = async(start = CoroutineStart.UNDISPATCHED) { studio.dispatch(Action.Edit(Intent.SetLyrics(frozenListOf(LyricLine("line", "New edit", 0, 960))))) }
        val open = async(start = CoroutineStart.UNDISPATCHED) { studio.dispatch(Action.Open(Location("old-confirmation"), revision)) }
        val new = async(start = CoroutineStart.UNDISPATCHED) { studio.dispatch(Action.New(Project(id = "new"), revision)) }
        assertTrue(edit.await().accepted)
        assertIs<Notice.StaleCompletion>(open.await().notice)
        assertIs<Notice.StaleCompletion>(new.await().notice)
        assertEquals("New edit", studio.document.value.project.lyrics.single().text); assertEquals(0, opens)
        assertTrue(studio.document.value.canUndo)
        assertTrue(studio.dispatch(Action.Undo).accepted)
        assertEquals(Project(), studio.document.value.project)
        assertFailsWith<IllegalArgumentException> { Action.Open(Location("invalid"), -1) }
        assertFailsWith<IllegalArgumentException> { Action.New(expectedRevision = -1) }
        studio.dispatch(Action.Close)
    }

    @Test fun readCompletionFromAnAcceptedOpenCannotReplaceAnEditMadeDuringReading() = runTest {
        val release = CompletableDeferred<Unit>()
        val studio = Studio(this, services { release.await(); Project(id = "opened") }, preparationDispatcher = StandardTestDispatcher(testScheduler))
        assertTrue(studio.dispatch(Action.Open(Location("chosen"), 0)).accepted)
        assertTrue(studio.dispatch(Action.Edit(Intent.Rename("Edited while reading"))).accepted)
        val before = studio.document.value
        release.complete(Unit); advanceUntilIdle()
        assertEquals(before, studio.document.value)
        assertNull(studio.work.value.jobId)
        // Legacy hosts without an explicit confirmation retain their compatible open API.
        assertTrue(studio.dispatch(Action.Open(Location("legacy"))).accepted); advanceUntilIdle()
        assertEquals("opened", studio.document.value.project.id)
        studio.dispatch(Action.Close)
    }

    private fun services(open: suspend () -> Project) = Services(object : AssetStore {
        override suspend fun containsVerified(asset: Asset) = true
        override suspend fun write(asset: Asset, bytes: ByteArray) = Unit
        override suspend fun read(asset: Asset) = byteArrayOf()
    }, object : ImportPort { override suspend fun import(location: Location): Asset = error("Unused") }, object : ProjectPort {
        override suspend fun open(location: Location) = open()
        override suspend fun save(project: Project, revision: Long, location: Location) = Unit
    }, object : ExportPort {
        override suspend fun export(project: Project, patternId: String, request: ExportRequest): ExportReceipt = error("Unused")
    }, object : EnginePort {
        override suspend fun prepare(project: Project, patternId: String, revision: Long) = EngineProgram(revision = revision)
        override suspend fun apply(command: EngineCommand) = true
        override fun snapshot() = TransportState(outputAttached = true)
    })
}
