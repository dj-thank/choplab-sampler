#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def write(path: str, content: str) -> None:
    (ROOT / path).write_text(content, encoding="utf-8")


def replace_once(path: str, old: str, new: str) -> None:
    content = read(path)
    count = content.count(old)
    if count != 1:
        raise RuntimeError(f"{path}: expected exactly one match, found {count}: {old[:80]!r}")
    write(path, content.replace(old, new, 1))


def insert_before_last_close(path: str, block: str) -> None:
    content = read(path)
    marker = "\n}\n"
    index = content.rfind(marker)
    if index < 0:
        raise RuntimeError(f"{path}: class closing brace not found")
    write(path, content[:index] + "\n" + block.rstrip() + content[index:])


# Shared file-size policy keeps Android and Desktop behavior identical and testable.
replace_once(
    "shared/src/commonMain/kotlin/com/choplab/sampler/audio/AudioResourceLimits.kt",
    """    const val MAX_DESKTOP_PROJECT_PCM_BYTES = 512L * 1024L * 1024L

    fun maxRecordingPcmBytes(sampleRate: Int, channelCount: Int): Long {""",
    """    const val MAX_DESKTOP_PROJECT_PCM_BYTES = 512L * 1024L * 1024L

    fun requireImportFileSize(sizeBytes: Long?) {
        if (sizeBytes == null || sizeBytes < 0L) return
        require(sizeBytes <= MAX_IMPORT_FILE_BYTES) {
            \"音声ファイルが大きすぎます。256 MiB以下のファイルを使用してください\"
        }
    }

    fun maxRecordingPcmBytes(sampleRate: Int, channelCount: Int): Long {""",
)
insert_before_last_close(
    "shared/src/commonTest/kotlin/com/choplab/sampler/audio/AudioResourceLimitsTest.kt",
    """
    @Test
    fun importFileSizeRejectsOnlyKnownOversizedInputs() {
        AudioResourceLimits.requireImportFileSize(null)
        AudioResourceLimits.requireImportFileSize(-1L)
        AudioResourceLimits.requireImportFileSize(AudioResourceLimits.MAX_IMPORT_FILE_BYTES)

        kotlin.test.assertFailsWith<IllegalArgumentException> {
            AudioResourceLimits.requireImportFileSize(AudioResourceLimits.MAX_IMPORT_FILE_BYTES + 1L)
        }
    }
""",
)

# Android checks container/file size before codec allocation; decoded frames remain bounded too.
replace_once(
    "app/src/main/java/com/choplab/sampler/audio/AudioDecoder.kt",
    """import android.provider.OpenableColumns
import com.choplab.sampler.model.PcmAudio""",
    """import android.provider.OpenableColumns
import com.choplab.sampler.model.PcmAudio
import java.io.File""",
)
replace_once(
    "app/src/main/java/com/choplab/sampler/audio/AudioDecoder.kt",
    """    private fun decodeBlocking(uri: Uri): PcmAudio {
        val extractor = MediaExtractor()""",
    """    private fun decodeBlocking(uri: Uri): PcmAudio {
        validateInputSize(uri)
        val extractor = MediaExtractor()""",
)
replace_once(
    "app/src/main/java/com/choplab/sampler/audio/AudioDecoder.kt",
    """            if (durationUs > MAX_DURATION_US) {""",
    """            if (durationUs > AudioResourceLimits.MAX_IMPORT_DURATION_SECONDS * 1_000_000L) {""",
)
replace_once(
    "app/src/main/java/com/choplab/sampler/audio/AudioDecoder.kt",
    """                maximumSize = MAX_MONO_FRAMES,""",
    """                maximumSize = AudioResourceLimits.MAX_DECODED_MONO_FRAMES,""",
)
replace_once(
    "app/src/main/java/com/choplab/sampler/audio/AudioDecoder.kt",
    """    private fun resolveDisplayName(uri: Uri): String {""",
    """    private fun validateInputSize(uri: Uri) {
        val sizeBytes = when (uri.scheme) {
            \"file\" -> uri.path?.let(::File)?.takeIf(File::isFile)?.length()
            else -> runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                        cursor.getLong(sizeIndex)
                    } else {
                        null
                    }
                }
            }.getOrNull()
        }
        AudioResourceLimits.requireImportFileSize(sizeBytes)
    }

    private fun resolveDisplayName(uri: Uri): String {""",
)
replace_once(
    "app/src/main/java/com/choplab/sampler/audio/AudioDecoder.kt",
    """        const val MAX_IDLE_POLLS = 500
        const val MAX_DURATION_US = 10L * 60L * 1_000_000L
        const val MAX_MONO_FRAMES = 30_000_000""",
    """        const val MAX_IDLE_POLLS = 500""",
)

# Desktop no longer duplicates the input byte ceiling and never uses an ambiguous method reference.
replace_once(
    "desktop/src/main/kotlin/com/choplab/desktop/audio/DesktopWavDecoder.kt",
    """        require(file.length() <= AudioResourceLimits.MAX_IMPORT_FILE_BYTES) {
            \"音声ファイルが大きすぎます。256 MiB以下のファイルを使用してください\"
        }""",
    """        AudioResourceLimits.requireImportFileSize(file.length())""",
)
replace_once(
    "desktop/src/test/kotlin/com/choplab/desktop/audio/DesktopWavDecoderTest.kt",
    """            .apply { samples.forEach(::putShort) }""",
    """            .apply { samples.forEach { sample -> putShort(sample) } }""",
)

# Project archives retain the 512 MiB format ceiling but apply a lower Android resident-memory budget.
replace_once(
    "jvm-core/src/main/kotlin/com/choplab/sampler/persistence/ProjectArchiveCodec.kt",
    """package com.choplab.sampler.persistence

import com.choplab.sampler.model.PadModel""",
    """package com.choplab.sampler.persistence

import com.choplab.sampler.audio.AudioResourceLimits
import com.choplab.sampler.model.PadModel""",
)
replace_once(
    "jvm-core/src/main/kotlin/com/choplab/sampler/persistence/ProjectArchiveCodec.kt",
    """    fun read(input: InputStream): SamplerUiState {
        ZipInputStream(BufferedInputStream(input)).use { zip ->""",
    """    fun read(
        input: InputStream,
        maximumResidentPcmBytes: Long = defaultMaximumResidentPcmBytes(),
    ): SamplerUiState {
        require(maximumResidentPcmBytes in 1L..ProjectLimits.MAX_TOTAL_PCM_BYTES) {
            \"プロジェクト常駐メモリ上限が不正です\"
        }
        ZipInputStream(BufferedInputStream(input)).use { zip ->""",
)
replace_once(
    "jvm-core/src/main/kotlin/com/choplab/sampler/persistence/ProjectArchiveCodec.kt",
    """                require(totalPcmBytes <= ProjectLimits.MAX_TOTAL_PCM_BYTES) {
                    \"プロジェクト内の音声データが大きすぎます\"
                }""",
    """                require(totalPcmBytes <= maximumResidentPcmBytes) {
                    \"この端末で展開できるプロジェクト音声容量を超えています\"
                }""",
)
replace_once(
    "jvm-core/src/main/kotlin/com/choplab/sampler/persistence/ProjectArchiveCodec.kt",
    """    private fun collectAudio(state: SamplerUiState): List<PcmAudio> {""",
    """    private fun defaultMaximumResidentPcmBytes(): Long {
        val vmName = System.getProperty(\"java.vm.name\").orEmpty()
        val runtimeName = System.getProperty(\"java.runtime.name\").orEmpty()
        val isAndroid = vmName.contains(\"dalvik\", ignoreCase = true) ||
            runtimeName.contains(\"android\", ignoreCase = true)
        return if (isAndroid) {
            AudioResourceLimits.MAX_MOBILE_PROJECT_PCM_BYTES
        } else {
            AudioResourceLimits.MAX_DESKTOP_PROJECT_PCM_BYTES
        }
    }

    private fun collectAudio(state: SamplerUiState): List<PcmAudio> {""",
)

callable_reference = ".use(ProjectArchiveCodec::read)"
callable_replacement = ".use { input -> ProjectArchiveCodec.read(input) }"
replaced = 0
for path in ROOT.rglob("*.kt"):
    if any(part in {"build", ".gradle"} for part in path.parts):
        continue
    content = path.read_text(encoding="utf-8")
    count = content.count(callable_reference)
    if count:
        path.write_text(content.replace(callable_reference, callable_replacement), encoding="utf-8")
        replaced += count
if replaced < 2:
    raise RuntimeError(f"Expected at least two ProjectArchiveCodec callable references, found {replaced}")

project_budget_test = ROOT / "jvm-core/src/test/kotlin/com/choplab/sampler/persistence/ProjectArchiveResidentBudgetTest.kt"
if project_budget_test.exists():
    raise RuntimeError(f"Test already exists: {project_budget_test}")
project_budget_test.write_text(
    """package com.choplab.sampler.persistence

import com.choplab.sampler.model.PcmAudio
import com.choplab.sampler.model.SamplerUiState
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ProjectArchiveResidentBudgetTest {
    @Test
    fun rejectsBeforeMaterializingPcmBeyondTheRuntimeBudget() {
        val samples = ShortArray(100) { it.toShort() }
        val audio = PcmAudio(id = 7L, name = \"bounded.wav\", samples = samples, sampleRate = 48_000)
        val state = SamplerUiState(
            currentAudio = audio,
            rangeStartFrame = 0,
            rangeEndFrame = samples.size,
        )
        val archive = ByteArrayOutputStream().also { ProjectArchiveCodec.write(state, it) }.toByteArray()

        assertThrows(IllegalArgumentException::class.java) {
            ProjectArchiveCodec.read(
                ByteArrayInputStream(archive),
                maximumResidentPcmBytes = samples.size.toLong() * Short.SIZE_BYTES - 1L,
            )
        }

        val restored = ProjectArchiveCodec.read(
            ByteArrayInputStream(archive),
            maximumResidentPcmBytes = samples.size.toLong() * Short.SIZE_BYTES,
        )
        assertArrayEquals(samples, restored.currentAudio?.samples)
    }
}
""",
    encoding="utf-8",
)

# Persist directory-entry updates after every archive/metadata rename where the host supports it.
replace_once(
    "jvm-core/src/main/kotlin/com/choplab/sampler/persistence/AtomicProjectStore.kt",
    """import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException""",
    """import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException""",
)
replace_once(
    "jvm-core/src/main/kotlin/com/choplab/sampler/persistence/AtomicProjectStore.kt",
    """import java.nio.file.StandardCopyOption
import java.security.MessageDigest""",
    """import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest""",
)
replace_once(
    "jvm-core/src/main/kotlin/com/choplab/sampler/persistence/AtomicProjectStore.kt",
    """        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private data class Generation""",
    """        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
        syncDirectory()
    }

    private fun syncDirectory() {
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { channel ->
                channel.force(true)
            }
        }
    }

    private data class Generation""",
)

print("Applied bounded import/project/autosave hardening patches")
