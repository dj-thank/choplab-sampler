package com.choplab.sampler.persistence

import com.choplab.sampler.model.PadModel
import com.choplab.sampler.model.PadContentKind
import com.choplab.sampler.model.PadPlayMode
import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.ProjectLaunchTarget
import com.choplab.sampler.model.SamplerConfig
import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.model.audibleStepKeys
import com.choplab.sampler.model.stepKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectArchiveCodecTest {
    @Test
    fun boundedArchiveReadsRejectAnInputStreamThatMakesNoProgress() {
        val zeroProgress = object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                return if (length > 0) 0 else super.read(buffer, offset, length)
            }
        }

        assertThrows(IllegalArgumentException::class.java) {
            zeroProgress.readWithProgress(ByteArray(8), 0, 8)
        }
    }

    @Test
    fun archiveUsesSchemaFiveForPagedRoleBanks() {
        val manifest = unzip(archiveFor(SamplerUiState()))
            .single { (name, _) -> name == "project.txt" }
            .second
            .toString(Charsets.UTF_8)

        assertTrue(manifest.startsWith("CHOPLAB_PROJECT\t5\n"))
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
            projectLaunchTarget = ProjectLaunchTarget.BEAT,
            projectLaunchRevision = 99L,
            scratchSpeed = -2f,
            scratchReturnAvailable = true,
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
        assertEquals(setOf(stepKey(1, 8)), restored.activeSteps.audibleStepKeys(restored.pads))
        assertEquals(123f, restored.bpm)
        assertEquals(61f, restored.swing)
        assertEquals(-2f, restored.masterPitchSemitones)
        assertEquals("vinyl-soul", restored.selectedDrumKitId)
        assertSame(restored.currentAudio, restored.pads[0].audio)
        assertSame(restored.currentAudio, restored.pads[1].audio)
        assertFalse(restored.transportPlaying)
        assertFalse(restored.recordArmed)
        assertFalse(restored.sourcePlaying)
        assertNull(restored.projectLaunchTarget)
        assertEquals(0L, restored.projectLaunchRevision)
        assertEquals(0f, restored.scratchSpeed)
        assertFalse(restored.scratchReturnAvailable)
        assertEquals(-1, restored.currentStep)
    }

    @Test
    fun schemaFourSixteenPadBanksMigrateToFirstPageWithoutIndexDrift() {
        val currentB01 = SamplerConfig.PADS_PER_BANK
        val current = SamplerUiState(
            selectedBank = 1,
            selectedPad = currentB01,
            activeSteps = setOf(stepKey(currentB01, 3)),
        )
        val entries = unzip(archiveFor(current)).map { (name, bytes) ->
            if (name != "project.txt") return@map name to bytes
            val migratedLines = bytes.toString(Charsets.UTF_8).lineSequence().mapNotNull { line ->
                when {
                    line.startsWith("CHOPLAB_PROJECT\t") -> "CHOPLAB_PROJECT\t4"
                    line.startsWith("padCount\t") -> "padCount\t64"
                    line.startsWith("state\t") -> line.split('\t').toMutableList().also { it[6] = "16" }.joinToString("\t")
                    line.startsWith("steps\t") -> "steps\t${stepKey(16, 3)}"
                    line.startsWith("pad\t") -> {
                        val fields = line.split('\t').toMutableList()
                        val currentIndex = fields[1].toInt()
                        val bank = currentIndex / SamplerConfig.PADS_PER_BANK
                        val indexInBank = currentIndex % SamplerConfig.PADS_PER_BANK
                        if (indexInBank >= 16) null else {
                            fields[1] = (bank * 16 + indexInBank).toString()
                            fields.joinToString("\t")
                        }
                    }
                    else -> line
                }
            }.joinToString("\n", postfix = "\n")
            name to migratedLines.toByteArray(Charsets.UTF_8)
        }

        val restored = ProjectArchiveCodec.read(ByteArrayInputStream(zip(entries)))

        assertEquals(currentB01, restored.selectedPad)
        assertEquals(setOf(stepKey(currentB01, 3)), restored.activeSteps)
        assertEquals(SamplerConfig.PAD_COUNT, restored.pads.size)
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
                    .replace("CHOPLAB_PROJECT\t5", "CHOPLAB_PROJECT\t1")
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
                    .replace("CHOPLAB_PROJECT\t5", "CHOPLAB_PROJECT\t2")
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
                    .replace("CHOPLAB_PROJECT\t5", "CHOPLAB_PROJECT\t3")
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
                    .replace("CHOPLAB_PROJECT\t5", "CHOPLAB_PROJECT\t999")
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

    @Test
    fun independentSchemaOneGoldenArchiveLoadsWithoutUsingTheCurrentWriter() {
        val samples = shortArrayOf(100, -200, 300, -400)
        val rawPcm = ByteBuffer.allocate(samples.size * Short.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .also { buffer -> samples.forEach(buffer::putShort) }
            .array()
        val manifest = buildString {
            appendLine("CHOPLAB_PROJECT\t1")
            appendLine("audioCount\t1")
            appendLine("audio\t0\t42\t48000\t4\tbGVnYWN5Lndhdg\taudio/0.pcm")
            appendLine("state\t0\t4\t-1\t0\t0\t0\t1\t120.0\t50.0\t0\t0.0\t0")
            appendLine("slices\t")
            appendLine("steps\t")
            appendLine("padCount\t64")
            repeat(64) { index ->
                val audioIndex = if (index == 0) 0 else -1
                val endFrame = if (index == 0) 4 else 0
                appendLine("pad\t$index\t$audioIndex\t0\t$endFrame\t0.0\t1.0\t1.0\t0\tONE_SHOT\t0")
            }
        }

        val restored = ProjectArchiveCodec.read(
            ByteArrayInputStream(
                zip(
                    listOf(
                        "project.txt" to manifest.toByteArray(Charsets.UTF_8),
                        "audio/0.pcm" to rawPcm,
                    ),
                ),
            ),
        )

        assertArrayEquals(samples, restored.currentAudio?.samples)
        assertEquals(42L, restored.currentAudio?.id)
        assertEquals(48_000, restored.currentAudio?.sampleRate)
        assertArrayEquals(samples, restored.pads[0].audio?.samples)
    }

    @Test
    fun deterministicMalformedInputCorpusAlwaysFailsClosed() {
        val random = Random(0x43484F50)
        repeat(256) { seedIndex ->
            val bytes = ByteArray(random.nextInt(0, 4_096)) { random.nextInt(0, 256).toByte() }
            val result = runCatching { ProjectArchiveCodec.read(ByteArrayInputStream(bytes)) }
            assertTrue("Malformed corpus item $seedIndex unexpectedly loaded", result.isFailure)
        }
    }

    @Test
    fun manyUnregisteredSmallEntriesAreRejectedAtTheFirstUnknownEntry() {
        val entries = unzip(archiveFor(SamplerUiState())).toMutableList()
        repeat(1_000) { index -> entries += "unknown/$index.bin" to byteArrayOf(index.toByte()) }

        val failure = assertThrows(IllegalStateException::class.java) {
            ProjectArchiveCodec.read(ByteArrayInputStream(zip(entries)))
        }

        assertTrue(failure.message.orEmpty().contains("unknown/0.bin"))
    }

    @Test
    fun declaredPcmExpansionBeyondProjectBudgetIsRejectedBeforeAnyAudioEntry() {
        val manifest = buildString {
            appendLine("CHOPLAB_PROJECT\t5")
            appendLine("audioCount\t9")
            repeat(9) { index ->
                appendLine("audio\t$index\t${index + 1}\t48000\t30000000\tYQ\taudio/$index.wav")
            }
            appendLine("state\t0\t0\t-1\t0\t0\t0\t1\t120.0\t50.0\t0\t0.0\t-1\t")
            appendLine("slices\t")
            appendLine("steps\t")
            appendLine("padCount\t128")
            repeat(128) { index ->
                appendLine("pad\t$index\t-1\t0\t0\t0.0\t1.0\t1.0\t0\tONE_SHOT\tSAMPLE\t0")
            }
        }

        val failure = assertThrows(IllegalArgumentException::class.java) {
            ProjectArchiveCodec.read(
                ByteArrayInputStream(zip(listOf("project.txt" to manifest.toByteArray(Charsets.UTF_8)))),
            )
        }

        assertTrue(failure.message.orEmpty().contains("音声データが大きすぎます"))
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
