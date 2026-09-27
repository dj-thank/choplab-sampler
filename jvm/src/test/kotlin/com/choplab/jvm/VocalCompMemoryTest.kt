package com.choplab.jvm

import com.choplab.core.model.*
import com.choplab.core.vocal.*
import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.*

class VocalCompMemoryTest {
    @Test fun sharedAdmissionRejectsBeforeRenderingThenSuccessfulCompReturnsAllSourceLeases() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("comp-ledger-")
        val store = FileAssetStore(directory.resolve("assets"))
        val bytes = ByteArrayOutputStream().also { WavCodec.writeFloat(it, FloatArray(9600) { if (it % 2 == 0) .25f else -.5f }) }.toByteArray()
        val asset = Asset(sha256(bytes), "wav", bytes.size.toLong(), 48000, 2, 4800, "Candidate")
        store.publish(asset, ByteArrayInputStream(bytes))
        val track = Track("voice", "VOICE", TrackKind.VOCAL)
        val project = Project(assets = frozenListOf(asset), tracks = frozenListOf(track),
            takes = frozenListOf(Take("take", track.id, asset.hash, FrameRange(0,4800), 0)))
        val draft = VocalCompEdits.fullTake(project, "take", "comp")
        val memory = PcmMemoryBudget(1_048_576)
        try {
            WavPcmPort(store, memory = memory).use { pcm ->
                val renderer = VocalCompRenderer(store, pcm, directory.resolve("scratch"))
                memory.reserve(1_048_576 - 188_416 + 8).use {
                    val prior = store.storedBytes()
                    assertFailsWith<PcmMemoryLimit> { renderer.render(project, draft, "Refused") }
                    assertEquals(prior, store.storedBytes()); assertEquals(0, memory.statistics().leasedAssets)
                    assertEquals(0, Files.list(directory.resolve("scratch")).use { it.count() })
                }
                val rendered = renderer.render(project, draft, "Comp")
                assertEquals(4800, rendered.frames)
                assertEquals(0, memory.statistics().leasedAssets, "Comp has relinquished every provider lease")
                // A following worker can evict the inactive candidate; it never waits for leaked active leases.
                memory.reserve(1_048_568).use { assertTrue(memory.statistics().usedBytes <= 1_048_576) }
                assertEquals(0, memory.statistics().usedBytes)
                assertTrue(store.verified(asset) && store.verified(rendered))
            }
            assertEquals(0, memory.statistics().usedBytes)
        } finally { directory.toFile().deleteRecursively() }
    }
}
