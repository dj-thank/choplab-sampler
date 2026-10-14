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

/** Seconds are rounded once to a 48 kHz frame with integer arithmetic; frame input is exact. */
internal fun ceParsePosition(text: String, seconds: Boolean, maximum: Long): Long? {
    val frame = if (!seconds) {
        if (!Regex("[0-9]{1,12}").matches(text)) return null
        text.toLongOrNull() ?: return null
    } else {
        if (!Regex("[0-9]{1,4}(\\.[0-9]{1,6})?").matches(text)) return null
        val parts = text.split('.')
        val micros = parts[0].toLong() * 1_000_000 + parts.getOrNull(1).orEmpty().padEnd(6, '0').toLong()
        (micros * CONTINUOUS_TIMELINE_RATE + 500_000) / 1_000_000
    }
    return frame.takeIf { it in 0..maximum }
}
internal fun cePositionSeconds(frame: Long): String {
    val micros = frame * 1_000_000 / CONTINUOUS_TIMELINE_RATE
    return "${micros / 1_000_000}.${(micros % 1_000_000).toString().padStart(6, '0')}"
}

@Composable internal fun CEExactPosition(start: Long, maximum: Long, enabled: Boolean, apply: (Long) -> Unit) {
    val tag = "ce-clip-position-exact"
    var open by remember { mutableStateOf(false) }
    var seconds by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf(start.toString()) }
    CEButton(stringResource(Res.string.ce_exact_position), {
        seconds = false; input = start.toString(); open = true
    }, enabled = enabled, tag = tag)
    if (open) {
        val frame = ceParsePosition(input, seconds, maximum)
        AlertDialog(onDismissRequest = { open = false }, title = { Text(stringResource(Res.string.ce_exact_position)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(Res.string.ce_exact_position_hint, maximum.toString()))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CEButton(stringResource(Res.string.ce_position_frames), {
                        input = (frame ?: start).toString(); seconds = false
                    }, Modifier.weight(1f), primary = !seconds, tag = "$tag-frames")
                    CEButton(stringResource(Res.string.ce_position_seconds), {
                        input = cePositionSeconds(frame ?: start); seconds = true
                    }, Modifier.weight(1f), primary = seconds, tag = "$tag-seconds")
                }
                OutlinedTextField(input, { if (it.length <= 16) input = it }, singleLine = true,
                    label = { Text(stringResource(if (seconds) Res.string.ce_position_seconds else Res.string.ce_position_frames)) },
                    modifier = Modifier.testTag("$tag-input"))
                Text(if (frame == null) stringResource(Res.string.ce_position_invalid)
                    else stringResource(Res.string.ce_position_resolved, frame.toString()), Modifier.testTag("$tag-preview"))
            } },
            confirmButton = { CEButton(stringResource(Res.string.ce_apply), {
                if (frame != null) { apply(frame); open = false }
            }, enabled = enabled && frame != null, tag = "$tag-apply") },
            dismissButton = { CEButton(stringResource(Res.string.ce_cancel), { open = false }, tag = "$tag-cancel") })
    }
}
