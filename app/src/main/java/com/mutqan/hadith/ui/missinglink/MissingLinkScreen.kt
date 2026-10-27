package com.mutqan.hadith.ui.missinglink

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.drill.DrillChoice
import com.mutqan.hadith.ui.common.ChainNode
import com.mutqan.hadith.ui.common.ChainNodeState
import com.mutqan.hadith.ui.common.narratorOpener
import com.mutqan.hadith.ui.common.MatnAnchorCard
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.UiStateHost
import com.mutqan.hadith.ui.common.contentWidth
import com.mutqan.hadith.ui.common.shakeOn
import com.mutqan.hadith.ui.common.stateText
import com.mutqan.hadith.ui.theme.MutqanTheme
import com.mutqan.hadith.ui.theme.NarratorTextStyle
import kotlin.math.roundToInt

@Composable
fun MissingLinkRoute(viewModel: MissingLinkViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MissingLinkScreen(state, viewModel::onEvent, viewModel::load, onClose)
}

@Composable
fun MissingLinkScreen(
    state: UiState<MissingLinkUi>,
    onEvent: (MissingLinkEvent) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val ui = (state as? UiState.Success)?.data
    val snackbar = remember { SnackbarHostState() }
    val errorText = stringResource(R.string.review_submit_error)
    // A buzz to go with the green or the red, for a student who answers without looking at the screen.
    val haptics = LocalHapticFeedback.current
    val verdict = ui?.isCorrect
    LaunchedEffect(ui?.roundsPlayed, verdict) {
        when (verdict) {
            true -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            false -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            null -> Unit
        }
    }
    val submitError = ui?.submitError
    LaunchedEffect(submitError) {
        if (submitError != null) {
            snackbar.showSnackbar(errorText)
            onEvent(MissingLinkEvent.DismissError)
        }
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { MutqanTopBar(stringResource(R.string.missing_title), onBack = onClose) },
        bottomBar = { if (ui != null) MissingLinkBottomPanel(ui, onEvent, onClose) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            UiStateHost(state, onRetry, Modifier.contentWidth()) { data ->
                MissingLinkContent(data, onEvent, Modifier.contentWidth())
            }
        }
    }
}

@Composable
fun MissingLinkContent(ui: MissingLinkUi, onEvent: (MissingLinkEvent) -> Unit, modifier: Modifier = Modifier) {
    val round = ui.round
    // A long chain can push the hidden node off screen; scroll it back into view for every new question.
    val maskedNode = remember { BringIntoViewRequester() }
    LaunchedEffect(round) { maskedNode.bringIntoView() }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        MatnAnchorCard(ui.sourceBook, ui.hadithNumber, ui.matn)

        Text(stringResource(R.string.missing_instruction), style = MaterialTheme.typography.titleMedium)

        Column {
            round.chain.forEachIndexed { i, link ->
                val masked = i == round.maskedIndex
                val revealed = masked && ui.isAnswered
                ChainNode(
                    position = i + 1,
                    name = if (masked && !revealed) null else link.narrator.primaryName,
                    fullName = if (masked && !revealed) null else link.narrator.fullName,
                    tier = if (masked && !revealed) null else link.narrator.tier,
                    term = link.transmissionTerm,
                    isFirst = i == 0,
                    isLast = i == round.chain.lastIndex,
                    // Once answered the node shows the true narrator, so it is green whatever was tapped.
                    state = when {
                        !masked -> ChainNodeState.Normal
                        revealed -> ChainNodeState.Correct
                        else -> ChainNodeState.Masked
                    },
                    modifier = if (masked) Modifier.bringIntoViewRequester(maskedNode) else Modifier,
                    // The hidden narrator is the answer: his page opens only once the question has been answered.
                    onOpenNarrator = narratorOpener(link.narrator.id.takeIf { !masked || revealed }),
                )
            }
        }

    }
}

/** The four candidates as a 2×2 grid; the cell count follows [MissingLinkRound.options]. */
@Composable
private fun OptionsGrid(ui: MissingLinkUi, onChoose: (Long) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ui.round.options.chunked(2).forEach { rowOptions ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                rowOptions.forEach { option ->
                    OptionCell(ui, option, Modifier.weight(1f)) { onChoose(option.id) }
                }
                if (rowOptions.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

private enum class OptionState { Idle, Correct, Wrong, Dimmed }

@Composable
private fun OptionCell(ui: MissingLinkUi, option: DrillChoice, modifier: Modifier, onClick: () -> Unit) {
    val colors = MutqanTheme.colors
    val scheme = MaterialTheme.colorScheme
    val isAnswer = option.id == ui.round.answer.id
    val state = when {
        !ui.isAnswered -> OptionState.Idle
        isAnswer -> OptionState.Correct
        option.id == ui.chosenId -> OptionState.Wrong
        else -> OptionState.Dimmed
    }
    val accent by animateColorAsState(
        when (state) {
            OptionState.Correct -> colors.correct
            OptionState.Wrong -> colors.wrong
            else -> scheme.outlineVariant
        },
        label = "option-accent",
    )
    val container = when (state) {
        OptionState.Correct -> colors.correctContainer.copy(alpha = 0.6f)
        OptionState.Wrong -> colors.wrongContainer.copy(alpha = 0.6f)
        else -> scheme.surfaceContainerLowest
    }
    val stateDesc = when (state) {
        OptionState.Correct -> stringResource(R.string.builder_correct_desc)
        OptionState.Wrong -> stringResource(R.string.builder_wrong_desc)
        else -> null
    }
    val shape = RoundedCornerShape(14.dp)
    Surface(
        modifier = modifier
            .heightIn(min = 64.dp)
            .shakeOn(ui.roundsPlayed, state == OptionState.Wrong)
            .clip(shape)
            .clickable(enabled = !ui.isAnswered, role = Role.Button, onClick = onClick)
            .stateText(stateDesc),
        shape = shape,
        color = container,
        border = BorderStroke(if (state == OptionState.Correct || state == OptionState.Wrong) 2.dp else 1.dp, accent),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                option.name,
                style = NarratorTextStyle,
                color = if (state == OptionState.Dimmed) scheme.onSurfaceVariant else scheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            when (state) {
                OptionState.Correct -> Icon(Icons.Filled.Check, contentDescription = null, tint = accent)
                OptionState.Wrong -> Icon(Icons.Filled.Close, contentDescription = null, tint = accent)
                else -> Unit
            }
        }
    }
}

@Composable
private fun Feedback(ui: MissingLinkUi) {
    val colors = MutqanTheme.colors
    val correct = ui.isCorrect == true
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (correct) colors.correctContainer else colors.wrongContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val round = ui.round
            Text(
                if (correct) {
                    stringResource(R.string.missing_correct)
                } else {
                    stringResource(R.string.missing_wrong, round.answer.name)
                },
                style = MaterialTheme.typography.titleMedium,
            )
            // The man by his canonical name, and where he stands in this chain.
            if (correct) Text(stringResource(R.string.missing_answer_name, round.answer.name), style = MaterialTheme.typography.bodyLarge)
            round.narratedFrom?.let { Text(stringResource(R.string.missing_relation_from, it.name), style = MaterialTheme.typography.bodyMedium) }
            round.narratedTo?.let { Text(stringResource(R.string.missing_relation_to, it.name), style = MaterialTheme.typography.bodyMedium) }
            if (round.isCollectorsTeacher) Text(stringResource(R.string.missing_relation_collector), style = MaterialTheme.typography.bodyMedium)
            if (round.isLast) Text(stringResource(R.string.missing_relation_first), style = MaterialTheme.typography.bodyMedium)
            ui.outcome?.let { outcome ->
                Text(
                    stringResource(
                        R.string.missing_link_stability,
                        (outcome.retentionRate * 100).roundToInt(),
                        outcome.successCount,
                        outcome.successCount + outcome.errorCount,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                stringResource(R.string.missing_score, ui.correctCount, ui.roundsPlayed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Options, feedback and buttons stay pinned, so the question is answerable without scrolling the chain. */
@Composable
private fun MissingLinkBottomPanel(ui: MissingLinkUi, onEvent: (MissingLinkEvent) -> Unit, onClose: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (ui.isAnswered) Feedback(ui)
            OptionsGrid(ui, onChoose = { onEvent(MissingLinkEvent.Choose(it)) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ui.isAnswered) {
                    Button(onClick = { onEvent(MissingLinkEvent.Next) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.missing_next))
                    }
                }
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.action_back_home))
                }
            }
        }
    }
}
