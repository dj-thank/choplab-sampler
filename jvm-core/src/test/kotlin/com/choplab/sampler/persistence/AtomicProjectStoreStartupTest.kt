package com.choplab.sampler.persistence

import com.choplab.sampler.audio.AudioResourceLimits
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerUiState
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Counts real bounded codec calls instead of asserting machine-dependent timings. */
class AtomicProjectStoreStartupTest {
    @Test
    fun fourHealthyGenerationsDecodeOnlyTheNewest() = inDirectory { directory ->
        generation(directory, "autosave.previous2", 1L, 81f)
        generation(directory, "autosave.previous", 2L, 82f)
        generation(directory, "autosave.pending", 3L, 83f)
        generation(directory, "autosave", 4L, 84f)
        val calls = mutableListOf<String>()

        val recovered = read(directory, calls)

        assertEquals(4L, recovered?.revision)
        assertEquals(84f, recovered?.state?.bpm)
        assertEquals(listOf("autosave.choplab"), calls)
    }

    @Test
    fun newerPendingGenerationWinsWithoutDecodingPrimary() = inDirectory { directory ->
        generation(directory, "autosave", 8L, 108f)
        generation(directory, "autosave.pending", 9L, 109f)
        val calls = mutableListOf<String>()

        assertEquals(9L, read(directory, calls)?.revision)
        assertEquals(listOf("autosave.pending.choplab"), calls)
    }

    @Test
    fun corruptDigestIsRejectedBeforeCodecAndBackupStillRecovers() = inDirectory { directory ->
        generation(directory, "autosave.previous", 2L, 102f)
        generation(directory, "autosave", 3L, 103f)
        File(directory, "autosave.choplab").appendBytes(byteArrayOf(1))
        val calls = mutableListOf<String>()

        assertEquals(2L, read(directory, calls)?.revision)
        assertEquals(listOf("autosave.previous.choplab"), calls)
    }

    @Test
    fun validDigestDoesNotMakeAnInvalidArchiveTrusted() = inDirectory { directory ->
        generation(directory, "autosave.previous", 2L, 102f)
        val broken = File(directory, "autosave.choplab")
        broken.writeBytes(byteArrayOf(1, 2, 3, 4))
        metadata(directory, "autosave", 3L)
        val calls = mutableListOf<String>()

        assertEquals(2L, read(directory, calls)?.revision)
        assertEquals(listOf("autosave.choplab", "autosave.previous.choplab"), calls)
    }

    @Test
    fun invalidMetadataCannotHideAValidOlderProject() = inDirectory { directory ->
        generation(directory, "autosave", 10L, 110f)
        generation(directory, "autosave.previous", 9L, 109f)
        File(directory, "autosave.revision").writeText("invalid\tmetadata\textra")
        val calls = mutableListOf<String>()

        assertEquals(9L, read(directory, calls)?.revision)
        assertEquals(listOf("autosave.previous.choplab"), calls)
    }

    @Test
    fun oversizedLatestProjectFallsBackUsingTheSameResidentBudget() = inDirectory { directory ->
        generation(directory, "autosave.previous", 1L, 91f)
        val audio = PcmAudio(id = 1L, name = "synthetic", samples = shortArrayOf(1, 2), sampleRate = 48_000)
        File(directory, "autosave.choplab").outputStream().use {
            ProjectArchiveCodec.write(SamplerUiState(currentAudio = audio, rangeEndFrame = audio.frameCount), it)
        }
        metadata(directory, "autosave", 2L)
        val calls = mutableListOf<String>()

        assertEquals(1L, read(directory, calls, budget = 2L)?.revision)
        assertEquals(listOf("autosave.choplab", "autosave.previous.choplab"), calls)
    }

    @Test
    fun equalRevisionsKeepTheOriginalGenerationPriority() = inDirectory { directory ->
        generation(directory, "autosave.previous2", 7L, 81f)
        generation(directory, "autosave.previous", 7L, 82f)
        generation(directory, "autosave.pending", 7L, 83f)
        generation(directory, "autosave", 7L, 84f)
        for ((name, bpm) in listOf("autosave" to 84f, "autosave.pending" to 83f,
            "autosave.previous" to 82f, "autosave.previous2" to 81f)) {
            val calls = mutableListOf<String>()
            assertEquals(bpm, read(directory, calls)?.state?.bpm)
            assertEquals(listOf("$name.choplab"), calls)
            assertTrue(File(directory, "$name.choplab").delete())
            assertTrue(File(directory, "$name.revision").delete())
        }
    }

    @Test
    fun legacyGenerationsWithoutSidecarsRetainPriorityAndNullRevision() = inDirectory { directory ->
        generation(directory, "autosave.pending", null, 99f)
        generation(directory, "autosave", null, 100f)
        val calls = mutableListOf<String>()

        val recovered = read(directory, calls)

        assertEquals(null, recovered?.revision)
        assertEquals(100f, recovered?.state?.bpm)
        assertEquals(listOf("autosave.choplab"), calls)
    }

    @Test
    fun validRevisionRanksAheadOfLegacyEvenIfItIsNegative() = inDirectory { directory ->
        generation(directory, "autosave", null, 100f)
        generation(directory, "autosave.previous", Long.MIN_VALUE + 1L, 99f)
        val calls = mutableListOf<String>()

        assertEquals(Long.MIN_VALUE + 1L, read(directory, calls)?.revision)
        assertEquals(listOf("autosave.previous.choplab"), calls)
    }

    @Test
    fun minimumRevisionAndLegacyTieKeepPrimaryPriority() = inDirectory { directory ->
        generation(directory, "autosave", null, 100f)
        generation(directory, "autosave.previous", Long.MIN_VALUE, 99f)
        val calls = mutableListOf<String>()

        assertEquals(100f, read(directory, calls)?.state?.bpm)
        assertEquals(listOf("autosave.choplab"), calls)
    }

    @Test
    fun maximumRevisionSortsWithoutOverflowAndStillRequiresVerification() = inDirectory { directory ->
        generation(directory, "autosave", Long.MIN_VALUE + 1L, 90f)
        generation(directory, "autosave.previous", Long.MAX_VALUE, 110f)
        val calls = mutableListOf<String>()

        assertEquals(Long.MAX_VALUE, read(directory, calls)?.revision)
        assertEquals(listOf("autosave.previous.choplab"), calls)
    }

    @Test
    fun missingArchivesDoNotBecomeCandidatesFromOrphanSidecars() = inDirectory { directory ->
        File(directory, "autosave.revision").writeText("100\tunused")
        val calls = mutableListOf<String>()

        assertEquals(null, read(directory, calls))
        assertTrue(calls.isEmpty())
        assertFalse(File(directory, "autosave.choplab").exists())
    }

    @Test
    fun allInvalidArchivesFailRatherThanReturningAnEmptyProject() = inDirectory { directory ->
        File(directory, "autosave.choplab").writeBytes(byteArrayOf(1, 2))
        val calls = mutableListOf<String>()

        val failure = assertThrows(IllegalStateException::class.java) { read(directory, calls) }

        assertEquals("自動保存プロジェクトを復元できません", failure.message)
        assertEquals(listOf("autosave.choplab"), calls)
    }

    @Test
    fun recoveryNeverDeletesOrRewritesAnyGeneration() = inDirectory { directory ->
        generation(directory, "autosave.previous", 1L, 90f)
        generation(directory, "autosave", 2L, 100f)
        generation(directory, "autosave.pending", 3L, 110f)
        File(directory, "autosave.pending.choplab").appendBytes(byteArrayOf(7))
        val before = directory.listFiles()!!.associate { it.name to it.readBytes().toList() }

        assertEquals(2L, read(directory, mutableListOf())?.revision)

        val after = directory.listFiles()!!.associate { it.name to it.readBytes().toList() }
        assertEquals(before, after)
    }

    private fun read(
        directory: File,
        calls: MutableList<String>,
        budget: Long = AudioResourceLimits.MAX_MOBILE_PROJECT_PCM_BYTES,
    ): RecoveredProjectState? = AtomicProjectStore(directory, budget).loadWithRevision { archive ->
        calls += archive.name
        archive.inputStream().buffered().use { ProjectArchiveCodec.read(it, budget) }
    }

    private fun generation(directory: File, name: String, revision: Long?, bpm: Float) {
        File(directory, "$name.choplab").outputStream().use {
            ProjectArchiveCodec.write(SamplerUiState(bpm = bpm), it)
        }
        if (revision != null) metadata(directory, name, revision)
    }

    private fun metadata(directory: File, name: String, revision: Long) {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(File(directory, "$name.choplab").readBytes())
            .joinToString("") { "%02x".format(it) }
        File(directory, "$name.revision").writeText("$revision\t$digest\n")
    }

    private fun inDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("choplab-startup-test").toFile()
        try {
            block(directory)
        } finally {
            directory.deleteRecursively()
        }
    }
}
