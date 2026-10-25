package com.mutqan.hadith.ui.orderchain

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.drill.OrderChip
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.ui.common.MatnAnchorCard
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.UiStateHost
import com.mutqan.hadith.ui.common.contentWidth
import com.mutqan.hadith.ui.common.shakeOn
import com.mutqan.hadith.ui.common.stateText
import com.mutqan.hadith.ui.theme.MutqanTheme
import com.mutqan.hadith.ui.theme.NarratorTextStyle

@Composable
fun OrderChainRoute(viewModel: OrderChainViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    OrderChainScreen(state, viewModel::onEvent, viewModel::load, onClose)
}

@Composable
fun OrderChainScreen(
    state: UiState<OrderChainUi>,
    onEvent: (OrderChainEvent) -> Unit,
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
            onEvent(OrderChainEvent.DismissError)
        }
    }
    // A buzz for a check that found mistakes, and another for an order that was right.
    val haptics = LocalHapticFeedback.current
    val tick = ui?.feedbackTick ?: 0
    LaunchedEffect(tick) {
        if (tick > 0) haptics.performHapticFeedback(HapticFeedbackType.Reject)
    }
    val result = ui?.result
    LaunchedEffect(result) {
        if (result != null && !result.revealed) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { MutqanTopBar(stringResource(R.string.order_title), onBack = onClose) },
        bottomBar = { if (ui != null) OrderBottomPanel(ui, onEvent, onClose) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            UiStateHost(state, onRetry, Modifier.contentWidth()) { data ->
                OrderChainContent(data, onEvent, Modifier.contentWidth())
            }
        }
    }
}

@Composable
fun OrderChainContent(ui: OrderChainUi, onEvent: (OrderChainEvent) -> Unit, modifier: Modifier = Modifier) {
    val chain = ui.chain
    val frozen = chain.isSolved || ui.isSubmitting
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MatnAnchorCard(ui.sourceBook, ui.hadithNumber, ui.matn, label = stringResource(R.string.builder_matn_anchor))

        Text(
            stringResource(R.string.order_instruction),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Caption(stringResource(R.string.order_top_caption))
            chain.order.forEachIndexed { place, chip ->
                OrderRow(
                    place = place,
                    chip = chip,
                    qualifier = qualifierOf(chip, chain.needsQualifier(chip)),
                    judged = chain.verdict?.correctPlaces?.get(place),
                    selected = chain.selected == place,
                    canMoveUp = !frozen && place > 0,
                    canMoveDown = !frozen && place < chain.order.lastIndex,
                    shakeTrigger = ui.feedbackTick,
                    onTap = { if (!frozen) onEvent(OrderChainEvent.Tap(place)) },
                    onMove = { delta -> onEvent(OrderChainEvent.Move(place, delta)) },
                )
            }
            Caption(stringResource(R.string.order_bottom_caption))
        }
    }
}

/** A name that another narrator of the chain shares or opens is told apart by his full name, or else his tier. */
private fun qualifierOf(chip: OrderChip, needed: Boolean): String? = when {
    !needed -> null
    chip.narrator.fullName.isNotBlank() && chip.narrator.fullName != chip.narrator.primaryName -> chip.narrator.fullName
    else -> chip.narrator.tier
}

@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun OrderRow(
    place: Int,
    chip: OrderChip,
    qualifier: String?,
    /** Whether this place held the right man when the order was last checked; null before a check. */
    judged: Boolean?,
    selected: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    shakeTrigger: Int,
    onTap: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val colors = MutqanTheme.colors
    val scheme = MaterialTheme.colorScheme
    val accent by animateColorAsState(
        when {
            judged == true -> colors.correct
            judged == false -> colors.wrong
            selected -> scheme.primary
            else -> scheme.outlineVariant
        },
        label = "order-accent",
    )
    val container = when {
        judged == true -> colors.correctContainer.copy(alpha = 0.55f)
        judged == false -> colors.wrongContainer.copy(alpha = 0.55f)
        selected -> scheme.primaryContainer.copy(alpha = 0.45f)
        else -> scheme.surfaceContainerLowest
    }
    val stateDesc = when {
        judged == true -> stringResource(R.string.builder_correct_desc)
        judged == false -> stringResource(R.string.builder_wrong_desc)
        selected -> stringResource(R.string.order_selected_desc)
        else -> null
    }
    val shape = RoundedCornerShape(14.dp)
    val placeDesc = stringResource(R.string.order_place_desc, place + 1)

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(28.dp).clip(CircleShape).background(scheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text((place + 1).toString(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = scheme.onPrimary)
        }
        Surface(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .shakeOn(shakeTrigger, judged == false)
                .clip(shape)
                .clickable(role = Role.Button, onClick = onTap)
                .semantics { contentDescription = placeDesc }
                .stateText(stateDesc),
            shape = shape,
            color = container,
            border = BorderStroke(if (judged != null || selected) 2.dp else 1.dp, accent),
        ) {
            Row(
                Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(chip.narrator.primaryName, style = NarratorTextStyle)
                    if (qualifier != null) {
                        Text(qualifier, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                }
                if (judged != null) {
                    Icon(if (judged) Icons.Filled.Check else Icons.Filled.Close, contentDescription = null, tint = accent)
                }
                IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.order_move_up))
                }
                IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.order_move_down))
                }
            }
        }
    }
}

@Composable
private fun ResultCard(result: OrderChainResult) {
    val colors = MutqanTheme.colors
    val container = when {
        result.revealed -> MaterialTheme.colorScheme.secondaryContainer
        result.rating == ReviewRating.AGAIN -> colors.wrongContainer
        result.rating == ReviewRating.HARD -> MaterialTheme.colorScheme.secondaryContainer
        else -> colors.correctContainer
    }
    val titleRes = if (result.revealed) R.string.order_result_revealed else R.string.order_result_correct
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = MaterialTheme.colorScheme.onSurface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(titleRes), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(
                    when (result.rating) {
                        ReviewRating.GOOD, ReviewRating.EASY -> R.string.builder_result_good
                        ReviewRating.HARD -> R.string.builder_result_hard
                        ReviewRating.AGAIN -> R.string.builder_result_again
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (result.missedNames.isNotEmpty()) {
                Text(
                    stringResource(R.string.order_misplaced, result.missedNames.joinToString(stringResource(R.string.list_separator))),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** The check, the verdict and the way out stay pinned, so a long chain never pushes them off screen. */
@Composable
private fun OrderBottomPanel(ui: OrderChainUi, onEvent: (OrderChainEvent) -> Unit, onClose: () -> Unit) {
    val verdict = ui.chain.verdict
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (ui.result != null) {
                ResultCard(ui.result)
            } else if (verdict != null && !verdict.isCorrect) {
                Text(
                    stringResource(R.string.order_result_partial, verdict.correctCount, verdict.correctPlaces.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutqanTheme.colors.wrong,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ui.result == null) {
                    Button(
                        onClick = { onEvent(OrderChainEvent.Verify) },
                        enabled = !ui.isSubmitting,
                        modifier = Modifier.weight(2f),
                    ) { Text(stringResource(R.string.order_verify)) }
                    TextButton(
                        onClick = { onEvent(OrderChainEvent.Reveal) },
                        enabled = !ui.isSubmitting,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.order_reveal)) }
                } else {
                    Button(onClick = { onEvent(OrderChainEvent.NewRound) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.order_new_round))
                    }
                    OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.action_back_home))
                    }
                }
            }
        }
    }
}
