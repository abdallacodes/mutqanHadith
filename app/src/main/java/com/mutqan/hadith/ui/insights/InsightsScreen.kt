package com.mutqan.hadith.ui.insights

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.usecase.ComponentRetention
import com.mutqan.hadith.domain.usecase.MasteryAnalytics
import com.mutqan.hadith.ui.analytics.WeekStrip
import com.mutqan.hadith.ui.analytics.formatDays
import com.mutqan.hadith.ui.common.InfoChip
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.NetworkIcon
import com.mutqan.hadith.ui.common.StatTile
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.contentWidth
import com.mutqan.hadith.ui.common.masteryColor
import kotlin.math.roundToInt

/** Where the Insights tab leads. */
data class InsightsActions(
    val onOpenGraph: () -> Unit,
    val onOpenAnalytics: () -> Unit,
    val onOpenSettings: () -> Unit,
)

@Composable
fun InsightsRoute(viewModel: InsightsViewModel, actions: InsightsActions) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Reviews made elsewhere change every figure here; recompute whenever the tab is shown again.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    InsightsScreen(state, actions, viewModel::retry)
}

/**
 * The third tab: the narrator network, and summaries of the mastery analytics and the memory clocks. Only the
 * summaries depend on the figures, so the way into the network and the settings never waits for them.
 */
@Composable
fun InsightsScreen(state: UiState<MasteryAnalytics>, actions: InsightsActions, onRetry: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val settingsLabel = stringResource(R.string.settings_open)
            MutqanTopBar(
                title = stringResource(R.string.insights_title),
                onBack = null,
                actions = {
                    IconButton(onClick = actions.onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = settingsLabel)
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                modifier = Modifier.contentWidth().fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { GraphEntry(actions.onOpenGraph) }
                item { AnalyticsSummary(state, actions.onOpenAnalytics, onRetry) }
                item { MemoryClocks(state, actions.onOpenAnalytics, onRetry) }
                item { SettingsEntry(actions.onOpenSettings) }
                item { Box(Modifier.size(8.dp)) }
            }
        }
    }
}

@Composable
private fun PlainCard(onClick: () -> Unit, colors: CardColors? = null, content: @Composable () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = colors ?: CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = if (colors == null) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) { content() }
}

/** A title over its hint, with the chevron that says the whole card opens something. */
@Composable
private fun CardHeader(title: String, hint: String, leading: (@Composable () -> Unit)? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) leading()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = LocalContentColor.current.copy(alpha = 0.78f))
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun GraphEntry(onClick: () -> Unit) {
    PlainCard(onClick) {
        Box(Modifier.padding(18.dp)) {
            CardHeader(
                title = stringResource(R.string.insights_graph_title),
                hint = stringResource(R.string.insights_graph_hint),
                leading = { NetworkIcon(tint = MaterialTheme.colorScheme.primary, size = 32.dp) },
            )
        }
    }
}

@Composable
private fun AnalyticsSummary(state: UiState<MasteryAnalytics>, onOpen: () -> Unit, onRetry: () -> Unit) {
    PlainCard(onOpen) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CardHeader(stringResource(R.string.insights_analytics_title), stringResource(R.string.insights_analytics_hint))
            SummaryBody(state, onRetry) { analytics ->
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RetentionTile(stringResource(R.string.badge_matn), analytics.retention.matn, Modifier.weight(1f).fillMaxHeight())
                    RetentionTile(stringResource(R.string.badge_sanad), analytics.retention.sanad, Modifier.weight(1f).fillMaxHeight())
                }
                Text(
                    stringResource(R.string.analytics_status_summary, analytics.status.mastered, analytics.status.total),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RetentionTile(label: String, component: ComponentRetention?, modifier: Modifier) {
    StatTile(
        value = component?.let { stringResource(R.string.mastery_percent, (it.retention * 100).roundToInt()) } ?: stringResource(R.string.mastery_none),
        label = label,
        modifier = modifier,
        valueColor = component?.let { masteryColor(it.retention) } ?: MaterialTheme.colorScheme.onSurface,
    )
}

/** The streak and the week it was made of, and how many days the memory of each component holds. */
@Composable
private fun MemoryClocks(state: UiState<MasteryAnalytics>, onOpen: () -> Unit, onRetry: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    PlainCard(onOpen, CardDefaults.cardColors(containerColor = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            CardHeader(stringResource(R.string.insights_memory_title), stringResource(R.string.insights_memory_hint))
            SummaryBody(state, onRetry) { analytics ->
                val streak = analytics.streak
                Text(
                    if (streak.current > 0) {
                        pluralStringResource(R.plurals.home_streak_days, streak.current, streak.current)
                    } else {
                        stringResource(R.string.home_streak_none)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                WeekStrip(streak.lastSevenDays)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ClockChip(R.string.insights_clock_matn, analytics.retention.matn)
                    ClockChip(R.string.insights_clock_sanad, analytics.retention.sanad)
                }
            }
        }
    }
}

@Composable
private fun ClockChip(label: Int, component: ComponentRetention?) {
    val value = component?.let { stringResource(R.string.insights_clock_value, formatDays(it.averageStabilityDays)) }
        ?: stringResource(R.string.mastery_none)
    InfoChip(
        stringResource(label, value),
        container = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
        content = MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
private fun SettingsEntry(onClick: () -> Unit) {
    PlainCard(onClick) {
        Box(Modifier.padding(18.dp)) {
            CardHeader(stringResource(R.string.settings_open), stringResource(R.string.insights_settings_hint))
        }
    }
}

/** What a summary card shows while its figures load or fail; the card itself is always there. */
@Composable
private fun SummaryBody(state: UiState<MasteryAnalytics>, onRetry: () -> Unit, content: @Composable (MasteryAnalytics) -> Unit) {
    when (state) {
        UiState.Loading -> Text(stringResource(R.string.loading), style = MaterialTheme.typography.bodyMedium)
        is UiState.Error -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.error_generic), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
        is UiState.Success -> content(state.data)
    }
}
