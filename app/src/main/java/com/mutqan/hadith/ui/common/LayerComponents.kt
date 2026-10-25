package com.mutqan.hadith.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.drill.SanadDrill
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

/**
 * A line of text behind a lock: what is not open yet, and what opens it. With an [onClick] the line can be tapped to
 * learn why it is locked (see [LayerLockSheet]).
 */
@Composable
fun LockedHint(text: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val tappable = if (onClick != null) {
        Modifier.heightIn(min = 48.dp).clickable(onClickLabel = stringResource(R.string.layer_lock_tap_label), role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Row(modifier.then(tappable), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text,
            modifier = Modifier.weight(1f, fill = false),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onClick != null) {
            Icon(Icons.Filled.Info, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * Why the chain is held back until the matn is firm, said once and kindly, with the way out for a student who does not
 * need it. Opened by tapping a locked Sanad row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LayerLockSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.layer_lock_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.layer_lock_body), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.layer_lock_advanced),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.layer_lock_close)) }
        }
    }
}

/**
 * The Sanad drills open on one hadith ([open], from [com.mutqan.hadith.domain.drill.SanadDrillPolicy]): naming the
 * narrator taken out of the chain, putting the chain in order, and building it slot by slot. A drill the chain is too
 * short for is not offered, and a line says so rather than leaving the student to wonder where it went.
 */
@Composable
fun SanadDrillButtons(
    hadithId: Long,
    open: List<SanadDrill>,
    onSanadBuilder: (Long) -> Unit,
    onMissingLink: (Long) -> Unit,
    onOrderChain: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.fillMaxWidth()) {
        for (drill in open) {
            val (labelRes, onClick) = when (drill) {
                SanadDrill.MISSING_NARRATOR -> R.string.drill_missing_link to onMissingLink
                SanadDrill.ORDER_CHAIN -> R.string.drill_order_chain to onOrderChain
                SanadDrill.SANAD_BUILDER -> R.string.drill_sanad_builder to onSanadBuilder
            }
            OutlinedButton(onClick = { onClick(hadithId) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(labelRes), textAlign = TextAlign.Center)
            }
        }
        val hint = when {
            open.isEmpty() -> stringResource(R.string.drills_short_none)
            SanadDrill.ORDER_CHAIN !in open -> stringResource(R.string.drills_short_order, SanadDrill.ORDER_CHAIN.minLinks)
            else -> null
        }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
