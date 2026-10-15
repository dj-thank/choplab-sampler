package com.choplab.jvm

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.LockSupport
import kotlin.test.*

class AssetStagingRecoveryTest {
    private val roots = mutableListOf<Path>()
    private fun root() = Files.createTempDirectory("asset-recovery-").also(roots::add)
    @AfterTest fun cleanup() { roots.forEach { it.toFile().deleteRecursively() } }

    @Test fun killedPublisherReleasesItsLeaseAndReclaimsQuotaBeforeRetry() {
        val root = root()
        val bytes = Fixtures.wav()
        val asset = Fixtures.asset(bytes)
        val store = FileAssetStore(root.resolve("assets"), maxStoredBytes = asset.byteCount)
        child(root, "publish").use { child ->
            child.awaitReady()
            val staged = payloads(store.directory).single()
            val partial = Files.readAllBytes(staged)
            assertTrue(partial.isNotEmpty())
            val other = FileAssetStore(store.directory, maxStoredBytes = asset.byteCount)
            assertEquals(partial.size.toLong(), other.storedBytes())
            assertContentEquals(partial, Files.readAllBytes(staged))
            assertFailsWith<IOException> { other.publish(asset, bytes.inputStream()) }
            assertContentEquals(partial, Files.readAllBytes(staged))
            val terminationDeadline = child.kill()
            assertTrue(Files.exists(staged), "An abrupt process death bypasses finally cleanup")
            child.awaitPublicationLockRelease(terminationDeadline)
            // The already-open store also recovers at admission; it need not be reconstructed.
            store.publish(asset, bytes.inputStream())
            assertTrue(store.verified(asset))
            assertContentEquals(bytes, store.openVerified(asset).use { it.readBytes() })
            assertEquals(asset.byteCount, store.storedBytes())
            assertTrue(payloads(store.directory).isEmpty())
            assertEquals(2L, Files.list(store.directory).use { it.count() }, "Only committed audio and the stable lock remain")
        }
    }

    @Test fun activePublisherSurvivesAnotherStoreAndFinishesNormally() {
        val root = root()
        child(root, "publish").use { child ->
            child.awaitReady()
            val store = FileAssetStore(root.resolve("assets"))
            val staged = payloads(store.directory).single()
            val before = Files.readAllBytes(staged)
            repeat(3) { assertEquals(before.size.toLong(), store.storedBytes()) }
            assertContentEquals(before, Files.readAllBytes(staged))
            child.finish()
            val bytes = Fixtures.wav()
            assertTrue(store.verified(Fixtures.asset(bytes)))
            assertTrue(payloads(store.directory).isEmpty())
        }
    }

    @Test fun reentrantSecondInstanceNeverClosesTheOwnersProcessLock() {
        val root = root()
        child(root, "reentrant").use { child ->
            child.awaitReady()
            // The child opened another store and queried quota from inside its active input.
            // If either operation opened/closed a second channel, POSIX would drop its lock.
            val store = FileAssetStore(root.resolve("assets"))
            assertFailsWith<IOException> { store.publish(Fixtures.asset(Fixtures.wav()), Fixtures.wav().inputStream()) }
            assertEquals(1, payloads(store.directory).size)
            child.finish()
            assertTrue(store.verified(Fixtures.asset(Fixtures.wav())))
        }
    }

    @Test fun activeAdoptionAndRetainingAdoptionHoldTheSameCrossProcessLock() {
        for (mode in listOf("adopt", "retain")) {
            val root = root()
            child(root, mode).use { child ->
                child.awaitReady()
                val store = FileAssetStore(root.resolve("assets"))
                val bytes = Fixtures.wav()
                assertFailsWith<IOException> { store.publish(Fixtures.asset(bytes), bytes.inputStream()) }
                child.finish()
                val target = store.directory.resolve("${sha256(bytes)}.flac")
                assertContentEquals(bytes, Files.readAllBytes(target))
                assertEquals(mode == "retain", Files.exists(root.resolve("original")))
            }
        }
    }

    @Test fun onlyCompleteRegisteredRegularStagesAreRecovered() {
        val root = root()
        val store = FileAssetStore(root.resolve("assets"))
        val bytes = Fixtures.wav()
        val asset = Fixtures.asset(bytes)
        store.publish(asset, bytes.inputStream())
        val legacy = Files.write(store.directory.resolve(".pending-${UUID.randomUUID()}"), byteArrayOf(1, 2))
        val unknown = Files.write(store.directory.resolve(".pending-v1-${UUID.randomUUID()}"), byteArrayOf(3))
        val malformed = registeredStage(store.directory)
        Files.writeString(owner(malformed), "unknown\n")
        val oversized = registeredStage(store.directory)
        Files.write(owner(oversized), ByteArray(1_000_000))
        val nested = registeredStage(store.directory)
        Files.delete(nested); Files.createDirectory(nested)
        Files.write(nested.resolve("keep"), byteArrayOf(9))
        val orphan = registeredStage(store.directory)
        val empty = registeredStage(store.directory)
        Files.delete(empty)
        val preserved = listOf(legacy, unknown, malformed, owner(malformed), oversized, owner(oversized), owner(nested), nested.resolve("keep"))
            .associateWith { Files.readAllBytes(it) }
        FileAssetStore(store.directory)
        preserved.forEach { (path, content) -> assertContentEquals(content, Files.readAllBytes(path)) }
        assertTrue(Files.isDirectory(nested, NOFOLLOW_LINKS))
        assertFalse(Files.exists(orphan)); assertFalse(Files.exists(owner(orphan)))
        assertFalse(Files.exists(owner(empty)))
        assertTrue(store.verified(asset))
        // Unknown bytes still count and cannot be erased just to admit a new import.
        val small = FileAssetStore(store.directory, maxStoredBytes = asset.byteCount)
        val other = Fixtures.wav(samples = shortArrayOf(7, 8, 9, 10))
        assertFailsWith<IllegalArgumentException> { small.publish(Fixtures.asset(other), other.inputStream()) }
        preserved.forEach { (path, content) -> assertContentEquals(content, Files.readAllBytes(path)) }
    }

    @Test fun registeredSymlinksAndSymlinkedOwnersAreNeverUnlinkedOrFollowed() {
        // Windows requires a separately enabled privilege for creation of symlinks. Other protection
        // cases and the real process-lock cases run there too; this case runs where links are available.
        org.junit.Assume.assumeFalse("Symlink creation needs an explicitly enabled Windows privilege",
            System.getProperty("os.name").startsWith("Windows"))
        val root = root()
        val store = FileAssetStore(root.resolve("assets"))
        val outside = Files.write(root.resolve("keep"), byteArrayOf(1, 2, 3))
        val linkedPayload = registeredStage(store.directory)
        Files.delete(linkedPayload); Files.createSymbolicLink(linkedPayload, outside)
        val linkedOwner = registeredStage(store.directory)
        val marker = Files.move(owner(linkedOwner), root.resolve("marker"))
        Files.createSymbolicLink(owner(linkedOwner), marker)
        FileAssetStore(store.directory)
        assertTrue(Files.isSymbolicLink(linkedPayload))
        assertTrue(Files.exists(owner(linkedPayload)))
        assertTrue(Files.isSymbolicLink(owner(linkedOwner)))
        assertTrue(Files.exists(linkedOwner))
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(outside))
        val locked = Files.createDirectory(root.resolve("linked-lock-store"))
        val orphan = registeredStage(locked)
        val linkedLock = Files.createSymbolicLink(locked.resolve(AssetStaging.LOCK_NAME), outside)
        assertFailsWith<IOException> { FileAssetStore(locked) }
        assertTrue(Files.isSymbolicLink(linkedLock)); assertTrue(Files.exists(orphan))
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(outside))
    }

    @Test fun unknownLockContentRejectsRecoveryWithoutChangingAnyBytes() {
        val root = root()
        val directory = Files.createDirectory(root.resolve("assets"))
        val pending = registeredStage(directory)
        val lock = Files.writeString(directory.resolve(AssetStaging.LOCK_NAME), "keep")
        assertFailsWith<IllegalArgumentException> { FileAssetStore(directory) }
        assertEquals("keep", Files.readString(lock))
        assertTrue(Files.exists(pending)); assertTrue(Files.exists(owner(pending)))
    }

    @Test fun cancellationBadHashIoFailureAndQuotaRefusalLeaveRetryableStore() {
        val root = root()
        val bytes = Fixtures.wav()
        val asset = Fixtures.asset(bytes)
        val store = FileAssetStore(root.resolve("assets"), maxStoredBytes = asset.byteCount)
        assertFailsWith<IllegalArgumentException> { store.publish(asset, bytes.inputStream()) { true } }
        assertFailsWith<IllegalArgumentException> { store.publish(asset.copy(hash = "f".repeat(64)), bytes.inputStream()) }
        assertFailsWith<IOException> { store.publish(asset, object : InputStream() { override fun read(): Int = throw IOException("fixture") }) }
        val tooSmall = FileAssetStore(store.directory, maxStoredBytes = 1)
        assertFailsWith<IllegalArgumentException> { tooSmall.publish(asset, bytes.inputStream()) }
        assertEquals(listOf(AssetStaging.LOCK_NAME), Files.list(store.directory).use { it.map { path -> path.fileName.toString() }.toList() })
        store.publish(asset, bytes.inputStream())
        assertTrue(store.verified(asset))
        assertEquals(asset.byteCount, store.storedBytes())
    }

    @Test fun retainedOriginalSurvivesQuotaFailureAndSuccessfulRetry() {
        val root = root()
        val bytes = Fixtures.wav()
        val asset = Fixtures.asset(bytes)
        val original = Files.write(root.resolve("original"), bytes)
        val small = FileAssetStore(root.resolve("assets"), maxStoredBytes = 1)
        assertFailsWith<IllegalArgumentException> { small.adoptRetaining(asset, original) }
        assertContentEquals(bytes, Files.readAllBytes(original))
        val store = FileAssetStore(small.directory, maxStoredBytes = asset.byteCount)
        store.adoptRetaining(asset, original)
        assertContentEquals(bytes, Files.readAllBytes(original))
        assertTrue(store.verified(asset))
    }

    @Test fun cancelledAdoptionConsumesOnlyItsTransferredFileAndCanRetry() {
        val root = root()
        val bytes = Fixtures.wav()
        val asset = Fixtures.asset(bytes)
        val store = FileAssetStore(root.resolve("assets"), maxStoredBytes = asset.byteCount)
        val transfer = Files.write(root.resolve("transfer"), bytes)
        assertFailsWith<IllegalArgumentException> { store.adopt(asset, transfer) { true } }
        assertFalse(Files.exists(transfer), "adopt's existing transfer-of-ownership contract is retained")
        assertEquals(0L, store.storedBytes())
        Files.write(transfer, bytes)
        store.adopt(asset, transfer)
        assertFalse(Files.exists(transfer))
        assertTrue(store.verified(asset))
    }

    private fun registeredStage(directory: Path): Path {
        val pending = directory.resolve(".pending-v1-${UUID.randomUUID()}")
        Files.writeString(owner(pending), "ChopLab asset staging v1\n${pending.fileName}\n")
        return Files.write(pending, byteArrayOf(4, 5))
    }
    private fun owner(pending: Path) = pending.resolveSibling("${pending.fileName}.owner")
    private fun payloads(directory: Path) = Files.list(directory).use { paths ->
        paths.filter { it.fileName.toString().startsWith(".pending-v1-") && !it.fileName.toString().endsWith(".owner") }.toList()
    }

    private fun child(root: Path, mode: String): Child {
        // Gradle workers do not necessarily use URLClassLoader (nor put the test runtime in
        // java.class.path). Bind each needed module/library to the actual classes under test.
        val urls = listOf(AssetStagingProcessFixture::class.java, FileAssetStore::class.java,
            com.choplab.core.model.Asset::class.java, com.choplab.engine.EngineFormat::class.java,
            Unit::class.java, kotlinx.coroutines.Dispatchers::class.java, kotlinx.serialization.Serializable::class.java)
            .map { Paths.get(it.protectionDomain.codeSource.location.toURI()).toString() }.distinct()
        val executable = if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java"
        val process = ProcessBuilder(Paths.get(System.getProperty("java.home"), "bin", executable).toString(),
            "-cp", urls.joinToString(java.io.File.pathSeparator), AssetStagingProcessFixture::class.java.name, root.toString(), mode)
            .redirectErrorStream(true).redirectOutput(root.resolve("child.log").toFile()).start()
        return Child(process, root)
    }

    private class Child(private val process: Process, private val root: Path) : AutoCloseable {
        fun awaitReady() {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!Files.exists(root.resolve("ready")) && process.isAlive && System.nanoTime() < deadline) Thread.sleep(10)
            assertTrue(Files.exists(root.resolve("ready")), Files.readString(root.resolve("child.log")))
            assertTrue(process.isAlive)
        }
        fun kill(): Long {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            process.destroyForcibly()
            assertTrue(process.waitFor(maxOf(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS), "Child did not terminate")
            return deadline
        }
        fun awaitPublicationLockRelease(deadline: Long) {
            assertFalse(process.isAlive)
            // Windows may signal process death before releasing its file locks:
            // https://learn.microsoft.com/en-us/windows/win32/api/fileapi/nf-fileapi-lockfile
            // Observe ownership on the same stable inode, within the original termination budget.
            FileChannel.open(root.resolve("assets").resolve(AssetStaging.LOCK_NAME), READ, WRITE, NOFOLLOW_LINKS).use { channel ->
                while (true) {
                    val remaining = deadline - System.nanoTime()
                    assertTrue(remaining > 0, "Child exited but its publication lock is still held")
                    val lock = channel.tryLock()
                    if (lock != null) { lock.release(); return }
                    LockSupport.parkNanos(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(1)))
                }
            }
        }
        fun finish() {
            Files.writeString(root.resolve("release"), "release")
            assertTrue(process.waitFor(15, TimeUnit.SECONDS), "Child did not finish")
            assertEquals(0, process.exitValue(), Files.readString(root.resolve("child.log")))
        }
        override fun close() { if (process.isAlive) kill() }
    }
}

/** Separate JVM fixture: it can be killed while production publication owns an OS file lock. */
object AssetStagingProcessFixture {
    @JvmStatic fun main(arguments: Array<String>) {
        val root = Paths.get(arguments[0]); val mode = arguments[1]
        val bytes = Fixtures.wav(); val asset = Fixtures.asset(bytes)
        fun awaitRelease() {
            Files.writeString(root.resolve("ready"), "ready")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            while (!Files.exists(root.resolve("release"))) {
                check(System.nanoTime() < deadline) { "Fixture release timed out" }
                Thread.sleep(10)
            }
        }
        if (mode == "adopt" || mode == "retain") {
            val decoder = object : OriginalAudioDecoder {
                override fun inspect(path: Path, hash: String, cancelled: () -> Boolean): WavInfo {
                    awaitRelease()
                    return WavCodec.inspect(bytes.inputStream())
                }
                override fun decode(path: Path, hash: String, cancelled: () -> Boolean): WavAudio = error("Unused")
            }
            val store = FileAssetStore(root.resolve("assets"), decoder = decoder)
            val original = Files.write(root.resolve("original"), bytes)
            if (mode == "adopt") store.adopt(asset.copy(extension = "flac"), original)
            else store.adoptRetaining(asset.copy(extension = "flac"), original)
        } else {
            val store = FileAssetStore(root.resolve("assets"), maxStoredBytes = asset.byteCount)
            val input = object : ByteArrayInputStream(bytes) {
                private var reads = 0
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    if (++reads == 2) {
                        if (mode == "reentrant") {
                            val other = FileAssetStore(store.directory, maxStoredBytes = asset.byteCount)
                            check(other.storedBytes() == 16L)
                        }
                        awaitRelease()
                    }
                    return super.read(buffer, offset, minOf(length, 16))
                }
            }
            store.publish(asset, input)
        }
    }
}
