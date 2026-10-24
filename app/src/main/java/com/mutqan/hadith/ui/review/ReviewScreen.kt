package com.mutqan.hadith.ui.review

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.ui.common.ChainNode
import com.mutqan.hadith.ui.common.ChainNodeState
import com.mutqan.hadith.ui.common.InfoChip
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.UiStateHost
import com.mutqan.hadith.ui.common.contentWidth
import com.mutqan.hadith.ui.theme.MatnTextStyle
import com.mutqan.hadith.ui.theme.MutqanTheme

@Composable
fun ReviewRoute(viewModel: ReviewViewModel, mode: ReviewMode, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReviewScreen(state, mode, viewModel::onEvent, viewModel::load, onClose)
}

@Composable
fun ReviewScreen(
    state: UiState<ReviewSession>,
    mode: ReviewMode,
    onEvent: (ReviewEvent) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    val errorText = stringResource(R.string.review_submit_error)
    val submitError = (state as? UiState.Success)?.data?.submitError
    LaunchedEffect(submitError) {
        if (submitError != null) {
            snackbar.showSnackbar(errorText)
            onEvent(ReviewEvent.DismissError)
        }
    }

    val title = stringResource(
        when (mode) {
            ReviewMode.DUE -> R.string.review_title_due
            ReviewMode.NEW -> R.string.review_title_new
            ReviewMode.SINGLE -> R.string.review_title_single
        },
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            MutqanTopBar(
                title = title,
                onBack = onClose,
                actions = {
                    val session = (state as? UiState.Success)?.data
                    if (session != null && !session.isFinished) {
                        Text(
                            stringResource(R.string.review_progress, session.index + 1, session.queue.size),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            UiStateHost(state, onRetry, Modifier.contentWidth()) { session ->
                ReviewContent(session, onEvent, onClose, Modifier.contentWidth())
            }
        }
    }
}

/** Everything the card area needs; a value type so [AnimatedContent] can freeze the outgoing card. */
private data class CardFrame(val card: ReviewCard, val stage: CardStage, val missed: Set<Long>)

@Composable
fun ReviewContent(session: ReviewSession, onEvent: (ReviewEvent) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val card = session.current
    if (card == null) {
        FinishedPanel(session, onClose, modifier)
        return
    }
    Column(modifier.fillMaxSize()) {
        LinearProgressIndicator(
            progress = { session.index.toFloat() / session.queue.size },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = CardFrame(card, session.stage, session.missedNarratorIds),
                contentKey = { it.card.hadithId },
                transitionSpec = {
                    (slideIntoContainer(SlideDirection.Start, tween(380)) + fadeIn(tween(380))) togetherWith
                        (slideOutOfContainer(SlideDirection.Start, tween(380)) + fadeOut(tween(200)))
                },
                label = "review-card",
            ) { frame ->
                CardPane(frame, onReveal = { onEvent(ReviewEvent.Reveal) }, onToggleMissed = { onEvent(ReviewEvent.ToggleMissed(it)) })
            }
        }
        RatingPanel(
            session = session,
            isLast = session.index == session.queue.lastIndex,
            onMatn = { onEvent(ReviewEvent.RateMatn(it)) },
            onSanad = { onEvent(ReviewEvent.RateSanad(it)) },
            onSubmit = { onEvent(ReviewEvent.Submit) },
        )
    }
}

@Composable
private fun CardPane(frame: CardFrame, onReveal: () -> Unit, onToggleMissed: (Long) -> Unit) {
    val (card, stage, missed) = frame
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            InfoChip(card.sourceBook)
            InfoChip(
                stringResource(R.string.hadith_number, card.hadithNumber),
                container = MaterialTheme.colorScheme.primaryContainer,
                content = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            InfoChip(card.grade, container = MaterialTheme.colorScheme.surfaceVariant, content = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = stage != CardStage.SANAD, onClick = onReveal),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimatedVisibility(stage == CardStage.RECALL, enter = fadeIn(), exit = fadeOut() + shrinkVertically()) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.review_recall_prompt),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                AnimatedVisibility(stage != CardStage.RECALL, enter = fadeIn() + expandVertically(), exit = fadeOut()) {
                    Text(
                        card.matn,
                        style = MatnTextStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                AnimatedVisibility(stage == CardStage.MATN, enter = fadeIn(), exit = fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider()
                        Text(
                            stringResource(R.string.review_reveal_sanad),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                AnimatedVisibility(stage == CardStage.SANAD, enter = fadeIn() + expandVertically(), exit = fadeOut()) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        HorizontalDivider()
                        Text(stringResource(R.string.review_sanad_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.review_sanad_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        card.links.forEachIndexed { i, link ->
                            ChainNode(
                                position = i + 1,
                                name = link.narrator.primaryName,
                                fullName = link.narrator.fullName,
                                tier = link.narrator.tier,
                                term = link.transmissionTerm,
                                isFirst = i == 0,
                                isLast = i == card.links.lastIndex,
                                state = if (link.narrator.id in missed) ChainNodeState.Missed else ChainNodeState.Normal,
                                onClick = { onToggleMissed(link.narrator.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RatingPanel(
    session: ReviewSession,
    isLast: Boolean,
    onMatn: (ReviewRating) -> Unit,
    onSanad: (ReviewRating) -> Unit,
    onSubmit: () -> Unit,
) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // A component can only be rated once it has been shown, so an unseen card cannot be graded.
            RatingRow(stringResource(R.string.badge_matn), session.matnRating, onMatn, enabled = session.stage != CardStage.RECALL)
            RatingRow(stringResource(R.string.badge_sanad), session.sanadRating, onSanad, enabled = session.stage == CardStage.SANAD)
            Button(onClick = onSubmit, enabled = session.canSubmit, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (isLast) R.string.action_finish else R.string.action_next))
            }
        }
    }
}

@Composable
fun RatingRow(
    label: String,
    selected: ReviewRating?,
    onSelect: (ReviewRating) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(52.dp))
        ReviewRating.entries.forEach { rating ->
            RatingButton(rating, selected == rating, enabled, { onSelect(rating) }, Modifier.weight(1f))
        }
    }
}

@Composable
private fun RatingButton(rating: ReviewRating, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MutqanTheme.colors
    val accent = when (rating) {
        ReviewRating.AGAIN -> colors.wrong
        ReviewRating.HARD -> colors.masteryMid
        ReviewRating.GOOD -> colors.masteryHigh
        ReviewRating.EASY -> colors.correct
    }
    val label = stringResource(
        when (rating) {
            ReviewRating.AGAIN -> R.string.rating_again
            ReviewRating.HARD -> R.string.rating_hard
            ReviewRating.GOOD -> R.string.rating_good
            ReviewRating.EASY -> R.string.rating_easy
        },
    )
    Surface(
        modifier = modifier
            .heightIn(min = 44.dp)
            .alpha(if (enabled) 1f else 0.4f)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceContainerLowest,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Box(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FinishedPanel(session: ReviewSession, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        if (session.queue.isEmpty()) {
            Text(
                stringResource(if (session.mode == ReviewMode.NEW) R.string.review_empty_new else R.string.review_empty_due),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(stringResource(R.string.review_done_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.review_done_body, session.queue.size),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        }
        Button(onClick = onClose) { Text(stringResource(R.string.action_back_home)) }
    }
}
