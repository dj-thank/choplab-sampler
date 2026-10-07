package com.choplab.desktop.next

import com.choplab.jvm.AutosaveRecoveryException
import com.choplab.ui.resources.*
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import java.awt.Desktop
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JOptionPane
import javax.swing.SwingUtilities

internal enum class NextStartupFailure { RECOVERY, ACCESS, STORAGE, OTHER }
internal enum class NextStartupChoice { RETRY, SHOW_FOLDER, QUIT }

internal fun nextStartupFailure(error: Exception): NextStartupFailure {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(16).toList()
    return when {
        causes.any { it is AutosaveRecoveryException } -> NextStartupFailure.RECOVERY
        causes.any { it is AccessDeniedException || it is SecurityException } -> NextStartupFailure.ACCESS
        causes.any { it is FileSystemException || it is java.io.IOException } -> NextStartupFailure.STORAGE
        else -> NextStartupFailure.OTHER
    }
}

/** No profile reset, deletion or fallback project: retry only follows an explicit choice. */
internal fun <T> recoverNextStartup(
    create: () -> T,
    choose: (NextStartupFailure) -> NextStartupChoice,
    showFolder: () -> Unit,
): T? {
    while (true) {
        val failure = try { return create() } catch (error: Exception) { nextStartupFailure(error) }
        while (true) {
            when (choose(failure)) {
                NextStartupChoice.QUIT -> return null
                NextStartupChoice.RETRY -> break
                NextStartupChoice.SHOW_FOLDER -> showFolder()
            }
        }
    }
}

internal fun <T> startNextWithRecovery(directory: Path, create: () -> T): T? {
    fun <R> onUi(block: () -> R): R {
        if (SwingUtilities.isEventDispatchThread()) return block()
        var answer: Result<R>? = null
        SwingUtilities.invokeAndWait { answer = runCatching(block) }
        return checkNotNull(answer).getOrThrow()
    }
    val title = runBlocking { getString(Res.string.next_startup_title) }
    val choices = runBlocking { arrayOf(getString(Res.string.next_startup_retry),
        getString(Res.string.next_startup_folder), getString(Res.string.next_startup_quit)) }
    return recoverNextStartup(create, choose = { failure ->
        val resource = when (failure) {
            NextStartupFailure.RECOVERY -> Res.string.next_startup_recovery
            NextStartupFailure.ACCESS -> Res.string.next_startup_access
            NextStartupFailure.STORAGE -> Res.string.next_startup_storage
            NextStartupFailure.OTHER -> Res.string.next_startup_other
        }
        val message = runBlocking { getString(resource) + "\n\n" + getString(Res.string.next_startup_preserved) }
        when (onUi { JOptionPane.showOptionDialog(null, message, title, JOptionPane.DEFAULT_OPTION,
            JOptionPane.ERROR_MESSAGE, null, choices, choices.last()) }) {
            0 -> NextStartupChoice.RETRY
            1 -> NextStartupChoice.SHOW_FOLDER
            else -> NextStartupChoice.QUIT
        }
    }, showFolder = {
        // A failed first launch may not have created the profile yet. Reveal its nearest existing parent.
        val existing = generateSequence(directory) { it.parent }.firstOrNull { Files.isDirectory(it) }
        try {
            check(existing != null && Desktop.isDesktopSupported())
            Desktop.getDesktop().open(existing.toFile())
        } catch (_: Exception) {
            val message = runBlocking { getString(Res.string.next_startup_folder_failed) } + "\n" + directory
            onUi { JOptionPane.showMessageDialog(null, message, title, JOptionPane.INFORMATION_MESSAGE) }
        }
    })
}
