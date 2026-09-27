package com.choplab.desktop.next

import com.choplab.jvm.WavAudio
import com.choplab.jvm.WavCodec
import com.choplab.jvm.WavInfo
import com.choplab.sampler.separation.ChunkInference
import com.choplab.sampler.separation.SeparatorSpec
import java.nio.file.Files
import java.util.concurrent.CancellationException
import kotlin.test.*

class NextDrumSeparationTest {
    private val passthrough = ChunkInference { chunk -> FloatArray(4 * chunk.size).also {
        chunk.copyInto(it, SeparatorSpec.DRUM_STEM_INDEX * chunk.size)
    } }
    private fun audio() = WavAudio(WavInfo(44_100, 2, 4, 32, true),
        floatArrayOf(0f, 0f, 0.000001f, -0.000002f, 1.25f, -1.5f, .125f, -.75f))

    @Test fun writesFloatWithOriginalPrecisionAndStereoHeadroom() {
        val root = Files.createTempDirectory("next-separation-")
        try {
            val target = root.resolve("drums.wav")
            val source = audio()
            NextDrumSeparation.render(source, target, passthrough)
            val result = Files.newInputStream(target).use { WavCodec.read(it) }
            assertEquals(source.info, result.info)
            source.samples.indices.forEach { assertEquals(source.samples[it], result.samples[it], 0.0000002f) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cancelledOrInvalidInferenceLeavesNoOutputAndExistingFilesArePreserved() {
        val root = Files.createTempDirectory("next-separation-")
        try {
            val target = root.resolve("drums.wav")
            assertFailsWith<CancellationException> {
                var cancelled = false
                NextDrumSeparation.render(audio(), target, passthrough, progress = { cancelled = true }, cancelled = { cancelled })
            }
            assertFalse(Files.exists(target))
            assertFailsWith<IllegalArgumentException> {
                NextDrumSeparation.render(audio(), target, ChunkInference { FloatArray(4 * it.size) { Float.NaN } })
            }
            assertFalse(Files.exists(target))
            val original = byteArrayOf(7, 8, 9)
            Files.write(target, original)
            assertFailsWith<java.nio.file.FileAlreadyExistsException> { NextDrumSeparation.render(audio(), target, passthrough) }
            assertContentEquals(original, Files.readAllBytes(target))
        } finally { root.toFile().deleteRecursively() }
    }
}
