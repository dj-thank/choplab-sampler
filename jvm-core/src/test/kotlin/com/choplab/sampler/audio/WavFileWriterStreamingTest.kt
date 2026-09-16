package com.choplab.sampler.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class WavFileWriterStreamingTest {
    private inline fun inDirectory(block: (File) -> Unit) {
        val directory = Files.createTempDirectory("choplab-streaming-writer").toFile()
        try { block(directory) } finally { directory.deleteRecursively() }
    }

    @Test fun invalidConstructionNeverCreatesAFile() = inDirectory { directory ->
        val invalid = listOf(0 to 1, -1 to 1, 48_000 to 0, 48_000 to 3, Int.MAX_VALUE to 2)
        invalid.forEachIndexed { index, (rate, channels) ->
            val target = File(directory, "invalid-$index.wav")
            assertThrows(IllegalArgumentException::class.java) { WavFileWriter(target, rate, channels) }
            assertFalse(target.exists())
        }
    }

    @Test fun invalidConstructionPreservesExistingBytes() = inDirectory { directory ->
        val target = File(directory, "keep.wav")
        val sentinel = byteArrayOf(7, 6, 5, 4, 3, 2, 1)
        target.writeBytes(sentinel)
        assertThrows(IllegalArgumentException::class.java) { WavFileWriter(target, Int.MAX_VALUE, 2) }
        assertArrayEquals(sentinel, target.readBytes())
    }

    @Test fun invalidBudgetsAreRejectedBeforeOpening() = inDirectory { directory ->
        for (limit in listOf(-1L, 0L, 1L, WavFileWriter.MAX_RIFF_PCM_BYTES + 1L)) {
            val target = File(directory, "budget-$limit.wav")
            assertThrows(IllegalArgumentException::class.java) { WavFileWriter(target, 48_000, 1, limit) }
            assertFalse(target.exists())
        }
    }

    @Test fun bulkAndMixedWritesPreserveEveryInterleavedSample() = inDirectory { directory ->
        val target = File(directory, "mixed.wav")
        val samples = ShortArray(25_004) { index -> (index * 7919).toShort() }
        val extra = byteArrayOf(0, -128, -1, 127)
        WavFileWriter(target, 48_000, 2).use { writer ->
            writer.writePcm16(samples, 8_194)
            writer.writePcm16(shortArrayOf())
            writer.writePcm16Bytes(extra)
            writer.writePcm16(samples)
        }
        val expected = ByteBuffer.allocate((8_194 + samples.size) * 2 + extra.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (index in 0 until 8_194) expected.putShort(samples[index])
        expected.put(extra)
        samples.forEach(expected::putShort)
        val result = target.readBytes()
        assertArrayEquals(expected.array(), result.copyOfRange(44, result.size))
        assertEquals(result.size - 44, ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
    }

    @Test fun rejectedOversizedBatchDoesNotPartiallyWrite() = inDirectory { directory ->
        val target = File(directory, "bounded.wav")
        WavFileWriter(target, 48_000, 2, 16_400L).use { writer ->
            writer.writePcm16(shortArrayOf(1, -2))
            assertThrows(IllegalStateException::class.java) { writer.writePcm16(ShortArray(8_200)) }
            writer.writePcm16(shortArrayOf(3, -4))
        }
        assertArrayEquals(byteArrayOf(1, 0, -2, -1, 3, 0, -4, -1), target.readBytes().drop(44).toByteArray())
    }

    @Test fun callerBufferIsCopiedBeforeItCanBeReused() = inDirectory { directory ->
        val target = File(directory, "reuse.wav")
        val input = shortArrayOf(10, -20)
        WavFileWriter(target, 8_000, 1).use { writer ->
            writer.writePcm16(input)
            input.fill(99)
            writer.writePcm16(input, 1)
        }
        assertArrayEquals(byteArrayOf(10, 0, -20, -1, 99, 0), target.readBytes().drop(44).toByteArray())
    }

    @Test fun closeIsIdempotentAndAllFurtherWritesFail() = inDirectory { directory ->
        val target = File(directory, "closed.wav")
        val writer = WavFileWriter(target, 8_000, 1)
        writer.writePcm16(shortArrayOf(1))
        writer.close()
        val original = target.readBytes()
        writer.close()
        assertThrows(IllegalStateException::class.java) { writer.writePcm16(shortArrayOf()) }
        assertThrows(IllegalStateException::class.java) { writer.writePcm16Bytes(byteArrayOf()) }
        assertArrayEquals(original, target.readBytes())
    }
}
