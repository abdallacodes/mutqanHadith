package com.mutqan.hadith.ui.placement

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.model.RecallBand
import com.mutqan.hadith.domain.model.StartingPoint
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.contentWidth
import com.mutqan.hadith.ui.common.labelRes
import com.mutqan.hadith.ui.theme.MatnTextStyle

/**
 * @param firstRun the screen is shown at the first launch, where leaving it without an answer counts as "beginner":
 *   the app is as it is for anyone new and is not asked again. From Settings, Back just closes it.
 */
@Composable
fun PlacementRoute(viewModel: PlacementViewModel, firstRun: Boolean, onClose: () -> Unit) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(ui.finished) { if (ui.finished) onClose() }
    BackHandler(enabled = firstRun && ui.step is PlacementStep.Pick) { viewModel.choose(StartingPoint.BEGINNER) }
    PlacementScreen(
        ui = ui,
        actions = PlacementActions(
            onBack = if (firstRun) null else onClose,
            onChoose = viewModel::choose,
            onTakeCheck = viewModel::startCheck,
            onSkipCheck = viewModel::skipCheck,
            onReveal = viewModel::reveal,
            onRate = viewModel::rate,
            onFinishRest = viewModel::finishRest,
            onDone = viewModel::close,
        ),
    )
}

data class PlacementActions(
    val onBack: (() -> Unit)?,
    val onChoose: (StartingPoint) -> Unit,
    val onTakeCheck: () -> Unit,
    val onSkipCheck: () -> Unit,
    val onReveal: () -> Unit,
    val onRate: (RecallBand?) -> Unit,
    val onFinishRest: () -> Unit,
    val onDone: () -> Unit,
)

@Composable
fun PlacementScreen(ui: PlacementUi, actions: PlacementActions) {
    val title = stringResource(if (ui.step is PlacementStep.Check) R.string.placement_check_title else R.string.placement_title)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { MutqanTopBar(title, onBack = actions.onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.contentWidth().fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when (val step = ui.step) {
                    PlacementStep.Pick -> PickStep(ui.current, actions.onChoose)
                    is PlacementStep.Offer -> OfferStep(step.point, ui.isBusy, actions)
                    is PlacementStep.Check -> CheckStep(step, ui.isBusy, actions)
                    is PlacementStep.Done -> DoneStep(step, actions.onDone)
                }
            }
        }
    }
}

@Composable
private fun PickStep(current: StartingPoint?, onChoose: (StartingPoint) -> Unit) {
    Text(stringResource(R.string.placement_pick_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(
        stringResource(R.string.placement_pick_subtitle),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    StartingPoint.entries.forEach { point ->
        PointCard(
            point = point,
            description = stringResource(point.descriptionRes()),
            selected = point == current,
            onClick = { onChoose(point) },
        )
    }
}

private fun StartingPoint.descriptionRes(): Int = when (this) {
    StartingPoint.BEGINNER -> R.string.placement_beginner_desc
    StartingPoint.INTERMEDIATE -> R.string.placement_intermediate_desc
    StartingPoint.EXPERT -> R.string.placement_expert_desc
}

@Composable
private fun PointCard(point: StartingPoint, description: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) scheme.primaryContainer else scheme.surfaceContainerLowest),
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            RadioButton(selected = selected, onClick = null)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(point.labelRes), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun OfferStep(point: StartingPoint, busy: Boolean, actions: PlacementActions) {
    val band = point.defaultBand ?: return
    Text(stringResource(R.string.placement_offer_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.placement_offer_body), style = MaterialTheme.typography.bodyLarge)
    Button(onClick = actions.onTakeCheck, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.placement_offer_check))
    }
    OutlinedButton(onClick = actions.onSkipCheck, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.placement_offer_skip, stringResource(band.labelRes)))
    }
}

@Composable
private fun CheckStep(step: PlacementStep.Check, busy: Boolean, actions: PlacementActions) {
    val card = step.card
    LinearProgressIndicator(progress = { step.index.toFloat() / step.cards.size }, modifier = Modifier.fillMaxWidth())
    Text(
        stringResource(R.string.placement_check_progress, step.index + 1, step.cards.size),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.hadith_number, card.hadithNumber), style = MaterialTheme.typography.titleLarge)
            if (step.revealed) {
                Text(card.matn, style = MatnTextStyle, modifier = Modifier.fillMaxWidth())
            } else {
                Text(card.cue, style = MatnTextStyle, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.placement_check_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (!step.revealed) {
        Button(onClick = actions.onReveal, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.placement_check_reveal)) }
    } else {
        Text(stringResource(R.string.placement_check_ask), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        // Best first, so the answer that needs no further thought is the first one.
        RecallBand.entries.reversed().forEach { band ->
            OutlinedButton(onClick = { actions.onRate(band) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(band.labelRes))
            }
        }
        OutlinedButton(onClick = { actions.onRate(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.placement_check_none))
        }
    }
    step.point.defaultBand?.let { band ->
        TextButton(onClick = actions.onFinishRest, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.placement_check_finish, stringResource(band.labelRes)))
        }
    }
}

@Composable
private fun DoneStep(step: PlacementStep.Done, onDone: () -> Unit) {
    Text(stringResource(R.string.placement_done_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    val total = step.tally.values.sum()
    if (total == 0) {
        Text(stringResource(R.string.placement_done_none), style = MaterialTheme.typography.bodyLarge)
    } else {
        Text(stringResource(R.string.placement_done_body, total), style = MaterialTheme.typography.bodyLarge)
        RecallBand.entries.reversed().forEach { band ->
            val count = step.tally[band] ?: return@forEach
            Text(stringResource(R.string.placement_done_band, stringResource(band.labelRes), count), style = MaterialTheme.typography.titleMedium)
        }
    }
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.placement_done_button)) }
}
