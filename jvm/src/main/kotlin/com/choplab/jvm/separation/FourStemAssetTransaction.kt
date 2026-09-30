package com.choplab.jvm.separation

import com.choplab.core.model.Asset
import com.choplab.core.separation.SeparationProblem
import com.choplab.jvm.*
import java.nio.file.*
import java.security.MessageDigest

internal data class StagedStem(val asset: Asset, val file: Path)

/**
 * Uses the store's own lock, so readers/publications see all four files or the previous store contents.
 * Rollback removes only new hashes created here. A crash can leave unreferenced immutable assets, never a partial Project.
 */
internal fun FileAssetStore.adoptFourStems(stems: List<StagedStem>, check: () -> Unit) = synchronized(StoreLocks.forPath(directory)) {
    require(stems.size == 4)
    val unique = stems.distinctBy { it.asset.hash }
    val created = mutableListOf<Path>()
    try {
        for ((asset, file) in stems) {
            check()
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) != asset.byteCount)
                throw SeparationException(SeparationProblem.INVALID_OUTPUT)
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(file).use { input ->
                val bytes = ByteArray(8192)
                while (true) {
                    check(); val count = input.read(bytes); if (count < 0) break
                    if (count == 0) throw SeparationException(SeparationProblem.INVALID_OUTPUT)
                    digest.update(bytes, 0, count)
                }
            }
            val info = Files.newInputStream(file).use(WavCodec::inspect)
            if (digest.digest().hex() != asset.hash || info.frames != asset.frames || info.sampleRate != asset.sampleRate ||
                info.channels != 2 || !info.floatingPoint || info.bits != 32)
                throw SeparationException(SeparationProblem.INVALID_OUTPUT)
        }
        val absent = unique.filter { !Files.exists(directory.resolve("${it.asset.hash}.${it.asset.extension}"), LinkOption.NOFOLLOW_LINKS) }
        if (storedBytes() + absent.sumOf { it.asset.byteCount } > maxStoredBytes)
            throw SeparationException(SeparationProblem.NO_SPACE)
        for (stem in unique) {
            check()
            val target = directory.resolve("${stem.asset.hash}.${stem.asset.extension}")
            val existed = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
            if (existed && !verified(stem.asset)) throw SeparationException(SeparationProblem.INVALID_OUTPUT)
            adopt(stem.asset, stem.file) { check(); false }
            if (!existed) created.add(target)
        }
        check()
    } catch (failure: Throwable) {
        for (path in created) try { Files.deleteIfExists(path) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
        throw failure
    } finally {
        for (stem in stems) Files.deleteIfExists(stem.file)
    }
}
