package com.choplab.sampler.next

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The bridge between suspend editor ports and the Activity's system document screens. */
class DocumentPickersTest {
    private val launched = mutableListOf<Pair<PickerKind, String?>>()
    private val launch: (PickerKind, String?) -> Unit = { kind, name -> launched += kind to name }

    @Test
    fun pickedDocumentReachesTheWaitingPort() = runBlocking {
        val pickers = DocumentPickers<String>(Dispatchers.Unconfined).apply { attach(launch) }
        val answer = async(Dispatchers.Unconfined) { pickers.pick(PickerKind.SAVE_PROJECT, "song.choplab") }
        assertEquals(listOf(PickerKind.SAVE_PROJECT to "song.choplab"), launched)
        pickers.complete(PickerKind.AUDIO, "content://wrong-screen")
        pickers.complete(PickerKind.SAVE_PROJECT, "content://song")
        assertEquals("content://song", answer.await())
    }

    @Test
    fun resultAfterRotationStillCompletesThePendingRequest() = runBlocking {
        val pickers = DocumentPickers<String>(Dispatchers.Unconfined).apply { attach(launch) }
        val answer = async(Dispatchers.Unconfined) { pickers.pick(PickerKind.AUDIO) }
        // The old Activity goes away and a new one registers its own launchers.
        pickers.detach(launch)
        val recreated: (PickerKind, String?) -> Unit = { _, _ -> error("No second screen for the same request") }
        pickers.attach(recreated)
        pickers.complete(PickerKind.AUDIO, "content://loop")
        assertEquals("content://loop", answer.await())
    }

    @Test
    fun withoutAnActivityOrAfterCloseNothingIsPicked() = runBlocking {
        val pickers = DocumentPickers<String>(Dispatchers.Unconfined)
        assertNull(pickers.pick(PickerKind.PROJECT))
        pickers.attach(launch)
        val waiting = async(Dispatchers.Unconfined) { pickers.pick(PickerKind.PROJECT) }
        pickers.close()
        assertNull(waiting.await())
        pickers.attach(launch)
        assertNull(pickers.pick(PickerKind.PROJECT))
        assertEquals(1, launched.size)
    }

    @Test
    fun aNewerRequestReplacesTheOlderOne() = runBlocking {
        val pickers = DocumentPickers<String>(Dispatchers.Unconfined).apply { attach(launch) }
        val first = async(Dispatchers.Unconfined) { pickers.pick(PickerKind.AUDIO) }
        val second = async(Dispatchers.Unconfined) { pickers.pick(PickerKind.EXPORT_WAV, "beat.wav") }
        yield()
        assertNull(first.await())
        pickers.complete(PickerKind.EXPORT_WAV, "content://beat")
        assertEquals("content://beat", second.await())
    }

    @Test
    fun deviceWithoutADocumentsScreenAnswersNothing() = runBlocking {
        val pickers = DocumentPickers<String>(Dispatchers.Unconfined)
        pickers.attach { _, _ -> throw IllegalStateException("No documents provider") }
        assertNull(pickers.pick(PickerKind.AUDIO))
        // The failed launch left nothing pending that a later result could complete.
        pickers.attach(launch)
        val next = async(Dispatchers.Unconfined) { pickers.pick(PickerKind.AUDIO) }
        pickers.complete(PickerKind.AUDIO, "content://after")
        assertEquals("content://after", next.await())
    }
}
