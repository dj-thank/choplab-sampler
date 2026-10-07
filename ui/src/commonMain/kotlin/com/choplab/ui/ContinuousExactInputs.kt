package com.choplab.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.choplab.ui.resources.*
import org.jetbrains.compose.resources.stringResource

/** Decimal parsing without Float rounding or silently stripping a mistyped sign/separator. */
internal fun ceParseTempo(text: String): Int? {
    if (!Regex("[0-9]{1,3}(\\.[0-9]{1,3})?").matches(text)) return null
    val parts = text.split('.')
    val milli = parts[0].toInt() * 1000 + parts.getOrNull(1).orEmpty().padEnd(3, '0').toInt()
    return milli.takeIf { it in 40_000..240_000 }
}
internal fun ceTempoText(milli: Int): String = if (milli % 1000 == 0) (milli / 1000).toString()
    else "${milli / 1000}.${(milli % 1000).toString().padStart(3, '0').trimEnd('0')}"

/** Source-frame inputs are exact for long files; one Apply creates one reversible edit. */
@Composable internal fun CEExactRange(start: Long, end: Long, total: Long, rate: Int, enabled: Boolean,
    tag: String, minimumFrames: Long = 1, apply: (Long, Long) -> Unit) {
    var open by remember(start, end, total) { mutableStateOf(false) }
    var from by remember(start, end, total) { mutableStateOf(start.toString()) }
    var to by remember(start, end, total) { mutableStateOf(end.toString()) }
    CEButton(stringResource(Res.string.ce_exact_range), { from = start.toString(); to = end.toString(); open = true },
        enabled = enabled, tag = tag)
    if (open) {
        val a = from.toLongOrNull()
        val b = to.toLongOrNull()
        val valid = a != null && b != null && a >= 0 && b <= total && b - a >= minimumFrames
        AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(Res.string.ce_exact_range)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.ce_exact_range_hint, rate, total.toString()))
                OutlinedTextField(from, { from = it }, label = { Text(stringResource(Res.string.ce_frame_start)) },
                    singleLine = true, modifier = Modifier.testTag("$tag-start"))
                OutlinedTextField(to, { to = it }, label = { Text(stringResource(Res.string.ce_frame_end)) },
                    singleLine = true, modifier = Modifier.testTag("$tag-end"))
                if (!valid) Text(stringResource(Res.string.ce_range_invalid), Modifier.testTag("$tag-invalid"))
            } },
            confirmButton = { CEButton(stringResource(Res.string.ce_apply), {
                if (valid) { apply(requireNotNull(a), requireNotNull(b)); open = false }
            }, enabled = enabled && valid, tag = "$tag-apply") },
            dismissButton = { CEButton(stringResource(Res.string.ce_cancel), { open = false }, tag = "$tag-cancel") })
    }
}

/** A split must leave native audio frames on both sides, including low-rate input on the 48 kHz timeline. */
internal fun ceCanSplit(start: Long, duration: Long, sourceStart: Long, sourceEnd: Long, sourceRate: Int, frame: Long): Boolean {
    val delta = frame - start
    if (delta <= 0 || delta >= duration) return false
    val cut = sourceStart + delta * sourceRate / CONTINUOUS_TIMELINE_RATE
    return cut > sourceStart && cut < sourceEnd
}
