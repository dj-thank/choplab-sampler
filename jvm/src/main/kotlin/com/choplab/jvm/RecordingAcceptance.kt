package com.choplab.jvm

import com.choplab.core.model.Project
import com.choplab.core.persistence.ProjectJson
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Private write-ahead receipt. Asset existence alone never proves that a recording was accepted. */
internal object RecordingAcceptance {
    private fun receipt(file: Path) = file.resolveSibling("acceptance-${file.fileName}.receipt")
    fun prepare(file: Path, project: Project, revision: Long) {
        val take = TakeFile.Pending.inspect(file)
        require(project.assets.any { it.hash == take.hash })
        val bytes = listOf("CHOPLAB-ACCEPTED-1", file.fileName.toString(), take.hash,
            sha256(ProjectJson.encode(project)), revision.toString()).joinToString("\n").toByteArray(Charsets.UTF_8)
        val target = receipt(file)
        require(!Files.isSymbolicLink(target))
        val temporary = Files.createTempFile(file.parent, ".acceptance-", ".pending")
        try {
            FileOutputStream(temporary.toFile()).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temporary) }
    }
    fun matches(file: Path, persistedProject: Project, revision: Long): Boolean = try {
        val target = receipt(file)
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) !in 1..512) false
        else {
            val fields = Files.readString(target).split('\n')
            fields.size == 5 && fields[0] == "CHOPLAB-ACCEPTED-1" && fields[1] == file.fileName.toString() &&
                fields[2] == TakeFile.Pending.inspect(file).hash && fields[3] == sha256(ProjectJson.encode(persistedProject)) && fields[4].toLongOrNull() == revision
        }
    } catch (_: Exception) { false }
    fun remove(file: Path) { Files.deleteIfExists(receipt(file)) }
}
