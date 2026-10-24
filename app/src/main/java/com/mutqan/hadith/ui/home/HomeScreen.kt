package com.mutqan.hadith.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.usecase.DashboardItem
import com.mutqan.hadith.domain.usecase.StudyDashboard
import com.mutqan.hadith.ui.common.InfoChip
import com.mutqan.hadith.ui.common.MasteryBadge
import com.mutqan.hadith.ui.common.MasteryRing
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.UiStateHost
import com.mutqan.hadith.ui.common.contentWidth
import com.mutqan.hadith.ui.common.masteryColor
import kotlin.math.roundToInt

/** Navigation requests the home screen can make. */
data class HomeActions(
    val onStartDueReview: () -> Unit,
    val onLearnNew: () -> Unit,
    val onReviewHadith: (Long) -> Unit,
    val onSanadBuilder: (Long) -> Unit,
    val onMissingLink: (Long) -> Unit,
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
        topBar = { MutqanTopBar(title = stringResource(R.string.home_title), onBack = null) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            UiStateHost(state, onRetry, Modifier.contentWidth()) { dashboard ->
                HomeContent(dashboard, actions, Modifier.contentWidth())
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeContent(dashboard: StudyDashboard, actions: HomeActions, modifier: Modifier = Modifier) {
    var sheetFor by rememberSaveable { mutableStateOf<Long?>(null) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SummaryCard(dashboard) }
        item { QuickActions(dashboard, actions) }
        item { DrillActions(dashboard, actions) }
        item {
            Text(
                stringResource(R.string.home_library),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        items(dashboard.items, key = { it.hadithId }) { item ->
            HadithRow(item, onClick = { sheetFor = item.hadithId })
        }
        item { Box(Modifier.size(24.dp)) }
    }

    sheetFor?.let { id ->
        ModalBottomSheet(onDismissRequest = { sheetFor = null }) {
            Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                SheetAction(stringResource(R.string.sheet_review_this)) { sheetFor = null; actions.onReviewHadith(id) }
                HorizontalDivider()
                SheetAction(stringResource(R.string.drill_sanad_builder)) { sheetFor = null; actions.onSanadBuilder(id) }
                HorizontalDivider()
                SheetAction(stringResource(R.string.drill_missing_link)) { sheetFor = null; actions.onMissingLink(id) }
            }
        }
    }
}

@Composable
private fun SheetAction(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 16.dp),
    )
}

@Composable
private fun SummaryCard(dashboard: StudyDashboard) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.home_today), style = MaterialTheme.typography.headlineSmall)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Stat(dashboard.dueCount.toString(), stringResource(R.string.home_due))
                Stat(dashboard.newCount.toString(), stringResource(R.string.home_new))
                MasteryStat(dashboard.overallMastery)
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun MasteryStat(mastery: Float) {
    val percent = (mastery * 100).roundToInt()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            MasteryRing(progress = mastery, color = masteryColor(mastery), size = 64.dp, strokeWidth = 6.dp)
            Text(
                stringResource(R.string.mastery_percent, percent),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(stringResource(R.string.home_mastery), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun QuickActions(dashboard: StudyDashboard, actions: HomeActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = actions.onStartDueReview,
                enabled = dashboard.dueCount > 0,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_start_review) + " (${dashboard.dueCount})") }
            FilledTonalButton(
                onClick = actions.onLearnNew,
                enabled = dashboard.newCount > 0,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(R.string.action_learn_new) + " (${dashboard.newCount})") }
        }
        val hint = when {
            dashboard.dueCount == 0 && dashboard.newCount == 0 -> R.string.home_nothing_new
            dashboard.dueCount == 0 -> R.string.home_nothing_due
            else -> null
        }
        if (hint != null) {
            Text(
                stringResource(hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun DrillActions(dashboard: StudyDashboard, actions: HomeActions) {
    val target = dashboard.drillSuggestionId ?: return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.home_drills), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { actions.onSanadBuilder(target) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.drill_sanad_builder))
            }
            OutlinedButton(onClick = { actions.onMissingLink(target) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.drill_missing_link))
            }
        }
    }
}

@Composable
private fun HadithRow(item: DashboardItem, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(44.dp).clip(CircleShape),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(item.hadithNumber.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(item.sourceBook, style = MaterialTheme.typography.titleMedium)
                    when {
                        item.isNew -> InfoChip(stringResource(R.string.badge_new))
                        item.isDue -> InfoChip(
                            stringResource(R.string.badge_due),
                            container = MaterialTheme.colorScheme.primaryContainer,
                            content = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                }
                Text(
                    stringResource(R.string.sahabi_label, item.sahabiName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MasteryBadge(stringResource(R.string.badge_matn), item.metrics?.matnMastery)
                    MasteryBadge(stringResource(R.string.badge_sanad), item.metrics?.sanadMastery)
                }
            }
        }
    }
}
