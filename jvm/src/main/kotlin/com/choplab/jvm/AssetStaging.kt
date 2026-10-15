package com.choplab.jvm

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.*
import java.util.UUID

/** Worker-only publication ownership. The stable lock inode is deliberately never deleted. */
internal class AssetStaging(private val directory: Path, private val monitor: StoreLock) {
    fun <T> publish(action: () -> T): T = synchronized(monitor) {
        check(!monitor.publishing) { "Recursive asset publication" }
        withLock(ifBusy = { throw IOException("Asset publication is active in another process") }) {
            recover()
            monitor.publishing = true
            try { action() } finally { monitor.publishing = false }
        }
    }

    fun recoverIfIdle() = synchronized(monitor) {
        // A second channel closed in this JVM can release a POSIX process lock. Never open it
        // while any store instance sharing this monitor owns publication, including reentrant reads.
        if (!monitor.publishing) withLock(ifBusy = { Unit }) { recover() }
    }

    private fun <T> withLock(ifBusy: () -> T, action: () -> T): T {
        val path = directory.resolve(LOCK_NAME)
        FileChannel.open(path, CREATE, READ, WRITE, NOFOLLOW_LINKS).use { channel ->
            require(Files.isRegularFile(path, NOFOLLOW_LINKS) && channel.size() == 0L) { "Invalid asset publication lock" }
            val lock = try { channel.tryLock() } catch (_: OverlappingFileLockException) { null }
            if (lock == null) return ifBusy()
            lock.use { return action() }
        }
    }

    /** Register before creating payload: an incomplete registration can never own large audio bytes. */
    fun create(): Path {
        check(monitor.publishing)
        val pending = directory.resolve(".pending-v1-${UUID.randomUUID()}")
        require(!Files.exists(pending, NOFOLLOW_LINKS)) { "Asset staging name already exists" }
        val owner = owner(pending)
        var created = false
        try {
            FileChannel.open(owner, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
                created = true
                val record = ByteBuffer.wrap(record(pending))
                while (record.hasRemaining()) channel.write(record)
                channel.force(true)
            }
        } catch (failure: Throwable) {
            if (created) try { Files.deleteIfExists(owner) } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        return pending
    }

    fun discard(pending: Path) {
        check(monitor.publishing)
        removeRegistered(pending, owner(pending))
    }

    /** Protocol metadata is fixed and small; unknown regular files still consume the audio quota. */
    fun countedBytes(path: Path): Long {
        if (OWNER_NAME.matches(path.fileName.toString())) {
            val pending = directory.resolve(path.fileName.toString().removeSuffix(".owner"))
            if (registered(pending, path)) return 0
        }
        return Files.size(path)
    }

    private fun recover() {
        Files.newDirectoryStream(directory) { OWNER_NAME.matches(it.fileName.toString()) }.use { owners ->
            for (owner in owners) {
                val pending = directory.resolve(owner.fileName.toString().removeSuffix(".owner"))
                removeRegistered(pending, owner)
            }
        }
    }

    private fun removeRegistered(pending: Path, owner: Path) {
        if (!registered(pending, owner)) return
        if (Files.exists(pending, NOFOLLOW_LINKS)) {
            // Neither recurse into directories nor unlink symlinks. Legacy/unregistered names
            // have no complete owner record and are intentionally outside this recovery protocol.
            if (!Files.isRegularFile(pending, NOFOLLOW_LINKS)) return
            Files.delete(pending)
        }
        Files.delete(owner)
    }

    private fun registered(pending: Path, owner: Path): Boolean {
        if (!Files.isRegularFile(owner, NOFOLLOW_LINKS)) return false
        val expected = record(pending)
        if (Files.size(owner) != expected.size.toLong()) return false
        // Fixed maximum, even if a non-cooperating writer grows this file after the size check.
        return Files.newInputStream(owner, NOFOLLOW_LINKS).use { input ->
            val actual = ByteArray(expected.size)
            var read = 0
            while (read < actual.size) {
                val count = input.read(actual, read, actual.size - read)
                if (count <= 0) return@use false
                read += count
            }
            actual.contentEquals(expected) && input.read() == -1
        }
    }

    private fun owner(pending: Path) = pending.resolveSibling("${pending.fileName}.owner")
    private fun record(pending: Path) = "ChopLab asset staging v1\n${pending.fileName}\n".toByteArray(Charsets.US_ASCII)

    companion object {
        const val LOCK_NAME = ".asset-publication-v1.lock"
        private val OWNER_NAME = Regex("\\.pending-v1-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.owner")
    }
}
