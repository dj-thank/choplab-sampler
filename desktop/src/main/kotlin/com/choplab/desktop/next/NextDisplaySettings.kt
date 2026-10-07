package com.choplab.desktop.next

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.window.MenuBarScope
import com.choplab.ui.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.Properties

internal enum class NextDisplayLanguage(val tag: String) { SYSTEM("system"), JAPANESE("ja"), ENGLISH("en") }
internal data class NextDisplayPreferences(val language: NextDisplayLanguage = NextDisplayLanguage.SYSTEM, val scale: Float = 1f) {
    init { require(scale in listOf(1f, 1.3f, 2f)) }
}

/** UI preferences are separate from projects, undo and the OS's settings. */
internal class NextDisplaySettings(private val file: Path, private val systemLocale: Locale = Locale.getDefault()) : AutoCloseable {
    private val mutex = Mutex()
    val problem = MutableStateFlow(false)
    val preferences = MutableStateFlow(try { read(file) } catch (_: Exception) { problem.value = true; NextDisplayPreferences() })
    init { apply(preferences.value) }

    suspend fun change(value: NextDisplayPreferences) = update { value }
    suspend fun update(transform: (NextDisplayPreferences) -> NextDisplayPreferences) = mutex.withLock {
        val value = transform(preferences.value)
        val saved = withContext(Dispatchers.IO) { runCatching { write(file, value) }.isSuccess }
        problem.value = !saved
        if (saved) {
            // Language starts with the next app instance; recreating this composition would discard
            // remembered text drafts and gestures. Text scale can safely update the existing editor.
            current.value = current.value.copy(scale = value.scale)
            preferences.value = value
        }
        saved
    }
    private fun apply(value: NextDisplayPreferences) {
        Locale.setDefault(if (value.language == NextDisplayLanguage.SYSTEM) systemLocale else Locale.forLanguageTag(value.language.tag))
        current.value = value
    }
    override fun close() { Locale.setDefault(systemLocale); current.value = NextDisplayPreferences() }

    companion object {
        val current = MutableStateFlow(NextDisplayPreferences())
        fun read(file: Path): NextDisplayPreferences {
            if (!Files.exists(file)) return NextDisplayPreferences()
            require(Files.isRegularFile(file) && Files.size(file) <= 1024)
            val properties = Properties().apply { Files.newBufferedReader(file).use(::load) }
            require(properties.getProperty("version") == "1")
            val language = NextDisplayLanguage.entries.single { it.tag == properties.getProperty("language") }
            return NextDisplayPreferences(language, properties.getProperty("scale").toFloat())
        }
        fun write(file: Path, value: NextDisplayPreferences) {
            Files.createDirectories(file.parent)
            val temporary = Files.createTempFile(file.parent, ".display-", ".tmp")
            try {
                Files.writeString(temporary, "version=1\nlanguage=${value.language.tag}\nscale=${value.scale}\n")
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temporary) }
        }
    }
}

/** Each independent Compose root (editor or native browser) uses the same preference exactly once. */
@Composable internal fun NextDisplayEnvironment(content: @Composable () -> Unit) {
    val preferences by NextDisplaySettings.current.collectAsState()
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * preferences.scale), content = content)
}

@Composable internal fun MenuBarScope.NextDisplayMenu(settings: NextDisplaySettings, disabled: Boolean) {
    val value by settings.preferences.collectAsState()
    val problem by settings.problem.collectAsState()
    val scope = rememberCoroutineScope()
    Menu(stringResource(Res.string.next_display_menu)) {
        Menu(stringResource(Res.string.next_display_language), enabled = !disabled) {
            for (language in NextDisplayLanguage.entries) {
                val label = when (language) {
                    NextDisplayLanguage.SYSTEM -> stringResource(Res.string.next_display_system)
                    NextDisplayLanguage.JAPANESE -> "日本語"
                    NextDisplayLanguage.ENGLISH -> "English"
                }
                CheckboxItem(label, checked = value.language == language,
                    onCheckedChange = { scope.launch { settings.update { it.copy(language = language) } } })
            }
        }
        Menu(stringResource(Res.string.next_display_size), enabled = !disabled) {
            for ((scale, label) in listOf(1f to "100%", 1.3f to "130%", 2f to "200%"))
                CheckboxItem(label, checked = value.scale == scale,
                    onCheckedChange = { scope.launch { settings.update { it.copy(scale = scale) } } })
        }
        if (problem) Item(stringResource(Res.string.next_display_failed), enabled = false, onClick = {})
    }
}
