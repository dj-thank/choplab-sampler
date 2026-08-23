package com.choplab.sampler.format

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Small locale-independent formatter for the numeric/status labels used by the
 * shared UI. Kotlin/JVM's String.format extension is unavailable on Native, so
 * common code must not depend on it.
 *
 * Supported conversions: %% / %s / %c / %d / %f, with '-', '+', space and
 * zero-padding flags, decimal width, and decimal precision for %f.
 */
internal fun portableStringFormat(pattern: String, arguments: Array<out Any?>): String {
    val output = StringBuilder(pattern.length + 16)
    var patternIndex = 0
    var argumentIndex = 0

    while (patternIndex < pattern.length) {
        val current = pattern[patternIndex]
        if (current != '%') {
            output.append(current)
            patternIndex += 1
            continue
        }

        require(patternIndex + 1 < pattern.length) { "Dangling '%' in format string" }
        if (pattern[patternIndex + 1] == '%') {
            output.append('%')
            patternIndex += 2
            continue
        }

        var cursor = patternIndex + 1
        var leftAlign = false
        var forceSign = false
        var leadingSpace = false
        var zeroPad = false
        while (cursor < pattern.length) {
            when (pattern[cursor]) {
                '-' -> leftAlign = true
                '+' -> forceSign = true
                ' ' -> leadingSpace = true
                '0' -> zeroPad = true
                else -> break
            }
            cursor += 1
        }

        val widthStart = cursor
        while (cursor < pattern.length && pattern[cursor].isDigit()) cursor += 1
        val width = pattern.substring(widthStart, cursor).takeIf(String::isNotEmpty)?.toInt() ?: 0

        var precision: Int? = null
        if (cursor < pattern.length && pattern[cursor] == '.') {
            cursor += 1
            val precisionStart = cursor
            while (cursor < pattern.length && pattern[cursor].isDigit()) cursor += 1
            require(cursor > precisionStart) { "Missing precision in format string" }
            precision = pattern.substring(precisionStart, cursor).toInt()
            require(precision in 0..9) { "Unsupported format precision: $precision" }
        }

        require(cursor < pattern.length) { "Missing conversion in format string" }
        require(argumentIndex < arguments.size) { "Not enough format arguments" }
        val conversion = pattern[cursor]
        val argument = arguments[argumentIndex++]
        val formatted = when (conversion) {
            's' -> formatText(argument?.toString() ?: "null", width, leftAlign)
            'c' -> {
                val text = when (argument) {
                    is Char -> argument.toString()
                    is Number -> argument.toInt().toChar().toString()
                    else -> error("%c requires Char or Number")
                }
                formatText(text, width, leftAlign)
            }
            'd' -> formatInteger(
                value = requireNumber(argument, conversion).toLong(),
                width = width,
                leftAlign = leftAlign,
                forceSign = forceSign,
                leadingSpace = leadingSpace,
                zeroPad = zeroPad,
            )
            'f' -> formatDecimal(
                value = requireNumber(argument, conversion).toDouble(),
                precision = precision ?: 6,
                width = width,
                leftAlign = leftAlign,
                forceSign = forceSign,
                leadingSpace = leadingSpace,
                zeroPad = zeroPad,
            )
            else -> error("Unsupported format conversion: %$conversion")
        }
        output.append(formatted)
        patternIndex = cursor + 1
    }

    require(argumentIndex == arguments.size) { "Too many format arguments" }
    return output.toString()
}

private fun requireNumber(value: Any?, conversion: Char): Number =
    value as? Number ?: error("%$conversion requires a Number")

private fun formatText(value: String, width: Int, leftAlign: Boolean): String {
    if (value.length >= width) return value
    val padding = " ".repeat(width - value.length)
    return if (leftAlign) value + padding else padding + value
}

private fun formatInteger(
    value: Long,
    width: Int,
    leftAlign: Boolean,
    forceSign: Boolean,
    leadingSpace: Boolean,
    zeroPad: Boolean,
): String {
    val raw = value.toString()
    val negative = raw.startsWith('-')
    val digits = if (negative) raw.drop(1) else raw
    val sign = when {
        negative -> "-"
        forceSign -> "+"
        leadingSpace -> " "
        else -> ""
    }
    return applyWidth(sign, digits, width, leftAlign, zeroPad)
}

private fun formatDecimal(
    value: Double,
    precision: Int,
    width: Int,
    leftAlign: Boolean,
    forceSign: Boolean,
    leadingSpace: Boolean,
    zeroPad: Boolean,
): String {
    if (value.isNaN() || value.isInfinite()) {
        val raw = value.toString()
        val sign = when {
            raw.startsWith('-') -> "-"
            forceSign -> "+"
            leadingSpace -> " "
            else -> ""
        }
        val body = raw.removePrefix("-")
        return applyWidth(sign, body, width, leftAlign, zeroPad)
    }

    val factor = pow10(precision)
    val absolute = abs(value)
    val scaled = (absolute * factor.toDouble()).roundToLong()
    val whole = scaled / factor
    val fraction = scaled % factor
    val body = if (precision == 0) {
        whole.toString()
    } else {
        "$whole.${fraction.toString().padStart(precision, '0')}"
    }
    val sign = when {
        value < 0.0 -> "-"
        forceSign -> "+"
        leadingSpace -> " "
        else -> ""
    }
    return applyWidth(sign, body, width, leftAlign, zeroPad)
}

private fun pow10(exponent: Int): Long {
    var result = 1L
    repeat(exponent) { result *= 10L }
    return result
}

private fun applyWidth(
    sign: String,
    body: String,
    width: Int,
    leftAlign: Boolean,
    zeroPad: Boolean,
): String {
    val paddingCount = (width - sign.length - body.length).coerceAtLeast(0)
    if (paddingCount == 0) return sign + body
    val padding = (if (zeroPad && !leftAlign) "0" else " ").repeat(paddingCount)
    return when {
        leftAlign -> sign + body + padding
        zeroPad -> sign + padding + body
        else -> padding + sign + body
    }
}
