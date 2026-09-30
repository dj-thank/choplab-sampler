package com.choplab.jvm.separation

import com.choplab.core.model.*
import com.choplab.core.separation.SeparationProblem
import com.choplab.jvm.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.*
import kotlin.test.*

class FourStemTransactionTest {
    private fun stage(directory: Path): List<StagedStem> = (0 until 4).map { index ->
        val bytes = ByteArrayOutputStream().also { WavCodec.writeFloat(it, FloatArray(16) { (index + 1) * .01f }, 44_100) }.toByteArray()
        val asset = Asset(sha256(bytes), "wav", bytes.size.toLong(), 44_100, 2, 8, "Stem", AssetRole.RENDERED, derivedFrom = "a".repeat(64))
        StagedStem(asset, Files.write(directory.resolve("$index.wav"), bytes))
    }

    @Test fun cancellationAfterOneNewPublicationRollsBackOnlyNewHashesAndKeepsPreexistingBytes() {
        val directory = Files.createTempDirectory("four-stem-atomic-")
        val assets = FileAssetStore(directory.resolve("assets"))
        try {
            val staged = stage(directory)
            val before = Files.readAllBytes(staged.first().file)
            assets.publish(staged.first().asset, ByteArrayInputStream(before))
            assertEquals(SeparationProblem.CANCELLED, assertFailsWith<SeparationException> {
                assets.adoptFourStems(staged) { if (assets.storedBytes() > before.size) throw SeparationException(SeparationProblem.CANCELLED) }
            }.problem)
            assertEquals(before.size.toLong(), assets.storedBytes()); assertTrue(assets.verified(staged.first().asset))
            assertContentEquals(before, Files.readAllBytes(assets.directory.resolve("${staged.first().asset.hash}.wav")))
            assertTrue(staged.all { !Files.exists(it.file) })
            val retry = stage(directory)
            assets.adoptFourStems(retry) {}
            assertTrue(retry.all { assets.verified(it.asset) })
            assertEquals(4L, Files.list(assets.directory).use { it.count() })
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun malformedFourthHeadOrQuotaRefusalNeverPublishesTheEarlierHeads() {
        val directory = Files.createTempDirectory("four-stem-validation-")
        try {
            val assets = FileAssetStore(directory.resolve("assets"))
            val staged = stage(directory)
            Files.write(staged.last().file, byteArrayOf(0))
            assertEquals(SeparationProblem.INVALID_OUTPUT, assertFailsWith<SeparationException> { assets.adoptFourStems(staged) {} }.problem)
            assertEquals(0L, assets.storedBytes()); assertTrue(staged.none { Files.exists(it.file) })
            val limited = FileAssetStore(directory.resolve("limited"), 200)
            val next = stage(directory)
            assertEquals(SeparationProblem.NO_SPACE, assertFailsWith<SeparationException> { limited.adoptFourStems(next) {} }.problem)
            assertEquals(0L, limited.storedBytes()); assertTrue(next.none { Files.exists(it.file) })
        } finally { directory.toFile().deleteRecursively() }
    }
}
