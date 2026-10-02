package com.choplab.jvm.separation

import com.choplab.core.separation.SeparationProblem
import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.*

class FourStemModelTest {
    @Test fun explicitPinnedDownloadFailureAndCancellationPreserveOldBytesAndDeleteOnlyTheOwnedPartial() {
        val directory = Files.createTempDirectory("four-stem-model-")
        var downloads = 0; var released = 0; var cancel = false
        val store = FourStemModelStore(directory) { url ->
            assertEquals(FourStemSpec.MODEL_URL, url); assertTrue(url.contains(FourStemSpec.MODEL_COMMIT))
            downloads++
            FourStemModelStore.ModelDownload(FourStemSpec.MODEL_BYTES, ByteArrayInputStream(ByteArray(4096)), { released++ })
        }
        try {
            assertEquals(SeparationProblem.MODEL_MISSING, assertFailsWith<SeparationException> { store.ensure(false) }.problem)
            assertEquals(0, downloads)
            val before = byteArrayOf(9, 8, 7); Files.write(store.model, before)
            val unrelated = directory.resolve("previous.part"); Files.write(unrelated, byteArrayOf(1))
            assertEquals(SeparationProblem.MODEL_INVALID, assertFailsWith<SeparationException> { store.ensure(false) }.problem)
            assertEquals(0, downloads); assertEquals(0, released)
            assertEquals(SeparationProblem.MODEL_INVALID, assertFailsWith<SeparationException> { store.ensure(true) }.problem)
            assertContentEquals(before, Files.readAllBytes(store.model)); assertContentEquals(byteArrayOf(1), Files.readAllBytes(unrelated))
            assertEquals(2L, Files.list(directory).use { it.count() }); assertEquals(1, downloads); assertEquals(1, released)
            assertEquals(SeparationProblem.CANCELLED, assertFailsWith<SeparationException> {
                store.ensure(true, progress = { _, _ -> cancel = true }, check = { if (cancel) throw SeparationException(SeparationProblem.CANCELLED) })
            }.problem)
            assertTrue(cancel); assertContentEquals(before, Files.readAllBytes(store.model)); assertContentEquals(byteArrayOf(1), Files.readAllBytes(unrelated))
            assertEquals(2L, Files.list(directory).use { it.count() }); assertEquals(2, downloads); assertEquals(2, released)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun lowOrUnknownRamRefusesBeforeOpeningTheModelAndDynamicMetadataIsOnlyADeclaration() {
        val missing = java.nio.file.Path.of("missing-model.onnx")
        assertEquals(SeparationProblem.RAM_UNAVAILABLE, assertFailsWith<SeparationException> {
            OnnxFourStemInference(missing, available = SeparationMemory(0, 0, false))
        }.problem)
        assertEquals(SeparationProblem.LOW_MEMORY, assertFailsWith<SeparationException> {
            OnnxFourStemInference(missing, available = SeparationMemory(8L shl 30, 1L shl 30, false))
        }.problem)
        assertEquals(SeparationProblem.LOW_MEMORY, SeparationMemory(8L shl 30, 4L shl 30, true).refusal())
        assertNull(SeparationMemory(8L shl 30, 4L shl 30, false).refusal())
        val expected = longArrayOf(1, 4, 2, FourStemSpec.FRAMES.toLong())
        assertTrue(OnnxFourStemInference.declaredShape(longArrayOf(-1, -1, -1, -1), expected))
        assertFalse(OnnxFourStemInference.declaredShape(longArrayOf(1, 1, 2, FourStemSpec.FRAMES.toLong()), expected))
        assertFalse(OnnxFourStemInference.declaredShape(longArrayOf(-2, -1, -1, -1), expected))
        assertFalse(OnnxFourStemInference.declaredShape(longArrayOf(1, 4, 2), expected))
    }
}
