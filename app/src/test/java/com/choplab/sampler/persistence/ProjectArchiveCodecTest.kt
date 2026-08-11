package com.choplab.sampler.persistence

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.stepKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectArchiveCodecTest {
    @Test
    fun archiveUsesSchemaFourWhenPadContentRolesCanBeStored() {
        val manifest = unzip(archiveFor(SamplerUiState()))
            .single { (name, _) -> name == "project.txt" }
            .second
            .toString(Charsets.UTF_8)

        assertTrue(manifest.startsWith("CHOPLAB_PROJECT\t4\n"))
    }

    @Test
    fun projectRoundTripPreservesMusicStateAndSharesAudioAssets() {
        val audio = PcmAudio(
            id = 42L,
            name = "声 ネタ.wav",
            samples = shortArrayOf(100, -200, 300, -400, 500, -600),
            sampleRate = 48_000,
        )
        val pads = List(SamplerConfig.PAD_COUNT) { index ->
            when (index) {
                0 -> PadModel(
                    globalIndex = index,
                    audio = audio,
                    startFrame = 1,
                    endFrame = 4,
                    pitchSemitones = 3f,
                    tone = 0.35f,
                    gain = 1.1f,
                    reverse = true,
                    playMode = PadPlayMode.LOOP,
                    contentKind = PadContentKind.VOCAL,
                    chokeGroup = 2,
                )
                1 -> PadModel(index, audio = audio, startFrame = 4, endFrame = 6)
                else -> PadModel(index)
            }
        }
        val original = SamplerUiState(
            statusMessage = "保存前の一時メッセージ",
            currentAudio = audio,
            rangeStartFrame = 1,
            rangeEndFrame = 6,
            sliceMarkers = listOf(2, 4),
            activeSliceIndex = 1,
            manualChopEnabled = true,
            selectedBank = 0,
            selectedPad = 1,
            autoNextPad = false,
            pads = pads,
            activeSteps = setOf(stepKey(0, 0), stepKey(1, 8)),
            bpm = 123f,
            swing = 61f,
            transportPlaying = true,
            recordArmed = true,
            currentStep = 7,
            sourcePlaying = true,
            sourcePlayheadFrame = 3,
            masterPitchSemitones = -2f,
            selectedDrumKitId = "vinyl-soul",
        )

        val bytes = ByteArrayOutputStream().also { ProjectArchiveCodec.write(original, it) }.toByteArray()
        val restored = ProjectArchiveCodec.read(ByteArrayInputStream(bytes))

        assertEquals("声 ネタ.wav", restored.currentAudio?.name)
        assertArrayEquals(audio.samples, restored.currentAudio?.samples)
        assertEquals(1, restored.rangeStartFrame)
        assertEquals(6, restored.rangeEndFrame)
        assertEquals(listOf(2, 4), restored.sliceMarkers)
        assertEquals(1, restored.activeSliceIndex)
        assertEquals(1, restored.selectedPad)
        assertFalse(restored.autoNextPad)
        assertEquals(3f, restored.pads[0].pitchSemitones)
        assertEquals(0.35f, restored.pads[0].tone)
        assertEquals(1.1f, restored.pads[0].gain)
        assertEquals(PadPlayMode.LOOP, restored.pads[0].playMode)
        assertEquals(PadContentKind.VOCAL, restored.pads[0].contentKind)
        assertEquals(setOf(stepKey(0, 0), stepKey(1, 8)), restored.activeSteps)
        assertEquals(123f, restored.bpm)
        assertEquals(61f, restored.swing)
        assertEquals(-2f, restored.masterPitchSemitones)
        assertEquals("vinyl-soul", restored.selectedDrumKitId)
        assertSame(restored.currentAudio, restored.pads[0].audio)
        assertSame(restored.currentAudio, restored.pads[1].audio)
        assertFalse(restored.transportPlaying)
        assertFalse(restored.recordArmed)
        assertFalse(restored.sourcePlaying)
        assertEquals(-1, restored.currentStep)
    }

    @Test
    fun archiveRejectsPathTraversalBeforeUsingUnknownContent() {
        val valid = archiveFor(SamplerUiState())
        val entries = unzip(valid).toMutableList().apply {
            add("../outside.bin" to byteArrayOf(1, 2, 3))
        }

        assertThrows(IllegalArgumentException::class.java) {
            ProjectArchiveCodec.read(ByteArrayInputStream(zip(entries)))
        }
    }

    @Test
    fun archiveRejectsTruncatedWav() {
        val audio = PcmAudio(id = 7L, name = "short.wav", samples = shortArrayOf(1, 2, 3), sampleRate = 48_000)
        val valid = archiveFor(
            SamplerUiState(
                currentAudio = audio,
                rangeEndFrame = audio.frameCount,
            ),
        )
        val entries = unzip(valid).map { (name, bytes) ->
            if (name.startsWith("audio/")) name to bytes.dropLast(2).toByteArray() else name to bytes
        }

        assertThrows(IllegalArgumentException::class.java) {
            ProjectArchiveCodec.read(ByteArrayInputStream(zip(entries)))
        }
    }

    @Test
    fun archiveStoresEachAudioAssetAsPcm16Wav() {
        val audio = PcmAudio(id = 9L, name = "kick.wav", samples = shortArrayOf(1, -2), sampleRate = 44_100)
        val entries = unzip(
            archiveFor(SamplerUiState(currentAudio = audio, rangeEndFrame = audio.frameCount)),
        )
        val wav = entries.single { (name, _) -> name == "audio/0.wav" }.second

        assertEquals("RIFF", wav.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals("WAVE", wav.copyOfRange(8, 12).toString(Charsets.US_ASCII))
        assertEquals("data", wav.copyOfRange(36, 40).toString(Charsets.US_ASCII))
        assertEquals(48, wav.size)
    }

    @Test
    fun schemaOneRawPcmArchiveMigratesOnRead() {
        val audio = PcmAudio(id = 10L, name = "legacy.wav", samples = shortArrayOf(8, -9), sampleRate = 48_000)
        val schemaTwoEntries = unzip(
            archiveFor(SamplerUiState(currentAudio = audio, rangeEndFrame = audio.frameCount)),
        )
        val schemaOneEntries = schemaTwoEntries.map { (name, bytes) ->
            when (name) {
                "project.txt" -> name to bytes.toString(Charsets.UTF_8)
                    .replace("CHOPLAB_PROJECT\t4", "CHOPLAB_PROJECT\t1")
                    .replace("audio/0.wav", "audio/0.pcm")
                    .removeSchemaFourFields()
                    .toByteArray(Charsets.UTF_8)
                "audio/0.wav" -> "audio/0.pcm" to bytes.copyOfRange(44, bytes.size)
                else -> name to bytes
            }
        }

        val restored = ProjectArchiveCodec.read(ByteArrayInputStream(zip(schemaOneEntries)))

        assertArrayEquals(audio.samples, restored.currentAudio?.samples)
        assertEquals(audio.sampleRate, restored.currentAudio?.sampleRate)
    }

    @Test
    fun schemaTwoWavArchiveStillLoads() {
        val audio = PcmAudio(id = 12L, name = "schema-two.wav", samples = shortArrayOf(3, -4), sampleRate = 48_000)
        val entries = unzip(
            archiveFor(SamplerUiState(currentAudio = audio, rangeEndFrame = audio.frameCount)),
        ).map { (name, bytes) ->
            if (name == "project.txt") {
                name to bytes.toString(Charsets.UTF_8)
                    .replace("CHOPLAB_PROJECT\t4", "CHOPLAB_PROJECT\t2")
                    .removeSchemaFourFields()
                    .toByteArray(Charsets.UTF_8)
            } else {
                name to bytes
            }
        }

        val restored = ProjectArchiveCodec.read(ByteArrayInputStream(zip(entries)))

        assertArrayEquals(audio.samples, restored.currentAudio?.samples)
    }

    @Test
    fun schemaThreeWholeChopArchiveStillLoadsWithSampleContentRole() {
        val audio = PcmAudio(id = 13L, name = "schema-three.wav", samples = shortArrayOf(5, -6), sampleRate = 48_000)
        val state = SamplerUiState(
            currentAudio = audio,
            rangeEndFrame = audio.frameCount,
            pads = List(SamplerConfig.PAD_COUNT) { index ->
                if (index == 0) {
                    PadModel(index, audio = audio, startFrame = 0, endFrame = 2, playMode = PadPlayMode.LOOP)
                } else {
                    PadModel(index)
                }
            },
        )
        val entries = unzip(archiveFor(state)).map { (name, bytes) ->
            if (name == "project.txt") {
                name to bytes.toString(Charsets.UTF_8)
                    .replace("CHOPLAB_PROJECT\t4", "CHOPLAB_PROJECT\t3")
                    .removeSchemaFourFields()
                    .toByteArray(Charsets.UTF_8)
            } else {
                name to bytes
            }
        }

        val restored = ProjectArchiveCodec.read(ByteArrayInputStream(zip(entries)))

        assertEquals(PadPlayMode.LOOP, restored.pads[0].playMode)
        assertEquals(PadContentKind.SAMPLE, restored.pads[0].contentKind)
    }

    @Test
    fun archiveRejectsNewerSchemaWithActionableMessage() {
        val entries = unzip(archiveFor(SamplerUiState())).map { (name, bytes) ->
            if (name == "project.txt") {
                name to bytes.toString(Charsets.UTF_8)
                    .replace("CHOPLAB_PROJECT\t4", "CHOPLAB_PROJECT\t999")
                    .toByteArray(Charsets.UTF_8)
            } else {
                name to bytes
            }
        }

        val failure = assertThrows(IllegalArgumentException::class.java) {
            ProjectArchiveCodec.read(ByteArrayInputStream(zip(entries)))
        }

        assertEquals("このプロジェクトは新しいChopLabで作成されています。アプリを更新してください", failure.message)
    }

    @Test
    fun archiveRejectsWavWhoseHeaderDisagreesWithManifest() {
        val audio = PcmAudio(id = 11L, name = "wrong-rate.wav", samples = shortArrayOf(1, 2), sampleRate = 48_000)
        val entries = unzip(
            archiveFor(SamplerUiState(currentAudio = audio, rangeEndFrame = audio.frameCount)),
        ).map { (name, bytes) ->
            if (name == "audio/0.wav") {
                name to bytes.copyOf().also { wav ->
                    wav[24] = 0x44
                    wav[25] = 0xAC.toByte()
                    wav[26] = 0
                    wav[27] = 0
                }
            } else {
                name to bytes
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            ProjectArchiveCodec.read(ByteArrayInputStream(zip(entries)))
        }
    }

    @Test
    fun archiveRejectsOversizedManifest() {
        val oversized = zip(listOf("project.txt" to ByteArray(256 * 1024 + 1) { 'A'.code.toByte() }))

        assertThrows(IllegalArgumentException::class.java) {
            ProjectArchiveCodec.read(ByteArrayInputStream(oversized))
        }
    }

    private fun archiveFor(state: SamplerUiState): ByteArray =
        ByteArrayOutputStream().also { ProjectArchiveCodec.write(state, it) }.toByteArray()

    private fun String.removeSchemaFourFields(): String =
        lineSequence().joinToString("\n") { line ->
            val fields = line.split('\t').toMutableList()
            when {
                line.startsWith("state\t") -> fields.dropLast(1).joinToString("\t")
                line.startsWith("pad\t") -> fields.apply { removeAt(10) }.joinToString("\t")
                else -> line
            }
        } + "\n"

    private fun unzip(bytes: ByteArray): List<Pair<String, ByteArray>> = buildList {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                add(entry.name to zip.readBytes())
                zip.closeEntry()
            }
        }
    }

    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()
}
