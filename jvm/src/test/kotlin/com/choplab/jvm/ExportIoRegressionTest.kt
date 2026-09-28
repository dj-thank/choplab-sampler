package com.choplab.jvm

import com.choplab.engine.*
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import kotlin.math.sin
import kotlin.test.*

class ExportIoRegressionTest {
    @Test fun residentExportDoesNotDispatchAnEmptyPcmReadForEveryBlock() {
        val source = PcmAsset.fromInterleaved(FloatArray(9_600) { i ->
            (sin(i / 2 * .05) * if (i % 2 == 0) .3 else -.15).toFloat()
        })
        val program = EngineProgram(emptyList(), arrangement = Arrangement(listOf(
            ArrangementClip("first", source, 4_800, 0, source.frameCount, .7f, -.25f, 0),
            ArrangementClip("second", source, 24_000, 0, source.frameCount, .5f, .25f, 0),
        ), 48_000))
        val frames = 48_000
        val expected = ByteArrayOutputStream().also { output ->
            val samples = OfflineRender.render(program, listOf(EngineCommand.StartSequence(0, 1),
                EngineCommand.Stop(frames.toLong(), 2)), frames, blockFrames = 17)
            WavCodec.writePcm(output, samples, bits = 24, seed = 47)
        }.toByteArray()
        var preparations = 0
        val output = ByteArrayOutputStream()
        StreamingWavRenderer.render(program, output, frames, seed = 47, prepared = { _, render ->
            preparations++
            render()
        })
        assertContentEquals(expected, output.toByteArray(), "The arrangement, stereo, dither and latency trim are unchanged")
        assertEquals(0, preparations, "Resident audio and silent gaps have no page I/O to dispatch")
    }

    @Test fun smallWavWritesAreBatchedWithoutChangingBytesAndTheBufferIsCharged() = runBlocking<Unit> {
        val frames = 96_037
        val expected = ByteArrayOutputStream().also { StreamingWavRenderer.render(EngineProgram.EMPTY, it, frames) }.toByteArray()
        val bytes = ByteArrayOutputStream()
        var writes = 0
        var largest = 0
        val destination = object : OutputStream() {
            override fun write(value: Int) { writes++; bytes.write(value) }
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                writes++; largest = maxOf(largest, length); bytes.write(buffer, offset, length)
            }
        }
        val memory = PcmMemoryBudget.shared
        val before = memory.statistics().usedBytes
        writeBuffered(destination) { buffered ->
            assertEquals(before + 65_536, runBlocking { memory.statistics().usedBytes }, "Reserve before allocating the file buffer")
            StreamingWavRenderer.render(EngineProgram.EMPTY, buffered, frames)
        }
        assertEquals(before, memory.statistics().usedBytes)
        assertContentEquals(expected, bytes.toByteArray())
        assertTrue(writes <= expected.size / 16_384 + 2, "Small WAV blocks must not each become a filesystem write: $writes")
        assertTrue(largest <= 65_536)
        println("WAV output bytes=${expected.size} hostWrites=$writes largestWrite=$largest fixedBuffer=65536")

        assertFailsWith<IOException> {
            writeBuffered(object : OutputStream() {
                override fun write(value: Int): Unit = throw IOException("Destination failed while flushing")
                override fun write(buffer: ByteArray, offset: Int, length: Int): Unit = throw IOException("Destination failed while flushing")
            }) { it.write(byteArrayOf(1, 2, 3)) }
        }
        assertEquals(before, memory.statistics().usedBytes, "A failed final flush releases the buffer reservation")
    }

    @Test fun fullPcmBudgetRefusesTheBufferBeforeWritingAndRetainsTheOldDestination() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("export-io-full-")
        try {
            val target = directory.resolve("mix.wav")
            Files.writeString(target, "previous successful export")
            val memory = PcmMemoryBudget.shared
            memory.reserve(memory.limitBytes).use {
                var entered = false
                assertFailsWith<PcmMemoryLimit> { atomicOutput(target) { entered = true; it.write(1) } }
                assertFalse(entered)
                assertEquals(memory.limitBytes, memory.statistics().usedBytes, "The existing active reservation remains owned")
                assertEquals("previous successful export", Files.readString(target))
                assertEquals(1, Files.list(directory).use { it.count() }.toInt(), "No pending output survives failed admission")
            }
            assertEquals(0L, memory.statistics().usedBytes)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun archiveCancellationAndCompressedSizeLimitKeepSourceAndOldDestination() {
        val directory = Files.createTempDirectory("archive-io-cancel-")
        try {
            var state = 47
            val original = Fixtures.wav(samples = ShortArray(131_072) {
                state = state * 1_664_525 + 1_013_904_223
                (state ushr 16).toShort()
            })
            val asset = Fixtures.asset(original)
            val store = FileAssetStore(directory.resolve("assets"))
            store.publish(asset, ByteArrayInputStream(original))
            val target = directory.resolve("song.choplab")
            Files.writeString(target, "previous successful archive")
            var written = 0L
            var observedCancellation = false
            val cancelled = { (written > 8192).also { if (it) observedCancellation = true } }
            assertFailsWith<IllegalArgumentException> {
                atomicOutput(target, cancelled) { file ->
                    val counted = object : OutputStream() {
                        override fun write(value: Int) { written++; file.write(value) }
                        override fun write(buffer: ByteArray, offset: Int, length: Int) {
                            written += length; file.write(buffer, offset, length)
                        }
                    }
                    ArchiveCodec().write(Fixtures.project(asset), store, counted, cancelled)
                }
            }
            assertTrue(observedCancellation, "Cancel is observed between bounded compression chunks")
            assertTrue(written < original.size, "Cancel stops before compressing the complete source")
            assertEquals("previous successful archive", Files.readString(target))
            assertEquals(2L, Files.list(directory).use { it.count() }, "Only the store and old destination remain")
            assertFailsWith<IllegalArgumentException> {
                atomicOutput(target) { output ->
                    ArchiveCodec(ArchiveLimits(maxArchiveBytes = 1024)).write(Fixtures.project(asset), store, output)
                }
            }
            assertEquals("previous successful archive", Files.readString(target))
            assertEquals(2L, Files.list(directory).use { it.count() }, "Buffered output cannot bypass the compressed size limit")
            assertContentEquals(original, store.openVerified(asset).use { it.readBytes() })
        } finally { directory.toFile().deleteRecursively() }
    }
}
