package com.choplab.desktop.persistence

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtomicSiblingFileTest {
    @Test
    fun failedFirstWriteLeavesNoDestinationOrTemporaryDebris() {
        val directory = Files.createTempDirectory("choplab-atomic-sibling-new-failure").toFile()
        val target = directory.resolve("beat.wav")

        try {
            assertFailsWith<IllegalStateException> {
                replaceWithAtomicSibling(target) { temporary ->
                    temporary.writeText("partial", Charsets.UTF_8)
                    error("test first export failure")
                }
            }

            assertFalse(target.exists())
            assertEquals(emptyList(), directory.list()?.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun writerThatRemovesItsTemporaryCannotPublishOrChangeTheExistingTarget() {
        val directory = Files.createTempDirectory("choplab-atomic-sibling-missing").toFile()
        val target = directory.resolve("beat.wav").apply { writeText("old", Charsets.UTF_8) }

        try {
            assertFailsWith<IllegalArgumentException> {
                replaceWithAtomicSibling(target) { temporary ->
                    assertTrue(temporary.delete())
                    Unit
                }
            }

            assertEquals("old", target.readText(Charsets.UTF_8))
            assertEquals(listOf("beat.wav"), directory.list()?.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun successfulWritePublishesTheCompletedBytesAndReturnsTheWriterResult() {
        val directory = Files.createTempDirectory("choplab-atomic-sibling-success").toFile()
        val target = directory.resolve("beat.wav").apply { writeText("old", Charsets.UTF_8) }
        val replacement = "complete replacement".encodeToByteArray()

        try {
            val result = replaceWithAtomicSibling(target) { temporary ->
                temporary.writeBytes(replacement)
                "render summary"
            }

            assertEquals("render summary", result)
            assertContentEquals(replacement, target.readBytes())
            assertEquals(listOf("beat.wav"), directory.list()?.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun failedWritePreservesTheExistingTargetAndRemovesTheTemporarySibling() {
        val directory = Files.createTempDirectory("choplab-atomic-sibling").toFile()
        val target = directory.resolve("beat.wav")
        val previous = "previous-complete-wav".encodeToByteArray()
        target.writeBytes(previous)

        try {
            val failure = assertFailsWith<IllegalStateException> {
                replaceWithAtomicSibling(target) { temporary ->
                    temporary.writeText("partial replacement", Charsets.UTF_8)
                    error("test render failure")
                }
            }

            assertEquals("test render failure", failure.message)
            assertContentEquals(previous, target.readBytes())
            assertEquals(listOf("beat.wav"), directory.list()?.sorted())
        } finally {
            directory.deleteRecursively()
        }
    }
}
