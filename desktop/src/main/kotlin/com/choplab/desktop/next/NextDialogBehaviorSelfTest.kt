package com.choplab.desktop.next

import java.awt.*
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.swing.*

/** Explicit native, owned-window fixture; never opens audio, accounts or a user profile. */
object NextDialogBehaviorSelfTest {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1 && args[0] in listOf("ja", "en"))
        Locale.setDefault(Locale.forLanguageTag(args[0]))
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>(null)
        SwingUtilities.invokeLater {
            val frame = JFrame("ChopLab isolated dialog verification")
            frame.setSize(620, 420)
            var stops = 0; var closes = 0
            val owner = NextDialogOwners.register(frame) { stops++ }
            val dialog = JDialog(frame, "ChopLab dialog", Dialog.ModalityType.DOCUMENT_MODAL)
            val query = JTextField("unchanged search draft")
            dialog.contentPane = JPanel(BorderLayout()).apply { add(query, BorderLayout.NORTH) }
            configureNextDialog(dialog, Dimension(900, 700), Dimension(680, 540)) {
                closes++; dialog.dispose()
            }
            var step = 0
            val timer = Timer(150, null)
            fun complete() {
                timer.stop(); dialog.dispose(); frame.dispose(); owner.close(); done.countDown()
            }
            val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
            fun key(code: Int, id: Int, modifiers: Int = 0) = manager.dispatchEvent(
                KeyEvent(query, id, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED))
            timer.addActionListener {
                try {
                    if (step++ == 0) {
                        check(dialog.isVisible && dialog.width > 0 && dialog.height > 0)
                        query.requestFocusInWindow()
                    } else {
                        val modifier = if (com.choplab.desktop.isMacOsHost()) InputEvent.META_DOWN_MASK else InputEvent.CTRL_DOWN_MASK
                        key(KeyEvent.VK_PERIOD, KeyEvent.KEY_PRESSED, modifier)
                        key(KeyEvent.VK_PERIOD, KeyEvent.KEY_PRESSED, modifier)
                        check(stops == 1) { "Repeated key-down must not stop twice" }
                        key(KeyEvent.VK_PERIOD, KeyEvent.KEY_RELEASED)
                        key(KeyEvent.VK_Z, KeyEvent.KEY_PRESSED, modifier)
                        check(stops == 1 && query.text == "unchanged search draft")
                        val controls = dialog.contentPane.components.filterIsInstance<JPanel>().single()
                        val stop = controls.components.filterIsInstance<JButton>().single { it.name == "next-dialog-stop-all" }
                        check(stop.isShowing && stop.isEnabled)
                        stop.doClick(0); check(stops == 2)
                        key(KeyEvent.VK_ESCAPE, KeyEvent.KEY_PRESSED)
                        key(KeyEvent.VK_ESCAPE, KeyEvent.KEY_PRESSED)
                        check(closes == 1 && !dialog.isVisible)
                        complete()
                    }
                } catch (error: Throwable) { failure.set(error); complete() }
            }
            frame.isVisible = true
            timer.start()
            dialog.isVisible = true
        }
        check(done.await(15, TimeUnit.SECONDS)) { "Owned dialog fixture timed out" }
        failure.get()?.let { throw it }
        println("""{"status":"LOCAL_PASS","scope":"owned-native-dialog-keys","locale":"${args[0]}","stopDispatches":2,"closeDispatches":1,"searchDraftPreserved":true,"audioStarted":false}""")
    }
}
