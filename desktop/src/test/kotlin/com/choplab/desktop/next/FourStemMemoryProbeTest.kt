package com.choplab.desktop.next

import com.choplab.core.separation.SeparationMemorySource
import com.choplab.core.separation.SeparationProblem
import com.choplab.jvm.separation.FourStemSpec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlin.test.*

class FourStemMemoryProbeTest {
    @Test fun macUsesRealPageSizeFreePlusFileBackedAndPressureWithoutCountingSpeculativeTwice() {
        val f = Fixture("Mac OS X")
        val first = f.probe.sample()
        assertEquals((8192L + 196608) * 16384, first.availableBytes)
        assertTrue(8192L * 16384 < FourStemSpec.REQUIRED_AVAILABLE_RAM)
        assertNull(first.refusal(), "Immediate free alone would incorrectly refuse this fixture")
        val receipt = assertNotNull(first.receipt)
        assertEquals(SeparationMemorySource.MAC_FREE_AND_FILE_BACKED, receipt.source)
        assertTrue(receipt.source.estimated)
        assertEquals(16 * GIB, receipt.totalBytes)
        assertEquals(f.epoch, receipt.measuredAtEpochMillis)
        assertEquals(listOf(MemoryProbeSystem.VM_STAT, MemoryProbeSystem.TOTAL, MemoryProbeSystem.PRESSURE), f.commands)
        assertEquals(listOf("/usr/sbin/sysctl", "-n", "kern.memorystatus_vm_pressure_level"), MemoryProbeSystem.PRESSURE)
        // The userspace sysctl returns dispatch flags, not XNU's internal 0/1/2/3 levels.
        for (pressure in listOf("2", "4")) {
            f.pressure = pressure
            assertEquals(SeparationProblem.LOW_MEMORY, f.probe.sample().refusal())
        }
        f.pressure = "1"
        f.mac = mac(4096, 32768, 393216)
        assertEquals((32768L + 393216) * 4096, f.probe.sample().availableBytes)
    }

    @Test fun macRejectsUnknownMalformedDuplicateAndOverflowValuesInsteadOfReusingOldSuccess() {
        val invalid = listOf(
            "", mac().replace("page size of 16384", "page size of 3000"),
            mac().replace("Pages free: 8192.", "Pages free: -1."),
            mac().replace("File-backed pages: 196608.", "File-backed pages: 1.5."),
            mac() + "\nPages free: 9.", mac().replace("File-backed pages", "Other pages"),
            mac().replace("8192.", "9223372036854775807."),
            "x".repeat(MemoryProbeSystem.MAX_BYTES + 1),
        )
        for (text in invalid) {
            val f = Fixture("Mac OS X")
            assertNull(f.probe.sample().refusal())
            f.mac = text
            assertUnknown(f.probe)
        }
        for (text in listOf(null, "", "-1", "0", "18446744073709551615", "17179869184\n1")) {
            val f = Fixture("Mac OS X"); f.total = text; assertUnknown(f.probe)
        }
        for (text in listOf(null, "", "-1", "normal", "0", "3", "8", "86")) {
            val f = Fixture("Mac OS X"); f.pressure = text; assertUnknown(f.probe)
        }
    }

    @Test fun linuxRequiresKernelMemAvailableAndKeepsTheExistingAdmissionThreshold() {
        val f = Fixture("Linux")
        f.linux = "MemTotal: 4194304 kB\nMemAvailable: 1572864 kB\nMemFree: 1 kB\nCached: 2000000 kB\n"
        val exact = f.probe.sample()
        assertNull(exact.refusal()); assertEquals(FourStemSpec.REQUIRED_AVAILABLE_RAM, exact.availableBytes)
        assertEquals(SeparationMemorySource.LINUX_MEM_AVAILABLE, exact.receipt!!.source)
        assertTrue(exact.receipt!!.source.estimated)
        assertTrue(f.commands.isEmpty())
        f.linux = f.linux!!.replace("1572864", "1572863")
        assertEquals(SeparationProblem.LOW_MEMORY, f.probe.sample().refusal())
        for (text in listOf(null, "MemTotal: 4194304 kB\nMemFree: 3000000 kB", "MemTotal: 1 MB\nMemAvailable: 1 kB",
            "MemTotal: 4194304 kB\nMemAvailable: 2 kB\nMemAvailable: 3 kB",
            "MemTotal: 4194304 kB\nMemAvailable: -1 kB", "MemTotal: 10 kB\nMemAvailable: 11 kB",
            "MemTotal: 9223372036854775807 kB\nMemAvailable: 2 kB")) {
            f.linux = text; assertUnknown(f.probe)
        }
    }

    @Test fun windowsUsesPhysicalMemoryIncludingAllNumaNodesAndRefusesApiFailureOrHighLoad() {
        val f = Fixture("Windows 11")
        f.win = WindowsPhysicalMemory(32 * GIB, 15 * GIB, 53)
        val sample = f.probe.sample()
        assertEquals(15 * GIB, sample.availableBytes); assertNull(sample.refusal())
        assertEquals(SeparationMemorySource.WINDOWS_GLOBAL_MEMORY_STATUS, sample.receipt!!.source)
        assertFalse(sample.receipt!!.source.estimated)
        assertFalse(SeparationMemorySource.ANDROID_ACTIVITY_MANAGER.estimated)
        assertTrue(f.commands.isEmpty())
        f.win = f.win!!.copy(loadPercent = 90)
        assertEquals(SeparationProblem.LOW_MEMORY, f.probe.sample().refusal())
        for (value in listOf(null, WindowsPhysicalMemory(0, 0, 0), WindowsPhysicalMemory(1, 2, 0),
            WindowsPhysicalMemory(-1, 1, 1), WindowsPhysicalMemory(32 * GIB, 15 * GIB, 101))) {
            f.win = value; assertUnknown(f.probe)
        }
    }

    @Test fun everyCallIsFreshAndSlowUnknownOrUnavailableMeasurementsHaveNoSuccessReceipt() {
        val f = Fixture("Windows 11")
        val first = f.probe.sample()
        f.epoch += 50
        f.win = f.win!!.copy(availableBytes = 3 * GIB)
        val second = f.probe.sample()
        assertNotEquals(first.availableBytes, second.availableBytes)
        assertEquals(f.epoch, second.receipt!!.measuredAtEpochMillis)
        f.elapsed = FourStemMemoryProbe.MAX_CAPTURE_NANOS + 1
        assertUnknown(f.probe)
        f.elapsed = -1
        assertUnknown(f.probe)
        val unsupported = Fixture("Unknown OS")
        assertUnknown(unsupported.probe); assertTrue(unsupported.commands.isEmpty()); assertEquals(0, unsupported.windowsCalls)
        val throws = FourStemMemoryProbe("Mac OS X", { throw IOException("unavailable") }, { null }, { null }, { 0 }, { 1 })
        assertUnknown(throws)
        val missingLibrary = FourStemMemoryProbe("Windows", { null }, { null }, { throw UnsatisfiedLinkError() }, { 0 }, { 1 })
        assertUnknown(missingLibrary)
    }

    @Test fun fixedCommandReaderBoundsBytesExitTimeAndCleanupWithoutShellOrTemporaryFiles() {
        val exited = FakeProcess("123\n".toByteArray())
        assertEquals("123\n", MemoryProbeSystem.command(MemoryProbeSystem.TOTAL) { arguments ->
            assertEquals(listOf("/usr/sbin/sysctl", "-n", "hw.memsize"), arguments); exited
        })
        assertTrue(exited.inputClosed); assertTrue(exited.outputClosed)
        assertNull(MemoryProbeSystem.command(MemoryProbeSystem.VM_STAT) { FakeProcess(ByteArray(MemoryProbeSystem.MAX_BYTES + 1) { 65 }) })
        assertNull(MemoryProbeSystem.command(MemoryProbeSystem.VM_STAT) { FakeProcess("denied".toByteArray(), code = 1) })
        val running = FakeProcess(byteArrayOf(), alive = true)
        assertNull(MemoryProbeSystem.command(MemoryProbeSystem.VM_STAT) { running })
        assertTrue(running.destroyed); assertTrue(running.inputClosed); assertTrue(running.outputClosed)
        assertFailsWith<IllegalArgumentException> { MemoryProbeSystem.command(listOf("sh", "-c", "unused")) { error("must not launch") } }
        assertNull(MemoryProbeSystem.boundedText(ByteArrayInputStream(ByteArray(MemoryProbeSystem.MAX_BYTES + 1))))
        assertNull(MemoryProbeSystem.boundedText(ByteArrayInputStream(byteArrayOf(-1))))
        assertEquals("MemTotal: 100 kB\n", MemoryProbeSystem.boundedText(ByteArrayInputStream("MemTotal: 100 kB\n".toByteArray())))
    }

    private class Fixture(os: String) {
        var mac: String? = mac()
        var total: String? = (16 * GIB).toString()
        var pressure: String? = "1"
        var linux: String? = "MemTotal: 4194304 kB\nMemAvailable: 2097152 kB\n"
        var win: WindowsPhysicalMemory? = WindowsPhysicalMemory(32 * GIB, 16 * GIB, 50)
        var epoch = 1_800_000_000_000L
        var elapsed = 1_000_000L
        var ticks = 0
        var windowsCalls = 0
        val commands = mutableListOf<List<String>>()
        val probe = FourStemMemoryProbe(os, { command ->
            commands += command
            when (command) { MemoryProbeSystem.VM_STAT -> mac; MemoryProbeSystem.TOTAL -> total; MemoryProbeSystem.PRESSURE -> pressure; else -> error("unexpected command") }
        }, { linux }, { windowsCalls++; win }, { if (ticks++ % 2 == 0) 0 else elapsed }, { epoch })
    }
    private class FakeProcess(bytes: ByteArray, private val code: Int = 0, private var alive: Boolean = false) : Process() {
        var destroyed = false; var inputClosed = false; var outputClosed = false
        private val input = object : ByteArrayInputStream(bytes) { override fun close() { inputClosed = true; super.close() } }
        private val output = object : ByteArrayOutputStream() { override fun close() { outputClosed = true; super.close() } }
        override fun getInputStream(): InputStream = input
        override fun getErrorStream(): InputStream = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream(): OutputStream = output
        override fun isAlive() = alive
        override fun exitValue() = code
        override fun waitFor() = code
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean { if (alive) Thread.sleep(unit.toMillis(timeout)); return !alive }
        override fun destroy() { destroyed = true; alive = false }
        override fun destroyForcibly(): Process { destroy(); return this }
    }
    companion object {
        private const val GIB = 1024L * 1024 * 1024
        private fun mac(pageSize: Int = 16384, free: Long = 8192, fileBacked: Long = 196608) = """
            Mach Virtual Memory Statistics: (page size of $pageSize bytes)
            Pages free: $free.
            Pages active: 600000.
            Pages inactive: 400000.
            Pages speculative: 8192.
            Pages purgeable: 20000.
            File-backed pages: $fileBacked.
            Pages occupied by compressor: 200000.
        """.trimIndent()
        private fun assertUnknown(probe: FourStemMemoryProbe) {
            val value = probe.sample()
            assertEquals(SeparationProblem.RAM_UNAVAILABLE, value.refusal())
            assertNull(value.receipt)
        }
    }
}
