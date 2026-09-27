package com.choplab.jvm

import com.choplab.core.edit.EditSession
import com.choplab.core.edit.Intent
import com.choplab.core.model.*
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.*

class BankPadArchiveTest {
    @Test fun confirmedBankMetadataAndPadPanEnvelopeSurviveArchiveIntoAFreshStoreWithoutChangingOtherMusic() = runBlocking {
        val directory = Files.createTempDirectory("bank-pad-archive-test-")
        try {
            val store = FileAssetStore(directory.resolve("original"))
            val bytes = Fixtures.wav()
            val asset = Fixtures.asset(bytes)
            store.write(asset, bytes)
            val before = Fixtures.project(asset).copy(
                source = Source(asset.hash, FrameRange(0, asset.frames)),
                tracks = frozenListOf(Track("track", "Arrangement", TrackKind.BANK, gain = .8f, pan = .3f)),
                clips = frozenListOf(Clip("clip", "track", asset.hash, FrameRange(0, asset.frames), gain = .7f, pan = -.2f)),
            )
            val session = EditSession(before)
            val bank = before.banks[0].copy(name = "低音と声", color = 0xffaa13, role = "自由な役割")
            val pad = before.pads[0].copy(pan = -.375f, attackFrames = 600, decayFrames = 48_000,
                sustainLevel = .25f, releaseFrames = 4000)
            for (intent in listOf(Intent.SetBank(bank), Intent.SetPad(pad))) {
                val plan = session.plan(intent)
                plan.effects.indices.forEach { session.acknowledge(plan, it) }
                session.commit(plan)
            }
            assertEquals(2, session.undoCount, "One confirmed form is one Undo")
            val expected = before.copy(banks = before.banks.map { if (it.id == 0) bank else it }.frozen(),
                pads = before.pads.map { if (it.id == 0) pad else it }.frozen())
            assertEquals(expected, session.project)
            val archive = ByteArrayOutputStream().also { ArchiveCodec().write(session.project, store, it) }.toByteArray()
            val fresh = FileAssetStore(directory.resolve("restored"))
            val restored = ArchiveCodec().read(ByteArrayInputStream(archive), fresh)
            assertEquals(expected, restored)
            assertContentEquals(bytes, fresh.read(restored.asset(asset.hash)))
            assertEquals(before.source, restored.source)
            assertEquals(before.patterns, restored.patterns)
            assertEquals(before.clips, restored.clips)
            assertEquals(before.tracks, restored.tracks)
            repeat(2) {
                val plan = requireNotNull(session.planUndo())
                plan.effects.indices.forEach { session.acknowledge(plan, it) }
                session.commit(plan)
            }
            assertEquals(before, session.project)
            repeat(2) {
                val plan = requireNotNull(session.planRedo())
                plan.effects.indices.forEach { session.acknowledge(plan, it) }
                session.commit(plan)
            }
            assertEquals(restored, session.project)
        } finally { directory.toFile().deleteRecursively() }
    }
}
