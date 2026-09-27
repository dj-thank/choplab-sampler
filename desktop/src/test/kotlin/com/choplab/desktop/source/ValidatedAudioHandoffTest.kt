package com.choplab.desktop.source

import com.choplab.sampler.model.PcmAudio
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValidatedAudioHandoffTest {
    private fun audio(file: File) = PcmAudio(name = file.name, samples = shortArrayOf(120, -300, 400, -500), sampleRate = 48_000, channelCount = 2)

    @Test fun publishedRenameConsumesValidationOnceAndPreservesStereo() {
        val root = kotlin.io.path.createTempDirectory("validated-audio").toFile()
        try {
            val staged = File(root, "temporary.flac").apply { writeText("audio bytes") }
            var decodes = 0
            val handoff = ValidatedAudioHandoff({ decodes++; audio(it) })
            handoff.validate(staged)
            val published = File(root, "published.flac")
            check(staged.renameTo(published))
            val result = handoff.decode(published)
            assertEquals(1, decodes)
            assertEquals("published.flac", result.name)
            assertEquals(2, result.channelCount)
            assertEquals(48_000, result.sampleRate)
            assertContentEquals(shortArrayOf(120, -300, 400, -500), result.samples)
            result.samples[0] = 0
            assertEquals(120, handoff.decode(published).samples[0].toInt())
            assertEquals(2, decodes)
        } finally { root.deleteRecursively() }
    }

    @Test fun changedBytesWithIdenticalLengthAndTimestampCannotReuseValidation() {
        val root = kotlin.io.path.createTempDirectory("changed-audio").toFile()
        try {
            val file = File(root, "audio.flac").apply { writeText("first") }
            var decodes = 0
            val handoff = ValidatedAudioHandoff({ decodes++; audio(it) })
            handoff.validate(file)
            val timestamp = file.lastModified()
            file.writeText("other")
            file.setLastModified(timestamp)
            handoff.decode(file)
            assertEquals(2, decodes)
        } finally { root.deleteRecursively() }
    }

    @Test fun wavUsesTheDirectDecoderWithoutRetainingOrHashingPcm() {
        val file = File.createTempFile("direct-pcm", ".wav").apply { writeText("bytes") }
        try {
            var decodes = 0
            val handoff = ValidatedAudioHandoff({ decodes++; audio(it) })
            handoff.validate(file)
            handoff.decode(file)
            assertEquals(2, decodes)
        } finally { file.delete() }
    }

    @Test fun oversizedPcmIsValidatedButNotRetained() {
        val file = File.createTempFile("large-pcm", ".flac").apply { writeText("bytes") }
        try {
            var decodes = 0
            val handoff = ValidatedAudioHandoff({ decodes++; audio(it) }, maximumBytes = 7)
            handoff.validate(file)
            handoff.decode(file)
            assertEquals(2, decodes)
        } finally { file.delete() }
    }

    @Test fun failedValidationDoesNotKeepThePreviousAudio() {
        val file = File.createTempFile("failed-pcm", ".flac").apply { writeText("bytes") }
        try {
            var decodes = 0
            var fail = false
            val handoff = ValidatedAudioHandoff({ decodes++; check(!fail); audio(it) })
            handoff.validate(file)
            fail = true
            assertFailsWith<IllegalStateException> { handoff.validate(file) }
            fail = false
            handoff.decode(file)
            assertEquals(3, decodes)
        } finally { file.delete() }
    }

    @Test fun mutationDuringValidationIsRejectedAndCancellationDoesNotConsumeAudio() {
        val file = File.createTempFile("mutation-pcm", ".flac").apply { writeText("first") }
        try {
            val handoff = ValidatedAudioHandoff({ it.writeText("other"); audio(it) })
            assertFailsWith<IllegalArgumentException> { handoff.validate(file) }
            Thread.currentThread().interrupt()
            assertFailsWith<InterruptedException> { handoff.validate(file) }
        } finally { Thread.interrupted(); file.delete() }
    }
}
