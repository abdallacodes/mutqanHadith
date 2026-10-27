package com.mutqan.hadith.ui.analytics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.analytics.StudyStreak
import com.mutqan.hadith.domain.usecase.ChainHealth
import com.mutqan.hadith.domain.usecase.ComponentRetention
import com.mutqan.hadith.domain.usecase.DualRetention
import com.mutqan.hadith.domain.usecase.LibraryStatus
import com.mutqan.hadith.domain.usecase.MasteryAnalytics
import com.mutqan.hadith.domain.usecase.MemoryComponent
import com.mutqan.hadith.domain.usecase.WeakNarrator
import com.mutqan.hadith.ui.common.InfoChip
import com.mutqan.hadith.ui.common.MasteryRing
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.StatTile
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.UiStateHost
import com.mutqan.hadith.ui.common.contentWidth
import com.mutqan.hadith.ui.common.masteryColor
import com.mutqan.hadith.ui.common.tierLabel
import com.mutqan.hadith.ui.heatmap.color
import com.mutqan.hadith.ui.theme.MutqanTheme
import com.mutqan.hadith.ui.theme.NarratorTextStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun AnalyticsRoute(
    viewModel: AnalyticsViewModel,
    onClose: () -> Unit,
    onOpenNarrator: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Reviews made elsewhere change every figure here; recompute whenever the screen returns.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    AnalyticsScreen(state, viewModel::retry, onClose, onOpenNarrator)
}

@Composable
fun AnalyticsScreen(
    state: UiState<MasteryAnalytics>,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    onOpenNarrator: (Long) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { MutqanTopBar(stringResource(R.string.analytics_title), onBack = onClose) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            UiStateHost(state, onRetry, Modifier.contentWidth()) { analytics ->
                AnalyticsContent(analytics, onOpenNarrator, Modifier.contentWidth())
            }
        }
    }
}

@Composable
fun AnalyticsContent(
    analytics: MasteryAnalytics,
    onOpenNarrator: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (!analytics.hasActivity) {
            item {
                Text(
                    stringResource(R.string.analytics_empty_banner),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }
        item { StreakCard(analytics.streak) }
        item { DualMasteryCard(analytics.retention) }
        item { StatusCard(analytics.status) }
        item { WeakestNarratorsCard(analytics.weakestNarrators, onOpenNarrator) }
        item { Box(Modifier.size(16.dp)) }
    }
}

// -- Cards -----------------------------------------------------------------------------------

@Composable
private fun CardSection(
    title: String,
    modifier: Modifier = Modifier,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (hint != null) {
                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

@Composable
private fun StreakCard(streak: StudyStreak) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.analytics_streak_title), style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(streak.current.toString(), style = MaterialTheme.typography.displayLarge, fontWeight = FontWeight.Bold)
                Text(
                    stringResource(R.string.analytics_streak_unit),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            Text(
                stringResource(
                    when {
                        streak.studiedToday -> R.string.analytics_streak_today_done
                        streak.current > 0 -> R.string.analytics_streak_today_pending
                        else -> R.string.analytics_streak_start
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
            )
            WeekStrip(streak.lastSevenDays)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoChip(
                    stringResource(R.string.analytics_streak_best, streak.longest),
                    container = scheme.surface.copy(alpha = 0.7f),
                    content = scheme.onSurface,
                )
                InfoChip(
                    stringResource(R.string.analytics_streak_total, streak.studyDays),
                    container = scheme.surface.copy(alpha = 0.7f),
                    content = scheme.onSurface,
                )
            }
        }
    }
}

/** Seven dots, oldest first and today last and outlined; a filled dot is a day with a review. */
@Composable
internal fun WeekStrip(days: List<Boolean>) {
    val scheme = MaterialTheme.colorScheme
    val studied = stringResource(R.string.analytics_day_studied)
    val missed = stringResource(R.string.analytics_day_missed)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.analytics_week_title), style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            days.forEachIndexed { i, done ->
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(if (done) scheme.secondary else scheme.surface.copy(alpha = 0.55f))
                        .then(if (i == days.lastIndex) Modifier.border(2.dp, scheme.onSecondaryContainer, CircleShape) else Modifier)
                        .semantics { contentDescription = if (done) studied else missed },
                )
            }
        }
    }
}

@Composable
private fun DualMasteryCard(retention: DualRetention) {
    CardSection(
        title = stringResource(R.string.analytics_dual_title),
        hint = stringResource(R.string.analytics_dual_hint),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            RetentionRing(stringResource(R.string.badge_matn), retention.matn)
            RetentionRing(stringResource(R.string.badge_sanad), retention.sanad)
        }
        val insight = when {
            retention.matn == null || retention.sanad == null -> null
            retention.weaker == MemoryComponent.SANAD -> R.string.analytics_insight_sanad
            retention.weaker == MemoryComponent.MATN -> R.string.analytics_insight_matn
            else -> R.string.analytics_insight_balanced
        }
        if (insight != null) {
            Text(
                stringResource(insight),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RetentionRing(label: String, component: ComponentRetention?) {
    val none = stringResource(R.string.mastery_none)
    val percent = component?.let { stringResource(R.string.mastery_percent, (it.retention * 100).roundToInt()) } ?: none
    val color = if (component == null) MaterialTheme.colorScheme.outline else masteryColor(component.retention)
    val description = stringResource(R.string.analytics_dual_cd, label, percent)
    Column(
        Modifier.semantics(mergeDescendants = true) {
            contentDescription = description
        },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            MasteryRing(progress = component?.retention ?: 0f, color = color, size = 104.dp, strokeWidth = 10.dp)
            Text(percent, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = color)
        }
        Text(label, style = MaterialTheme.typography.titleMedium)
        Text(
            if (component == null) {
                stringResource(R.string.analytics_dual_unstudied)
            } else {
                stringResource(R.string.analytics_dual_stability, formatDays(component.averageStabilityDays))
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 0.4 → "0.4", 12.34 → "12.3", 120 → "120"; digits stay Western like the rest of the app. */
internal fun formatDays(days: Float): String =
    if (days >= 100f) days.roundToInt().toString() else String.format(Locale.ROOT, "%.1f", days).removeSuffix(".0")

@Composable
private fun StatusCard(status: LibraryStatus) {
    val colors = MutqanTheme.colors
    val track = MaterialTheme.colorScheme.outlineVariant
    CardSection(
        title = stringResource(R.string.analytics_status_title),
        hint = stringResource(R.string.analytics_status_summary, status.mastered, status.total),
    ) {
        StatusBar(
            segments = listOf(status.mastered to colors.heatStrong, status.learning to colors.heatFair, status.unseen to track),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile(status.mastered.toString(), stringResource(R.string.analytics_status_mastered), Modifier.weight(1f), colors.heatStrong)
            StatTile(status.learning.toString(), stringResource(R.string.analytics_status_learning), Modifier.weight(1f), colors.heatFair)
            StatTile(status.unseen.toString(), stringResource(R.string.analytics_status_unseen), Modifier.weight(1f))
        }
    }
}

/** A single bar split in proportion to the counts; segments of zero are left out. */
@Composable
private fun StatusBar(segments: List<Pair<Int, Color>>, height: Dp = 14.dp) {
    val shown = segments.filter { it.first > 0 }
    Row(
        Modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.outlineVariant),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        shown.forEach { (count, color) -> Box(Modifier.weight(count.toFloat()).fillMaxSize().background(color)) }
    }
}

@Composable
private fun WeakestNarratorsCard(narrators: List<WeakNarrator>, onDrill: (Long) -> Unit) {
    CardSection(
        title = stringResource(R.string.analytics_weak_title),
        hint = stringResource(R.string.analytics_weak_hint),
    ) {
        if (narrators.isEmpty()) {
            Text(
                stringResource(R.string.analytics_weak_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                narrators.forEachIndexed { index, narrator -> WeakNarratorRow(index + 1, narrator) { onDrill(narrator.narratorId) } }
            }
        }
    }
}

@Composable
private fun WeakNarratorRow(rank: Int, narrator: WeakNarrator, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val health = ChainHealth.of(narrator.errorCount, narrator.attempts - narrator.errorCount)
    val percent = (narrator.errorRate * 100).roundToInt()
    val description = stringResource(R.string.analytics_weak_cd, narrator.name, percent)
    val shape = RoundedCornerShape(16.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description },
        shape = shape,
        color = scheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, health.color().copy(alpha = 0.5f)),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(health.color()),
                contentAlignment = Alignment.Center,
            ) {
                Text(rank.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = scheme.surface)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(narrator.name, style = NarratorTextStyle, color = scheme.onSurface, modifier = Modifier.weight(1f, fill = false))
                    if (narrator.isPivot) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = scheme.secondary, modifier = Modifier.size(16.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    InfoChip(tierLabel(narrator.tier), container = scheme.surfaceVariant, content = scheme.onSurfaceVariant)
                    Text(
                        stringResource(R.string.heatmap_record, narrator.errorCount, narrator.attempts),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    stringResource(R.string.mastery_percent, percent),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = health.color(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.analytics_weak_drill), style = MaterialTheme.typography.labelMedium, color = scheme.primary)
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}
