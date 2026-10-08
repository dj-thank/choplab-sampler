package com.choplab.desktop.next

/** Only an editable initial suggestion; the chosen destination still goes through overwrite confirmation. */
internal fun nextSuggestedFilename(title: String, extension: String, japanese: Boolean): String {
    require(extension in setOf("choplab", "wav", "zip"))
    val safe = title.map { if (it.isISOControl() || it in "/\\:*?\"<>|") '_' else it }.joinToString("").trim().trim('.')
    val stem = listOf(".choplab", ".wav", ".zip").firstOrNull { safe.endsWith(it, ignoreCase = true) }
        ?.let { safe.dropLast(it.length) } ?: safe
    val short = stem.take(60).trimEnd { it.isHighSurrogate() || it == '.' || it.isWhitespace() }
        .ifBlank { if (japanese) "作品" else "Project" }
    val reserved = Regex("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?").matches(short)
    return (if (reserved) "_$short" else short) + ".$extension"
}
