package com.choplab.sampler.next

import android.net.Uri
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.choplab.core.Action
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The NEXT entry on a real Android runtime: the shared editor starts on the new core, draws, and imports
 * a picked-style document through the Storage Access path into its autosaved workspace. It does not prove
 * audible output (the CI emulator runs without audio) or the look on a phone.
 */
@RunWith(AndroidJUnit4::class)
class NextEditorDeviceTest {
    @get:Rule
    val rule = createAndroidComposeRule<NextActivity>()

    @Test
    fun editorStartsDrawsAndImportsAWavThroughTheDocumentPath() {
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("next-editor").fetchSemanticsNodes().isNotEmpty() }
        val model = ViewModelProvider(rule.activity)[NextViewModel::class.java]
        val session = (model.state.value as NextViewModel.Startup.Ready).session
        val studio = session.backend.studio
        val before = studio.document.value

        val file = File(rule.activity.cacheDir, "next-device-test.wav").apply { writeBytes(stereoWav(4_800)) }
        val location = session.documents.opened(Uri.fromFile(file))
        assertTrue(runBlocking { studio.dispatch(Action.Import(location)) }.accepted)
        rule.waitUntil(30_000) { studio.work.value.jobId == null && studio.document.value.project.assets.size > before.project.assets.size }
        assertEquals("next-device-test.wav", studio.document.value.project.assets.last().name)

        runBlocking { session.backend.flushAutosave() }
        val autosave = File(rule.activity.filesDir, "next-v10/autosave")
        assertTrue(autosave.listFiles().orEmpty().any { it.name.startsWith("autosave.") })

        // Leave the developer's NEXT document as it was.
        assertTrue(runBlocking { studio.dispatch(Action.Undo) }.accepted)
        rule.waitUntil(10_000) { studio.document.value.project == before.project }
        file.delete()
    }

    private fun stereoWav(frames: Int): ByteArray {
        val data = frames * 4
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(2); putInt(48_000); putInt(48_000 * 4); putShort(4); putShort(16)
            put("data".toByteArray()); putInt(data)
            repeat(frames) { putShort(((it % 96) * 300 - 14_000).toShort()); putShort(((it % 48) * 500 - 12_000).toShort()) }
        }.array()
    }
}
