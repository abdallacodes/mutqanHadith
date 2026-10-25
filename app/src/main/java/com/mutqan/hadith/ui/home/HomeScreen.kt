package com.mutqan.hadith.ui.home

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.analytics.StudyStreak
import com.mutqan.hadith.domain.model.ReviewBatching
import com.mutqan.hadith.domain.usecase.NextToLearn
import com.mutqan.hadith.domain.usecase.StudyDashboard
import com.mutqan.hadith.ui.common.InfoChip
import com.mutqan.hadith.ui.common.LockedHint
import com.mutqan.hadith.ui.common.SanadDrillButtons
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.StatTile
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.UiStateHost
import com.mutqan.hadith.ui.common.contentWidth
import com.mutqan.hadith.ui.common.masteryColor
import com.mutqan.hadith.ui.theme.MatnCompactTextStyle
import com.mutqan.hadith.ui.theme.MutqanTheme
import kotlin.math.roundToInt

/** Navigation requests the home screen can make. */
data class HomeActions(
    val onStartDueReview: () -> Unit,
    /** Opens the reader of a Hadith that has not been started, from which its memorization begins. */
    val onStudyHadith: (Long) -> Unit,
    val onSanadBuilder: (Long) -> Unit,
    val onMissingLink: (Long) -> Unit,
    val onOpenSettings: () -> Unit,
    val onOrderChain: (Long) -> Unit = {},
)

@Composable
fun HomeRoute(viewModel: HomeViewModel, actions: HomeActions) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Coming back from a review changes what is due; the data flow updates itself, due-ness needs a nudge.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    HomeScreen(state = state, actions = actions, onRetry = viewModel::refresh)
}

@Composable
fun HomeScreen(state: UiState<StudyDashboard>, actions: HomeActions, onRetry: () -> Unit) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            val settingsLabel = stringResource(R.string.settings_open)
            MutqanTopBar(
                title = stringResource(R.string.home_title),
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
            UiStateHost(state, onRetry, Modifier.contentWidth()) { dashboard ->
                HomeContent(dashboard, actions, Modifier.contentWidth())
            }
        }
    }
}

/** The day's study in the order it is done: keep the streak, review what is due, then go on to what is new. */
@Composable
fun HomeContent(dashboard: StudyDashboard, actions: HomeActions, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { StreakHeader(dashboard.streak) }
        item { DailyReviewSection(dashboard, actions.onStartDueReview) }
        item { NextStepSection(dashboard.nextToLearn, primary = dashboard.dueCount == 0, onStudy = actions.onStudyHadith) }
        item { QuickStatsRow(dashboard) }
        item { DrillActions(dashboard, actions) }
        item { Box(Modifier.size(8.dp)) }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleLarge)
}

@Composable
private fun StreakHeader(streak: StudyStreak) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                if (streak.current > 0) {
                    pluralStringResource(R.plurals.home_streak_days, streak.current, streak.current)
                } else {
                    stringResource(R.string.home_streak_none)
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            if (streak.current > 0) {
                Text(
                    stringResource(if (streak.studiedToday) R.string.analytics_streak_today_done else R.string.analytics_streak_today_pending),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun DailyReviewSection(dashboard: StudyDashboard, onStart: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.home_review_title))
        when {
            dashboard.dueCount > 0 -> Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (ReviewBatching.isBacklog(dashboard.dueCount)) {
                        val batch = ReviewBatching.nextBatchSize(dashboard.dueCount)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                pluralStringResource(R.plurals.home_review_batch, batch, batch, dashboard.dueCount),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(stringResource(R.string.home_review_backlog_note), style = MaterialTheme.typography.bodyMedium)
                        }
                    } else {
                        Text(
                            pluralStringResource(R.plurals.home_review_due, dashboard.dueCount, dashboard.dueCount),
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                    SessionEstimate(dashboard.dueCount)
                    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(stringResource(R.string.home_review_start), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            dashboard.studiedCount > 0 -> ReviewDoneBadge()
            else -> Text(
                stringResource(R.string.home_review_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How long the next sitting takes, so that five free minutes can be matched to it: "3 hadiths · ~4 mins". */
@Composable
private fun SessionEstimate(dueCount: Int) {
    val hadiths = ReviewBatching.nextBatchSize(dueCount)
    val minutes = ReviewBatching.estimatedMinutes(dueCount)
    Text(
        stringResource(
            R.string.home_review_estimate,
            pluralStringResource(R.plurals.hadith_count, hadiths, hadiths),
            pluralStringResource(R.plurals.home_review_minutes, minutes, minutes),
        ),
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.Center,
    )
}

/** Calm, not celebratory: today's reviews are done, and nothing more is asked of the user. */
@Composable
private fun ReviewDoneBadge() {
    val colors = MutqanTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = colors.correctContainer.copy(alpha = 0.55f),
        border = BorderStroke(1.dp, colors.correct.copy(alpha = 0.5f)),
    ) {
        Row(
            Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.correct, modifier = Modifier.size(32.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(R.string.home_review_done),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.home_review_done_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The first Hadith not yet started, in curriculum order; the button is the primary one once no review is waiting. */
@Composable
private fun NextStepSection(next: NextToLearn?, primary: Boolean, onStudy: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(R.string.home_next_title))
        if (next == null) {
            Text(
                stringResource(R.string.home_nothing_new),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        modifier = Modifier.size(44.dp).clip(CircleShape),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(next.hadithNumber.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.home_next_number, next.hadithNumber), style = MaterialTheme.typography.titleMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            InfoChip(stringResource(R.string.level_badge, next.level))
                            if (next.sahabiName.isNotBlank()) {
                                Text(
                                    stringResource(R.string.sahabi_label, next.sahabiName),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                        }
                    }
                }
                Text(
                    next.matn,
                    style = MatnCompactTextStyle,
                    maxLines = PREVIEW_LINES,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (primary) {
                    Button(onClick = { onStudy(next.hadithId) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_next_start))
                    }
                } else {
                    FilledTonalButton(onClick = { onStudy(next.hadithId) }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.home_next_start))
                    }
                }
            }
        }
    }
}

private const val PREVIEW_LINES = 3

@Composable
private fun QuickStatsRow(dashboard: StudyDashboard) {
    val retention = dashboard.retention
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile(
            dashboard.learningCount.toString(),
            stringResource(R.string.home_stats_learning),
            Modifier.weight(1f).fillMaxHeight(),
        )
        StatTile(
            dashboard.masteredCount.toString(),
            stringResource(R.string.home_stats_mastered),
            Modifier.weight(1f).fillMaxHeight(),
        )
        StatTile(
            if (retention == null) stringResource(R.string.mastery_none) else stringResource(R.string.mastery_percent, (retention * 100).roundToInt()),
            stringResource(R.string.home_stats_retention),
            Modifier.weight(1f).fillMaxHeight(),
            valueColor = if (retention == null) MaterialTheme.colorScheme.onSurface else masteryColor(retention),
        )
    }
}

@Composable
private fun DrillActions(dashboard: StudyDashboard, actions: HomeActions) {
    val target = dashboard.drillSuggestionId
    // The drills are on the chain, which opens hadith by hadith; with nothing studied there is nothing to wait for yet.
    if (target == null) {
        if (dashboard.studiedCount > 0) {
            // A chain opened but too short for any drill is another thing to wait for than a chain not yet opened.
            val chainOpened = dashboard.items.any { !it.isNew && it.layer.layer.opensSanad }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.home_drills), style = MaterialTheme.typography.titleMedium)
                LockedHint(stringResource(if (chainOpened) R.string.home_drills_short else R.string.home_drills_locked))
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.home_drills), style = MaterialTheme.typography.titleMedium)
        SanadDrillButtons(target, dashboard.drillSuggestionDrills, actions.onSanadBuilder, actions.onMissingLink, actions.onOrderChain)
    }
}
