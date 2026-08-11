package com.choplab.sampler.persistence

import com.choplab.sampler.model.SamplerUiState
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AtomicProjectStoreTest {
    @Test
    fun olderRevisionCannotReplaceNewerAutosave() {
        val directory = Files.createTempDirectory("choplab-autosave-revision-test").toFile()
        try {
            val store = AtomicProjectStore(directory)

            assertTrue(store.save(SamplerUiState(bpm = 130f), revision = 2L))
            assertFalse(store.save(SamplerUiState(bpm = 90f), revision = 1L))

            assertEquals(130f, store.load()?.bpm)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun newerRevisionWinsRegardlessOfArrivalOrder() {
        val directory = Files.createTempDirectory("choplab-autosave-newest-test").toFile()
        try {
            val store = AtomicProjectStore(directory)

            assertTrue(store.save(SamplerUiState(bpm = 90f), revision = 1L))
            assertTrue(store.save(SamplerUiState(bpm = 130f), revision = 2L))

            assertEquals(130f, store.load()?.bpm)
        } finally {
            directory.deleteRecursively()
        }
    }


    @Test
    fun twoCorruptNewestGenerationsStillRecoverTheLastGoodProject() {
        val directory = Files.createTempDirectory("choplab-autosave-three-generation-test").toFile()
        try {
            val store = AtomicProjectStore(directory)
            store.save(SamplerUiState(bpm = 90f))
            store.save(SamplerUiState(bpm = 100f))
            store.save(SamplerUiState(bpm = 110f))
            File(directory, "autosave.choplab").writeBytes(byteArrayOf(1, 2, 3))
            File(directory, "autosave.previous.choplab").writeBytes(byteArrayOf(4, 5, 6))

            val recovered = store.load()

            assertEquals(90f, recovered?.bpm)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun corruptLatestAutosaveFallsBackToPreviousGeneration() {
        val directory = Files.createTempDirectory("choplab-autosave-test").toFile()
        try {
            val store = AtomicProjectStore(directory)
            store.save(SamplerUiState(bpm = 100f))
            store.save(SamplerUiState(bpm = 130f))
            store.primaryFile.writeBytes(byteArrayOf(1, 2, 3, 4))

            val recovered = store.load()

            assertEquals(100f, recovered?.bpm)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun validPendingGenerationCanRecoverAfterInterruptedReplacement() {
        val directory = Files.createTempDirectory("choplab-autosave-pending-test").toFile()
        try {
            FileOutputStream(File(directory, "autosave.pending.choplab")).use { output ->
                ProjectArchiveCodec.write(SamplerUiState(bpm = 117f), output)
            }

            val recovered = AtomicProjectStore(directory).load()

            assertEquals(117f, recovered?.bpm)
        } finally {
            directory.deleteRecursively()
        }
    }
}
