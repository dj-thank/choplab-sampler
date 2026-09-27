package com.choplab.core

import com.choplab.core.edit.*
import com.choplab.core.kits.DrumKit
import com.choplab.core.kits.DrumKits
import com.choplab.core.model.*
import com.choplab.engine.PlayMode
import kotlin.math.abs
import kotlin.test.*

class DrumKitsTest {
    private fun hash(kit: DrumKit, slot: Int) = (DrumKits.catalog.indexOf(kit) * 16 + slot + 1).toString(16).padStart(64, '0')
    private fun sound(kit: DrumKit, slot: Int) = DrumKits.frames(slot).let { frames ->
        Asset(hash(kit, slot), "wav", 44L + frames * 2, 48_000, 1, frames.toLong(), DrumKits.soundName(kit, slot), AssetRole.RENDERED)
    }
    private fun install(kit: DrumKit, bank: Int = DrumKits.BANK): Intent.InstallKit {
        val assets = (0 until 16).map { sound(kit, it) }
        return Intent.InstallKit(assets.frozen(), assets.mapIndexed { slot, asset -> DrumKits.pad(bank * 16 + slot, slot, asset) }.frozen())
    }
    private fun apply(session: EditSession, intent: Intent): Project = commit(session, session.plan(intent))
    private fun commit(session: EditSession, plan: EditPlan): Project {
        plan.effects.indices.forEach { session.acknowledge(plan, it) }
        return session.commit(plan)
    }

    @Test fun theFiveKitsOfTheEarlierAppKeepTheirSlots() {
        assertEquals(listOf("dusty-jazz", "boom-bap", "vinyl-soul", "lofi-tape", "clean-studio"), DrumKits.catalog.map { it.id })
        assertEquals(listOf("DUSTY JAZZ", "BOOM BAP", "VINYL SOUL", "LO-FI TAPE", "CLEAN STUDIO"), DrumKits.catalog.map { it.name })
        assertEquals(listOf("KICK 1", "KICK 4", "SNARE 1", "CLOSED HAT 2", "OPEN HAT 3", "CLAP 1", "RIM 2", "SHAKER 3", "PERC 4"),
            listOf(0, 3, 4, 9, 10, 12, 13, 14, 15).map(DrumKits::padName))
        assertEquals("LO-FI TAPE OPEN HAT 4", DrumKits.soundName(DrumKits.kit("lofi-tape"), 11))
        assertEquals(listOf(20_160, 14_400, 4_800, 16_320, 11_520), listOf(0, 4, 8, 10, 12).map(DrumKits::frames))
        assertFailsWith<IllegalArgumentException> { DrumKits.kit("not-a-kit") }
        assertFailsWith<IllegalArgumentException> { DrumKits.padName(16) }
    }

    @Test fun kitPadsAreWholeOneShotsWithChokedHats() {
        val kit = DrumKits.kit("boom-bap")
        val pads = (0 until 16).map { DrumKits.pad(16 + it, it, sound(kit, it)) }
        pads.forEachIndexed { slot, pad ->
            val hat = slot in 8..11
            assertEquals(FrameRange(0, DrumKits.frames(slot).toLong()), pad.range)
            assertEquals(PlayMode.ONE_SHOT, pad.mode)
            assertEquals(if (hat) .72f else .9f, pad.gain)
            assertEquals(if (hat) 1 else 0, pad.chokeGroup)
            assertEquals(0, pad.attackFrames, "The drum transient is not faded in")
            assertEquals(DrumKits.padName(slot), pad.name)
        }
        assertFailsWith<IllegalArgumentException> { DrumKits.pad(16, 1, sound(kit, 0)) }
    }

    @Test fun renderingIsDeterministicAudibleAndDiffersByKit() {
        DrumKits.catalog.forEach { kit ->
            (0 until 16).forEach { slot ->
                val first = DrumKits.render(kit, slot)
                assertEquals(DrumKits.frames(slot), first.size)
                assertContentEquals(first, DrumKits.render(kit, slot))
                assertTrue(first.maxOf { abs(it.toInt()) } > 2_000, "${kit.id} $slot is audible")
                // The earlier app faded every sound to silence at its end, so a one-shot never clicks off.
                assertTrue(abs(first.last().toInt()) < 200, "${kit.id} $slot ends near silence")
            }
        }
        assertFalse(DrumKits.render(DrumKits.kit("dusty-jazz"), 0).contentEquals(DrumKits.render(DrumKits.kit("boom-bap"), 0)))
    }

    @Test fun onlyAppRenderedKitSoundsAreRecognized() {
        val kit = DrumKits.kit("vinyl-soul")
        val snare = sound(kit, 5)
        assertEquals(kit, DrumKits.identify(snare)?.kit)
        assertEquals(5, DrumKits.identify(snare)?.slot)
        assertNull(DrumKits.identify(snare.copy(role = AssetRole.ORIGINAL)), "An imported file with the same name stays the user's sound")
        assertNull(DrumKits.identify(snare.copy(frames = snare.frames - 1)))
        assertNull(DrumKits.identify(snare.copy(channels = 2)))
        assertNull(DrumKits.identify(snare.copy(name = "VINYL SOUL SNARE 9")))
    }

    @Test fun kitChangeKeepsThePlacedRhythmAndLeavesOtherMusicAlone() {
        val own = Asset("f".repeat(64), "wav", 100, 44_100, 2, 44_100, "own.wav")
        val session = EditSession()
        apply(session, Intent.ImportAsset(own))
        apply(session, Intent.AssignRange(own.hash, FrameRange(0, 22_050), 0))
        val dusty = DrumKits.kit("dusty-jazz")
        apply(session, install(dusty))
        val installed = session.project
        assertEquals((0 until 16).map { hash(dusty, it) }, installed.pads.subList(16, 32).map { it.assetHash })
        assertEquals(own.hash, installed.pads[0].assetHash, "Other BANKs keep their sounds")
        assertTrue(installed.pads.drop(32).all { it.assetHash == null })

        val track = Track("track-1", "Drums", TrackKind.BANK)
        val kick = Clip("clip-1", track.id, hash(dusty, 0), FrameRange(0, 9_000), timelineStartFrame = 12_000, gain = .5f)
        val hat = Clip("clip-2", track.id, hash(dusty, 9), FrameRange(100, 4_800), timelineStartFrame = 24_000)
        val mine = Clip("clip-3", track.id, own.hash, FrameRange(0, 22_050), timelineStartFrame = 0)
        apply(session, Intent.SetArrangement(frozenListOf(track), frozenListOf(kick, hat, mine), frozenListOf()))

        val boom = DrumKits.kit("boom-bap")
        val plan = session.plan(install(boom))
        assertEquals(frozenListOf<Effect>(Effect.StopPads((16 until 32).toList().frozen()), Effect.PublishProject), plan.effects)
        plan.effects.indices.forEach { session.acknowledge(plan, it) }
        val changed = session.commit(plan)
        assertEquals(listOf(kick.copy(assetHash = hash(boom, 0)), hat.copy(assetHash = hash(boom, 9)), mine), changed.clips,
            "Placed kit sounds take the new kit's sound in the same place and length; the user's clip stays")
        assertEquals((0 until 16).map { hash(boom, it) }, changed.pads.subList(16, 32).map { it.assetHash })
        assertEquals(installed.source, changed.source)
        assertEquals(installed.pads[0], changed.pads[0])

        val undo = assertNotNull(session.planUndo())
        undo.effects.indices.forEach { session.acknowledge(undo, it) }
        val restored = session.commit(undo)
        assertEquals(listOf(kick, hat, mine), restored.clips, "One Undo returns the previous kit and its rhythm")
        assertEquals(installed.pads, restored.pads)
    }

    @Test fun kitSoundsLeaveTheDocumentOnceNothingUsesThem() {
        val own = Asset("e".repeat(64), "wav", 100, 44_100, 2, 44_100, "own.wav")
        val song = Asset("f".repeat(64), "wav", 100, 44_100, 2, 44_100, "song.wav")
        val session = EditSession()
        apply(session, Intent.ImportAsset(own))
        apply(session, Intent.ImportAsset(song))
        val dusty = DrumKits.kit("dusty-jazz")
        val boom = DrumKits.kit("boom-bap")
        apply(session, install(dusty))
        val track = Track("track-1", "Drums", TrackKind.BANK)
        apply(session, Intent.SetArrangement(frozenListOf(track), frozenListOf(Clip("clip-1", track.id, hash(dusty, 0), FrameRange(0, 9_000),
            timelineStartFrame = 0)), frozenListOf()))
        val withDusty = session.project

        apply(session, install(boom))
        assertEquals((listOf(own.hash, song.hash) + (0 until 16).map { hash(boom, it) }).sorted(), session.project.assets.map { it.hash },
            "Only the kit in use stays; the user's audio stays even when nothing uses it")
        // A kit sound overwritten on its PAD leaves too, unless a placed clip still plays it.
        apply(session, Intent.AssignRange(song.hash, FrameRange(0, 1_000), 17))
        apply(session, Intent.AssignRange(song.hash, FrameRange(0, 1_000), 16))
        assertFalse(session.project.assets.any { it.hash == hash(boom, 1) })
        assertTrue(session.project.assets.any { it.hash == hash(boom, 0) }, "The placed kick still uses its sound")

        repeat(3) { commit(session, assertNotNull(session.planUndo())) }
        assertEquals(withDusty, session.project, "Undo brings each kit's sounds back")
    }

    @Test fun aRenderedPadSoundStaysWhileItsClipDoesAndLeavesWithIt() {
        val source = Asset("a".repeat(64), "wav", 44 + 48_000L * 8, 48_000, 2, 48_000, "Keys")
        val rendered = Asset("b".repeat(64), "wav", 44 + 24_000L * 8, 48_000, 2, 24_000, "Keys +12", AssetRole.RENDERED, derivedFrom = source.hash)
        val track = Track("t", "A", TrackKind.BANK)
        val project = Project(assets = frozenListOf(source), tracks = frozenListOf(track),
            pads = (0..127).map { if (it == 0) Pad(0, source.hash, FrameRange(0, 48_000), pitchSemitones = 12.0) else Pad(it) }.frozen())
        val clip = Clip("c", track.id, rendered.hash, FrameRange(0, 24_000), timelineStartFrame = 0)
        val placed = Reducer.reduce(project, Intent.SetArrangement(project.tracks, frozenListOf(clip), project.takes, frozenListOf(rendered))).project
        assertEquals(setOf(source.hash, rendered.hash), placed.assets.map { it.hash }.toSet())
        val removed = Reducer.reduce(placed, Intent.SetArrangement(placed.tracks, frozenListOf(), placed.takes)).project
        assertEquals(listOf(source.hash), removed.assets.map { it.hash }, "The rendered sound leaves with its clip; the PAD's own stays")
    }

    @Test fun installingRequiresOneWholeKitInSlotOrder() {
        val dusty = DrumKits.kit("dusty-jazz")
        val boom = DrumKits.kit("boom-bap")
        val valid = install(dusty)
        val session = EditSession()
        assertFailsWith<IllegalArgumentException> { session.plan(valid.copy(pads = valid.pads.take(15).frozen())) }
        assertFailsWith<IllegalArgumentException> { session.plan(valid.copy(pads = valid.pads.reversed().frozen())) }
        assertFailsWith<IllegalArgumentException> { session.plan(Intent.InstallKit(valid.assets, valid.pads.map { it.copy(id = it.id + 1) }.frozen())) }
        val mixed = install(boom)
        assertFailsWith<IllegalArgumentException> {
            session.plan(Intent.InstallKit((valid.assets + mixed.assets).frozen(), (valid.pads.take(8) + mixed.pads.drop(8)).frozen()))
        }
        val imported = valid.assets[0].copy(role = AssetRole.ORIGINAL)
        assertFailsWith<IllegalArgumentException> {
            session.plan(Intent.InstallKit((listOf(imported) + valid.assets.drop(1)).frozen(), valid.pads))
        }
        assertFailsWith<IllegalArgumentException> { session.plan(Intent.InstallKit(valid.assets.drop(1).frozen(), valid.pads)) }
        assertEquals(Mutation.PROJECT, session.plan(install(dusty, bank = 7)).mutation, "Any whole BANK can hold a kit")
    }
}
