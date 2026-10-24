package com.mutqan.hadith.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.MemorizationLayer

@get:StringRes
val MemorizationLayer.nameRes: Int
    get() = when (this) {
        MemorizationLayer.LAYER_1_CORE -> R.string.layer_1_name
        MemorizationLayer.LAYER_2_SANAD_TREE -> R.string.layer_2_name
        MemorizationLayer.LAYER_3_TRANSMISSION_TERMS -> R.string.layer_3_name
    }

/** "Layer 1: matn and Companion" and its siblings: where the hadith stands, in one line. */
@get:StringRes
val MemorizationLayer.badgeRes: Int
    get() = when (this) {
        MemorizationLayer.LAYER_1_CORE -> R.string.layer_1_badge
        MemorizationLayer.LAYER_2_SANAD_TREE -> R.string.layer_2_badge
        MemorizationLayer.LAYER_3_TRANSMISSION_TERMS -> R.string.layer_3_badge
    }

@get:StringRes
val MemorizationLayer.descriptionRes: Int
    get() = when (this) {
        MemorizationLayer.LAYER_1_CORE -> R.string.layer_1_desc
        MemorizationLayer.LAYER_2_SANAD_TREE -> R.string.layer_2_desc
        MemorizationLayer.LAYER_3_TRANSMISSION_TERMS -> R.string.layer_3_desc
    }

/** The layer a hadith is in, as a chip, with how far it is from the next one. */
@Composable
fun LayerBadge(status: LayerStatus, modifier: Modifier = Modifier, showProgress: Boolean = true) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        InfoChip(
            stringResource(status.layer.badgeRes),
            container = MaterialTheme.colorScheme.primaryContainer,
            content = MaterialTheme.colorScheme.onPrimaryContainer,
        )
        if (showProgress) LayerProgress(status)
    }
}

/** How close the memory is to opening the next layer; a note that everything is open at the last one. */
@Composable
fun LayerProgress(status: LayerStatus, modifier: Modifier = Modifier) {
    val next = status.layer.next
    if (next == null) {
        Text(
            stringResource(R.string.layer_all_open),
            modifier = modifier,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    // Rounded down: 49.7% must not read as the 50% that opens the layer.
    val percent = (status.progress * 100).toInt()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LinearProgressIndicator(
            progress = { status.progress },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Text(
            stringResource(
                if (next == MemorizationLayer.LAYER_2_SANAD_TREE) R.string.layer_progress_to_2 else R.string.layer_progress_to_3,
                percent,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** A line of text behind a lock: what is not open yet, and what opens it. */
@Composable
fun LockedHint(text: String, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The two Sanad drills of one hadith: ordering its chain, and naming the narrator that was taken out. */
@Composable
fun SanadDrillButtons(hadithId: Long, onSanadBuilder: (Long) -> Unit, onMissingLink: (Long) -> Unit, modifier: Modifier = Modifier) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { onSanadBuilder(hadithId) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.drill_sanad_builder), textAlign = TextAlign.Center)
        }
        OutlinedButton(onClick = { onMissingLink(hadithId) }, modifier = Modifier.weight(1f)) {
            Text(stringResource(R.string.drill_missing_link), textAlign = TextAlign.Center)
        }
    }
}
