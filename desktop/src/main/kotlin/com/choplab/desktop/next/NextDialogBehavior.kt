package com.choplab.desktop.next

import com.choplab.desktop.isMacOsHost
import com.choplab.ui.resources.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import java.awt.*
import java.awt.event.*
import java.util.WeakHashMap
import javax.swing.*

/** Kept outside document/undo state. Only dialogs owned by this editor can issue its stop command. */
internal object NextDialogOwners {
    private val owners = WeakHashMap<Window, () -> Unit>()
    @Synchronized fun register(owner: Window, stop: () -> Unit): AutoCloseable {
        owners[owner] = stop
        return AutoCloseable { synchronized(this) { owners.remove(owner) } }
    }
    @Synchronized fun stopFor(window: Window?): (() -> Unit)? =
        generateSequence(window) { it.owner }.firstNotNullOfOrNull { owners[it] }
}

internal data class NextDialogGeometry(val bounds: Rectangle, val minimum: Dimension)

internal fun nextDialogGeometry(workArea: Rectangle, preferred: Dimension, minimum: Dimension, center: Point): NextDialogGeometry {
    val width = preferred.width.coerceIn(1, workArea.width.coerceAtLeast(1))
    val height = preferred.height.coerceIn(1, workArea.height.coerceAtLeast(1))
    return NextDialogGeometry(Rectangle(
        (center.x - width / 2).coerceIn(workArea.x, workArea.x + workArea.width.coerceAtLeast(1) - width),
        (center.y - height / 2).coerceIn(workArea.y, workArea.y + workArea.height.coerceAtLeast(1) - height), width, height),
        Dimension(minimum.width.coerceIn(1, width), minimum.height.coerceIn(1, height)))
}

/** Scale only this newly created dialog; never change Swing defaults or another window's fonts. */
internal fun applyNextDialogTypography(component: Component, scale: Float) {
    fun children(value: Component): List<Component> = if (value is androidx.compose.ui.awt.ComposePanel) emptyList()
        else listOf(value) + (value as? Container)?.components.orEmpty().flatMap(::children)
    // Snapshot inherited fonts before changing a parent, so a child cannot accidentally receive 4x.
    val values = children(component).map { it to it.font }
    values.forEach { (value, font) -> font?.let { value.font = it.deriveFont(it.size2D * scale) } }
    values.forEach { (value, _) -> if (value is AbstractButton) {
        val metrics = value.getFontMetrics(value.font)
        value.preferredSize = Dimension(maxOf(value.preferredSize.width, metrics.stringWidth(value.text.orEmpty()) + 32),
            maxOf(48, metrics.height + 16))
    } }
}

/** One close path, global stop for this owner, and reachable controls on small work areas. Call on EDT. */
internal fun configureNextDialog(dialog: JDialog, preferred: Dimension, minimum: Dimension, close: () -> Unit) {
    check(SwingUtilities.isEventDispatchThread())
    var closed = false
    fun finish() { if (!closed) { closed = true; close() } }
    val labels = runBlocking { getString(Res.string.ce_stop_all) to getString(Res.string.next_dialog_close_escape) }
    val stop = NextDialogOwners.stopFor(dialog)
    val controls = JPanel(FlowLayout(FlowLayout.TRAILING)).apply {
        add(JButton(labels.first).apply {
            name = "next-dialog-stop-all"; isEnabled = stop != null
            addActionListener { stop?.invoke() }
        })
        add(JButton(labels.second).apply { name = "next-dialog-close"; addActionListener { finish() } })
    }
    val original = dialog.contentPane
    val scale = NextDisplaySettings.current.value.scale
    applyNextDialogTypography(original, scale)
    applyNextDialogTypography(controls, scale)
    // At small sizes, content scrolls while Stop/Close stay visible. Compose regions keep their usual size.
    original.preferredSize = Dimension(((minimum.width - 32).coerceAtLeast(240) * scale).toInt(),
        ((minimum.height - 88).coerceAtLeast(160) * scale).toInt())
    dialog.contentPane = JPanel(BorderLayout()).apply {
        add(JScrollPane(original).apply { border = null; verticalScrollBar.unitIncrement = 24 }, BorderLayout.CENTER)
        add(controls, BorderLayout.SOUTH)
    }
    val config = dialog.owner?.graphicsConfiguration ?: dialog.graphicsConfiguration
        ?: GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    val work = Rectangle(config.bounds).apply {
        x += insets.left; y += insets.top
        width -= insets.left + insets.right; height -= insets.top + insets.bottom
    }
    val owner = dialog.owner
    val center = if (owner != null) Point(owner.x + owner.width / 2, owner.y + owner.height / 2)
        else Point(work.x + work.width / 2, work.y + work.height / 2)
    val geometry = nextDialogGeometry(work, preferred, minimum, center)
    val needed = controls.components.sumOf { it.preferredSize.width } + 24
    controls.layout = GridLayout(if (needed > geometry.bounds.width) 2 else 1, if (needed > geometry.bounds.width) 1 else 2, 8, 8)
    controls.border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
    dialog.minimumSize = geometry.minimum
    dialog.bounds = geometry.bounds
    var stopHeld = false
    val keys = KeyEventDispatcher { event ->
        val window = (event.component as? Window) ?: SwingUtilities.getWindowAncestor(event.component)
        if (!dialog.isVisible || window != dialog) false
        else if (event.keyCode == KeyEvent.VK_ESCAPE && event.id == KeyEvent.KEY_PRESSED) { finish(); true }
        else if (event.keyCode == KeyEvent.VK_PERIOD && event.id == KeyEvent.KEY_RELEASED) {
            val consumed = stopHeld
            stopHeld = false
            consumed
        } else if (event.keyCode == KeyEvent.VK_PERIOD && (event.isMetaDown && isMacOsHost() || event.isControlDown && !isMacOsHost())) {
            if (event.id == KeyEvent.KEY_PRESSED && !stopHeld) { stopHeld = true; stop?.invoke() }
            true
        } else false
    }
    val manager = KeyboardFocusManager.getCurrentKeyboardFocusManager()
    manager.addKeyEventDispatcher(keys)
    dialog.addWindowListener(object : WindowAdapter() {
        override fun windowClosed(event: WindowEvent) { manager.removeKeyEventDispatcher(keys) }
        override fun windowDeactivated(event: WindowEvent) { stopHeld = false }
    })
}
