package com.choplab.jvm

import com.choplab.core.*
import com.choplab.core.edit.Intent
import com.choplab.core.kits.DrumKits
import com.choplab.core.model.*
import kotlinx.coroutines.*
import java.nio.file.Files
import kotlin.test.*

/** Built-in kit sounds as verified WAV assets, installed through the real Studio, compiler and PCM path. */
class DrumKitAssetsTest {
    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(15_000) { while (!condition()) delay(5) }

    @Test fun kitSoundsAreVerifiedMonoWavsOfTheSynthesis() = runBlocking<Unit> {
        val store = FileAssetStore(Files.createTempDirectory("choplab-kit-"))
        val kit = DrumKits.kit("lofi-tape")
        val assets = DrumKitAssets.publish(kit, store)
        assertEquals(16, assets.size)
        assets.forEachIndexed { slot, asset ->
            assertTrue(store.verified(asset))
            assertEquals(AssetRole.RENDERED, asset.role)
            assertEquals(slot, DrumKits.identify(asset)?.slot)
            val audio = store.openVerified(asset).use { WavCodec.read(it) }
            assertEquals(WavInfo(48_000, 1, DrumKits.frames(slot).toLong(), 32, true), audio.info)
            val expected = DrumKits.render(kit, slot)
            assertTrue(expected.indices.all { audio.samples[it] == expected[it] / 32768f }, "Slot $slot stores the synthesis unchanged")
        }
        assertEquals(assets, DrumKitAssets.publish(kit, store), "Preparing again reuses the same content-addressed sounds")
    }

    @Test fun installedKitCompilesSoundsAndSurvivesAutosave() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("choplab-kit-backend-")
        fun files(assets: FileAssetStore, compiler: ProgramCompiler) = HostFileServices(
            WavImportPort(assets, { error("No files in this test") }), FileProjectPort(assets, { error("No files in this test") }),
            WavExportPort(compiler) { error("No files in this test") })
        val backend = EditorBackend.create(directory, { StreamingEnginePort(it, { error("No device in test") }) }, ::files)
        val assets = try {
            waitUntil { backend.engine.status.value.phase == DriverPhase.EDITING_ONLY }
            val sounds = backend.prepareDrumKit("clean-studio")
            val pads = sounds.mapIndexed { slot, asset -> DrumKits.pad(DrumKits.BANK * 16 + slot, slot, asset) }
            // The edit is accepted only after the engine compiled every PAD from the stored WAVs.
            assertTrue(backend.studio.dispatch(Action.Edit(Intent.InstallKit(sounds.frozen(), pads.frozen()))).accepted)
            assertEquals(sounds.map { it.hash }, backend.studio.document.value.project.pads.subList(16, 32).map { it.assetHash })
            assertTrue(backend.studio.document.value.canUndo, "Installing a kit is one Undo step")
            backend.flushAutosave()
            sounds
        } finally { backend.shutdown() }
        val reopened = EditorBackend.create(directory, { StreamingEnginePort(it, { error("No device in test") }) }, ::files)
        val saved = try {
            val project = reopened.studio.document.value.project
            assertEquals(assets.map { it.hash }, project.pads.subList(16, 32).map { it.assetHash }, "The kit comes back from autosave with its sounds")
            project
        } finally { reopened.shutdown() }

        // A project file carries the app-rendered kit sounds to another profile and opens there intact.
        val archive = Files.createTempDirectory("choplab-kit-archive-").resolve("kit.choplab")
        FileProjectPort(FileAssetStore(directory.resolve("assets")), { archive }).save(saved, 1, Location("kit"))
        val elsewhere = FileAssetStore(Files.createTempDirectory("choplab-kit-elsewhere-"))
        val opened = FileProjectPort(elsewhere, { archive }).open(Location("kit"))
        assertEquals(saved, opened)
        assertTrue(assets.all { elsewhere.verified(it) })
    }
}
