package com.mutqan.hadith.ui.graph

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.usecase.ChainHealth
import com.mutqan.hadith.domain.usecase.NarratorHadith
import com.mutqan.hadith.ui.common.InfoChip
import com.mutqan.hadith.ui.common.StatTile
import com.mutqan.hadith.ui.common.tierLabel
import com.mutqan.hadith.ui.heatmap.color
import com.mutqan.hadith.ui.theme.MatnCompactTextStyle
import kotlin.math.roundToInt

/**
 * A narrator's profile: tier, how often the user fails to place him, and every Hadith in the
 * library whose chain runs through him, with a shortcut to review all of them.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NarratorDetailSheet(
    profile: NarratorProfile,
    onDismiss: () -> Unit,
    onReviewHadiths: () -> Unit,
    onOpenHadith: (Long) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        NarratorDetailBody(profile, onReviewHadiths, onOpenHadith)
    }
}

/** The sheet's content without its window, so it can be rendered on its own. */
@Composable
internal fun NarratorDetailBody(
    profile: NarratorProfile,
    onReviewHadiths: () -> Unit,
    onOpenHadith: (Long) -> Unit,
) {
    val node = profile.node
    val scheme = MaterialTheme.colorScheme
    val health = ChainHealth.of(node.errorCount, node.successCount)

    run {
        Column(Modifier.fillMaxWidth()) {
            LazyColumn(
                modifier = Modifier.weight(1f, fill = false),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(node.name, style = MaterialTheme.typography.headlineSmall)
                        if (node.fullName.isNotBlank() && node.fullName != node.name) {
                            Text(node.fullName, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                        }
                        if (node.aliases.isNotEmpty()) {
                            Text(
                                stringResource(R.string.narrator_also_known, node.aliases.joinToString(stringResource(R.string.list_separator))),
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            InfoChip(tierLabel(node.tier), container = scheme.surfaceVariant, content = scheme.onSurfaceVariant)
                            if (node.isPivot) InfoChip(stringResource(R.string.narrator_pivot_badge))
                        }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatTile(node.hadithCount.toString(), stringResource(R.string.narrator_stat_hadiths), Modifier.weight(1f))
                        StatTile(node.attempts.toString(), stringResource(R.string.narrator_stat_attempts), Modifier.weight(1f))
                        StatTile(node.errorCount.toString(), stringResource(R.string.narrator_stat_errors), Modifier.weight(1f))
                        StatTile(
                            if (node.attempts == 0) stringResource(R.string.mastery_none) else stringResource(R.string.mastery_percent, (node.errorRate * 100).roundToInt()),
                            stringResource(R.string.narrator_stat_error_rate),
                            Modifier.weight(1f),
                            valueColor = health.color(),
                        )
                    }
                }
                item {
                    Text(
                        stringResource(R.string.narrator_hadiths_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                when {
                    profile.isLoading -> item {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                            Text(stringResource(R.string.narrator_hadiths_loading), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    profile.hadiths.isEmpty() -> item {
                        Text(stringResource(R.string.narrator_hadiths_empty), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                    }
                    else -> items(profile.hadiths, key = { it.hadithId }) { hadith ->
                        HadithRow(hadith, onClick = { onOpenHadith(hadith.hadithId) })
                    }
                }
            }
            Button(
                onClick = onReviewHadiths,
                enabled = !profile.isLoading && profile.hadiths.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(stringResource(if (node.isPivot) R.string.narrator_review_pivot else R.string.narrator_review_normal))
            }
        }
    }
}

@Composable
private fun HadithRow(hadith: NarratorHadith, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val health = ChainHealth.of(hadith.errorCount, hadith.successCount)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.hadith_number, hadith.hadithNumber),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                InfoChip(hadith.sourceBook, container = scheme.surfaceVariant, content = scheme.onSurfaceVariant)
                if (!hadith.isStudied) InfoChip(stringResource(R.string.badge_new))
            }
            Text(hadith.matn, style = MatnCompactTextStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.heatmap_node_position, hadith.position, hadith.chainLength),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    if (hadith.attempts == 0) {
                        stringResource(R.string.narrator_not_tested)
                    } else {
                        stringResource(R.string.heatmap_record, hadith.errorCount, hadith.attempts)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (health == ChainHealth.UNTESTED) scheme.onSurfaceVariant else scheme.onSurface,
                )
            }
        }
    }
}
