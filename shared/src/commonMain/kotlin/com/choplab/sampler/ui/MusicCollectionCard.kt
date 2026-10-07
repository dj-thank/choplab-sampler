package com.choplab.sampler.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Neutral library artwork, independent from any provider's product assets. */
@Composable
fun MusicCollectionCard(title: String, subtitle: String, artist: Boolean,
    onOpen: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedCard(modifier.fillMaxWidth().heightIn(min = 180.dp).clickable(enabled = enabled, onClick = onOpen)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(Modifier.size(80.dp), shape = if (artist) CircleShape else RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(contentAlignment = Alignment.Center) { Text(if (artist) "♪" else "▤", style = MaterialTheme.typography.displaySmall) }
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
