package com.choplab.desktop.persistence

import com.choplab.sampler.model.SamplerUiState
import com.choplab.sampler.persistence.ProjectArchiveCodec
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Manual project-file boundary for Windows.
 *
 * The shared JVM codec owns the archive schema and validation. This adapter only
 * adds an atomic sibling write so an interrupted save cannot replace a valid file
 * with partial bytes.
 */
object DesktopProjectFiles {
    fun save(target: File, state: SamplerUiState): File {
        val output = withProjectExtension(target)
        val parent = output.absoluteFile.parentFile ?: error("保存先フォルダーを取得できません")
        require(parent.isDirectory || parent.mkdirs()) { "保存先フォルダーを作成できません" }

        val temporary = Files.createTempFile(parent.toPath(), ".${output.name}.", ".tmp").toFile()
        try {
            temporary.outputStream().buffered().use { stream -> ProjectArchiveCodec.write(state, stream) }
            temporary.inputStream().buffered().use(ProjectArchiveCodec::read)
            moveReplacing(temporary, output)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        return output
    }

    fun load(source: File): SamplerUiState {
        require(source.isFile) { "制作ファイルが見つかりません" }
        return source.inputStream().buffered().use(ProjectArchiveCodec::read)
    }

    fun withProjectExtension(file: File): File =
        if (file.extension.equals("choplab", ignoreCase = true)) {
            file
        } else {
            File(file.parentFile, "${file.nameWithoutExtension}.choplab")
        }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
