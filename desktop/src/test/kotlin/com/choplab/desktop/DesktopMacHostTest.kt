package com.choplab.desktop

import com.choplab.desktop.audio.DesktopSystemAudioRecorder
import com.choplab.desktop.audio.MacSystemAudioProcessRecorder
import com.choplab.desktop.audio.SystemAudioRoute
import com.choplab.desktop.audio.parseSystemAudioHeader
import org.junit.jupiter.api.Assumptions
import com.choplab.desktop.provider.NamedAudioDevice
import com.choplab.desktop.provider.javaSoundDeviceStatus
import com.choplab.desktop.source.locateDesktopMediaTools
import com.choplab.desktop.source.mediaToolFileName
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopMacHostTest {
    @Test
    fun windowsKeepsBundledExecutableNamesAndMacOmitsTheExtension() {
        assertEquals("ffmpeg.exe", mediaToolFileName("ffmpeg", windows = true))
        assertEquals("ffmpeg", mediaToolFileName("ffmpeg", windows = false))
    }

    @Test
    fun preparedDirectoryWinsAndSplitInstallationsAreAccepted() {
        val root = kotlin.io.path.createTempDirectory("choplab-tools").toFile()
        val prepared = File(root, "prepared").apply { mkdirs() }
        listOf("yt-dlp", "ffmpeg", "ffprobe", "node").forEach { name ->
            File(prepared, "$name.exe").apply { writeBytes(byteArrayOf(1)); setExecutable(true) }
        }
        val tools = locateDesktopMediaTools(listOf(prepared), emptyList(), windows = true)
        assertEquals(File(prepared, "yt-dlp.exe"), tools?.ytDlp)

        val bin = File(root, "bin").apply { mkdirs() }
        val nodeBin = File(root, "node/bin").apply { mkdirs() }
        listOf("yt-dlp", "ffmpeg", "ffprobe").forEach { name ->
            File(bin, name).apply { writeBytes(byteArrayOf(1)); setExecutable(true) }
        }
        File(nodeBin, "node").apply { writeBytes(byteArrayOf(1)); setExecutable(true) }
        val split = locateDesktopMediaTools(emptyList(), listOf(bin, nodeBin), windows = false)
        assertEquals(File(nodeBin, "node"), split?.node)
        assertEquals(File(bin, "ffmpeg"), split?.ffmpeg)
        check(root.deleteRecursively())
    }

    @Test
    fun missingToolMeansImportCannotStart() {
        assertNull(locateDesktopMediaTools(emptyList(), emptyList(), windows = false))
    }

    @Test
    fun systemAudioHeaderAndBackendFollowTheHost() {
        assertEquals(48_000, parseSystemAudioHeader("CHOPLAB-PCM 48000 2").sampleRate)
        assertFailsWith<IllegalStateException> { parseSystemAudioHeader("CHOPLAB-ERROR denied") }
        // A Mac records what is heard through the helper; BlackHole is silent unless output is routed to it.
        assertEquals(SystemAudioRoute.SCREEN_CAPTURE, DesktopSystemAudioRecorder.chooseSystemAudioRoute(loopbackAvailable = true, helperAvailable = true, macOs = true))
        assertEquals(SystemAudioRoute.LOOPBACK, DesktopSystemAudioRecorder.chooseSystemAudioRoute(loopbackAvailable = true, helperAvailable = false, macOs = true))
        assertEquals(SystemAudioRoute.LOOPBACK, DesktopSystemAudioRecorder.chooseSystemAudioRoute(loopbackAvailable = true, helperAvailable = true, macOs = false))
        assertEquals(SystemAudioRoute.MISSING, DesktopSystemAudioRecorder.chooseSystemAudioRoute(loopbackAvailable = false, helperAvailable = false, macOs = false))
    }

    @Test
    fun diagnosticsNameThePlaybackAndCaptureDevices() {
        assertEquals("音声デバイスが見つかりません", javaSoundDeviceStatus(emptyList()))
        assertEquals(
            "音声デバイス: 出力[Speakers] 入力[Microphone]",
            javaSoundDeviceStatus(
                listOf(
                    NamedAudioDevice("Speakers", playback = true, capture = false),
                    NamedAudioDevice("Microphone", playback = false, capture = true),
                ),
            ),
        )
        assertEquals("音声デバイス", desktopDiagnosticsMenuTitle(macOs = true))
        assertEquals("Windows 音声エンドポイント", desktopDiagnosticsMenuTitle(macOs = false))
    }

    @Test
    fun screenCaptureRecorderWritesWavWhenSmokeIsRequested() {
        Assumptions.assumeTrue("1" == System.getenv("CHOPLAB_CAPTURE_SMOKE"))
        val helper = listOf(File("build/choplab-sck-audio"), File("desktop/build/choplab-sck-audio")).firstOrNull { it.canExecute() }
        Assumptions.assumeTrue(helper != null)
        val recorder = MacSystemAudioProcessRecorder(helper!!)
        val file = kotlin.io.path.createTempFile("choplab-sck", ".wav").toFile()
        val started = recorder.start(file)
        assertTrue(started.isSuccess, started.exceptionOrNull()?.toString())
        Thread.sleep(1000)
        val stopped = recorder.stop()
        recorder.close()
        assertTrue(stopped.isSuccess, stopped.exceptionOrNull()?.toString())
        assertTrue(file.length() > 1_000)
        check(file.delete())
    }

    @Test
    fun helperLookupUsesAnExplicitExecutableBeforeOtherCandidates() {
        val helper = File.createTempFile("choplab-sck", "").apply { setExecutable(true) }
        assertEquals(helper, com.choplab.desktop.audio.locateMacSystemAudioHelper(helper.path, null, File("missing")))
        assertTrue(helper.delete())
    }
}
