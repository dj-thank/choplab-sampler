package com.choplab.sampler.persistence

import com.choplab.sampler.model.SamplerUiState
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class ProjectAutosaveWriterTest {
    @Test fun immediateRecreationReadsAfterOldWriterFinalSave() {
        val directory = Files.createTempDirectory("autosave-recreate").toFile()
        val oldWriter = ProjectAutosaveWriter({ state, revision ->
            AtomicProjectStore(directory).save(state, revision); Unit
        }, { _, _ -> }, delayMillis = 60_000)
        val newWriter = ProjectAutosaveWriter({ _, _ -> }, { _, _ -> })
        try {
            oldWriter.submit(SamplerUiState(bpm=147f), 12)
            oldWriter.close()
            val recovered = newWriter.readAfterPending {
                AtomicProjectStore(directory).loadWithRevision()
            }.get(5, TimeUnit.SECONDS)
            assertEquals(12L, recovered?.revision)
            assertEquals(147f, recovered?.state?.bpm)
        } finally { oldWriter.close(); newWriter.close(); directory.deleteRecursively() }
    }

    @Test fun closingBeforeDebouncePersistsLatestEditAndCanReopen() {
        val directory = Files.createTempDirectory("autosave-close").toFile()
        val saved = CountDownLatch(1)
        val store = AtomicProjectStore(directory)
        val writer = ProjectAutosaveWriter({ state, revision ->
            store.save(state, revision)
            saved.countDown()
        }, { _, _ -> }, delayMillis = 60_000)
        try {
            writer.submit(SamplerUiState(bpm = 90f), 1)
            writer.submit(SamplerUiState(bpm = 135f), 2)
            writer.close()
            assertTrue("close must drain without the debounce wait", saved.await(5, TimeUnit.SECONDS))
            val reopened = requireNotNull(AtomicProjectStore(directory).loadWithRevision())
            assertEquals(2L, reopened.revision)
            assertEquals(135f, reopened.state.bpm)
            assertFalse(directory.resolve("autosave.previous.choplab").exists())
        } finally { writer.close(); directory.deleteRecursively() }
    }

    @Test fun backgroundFlushAndLaterEditsRemainWritable() {
        val first = CountDownLatch(1)
        val second = CountDownLatch(1)
        val writer = ProjectAutosaveWriter({ _, revision ->
            if (revision == 1L) first.countDown() else second.countDown()
        }, { _, _ -> }, delayMillis = 60_000)
        try {
            writer.submit(SamplerUiState(), 1)
            writer.flush()
            assertTrue(first.await(5, TimeUnit.SECONDS))
            writer.submit(SamplerUiState(), 2)
            writer.close()
            assertTrue(second.await(5, TimeUnit.SECONDS))
        } finally { writer.close() }
    }

    @Test fun failedSaveDoesNotDisableNextSave() {
        val failed = CountDownLatch(1)
        val saved = CountDownLatch(1)
        val writer = ProjectAutosaveWriter({ _, revision ->
            if (revision == 1L) error("disk unavailable") else saved.countDown()
        }, { _, _ -> failed.countDown() }, delayMillis = 60_000)
        try {
            writer.submit(SamplerUiState(), 1)
            writer.flush()
            assertTrue(failed.await(5, TimeUnit.SECONDS))
            writer.submit(SamplerUiState(), 2)
            writer.close()
            assertTrue(saved.await(5, TimeUnit.SECONDS))
        } finally { writer.close() }
    }
}

