package com.choplab.jvm

import com.choplab.core.kits.DrumKit
import com.choplab.core.kits.DrumKits
import com.choplab.core.model.Asset
import com.choplab.core.model.AssetRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Stores a built-in kit as 16 app-rendered mono WAVs in slot order, ready for Intent.InstallKit. The
 * synthesis is 16-bit; app-rendered assets keep float PCM, which holds each 16-bit value exactly.
 * Rendering is deterministic, so a second preparation publishes nothing new and returns equal assets.
 */
object DrumKitAssets {
    suspend fun publish(kit: DrumKit, store: FileAssetStore): List<Asset> = withContext(Dispatchers.IO) {
        (0 until DrumKits.SOUNDS).map { slot ->
            ensureActive()
            PcmMemoryBudget.shared.reserve(DrumKits.frames(slot) * 24L + 256 * 1024).use {
            val pcm = DrumKits.render(kit, slot).let { values -> FloatArray(values.size) { values[it] / 32768f } }
            val bytes = ByteArrayOutputStream().also { WavCodec.writeFloat(it, pcm, DrumKits.SAMPLE_RATE, 1) }.toByteArray()
            val asset = Asset(sha256(bytes), "wav", bytes.size.toLong(), DrumKits.SAMPLE_RATE, 1, DrumKits.frames(slot).toLong(),
                DrumKits.soundName(kit, slot), AssetRole.RENDERED)
            store.publish(asset, ByteArrayInputStream(bytes))
            asset
            }
        }
    }
}
