package com.choplab.desktop.next

import com.choplab.core.separation.SeparationMemoryReceipt
import com.choplab.core.separation.SeparationMemorySource
import com.choplab.jvm.separation.SeparationMemory
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinBase
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Worker-only, fresh physical RAM admission. Model activations are separate from the shared PCM ledger. */
class FourStemMemoryProbe internal constructor(
    private val osName: String,
    private val command: (List<String>) -> String?,
    private val meminfo: () -> String?,
    private val windows: () -> WindowsPhysicalMemory?,
    private val nanoTime: () -> Long,
    private val epochMillis: () -> Long,
) {
    constructor() : this(System.getProperty("os.name", ""), MemoryProbeSystem::command,
        MemoryProbeSystem::meminfo, MemoryProbeSystem::windows, System::nanoTime, System::currentTimeMillis)

    /** Pass as FourStemService's memoryProbe. No cached success is returned after a failed or slow observation. */
    fun sample(): SeparationMemory {
        val started = nanoTime()
        return try {
            if (Thread.currentThread().isInterrupted) return unknown()
            val value = when {
                osName.startsWith("Mac", ignoreCase = true) -> mac()
                osName.startsWith("Windows", ignoreCase = true) -> windows()?.let {
                    require(it.loadPercent in 0..100)
                    Observation(SeparationMemorySource.WINDOWS_GLOBAL_MEMORY_STATUS, it.totalBytes, it.availableBytes, it.loadPercent >= 90)
                }
                osName.startsWith("Linux", ignoreCase = true) -> linux()
                else -> null
            } ?: return unknown()
            val elapsed = nanoTime() - started
            if (elapsed !in 0..MAX_CAPTURE_NANOS || Thread.currentThread().isInterrupted) return unknown()
            val receipt = SeparationMemoryReceipt(value.source, value.total, value.available, value.low, epochMillis())
            SeparationMemory(value.total, value.available, value.low, receipt)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            unknown()
        } catch (_: Exception) { unknown() }
        catch (_: LinkageError) { unknown() }
    }

    private fun mac(): Observation? {
        val statistics = command(MemoryProbeSystem.VM_STAT) ?: return null
        val total = unsigned(command(MemoryProbeSystem.TOTAL) ?: return null)
        // The userspace sysctl maps XNU's internal levels to NOTE_MEMORYSTATUS_PRESSURE_* flags.
        // https://github.com/apple-oss-distributions/xnu/blob/main/bsd/kern/kern_memorystatus_notify.c
        // https://github.com/apple-oss-distributions/xnu/blob/main/bsd/sys/event_private.h
        val lowMemory = when (unsigned(command(MemoryProbeSystem.PRESSURE) ?: return null)) {
            1L -> false // NORMAL
            2L, 4L -> true // WARN or CRITICAL
            else -> return null
        }
        val lines = checkedLines(statistics)
        val header = lines.single { it.startsWith("Mach Virtual Memory Statistics:") }
        val pageSize = Regex("Mach Virtual Memory Statistics: \\(page size of ([0-9]+) bytes\\)")
            .matchEntire(header)?.groupValues?.get(1)?.toLongOrNull() ?: return null
        require(pageSize in 4096L..65536L && (pageSize and (pageSize - 1)) == 0L)
        val free = macPages(lines, "Pages free")
        val fileBacked = macPages(lines, "File-backed pages")
        // XNU's free + file_backed approximation. Speculative pages are already included in free_count.
        // Do not add inactive/active/purgeable/compressed/swap pages or treat immediate-free alone as available.
        // https://github.com/apple-oss-distributions/xnu/blob/main/doc/vm/memorystatus.md
        val available = Math.multiplyExact(Math.addExact(free, fileBacked), pageSize)
        return Observation(SeparationMemorySource.MAC_FREE_AND_FILE_BACKED, total, available, lowMemory)
    }

    private fun linux(): Observation? {
        val lines = checkedLines(meminfo() ?: return null)
        // The kernel accounts for reclaimable pages and low watermarks; MemFree alone is not equivalent.
        // https://docs.kernel.org/filesystems/proc.html#meminfo
        return Observation(SeparationMemorySource.LINUX_MEM_AVAILABLE,
            linuxBytes(lines, "MemTotal"), linuxBytes(lines, "MemAvailable"), false)
    }

    private fun macPages(lines: List<String>, key: String): Long {
        val line = lines.single { it.startsWith("$key:") }
        val number = Regex("${Regex.escape(key)}:\\s*([0-9]+)\\.?").matchEntire(line)?.groupValues?.get(1)
        return requireNotNull(number?.toLongOrNull())
    }
    private fun linuxBytes(lines: List<String>, key: String): Long {
        val line = lines.single { it.startsWith("$key:") }
        val number = Regex("${Regex.escape(key)}:\\s*([0-9]+)\\s+kB").matchEntire(line)?.groupValues?.get(1)
        return Math.multiplyExact(requireNotNull(number?.toLongOrNull()), 1024)
    }
    private fun checkedLines(text: String): List<String> {
        require(text.length <= MemoryProbeSystem.MAX_BYTES)
        return text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    }
    private fun unsigned(text: String): Long {
        val value = text.trim()
        require(value.length <= 20 && value.matches(Regex("[0-9]+")))
        return requireNotNull(value.toLongOrNull())
    }
    private fun unknown() = SeparationMemory(0, 0, false)
    private data class Observation(val source: SeparationMemorySource, val total: Long, val available: Long, val low: Boolean)
    companion object { internal const val MAX_CAPTURE_NANOS = 3_000_000_000L }
}

internal data class WindowsPhysicalMemory(val totalBytes: Long, val availableBytes: Long, val loadPercent: Int)

internal object MemoryProbeSystem {
    const val MAX_BYTES = 16 * 1024
    const val PROCESS_MILLIS = 750L
    val VM_STAT = listOf("/usr/bin/vm_stat")
    val TOTAL = listOf("/usr/sbin/sysctl", "-n", "hw.memsize")
    val PRESSURE = listOf("/usr/sbin/sysctl", "-n", "kern.memorystatus_vm_pressure_level")

    fun command(arguments: List<String>): String? = command(arguments) { command ->
        ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).apply {
            environment()["LC_ALL"] = "C"
            environment()["LANG"] = "C"
        }.start()
    }

    internal fun command(arguments: List<String>, start: (List<String>) -> Process): String? {
        require(arguments == VM_STAT || arguments == TOTAL || arguments == PRESSURE)
        val process = start(arguments)
        try {
            process.outputStream.close()
            val input = process.inputStream
            val bytes = ByteArray(MAX_BYTES + 1)
            var count = 0
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PROCESS_MILLIS)
            while (process.isAlive) {
                val ready = input.available()
                if (ready > 0) {
                    val read = input.read(bytes, count, minOf(ready, bytes.size - count))
                    if (read > 0) count += read
                    if (count > MAX_BYTES) return null
                }
                if (System.nanoTime() >= deadline) return null
                process.waitFor(10, TimeUnit.MILLISECONDS)
            }
            if (process.exitValue() != 0) return null
            while (count <= MAX_BYTES) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                count += read
            }
            return if (count <= MAX_BYTES) ascii(bytes, count) else null
        } finally {
            if (process.isAlive) process.destroyForcibly()
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
            runCatching { process.outputStream.close() }
        }
    }

    fun meminfo(): String? = Files.newInputStream(Path.of("/proc/meminfo")).use(::boundedText)
    internal fun boundedText(input: InputStream): String? {
        val bytes = input.readNBytes(MAX_BYTES + 1)
        return if (bytes.size <= MAX_BYTES) ascii(bytes, bytes.size) else null
    }
    private fun ascii(bytes: ByteArray, count: Int): String? {
        if ((0 until count).any { bytes[it].toInt() !in 9..13 && bytes[it].toInt() !in 32..126 }) return null
        return String(bytes, 0, count, Charsets.US_ASCII)
    }

    fun windows(): WindowsPhysicalMemory? {
        val status = WinBase.MEMORYSTATUSEX()
        if (!Kernel32.INSTANCE.GlobalMemoryStatusEx(status)) return null
        // ullAvailPhys includes all NUMA nodes. These volatile values cannot reserve a later allocation.
        // https://learn.microsoft.com/windows/win32/api/sysinfoapi/nf-sysinfoapi-globalmemorystatusex
        return WindowsPhysicalMemory(status.ullTotalPhys.toLong(), status.ullAvailPhys.toLong(), status.dwMemoryLoad.toInt())
    }
}
