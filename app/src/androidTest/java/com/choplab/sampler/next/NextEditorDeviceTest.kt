package com.choplab.sampler.next

import android.net.Uri
import android.os.SystemClock
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.choplab.core.Action
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
        val session = startedSession()
        val studio = session.backend.studio
        val before = studio.document.value

        val file = File(rule.activity.cacheDir, "next-device-test.wav").apply { writeBytes(stereoWav(4_800)) }
        val location = session.documents.opened(Uri.fromFile(file))
        val project = EditorTrace(session).use { trace ->
            val started = runBlocking { studio.dispatch(Action.Import(location)) }
            assertTrue("Import was refused: $started$trace", started.accepted)
            trace.waitUntil("Import did not finish") {
                studio.work.value.jobId == null && studio.document.value.project.source != before.project.source
            }
            studio.document.value.project.also { requireNotNull(it.source) { "Import finished without a source$trace" } }
        }
        val source = requireNotNull(project.source)
        assertEquals("next-device-test.wav", project.asset(source.assetHash).name)
        assertEquals(4_800L, project.asset(source.assetHash).frames)

        runBlocking { session.backend.flushAutosave() }
        val autosave = File(rule.activity.filesDir, "next-v10/autosave")
        assertTrue("No autosave in $autosave", autosave.listFiles().orEmpty().any { it.name.startsWith("autosave.") })

        // Leave the developer's NEXT document as it was.
        val undone = runBlocking { studio.dispatch(Action.Undo) }
        assertTrue("Undo was refused: $undone", undone.accepted)
        rule.waitUntil(10_000) { studio.document.value.project == before.project }
        file.delete()
    }

    private fun startedSession(): NextSession {
        val model = ViewModelProvider(rule.activity)[NextViewModel::class.java]
        rule.waitUntil(30_000) { model.state.value != NextViewModel.Startup.Loading }
        val startup = model.state.value
        assertTrue("NEXT did not start: $startup", startup is NextViewModel.Startup.Ready)
        rule.waitUntil(30_000) { rule.onAllNodesWithTag("next-editor").fetchSemanticsNodes().isNotEmpty() }
        return (startup as NextViewModel.Startup.Ready).session
    }

    /**
     * What the editor and its output reported during a step, for a failure message: notices and output changes timed
     * from the start of the step (a StateFlow may skip a quickly undone change), the last engine receipt and the work.
     * One line each with the notices first, so a CI summary that shortens long lines still shows why.
     */
    private inner class EditorTrace(private val session: NextSession) : AutoCloseable {
        private val begin = SystemClock.elapsedRealtime()
        private val notices = CopyOnWriteArrayList<String>()
        private val outputs = CopyOnWriteArrayList<String>()
        private val listening = CoroutineScope(Dispatchers.Default)

        init {
            val subscribed = CountDownLatch(2)
            listening.launch {
                session.backend.studio.notices.onSubscription { subscribed.countDown() }.collect { notices += "${elapsed()} $it" }
            }
            listening.launch {
                session.backend.engine.status.onSubscription { subscribed.countDown() }
                    .collect { outputs += "${elapsed()} ${it.phase} ${it.encoding} ${it.fault} losses=${it.faults}" }
            }
            assertTrue("The trace did not start", subscribed.await(10, TimeUnit.SECONDS))
        }

        private fun elapsed() = "+${SystemClock.elapsedRealtime() - begin}ms"

        fun waitUntil(failure: String, timeoutMillis: Long = 30_000, condition: () -> Boolean) {
            try {
                rule.waitUntil(timeoutMillis, condition)
            } catch (timeout: ComposeTimeoutException) {
                throw AssertionError("$failure$this", timeout)
            }
        }

        override fun toString() = "\nnotices: $notices\nlast receipt: ${session.backend.engine.lastReceipt}" +
            "\nwork: ${session.backend.studio.work.value}\noutput: $outputs\nat ${elapsed()}"

        override fun close() { listening.cancel() }
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
