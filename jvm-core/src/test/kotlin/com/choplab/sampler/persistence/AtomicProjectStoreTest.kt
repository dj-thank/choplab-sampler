package com.choplab.sampler.persistence

import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.ProductionSession
import com.choplab.sampler.model.SamplerUiState
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class AtomicProjectStoreTest {
    @Test fun changedShortLibrarySourceCanBeSavedAndReopened() {
        val directory = Files.createTempDirectory("library-save-reopen").toFile()
        try {
            val old = PcmAudio(name="old", samples=ShortArray(256), sampleRate=8000)
            val next = PcmAudio(name="next", samples=ShortArray(100), sampleRate=8000)
            val before = SamplerUiState(currentAudio=old, rangeEndFrame=256,
                sliceMarkers=listOf(0, 200), activeSliceIndex=1)
            val changed = com.choplab.sampler.model.attachLibrarySource(before, next)
            AtomicProjectStore(directory).save(changed, 1)
            val restored = requireNotNull(AtomicProjectStore(directory).load())
            assertEquals(100, restored.currentAudio?.frameCount)
            assertTrue(restored.sliceMarkers.isEmpty())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun recoveryDecodesOnlyNewestHealthyGeneration() {
        val directory = Files.createTempDirectory("choplab-lazy-recovery").toFile()
        try {
            val store = AtomicProjectStore(directory)
            for (revision in 1L..3L) store.save(SamplerUiState(bpm = 100f + revision), revision)
            val decoded = mutableListOf<String>()
            val reader = AtomicProjectStore(directory, readArchive = { file, limit ->
                decoded += file.name
                file.inputStream().use { ProjectArchiveCodec.read(it, limit) }
            })
            assertEquals(3L, reader.loadWithRevision()?.revision)
            assertEquals(listOf("autosave.choplab"), decoded)
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun decodeFailureFallsBackWithoutDecodingOlderHealthyGenerations() {
        val directory = Files.createTempDirectory("choplab-lazy-fallback").toFile()
        try {
            val store = AtomicProjectStore(directory)
            for (revision in 1L..3L) store.save(SamplerUiState(bpm = 100f + revision), revision)
            val decoded = mutableListOf<String>()
            val reader = AtomicProjectStore(directory, readArchive = { file, limit ->
                decoded += file.name
                if (file.name == "autosave.choplab") error("unreadable PCM")
                file.inputStream().use { ProjectArchiveCodec.read(it, limit) }
            })
            assertEquals(2L, reader.loadWithRevision()?.revision)
            assertEquals(listOf("autosave.choplab", "autosave.previous.choplab"), decoded)
        } finally { directory.deleteRecursively() }
    }

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
    fun recoveredRevisionSeedsTheNextProductionSessionSaveAboveDisk() {
        val directory = Files.createTempDirectory("choplab-autosave-session-revision-test").toFile()
        try {
            val store = AtomicProjectStore(directory)
            assertTrue(store.save(SamplerUiState(bpm = 120f), revision = 7L))
            val recovered = requireNotNull(AtomicProjectStore(directory).loadWithRevision())
            assertEquals(7L, recovered.revision)

            val session = ProductionSession()
            val restored = session.replaceProject(
                state = recovered.state,
                persistenceRequired = false,
                recoveredRevision = recovered.revision,
            )
            assertEquals(8L, restored.revision)
            val edited = session.applyEdit(restored.state, restored.state.copy(bpm = 130f))

            assertEquals(9L, edited.revision)
            assertTrue(AtomicProjectStore(directory).save(edited.state, edited.revision))
            assertEquals(130f, AtomicProjectStore(directory).load()?.bpm)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun olderRevisionCannotReplaceNewerAutosaveAfterStoreRecreation() {
        val directory = Files.createTempDirectory("choplab-autosave-recreated-revision-test").toFile()
        try {
            assertTrue(AtomicProjectStore(directory).save(SamplerUiState(bpm = 130f), revision = 2L))

            assertFalse(AtomicProjectStore(directory).save(SamplerUiState(bpm = 90f), revision = 1L))

            assertEquals(130f, AtomicProjectStore(directory).load()?.bpm)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun equalRevisionWithDifferentStateIsRejectedAfterStoreRecreation() {
        val directory = Files.createTempDirectory("choplab-autosave-equal-revision-test").toFile()
        try {
            assertTrue(AtomicProjectStore(directory).save(SamplerUiState(bpm = 130f), revision = 2L))

            assertFalse(AtomicProjectStore(directory).save(SamplerUiState(bpm = 90f), revision = 2L))

            assertEquals(130f, AtomicProjectStore(directory).load()?.bpm)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun stalePendingGenerationCannotBeatNewerBackupWhenPrimaryIsCorrupt() {
        val directory = Files.createTempDirectory("choplab-autosave-pending-order-test").toFile()
        try {
            val store = AtomicProjectStore(directory)
            store.save(SamplerUiState(bpm = 90f), revision = 1L)
            store.save(SamplerUiState(bpm = 100f), revision = 2L)
            store.save(SamplerUiState(bpm = 110f), revision = 3L)
            store.writePendingForTest(SamplerUiState(bpm = 80f), revision = 0L)
            store.primaryFile.writeBytes(byteArrayOf(1, 2, 3))

            assertEquals(100f, AtomicProjectStore(directory).load()?.bpm)
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
    fun configuredResidentBudgetRejectsAutosaveBeforeCommit() {
        val directory = Files.createTempDirectory("choplab-autosave-budget-test").toFile()
        try {
            val audio = PcmAudio(
                id = 1L,
                name = "budget.wav",
                samples = shortArrayOf(1, 2),
                sampleRate = 48_000,
            )
            val store = AtomicProjectStore(directory, maxResidentPcmBytes = 2L)

            val failure = assertThrows(IllegalArgumentException::class.java) {
                store.save(
                    SamplerUiState(currentAudio = audio, rangeEndFrame = audio.frameCount),
                    revision = 1L,
                )
            }

            assertEquals("この端末で安全に開けるプロジェクト音声容量を超えています", failure.message)
            assertFalse(store.primaryFile.exists())
            assertFalse(File(directory, "autosave.pending.choplab").exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun configuredResidentBudgetRejectsExistingGenerationOnLoad() {
        val directory = Files.createTempDirectory("choplab-autosave-load-budget-test").toFile()
        try {
            val audio = PcmAudio(
                id = 2L,
                name = "budget.wav",
                samples = shortArrayOf(1, 2),
                sampleRate = 48_000,
            )
            AtomicProjectStore(directory, maxResidentPcmBytes = 4L).save(
                SamplerUiState(currentAudio = audio, rangeEndFrame = audio.frameCount),
                revision = 1L,
            )

            val failure = assertThrows(IllegalStateException::class.java) {
                AtomicProjectStore(directory, maxResidentPcmBytes = 2L).load()
            }

            assertEquals("自動保存プロジェクトを復元できません", failure.message)
            assertEquals(
                "この端末で安全に開けるプロジェクト音声容量を超えています",
                failure.cause?.message,
            )
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
