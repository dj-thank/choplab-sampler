package com.choplab.sampler.audio

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VocalLoopAdmissionCleanupTest {
    @Test
    fun failedLoopAdmissionStopsAndDeletesTheTakeWithoutAReadOrSaveCallback() {
        val directory = Files.createTempDirectory("choplab-vocal-loop-reject").toFile()
        val requested = directory.resolve("vocal_1.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val stopped = directory.resolve("vocal_2.wav").apply { writeBytes(byteArrayOf(4, 5, 6)) }
        val deleted = mutableListOf<String>()
        try {
            val result = discardVocalTakeAfterLoopAdmissionFailure(
                requestedFile = requested,
                stopRecorder = { Result.success(stopped) },
                deleteOwned = { file ->
                    deleted += file.name
                    !file.exists() || file.delete()
                },
            )

            assertTrue(result.isSuccess)
            assertEquals(listOf("vocal_2.wav", "vocal_1.wav"), deleted)
            assertFalse(requested.exists())
            assertFalse(stopped.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun recorderStopFailureStillDeletesTheRequestedOwnedTake() {
        val directory = Files.createTempDirectory("choplab-vocal-loop-stop-failure").toFile()
        val requested = directory.resolve("vocal_3.wav").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        try {
            val result = discardVocalTakeAfterLoopAdmissionFailure(
                requestedFile = requested,
                stopRecorder = { Result.failure(IllegalStateException("stop failed")) },
                deleteOwned = { file -> !file.exists() || file.delete() },
            )

            assertTrue(result.isFailure)
            assertEquals("stop failed", result.exceptionOrNull()?.message)
            assertFalse(requested.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun sameRequestedAndStoppedTakeIsDeletedOnlyOnce() {
        val directory = Files.createTempDirectory("choplab-vocal-loop-same-file").toFile()
        val requested = directory.resolve("vocal_4.wav").apply { writeBytes(byteArrayOf(1)) }
        var deleteCount = 0
        try {
            val result = discardVocalTakeAfterLoopAdmissionFailure(
                requestedFile = requested,
                stopRecorder = { Result.success(requested) },
                deleteOwned = { file ->
                    deleteCount++
                    !file.exists() || file.delete()
                },
            )

            assertTrue(result.isSuccess)
            assertEquals(1, deleteCount)
            assertFalse(requested.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun ownedTakeDeletionFailureIsReported() {
        val requested = Files.createTempFile("choplab-vocal-loop-delete-failure", ".wav").toFile()
        try {
            val result = discardVocalTakeAfterLoopAdmissionFailure(
                requestedFile = requested,
                stopRecorder = { Result.success(requested) },
                deleteOwned = { false },
            )

            assertTrue(result.isFailure)
            assertEquals("録音テイクを破棄できませんでした", result.exceptionOrNull()?.message)
            assertTrue(requested.exists())
        } finally {
            requested.delete()
        }
    }
}
