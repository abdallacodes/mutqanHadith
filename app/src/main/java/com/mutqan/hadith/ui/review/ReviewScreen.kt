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
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.data.text.ArabicNormalizer
import com.mutqan.hadith.data.relation.SanadLink
import com.mutqan.hadith.domain.drill.SanadDrillPolicy
import com.mutqan.hadith.domain.model.LayerStatus
import com.mutqan.hadith.domain.model.MemorizationLayer
import com.mutqan.hadith.domain.model.ReviewRating
import com.mutqan.hadith.domain.usecase.ChainHeatmap
import com.mutqan.hadith.ui.common.ChainNode
import com.mutqan.hadith.ui.common.ChainNodeState
import com.mutqan.hadith.ui.common.InfoChip
import com.mutqan.hadith.ui.common.LayerBadge
import com.mutqan.hadith.ui.common.LayerLockSheet
import com.mutqan.hadith.ui.common.LockedHint
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.SanadDrillButtons
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.UiStateHost
import com.mutqan.hadith.ui.common.contentWidth
import com.mutqan.hadith.ui.common.nameRes
import com.mutqan.hadith.ui.heatmap.ChainHeatmapView
import com.mutqan.hadith.ui.theme.MatnTextStyle
import com.mutqan.hadith.ui.theme.MutqanTheme

@Composable
fun ReviewRoute(
    viewModel: ReviewViewModel,
    mode: ReviewMode,
    onClose: () -> Unit,
    onSanadBuilder: (Long) -> Unit = {},
    onMissingLink: (Long) -> Unit = {},
    onOrderChain: (Long) -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReviewScreen(state, mode, viewModel::onEvent, viewModel::load, onClose, onSanadBuilder, onMissingLink, onOrderChain)
}

@Composable
fun ReviewScreen(
    state: UiState<ReviewSession>,
    mode: ReviewMode,
    onEvent: (ReviewEvent) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
    /** Opens the Sanad builder for a Hadith; offered once its Sanad tree is open. */
    onSanadBuilder: (Long) -> Unit = {},
    onMissingLink: (Long) -> Unit = {},
    onOrderChain: (Long) -> Unit = {},
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
            ReviewMode.NARRATOR -> R.string.review_title_narrator
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
                ReviewContent(session, onEvent, onClose, Modifier.contentWidth(), onSanadBuilder, onMissingLink, onOrderChain)
            }
        }
    }
}

@Composable
fun ReviewContent(
    session: ReviewSession,
    onEvent: (ReviewEvent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onSanadBuilder: (Long) -> Unit = {},
    onMissingLink: (Long) -> Unit = {},
    onOrderChain: (Long) -> Unit = {},
) {
    val card = session.current
    if (card == null) {
        if (session.isBatchBreak) BatchBreakPanel(session, onEvent, onClose, modifier) else FinishedPanel(session, onClose, modifier)
        return
    }
    var explainingLock by rememberSaveable { mutableStateOf(false) }
    if (explainingLock) LayerLockSheet(onDismiss = { explainingLock = false })
    Column(modifier.fillMaxSize()) {
        if (session.isBatched) BatchLabel(session)
        LinearProgressIndicator(
            progress = { session.index.toFloat() / session.queue.size },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        session.unlock?.let { unlock ->
            val links = session.queue.firstOrNull { it.hadithId == unlock.hadithId }?.links.orEmpty()
            UnlockBanner(unlock, links, { onEvent(ReviewEvent.DismissUnlock) }, onSanadBuilder, onMissingLink, onOrderChain)
        }
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = CardFrame(
                    card, session.stage, session.missedNarratorIds, session.heatmaps[card.hadithId], session.hideTashkeel, session.termsRevealed,
                    session.shownHints, session.canHint,
                ),
                contentKey = { it.card.hadithId },
                transitionSpec = {
                    (slideIntoContainer(SlideDirection.Start, tween(380)) + fadeIn(tween(380))) togetherWith
                        (slideOutOfContainer(SlideDirection.Start, tween(380)) + fadeOut(tween(200)))
                },
                label = "review-card",
            ) { frame ->
                CardPane(
                    frame,
                    onReveal = { onEvent(ReviewEvent.Reveal) },
                    onToggleMissed = { onEvent(ReviewEvent.ToggleMissed(it)) },
                    onToggleTashkeel = { onEvent(ReviewEvent.ToggleTashkeel) },
                    onRevealTerms = { onEvent(ReviewEvent.RevealTerms) },
                    onHint = { onEvent(ReviewEvent.Hint) },
                    onExplainLock = { explainingLock = true },
                    onSanadBuilder = onSanadBuilder,
                    onMissingLink = onMissingLink,
                    onOrderChain = onOrderChain,
                )
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

/** "The Sanad tree is unlocked": a layer was just opened by the previous review. */
@Composable
private fun UnlockBanner(
    unlock: LayerUnlock,
    links: List<SanadLink>,
    onDismiss: () -> Unit,
    onSanadBuilder: (Long) -> Unit,
    onMissingLink: (Long) -> Unit,
    onOrderChain: (Long) -> Unit,
) {
    val colors = MutqanTheme.colors
    val last = unlock.layer == MemorizationLayer.LAYER_3_TRANSMISSION_TERMS
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        color = colors.correctContainer,
        border = BorderStroke(1.dp, colors.correct),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(if (last) R.string.layer_unlock_title_3 else R.string.layer_unlock_title_2),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                stringResource(if (last) R.string.layer_unlock_body_3 else R.string.layer_unlock_body_2, unlock.hadithNumber),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (!last) {
                // The layer that was just opened is the Sanad tree, so only the chain decides which drills there are.
                val open = SanadDrillPolicy.openDrills(LayerStatus(unlock.layer, 0f), links)
                SanadDrillButtons(unlock.hadithId, open, onSanadBuilder, onMissingLink, onOrderChain)
            }
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.layer_unlock_dismiss)) }
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
            // The chain is only graded once its layer is open; before that a card is rated on its Matn alone.
            if (session.current?.activeLayer?.opensSanad == true) {
                RatingRow(stringResource(R.string.badge_sanad), session.sanadRating, onSanad, enabled = session.stage == CardStage.SANAD)
            }
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
                stringResource(
                    when {
                        session.notStarted -> R.string.review_not_started
                        session.mode == ReviewMode.NEW -> R.string.review_empty_new
                        session.mode == ReviewMode.NARRATOR ->
                            if (session.lockedSkipped > 0) R.string.review_empty_narrator_locked else R.string.review_empty_narrator
                        else -> R.string.review_empty_due
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(stringResource(R.string.review_done_title), style = MaterialTheme.typography.headlineMedium)
            Text(
                stringResource(R.string.review_done_body, session.totalReviewed),
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (session.unlocks.isNotEmpty()) UnlockSummary(session.unlocks)
        }
        Button(onClick = onClose) { Text(stringResource(R.string.action_back_home)) }
    }
}

/** Which batch of a long backlog is on, and how big it is. */
@Composable
private fun BatchLabel(session: ReviewSession) {
    Text(
        stringResource(
            R.string.review_batch_label,
            session.batchNumber,
            session.batchCount,
            pluralStringResource(R.plurals.hadith_count, session.queue.size, session.queue.size),
        ),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Between two batches: a small success, and the choice to go on or to stop for today. */
@Composable
private fun BatchBreakPanel(session: ReviewSession, onEvent: (ReviewEvent) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val next = session.laterBatches.first().size
    val left = session.laterBatches.sumOf { it.size }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            stringResource(R.string.review_batch_done_title, session.batchNumber, session.batchCount),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.review_batch_done_body),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(R.string.review_batch_left, pluralStringResource(R.plurals.hadith_count, left, left)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (session.unlocks.isNotEmpty()) UnlockSummary(session.unlocks)
        Button(onClick = { onEvent(ReviewEvent.NextBatch) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.review_batch_next, pluralStringResource(R.plurals.hadith_count, next, next)))
        }
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.review_batch_stop))
        }
    }
}

/** Every layer this session opened, so the last review's unlock is not lost when the session ends. */
@Composable
private fun UnlockSummary(unlocks: List<LayerUnlock>) {
    val colors = MutqanTheme.colors
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = colors.correctContainer,
        border = BorderStroke(1.dp, colors.correct),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(R.string.layer_unlock_summary_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            unlocks.forEach {
                Text(
                    stringResource(R.string.layer_unlock_summary_item, it.hadithNumber, stringResource(it.layer.nameRes)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
