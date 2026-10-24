package com.mutqan.hadith.ui.sanadbuilder

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.drill.BuilderChip
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.ui.common.InfoChip
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.UiStateHost
import com.mutqan.hadith.ui.common.contentWidth
import com.mutqan.hadith.ui.common.shakeOn
import com.mutqan.hadith.ui.common.stateText
import com.mutqan.hadith.ui.theme.MatnCompactTextStyle
import com.mutqan.hadith.ui.theme.MutqanTheme
import com.mutqan.hadith.ui.theme.NarratorTextStyle

@Composable
fun SanadBuilderRoute(viewModel: SanadBuilderViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SanadBuilderScreen(state, viewModel::onEvent, viewModel::load, onClose)
}

@Composable
fun SanadBuilderScreen(
    state: UiState<SanadBuilderUi>,
    onEvent: (SanadBuilderEvent) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val ui = (state as? UiState.Success)?.data
    val snackbar = remember { SnackbarHostState() }
    val errorText = stringResource(R.string.review_submit_error)
    val submitError = ui?.submitError
    LaunchedEffect(submitError) {
        if (submitError != null) {
            snackbar.showSnackbar(errorText)
            onEvent(SanadBuilderEvent.DismissError)
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { MutqanTopBar(stringResource(R.string.builder_title), onBack = onClose) },
        bottomBar = { if (ui != null) BuilderBottomPanel(ui, onEvent, onClose) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            UiStateHost(state, onRetry, Modifier.contentWidth()) { data ->
                SanadBuilderContent(data, onEvent, Modifier.contentWidth())
            }
        }
    }
}

@Composable
fun SanadBuilderContent(ui: SanadBuilderUi, onEvent: (SanadBuilderEvent) -> Unit, modifier: Modifier = Modifier) {
    val builder = ui.builder
    val frozen = ui.result != null
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MatnAnchor(ui)

        ui.result?.let { ResultCard(it) } ?: Text(
            stringResource(R.string.builder_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            builder.slots.forEachIndexed { i, link ->
                val chip = builder.placed[i]
                SlotRow(
                    index = i,
                    isFirst = i == 0,
                    isLast = i == builder.slots.lastIndex,
                    term = link.transmissionTerm,
                    chip = chip,
                    correct = chip != null && builder.isCorrect(i),
                    selected = builder.selectedSlot == i,
                    shakeTrigger = ui.feedbackTick,
                    shaking = ui.lastFeedback?.let { it.slotIndex == i && !it.correct } == true,
                    onClick = {
                        if (frozen) return@SlotRow
                        if (chip == null) onEvent(SanadBuilderEvent.SelectSlot(i)) else onEvent(SanadBuilderEvent.RemoveFromSlot(i))
                    },
                )
            }
        }
    }
}

@Composable
private fun MatnAnchor(ui: SanadBuilderUi) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InfoChip(stringResource(R.string.builder_matn_anchor))
                InfoChip(ui.sourceBook, container = MaterialTheme.colorScheme.surfaceVariant, content = MaterialTheme.colorScheme.onSurfaceVariant)
                InfoChip(
                    stringResource(R.string.hadith_number, ui.hadithNumber),
                    container = MaterialTheme.colorScheme.primaryContainer,
                    content = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                ui.matn,
                style = MatnCompactTextStyle,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SlotRow(
    index: Int,
    isFirst: Boolean,
    isLast: Boolean,
    term: String,
    chip: BuilderChip?,
    correct: Boolean,
    selected: Boolean,
    shakeTrigger: Int,
    shaking: Boolean,
    onClick: () -> Unit,
) {
    val colors = MutqanTheme.colors
    val scheme = MaterialTheme.colorScheme
    val accent by animateColorAsState(
        when {
            chip != null && correct -> colors.correct
            chip != null -> colors.wrong
            selected -> scheme.primary
            else -> scheme.outlineVariant
        },
        label = "slot-accent",
    )
    val container = when {
        chip != null && correct -> colors.correctContainer.copy(alpha = 0.55f)
        chip != null -> colors.wrongContainer.copy(alpha = 0.55f)
        selected -> scheme.primaryContainer.copy(alpha = 0.45f)
        else -> scheme.surfaceContainerLowest
    }
    val stateDesc = when {
        chip == null -> null
        correct -> stringResource(R.string.builder_correct_desc)
        else -> stringResource(R.string.builder_wrong_desc)
    }
    val roleLabel = when {
        isFirst -> stringResource(R.string.chain_first)
        isLast -> stringResource(R.string.chain_last)
        else -> null
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(scheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text((index + 1).toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = scheme.onPrimary)
        }
        Surface(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .shakeOn(shakeTrigger, shaking)
                .clip(RoundedCornerShape(14.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .stateText(stateDesc),
            shape = RoundedCornerShape(14.dp),
            color = container,
            border = BorderStroke(if (chip != null || selected) 2.dp else 1.dp, accent),
        ) {
            Row(
                Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    when {
                        chip != null -> Text(chip.narrator.primaryName, style = NarratorTextStyle)
                        selected -> Text(
                            stringResource(R.string.builder_slot_selected),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.primary,
                        )
                        else -> Text(
                            stringResource(R.string.builder_slot_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    if (chip != null && !correct) {
                        Text(stringResource(R.string.builder_slot_wrong), style = MaterialTheme.typography.bodySmall, color = colors.wrong)
                    } else if (roleLabel != null) {
                        Text(roleLabel, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                if (chip != null) {
                    Icon(
                        if (correct) Icons.Filled.Check else Icons.Filled.Close,
                        contentDescription = null,
                        tint = accent,
                    )
                }
                InfoChip(term)
            }
        }
    }
}

@Composable
private fun TrayChip(chip: BuilderChip, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.4f)),
    ) {
        Text(
            chip.narrator.primaryName,
            style = NarratorTextStyle.copy(fontSize = 17.sp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun ResultCard(result: BuilderResult) {
    val colors = MutqanTheme.colors
    val (container, titleRes) = when (result.rating) {
        ReviewRating.GOOD, ReviewRating.EASY -> colors.correctContainer to R.string.builder_result_good
        ReviewRating.HARD -> MaterialTheme.colorScheme.secondaryContainer to R.string.builder_result_hard
        ReviewRating.AGAIN -> colors.wrongContainer to R.string.builder_result_again
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleLarge)
            if (result.misplacedNames.isNotEmpty()) {
                Text(
                    stringResource(R.string.builder_misplaced, result.misplacedNames.joinToString("، ")),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** The narrator tray and the buttons stay pinned, so a long chain never pushes them off screen. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BuilderBottomPanel(ui: SanadBuilderUi, onEvent: (SanadBuilderEvent) -> Unit, onClose: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.result == null) {
                Text(stringResource(R.string.builder_tray_title), style = MaterialTheme.typography.titleMedium)
                if (ui.builder.tray.isEmpty()) {
                    Text(
                        stringResource(R.string.builder_tray_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(
                        modifier = Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ui.builder.tray.forEach { chip -> TrayChip(chip) { onEvent(SanadBuilderEvent.PlaceChip(chip.chipId)) } }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ui.result == null) {
                    Button(
                        onClick = { onEvent(SanadBuilderEvent.Submit) },
                        enabled = ui.builder.isComplete && !ui.isSubmitting,
                        modifier = Modifier.weight(2f),
                    ) { Text(stringResource(R.string.builder_submit)) }
                    TextButton(
                        onClick = { onEvent(SanadBuilderEvent.Reveal) },
                        enabled = !ui.builder.isComplete,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.builder_reveal)) }
                } else {
                    Button(onClick = { onEvent(SanadBuilderEvent.Retry) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.builder_retry))
                    }
                    OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_back_home))
                    }
                }
            }
        }
    }
}
