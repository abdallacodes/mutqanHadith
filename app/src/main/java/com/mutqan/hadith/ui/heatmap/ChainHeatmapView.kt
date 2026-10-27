package com.mutqan.hadith.ui.heatmap

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.usecase.ChainHealth
import com.mutqan.hadith.domain.usecase.ChainHeatmap
import com.mutqan.hadith.domain.usecase.NarratorHeat
import com.mutqan.hadith.ui.common.InfoChip
import com.mutqan.hadith.ui.common.StatTile
import com.mutqan.hadith.ui.common.tierLabel
import com.mutqan.hadith.ui.theme.MutqanTheme
import com.mutqan.hadith.ui.theme.NarratorTextStyle
import com.mutqan.hadith.ui.common.NarratorNameText
import com.mutqan.hadith.ui.common.narratorOpener
import kotlin.math.roundToInt

/** The colour that stands for a [ChainHealth]; an untested narrator gets the neutral outline colour. */
@Composable
fun ChainHealth.color(): Color = with(MutqanTheme.colors) {
    when (this@color) {
        ChainHealth.STRONG -> heatStrong
        ChainHealth.FAIR -> heatFair
        ChainHealth.FRAGILE -> heatFragile
        ChainHealth.UNTESTED -> MaterialTheme.colorScheme.outline
    }
}

@Composable
fun ChainHealth.label(): String = stringResource(
    when (this) {
        ChainHealth.STRONG -> R.string.health_strong
        ChainHealth.FAIR -> R.string.health_fair
        ChainHealth.FRAGILE -> R.string.health_fragile
        ChainHealth.UNTESTED -> R.string.health_untested
    },
)

private fun NarratorHeat.retentionPercent(): Int = (performance.retentionRate * 100).roundToInt()

/**
 * A Sanad drawn as a chain whose every link is coloured by how well the user recalls it: green above
 * 85%, ochre from 60% to 85%, coral below 60% or on the weakest link, grey when never tested.
 * The transmission term sits between neighbouring links. Tapping a link opens its record.
 *
 * [compact] packs the chain into a wrapping strip of chips, for places that already show the chain.
 */
@Composable
fun ChainHeatmapView(
    heatmap: ChainHeatmap,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showLegend: Boolean = true,
) {
    var selectedIndex by rememberSaveable { mutableStateOf<Int?>(null) }
    val selected = heatmap.nodes.firstOrNull { it.orderIndex == selectedIndex }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (showLegend) HeatLegend()
        if (compact) {
            CompactChain(heatmap) { selectedIndex = it.orderIndex }
        } else {
            FullChain(heatmap) { selectedIndex = it.orderIndex }
        }
    }

    if (selected != null) {
        NarratorHeatSheet(selected, chainLength = heatmap.nodes.size, onDismiss = { selectedIndex = null })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeatLegend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LegendItem(ChainHealth.STRONG, stringResource(R.string.heatmap_legend_strong, ChainHealth.STRONG_ABOVE_PERCENT))
        LegendItem(
            ChainHealth.FAIR,
            stringResource(R.string.heatmap_legend_fair, ChainHealth.FRAGILE_BELOW_PERCENT, ChainHealth.STRONG_ABOVE_PERCENT),
        )
        LegendItem(ChainHealth.FRAGILE, stringResource(R.string.heatmap_legend_fragile, ChainHealth.FRAGILE_BELOW_PERCENT))
    }
}

@Composable
private fun LegendItem(health: ChainHealth, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(health.color()))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// -- Full timeline ---------------------------------------------------------------------------

@Composable
private fun FullChain(heatmap: ChainHeatmap, onNodeClick: (NarratorHeat) -> Unit) {
    Column {
        if (heatmap.collector.isNotBlank()) {
            Row(Modifier.padding(start = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                InfoChip(
                    stringResource(R.string.heatmap_collector, heatmap.collector),
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        heatmap.nodes.forEachIndexed { i, node ->
            // The term on a node says how the narrator before him in the list received from him.
            if (i > 0 || heatmap.collector.isNotBlank()) TermConnector(node.transmissionTerm)
            HeatNodeCard(position = i + 1, node = node, onClick = { onNodeClick(node) })
        }
    }
}

@Composable
private fun TermConnector(term: String) {
    Row(Modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(32.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
        }
        if (term.isNotBlank()) InfoChip(term)
    }
}

@Composable
private fun HeatNodeCard(position: Int, node: NarratorHeat, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val health = node.health
    val accent = health.color()
    val shape = RoundedCornerShape(14.dp)
    val description = stringResource(R.string.heat_node_cd, node.performance.narratorName, health.label())

    Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.width(32.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(28.dp).clip(CircleShape).background(scheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(position.toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = scheme.onPrimary)
            }
        }
        Surface(
            modifier = Modifier
                .weight(1f)
                .clip(shape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics(mergeDescendants = true) { contentDescription = description },
            shape = shape,
            color = if (health == ChainHealth.UNTESTED) scheme.surfaceContainerLowest else accent.copy(alpha = 0.10f),
            border = BorderStroke(if (health == ChainHealth.FRAGILE) 2.dp else 1.dp, accent.copy(alpha = if (health == ChainHealth.UNTESTED) 0.5f else 0.9f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NarratorNameText(
                        node.performance.narratorName, NarratorTextStyle, scheme.onSurface, Modifier.weight(1f, fill = false),
                        onOpen = narratorOpener(node.performance.narratorId),
                    )
                    if (node.isWeakestLink) WeakestBadge()
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    InfoChip(tierLabel(node.tier), container = scheme.surfaceVariant, content = scheme.onSurfaceVariant)
                    InfoChip(health.label(), container = accent.copy(alpha = 0.18f), content = scheme.onSurface)
                    Text(
                        if (node.attempts == 0) {
                            stringResource(R.string.heatmap_untested_record)
                        } else {
                            stringResource(R.string.heatmap_record, node.performance.errorCount, node.attempts)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                RetentionBar(node.performance.retentionRate, accent, enabled = node.attempts > 0)
            }
        }
    }
}

@Composable
private fun WeakestBadge() {
    Surface(shape = RoundedCornerShape(50), color = MutqanTheme.colors.heatFragile.copy(alpha = 0.18f), contentColor = MaterialTheme.colorScheme.onSurface) {
        Text(
            stringResource(R.string.heatmap_weakest),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun RetentionBar(retention: Float, color: Color, enabled: Boolean) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (enabled) Box(Modifier.fillMaxWidth(retention.coerceIn(0.03f, 1f)).fillMaxHeight().background(color))
    }
}

// -- Compact strip ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompactChain(heatmap: ChainHeatmap, onNodeClick: (NarratorHeat) -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        heatmap.nodes.forEachIndexed { i, node ->
            if (i > 0) {
                // The arrow points the way the chain reads: leftwards in right-to-left, rightwards in left-to-right.
                Text(
                    "${node.transmissionTerm} ${if (LocalLayoutDirection.current == LayoutDirection.Rtl) "←" else "→"}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HeatChip(node) { onNodeClick(node) }
        }
    }
}

@Composable
private fun HeatChip(node: NarratorHeat, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val health = node.health
    val accent = health.color()
    val description = stringResource(R.string.heat_node_cd, node.performance.narratorName, health.label())
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        shape = RoundedCornerShape(50),
        color = accent.copy(alpha = if (health == ChainHealth.UNTESTED) 0.06f else 0.14f),
        border = BorderStroke(if (health == ChainHealth.FRAGILE) 2.dp else 1.dp, accent),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            NarratorNameText(
                node.performance.narratorName, NarratorTextStyle.copy(fontSize = 16.sp, lineHeight = 24.sp), scheme.onSurface,
                onOpen = narratorOpener(node.performance.narratorId),
            )
            if (node.isWeakestLink) {
                Text(stringResource(R.string.heatmap_weakest), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = scheme.onSurface)
            }
        }
    }
}

// -- Node record -----------------------------------------------------------------------------

/** The quick look at one link: how often it was tested, how often missed, and how well it is kept. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NarratorHeatSheet(node: NarratorHeat, chainLength: Int, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        NarratorHeatBody(node, chainLength)
    }
}

/** The sheet's content without its window, so it can be rendered on its own. */
@Composable
internal fun NarratorHeatBody(node: NarratorHeat, chainLength: Int) {
    val scheme = MaterialTheme.colorScheme
    val health = node.health
    val accent = health.color()
    run {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(node.performance.narratorName, style = MaterialTheme.typography.headlineSmall)
                if (node.fullName.isNotBlank() && node.fullName != node.performance.narratorName) {
                    Text(node.fullName, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InfoChip(tierLabel(node.tier), container = scheme.surfaceVariant, content = scheme.onSurfaceVariant)
                InfoChip(stringResource(R.string.heatmap_node_position, node.orderIndex + 1, chainLength))
                InfoChip(health.label(), container = accent.copy(alpha = 0.18f), content = scheme.onSurface)
            }
            if (node.isWeakestLink) {
                Text(
                    stringResource(R.string.heatmap_weakest_note),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = scheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MutqanTheme.colors.heatFragile.copy(alpha = 0.16f))
                        .padding(12.dp),
                )
            }
            if (node.attempts == 0) {
                Text(stringResource(R.string.heatmap_untested_record), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(node.performance.errorCount.toString(), stringResource(R.string.heat_sheet_errors), Modifier.weight(1f))
                    StatTile(node.attempts.toString(), stringResource(R.string.heat_sheet_appearances), Modifier.weight(1f))
                    StatTile(
                        stringResource(R.string.mastery_percent, node.retentionPercent()),
                        stringResource(R.string.heat_sheet_retention),
                        Modifier.weight(1f),
                        valueColor = accent,
                    )
                }
                RetentionBar(node.performance.retentionRate, accent, enabled = true)
            }
        }
    }
}
