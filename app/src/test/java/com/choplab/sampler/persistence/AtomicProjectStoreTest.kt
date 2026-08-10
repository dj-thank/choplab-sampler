package com.choplab.sampler.persistence

import com.choplab.sampler.model.SamplerUiState
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class AtomicProjectStoreTest {
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
}
