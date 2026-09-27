package com.choplab.desktop.next

import com.choplab.core.kits.DrumKits
import com.choplab.core.model.Asset
import com.choplab.core.model.AssetRole
import com.choplab.engine.PlayMode
import com.choplab.sampler.audio.BuiltInDrumKits
import com.choplab.sampler.model.PadPlayMode
import kotlin.test.*

/** The linked editor's kits are the earlier app's kits: every sample and PAD setting matches. */
class DrumKitParityTest {
    @Test fun everyKitSoundAndPadMatchesTheEarlierApp() {
        assertEquals(BuiltInDrumKits.catalog.map { it.id to it.name }, DrumKits.catalog.map { it.id to it.name })
        for (kit in DrumKits.catalog) {
            BuiltInDrumKits.createBankPads(kit.id, DrumKits.BANK).forEachIndexed { slot, legacy ->
                val audio = requireNotNull(legacy.audio)
                assertContentEquals(audio.samples, DrumKits.render(kit, slot), "${kit.id} slot $slot")
                assertEquals(audio.name, DrumKits.soundName(kit, slot))
                val frames = DrumKits.frames(slot)
                val pad = DrumKits.pad(DrumKits.BANK * 16 + slot, slot,
                    Asset("0".repeat(63) + "1", "wav", 44L + frames * 4, 48_000, 1, frames.toLong(), DrumKits.soundName(kit, slot), AssetRole.RENDERED))
                // The earlier BANK held 32 PADs over two pages; the linked editor's BANK is the 16 PADs of one page.
                assertEquals(slot, legacy.globalIndex % 32)
                assertEquals(legacy.gain, pad.gain)
                assertEquals(legacy.chokeGroup, pad.chokeGroup)
                assertEquals(PadPlayMode.ONE_SHOT, legacy.playMode)
                assertEquals(PlayMode.ONE_SHOT, pad.mode)
                assertEquals(legacy.endFrame.toLong(), pad.range?.end)
            }
        }
    }
}
