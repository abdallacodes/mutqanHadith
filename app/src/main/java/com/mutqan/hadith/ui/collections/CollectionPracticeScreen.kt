package com.mutqan.hadith.ui.collections

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mutqan.hadith.R
import com.mutqan.hadith.ui.common.InfoChip
import com.mutqan.hadith.ui.common.LayerLockSheet
import com.mutqan.hadith.ui.common.MutqanTopBar
import com.mutqan.hadith.ui.common.UiState
import com.mutqan.hadith.ui.common.UiStateHost
import com.mutqan.hadith.ui.common.contentWidth
import com.mutqan.hadith.ui.review.CardFrame
import com.mutqan.hadith.ui.review.CardPane
import com.mutqan.hadith.ui.review.CardStage

@Composable
fun CollectionPracticeRoute(viewModel: CollectionPracticeViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectionPracticeScreen(state, viewModel::onEvent, viewModel::load, onClose)
}

/**
 * The «ورد مخصص»: a collection's hadiths one after another as in a review, for the user to recall and then say whether
 * they knew each one. It says so in plain words that none of it is recorded: the schedule is not touched.
 */
@Composable
fun CollectionPracticeScreen(
    state: UiState<PracticeSession>,
    onEvent: (PracticeEvent) -> Unit,
    onRetry: () -> Unit,
    onClose: () -> Unit,
) {
    val session = (state as? UiState.Success)?.data
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            MutqanTopBar(
                title = stringResource(R.string.practice_title),
                onBack = onClose,
                actions = {
                    if (session != null && !session.isFinished) {
                        Text(
                            stringResource(R.string.review_progress, session.index + 1, session.deck.size),
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            UiStateHost(state, onRetry, Modifier.contentWidth()) { loaded ->
                PracticeContent(loaded, onEvent, onClose, Modifier.contentWidth())
            }
        }
    }
}

@Composable
private fun PracticeContent(session: PracticeSession, onEvent: (PracticeEvent) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val card = session.current
    if (card == null) {
        if (session.deck.isEmpty()) EmptyPractice(onClose, modifier) else PracticeDone(session, onEvent, onClose, modifier)
        return
    }
    var explainingLock by rememberSaveable { mutableStateOf(false) }
    if (explainingLock) LayerLockSheet(onDismiss = { explainingLock = false })
    Column(modifier.fillMaxSize()) {
        Text(
            if (session.round > 1) {
                stringResource(R.string.practice_round, session.round) + " · " + session.title
            } else {
                session.title
            },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { session.index.toFloat() / session.deck.size },
            modifier = Modifier.fillMaxWidth(),
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        Box(Modifier.weight(1f)) {
            AnimatedContent(
                targetState = CardFrame(
                    card, session.stage, emptySet(), null, session.hideTashkeel, session.termsRevealed, session.shownHints, session.canHint,
                    practice = true,
                ),
                contentKey = { it.card.hadithId },
                transitionSpec = {
                    (slideIntoContainer(SlideDirection.Start, tween(380)) + fadeIn(tween(380))) togetherWith
                        (slideOutOfContainer(SlideDirection.Start, tween(380)) + fadeOut(tween(200)))
                },
                label = "practice-card",
            ) { frame ->
                CardPane(
                    frame,
                    onReveal = { onEvent(PracticeEvent.Reveal) },
                    onToggleMissed = {},
                    onToggleTashkeel = { onEvent(PracticeEvent.ToggleTashkeel) },
                    onRevealTerms = { onEvent(PracticeEvent.RevealTerms) },
                    onHint = { onEvent(PracticeEvent.Hint) },
                    onExplainLock = { explainingLock = true },
                    onSanadBuilder = {},
                    onMissingLink = {},
                    onOrderChain = {},
                )
            }
        }
        PracticePanel(session, onEvent)
    }
}

/** Under the card: a button to turn it over, then the two answers; and, always, that nothing here is recorded. */
@Composable
private fun PracticePanel(session: PracticeSession, onEvent: (PracticeEvent) -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 8.dp, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (session.canJudge) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { onEvent(PracticeEvent.NeedsLook) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.practice_needs_look), textAlign = TextAlign.Center)
                    }
                    Button(onClick = { onEvent(PracticeEvent.Recalled) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.practice_recalled), textAlign = TextAlign.Center)
                    }
                }
            } else {
                Button(onClick = { onEvent(PracticeEvent.Reveal) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(if (session.stage == CardStage.RECALL) R.string.practice_reveal_matn else R.string.practice_reveal_sanad))
                }
            }
            Text(
                stringResource(R.string.practice_free_note),
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The round is over: how it went, the ones worth another look, and a calm word that the schedule is as it was. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PracticeDone(session: PracticeSession, onEvent: (PracticeEvent) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val total = session.deck.size
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.practice_done_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.practice_done_body, pluralStringResource(R.plurals.hadith_count, total, total), session.title),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            if (session.needsLook.isEmpty()) {
                stringResource(R.string.practice_all_known)
            } else {
                stringResource(R.string.practice_tally, session.recalled, session.needsLook.size)
            },
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        if (session.needsLook.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                session.deck.filter { it.hadithId in session.needsLook }.forEach {
                    InfoChip("${it.sourceBook} · ${stringResource(R.string.hadith_number, it.hadithNumber)}")
                }
            }
            Button(onClick = { onEvent(PracticeEvent.PracticeAgain) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.practice_again, session.needsLook.size), textAlign = TextAlign.Center)
            }
        }
        Text(
            stringResource(R.string.practice_nothing_changed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.practice_back)) }
    }
}

/** A collection with no hadiths has nothing to practise; say so warmly and go back. */
@Composable
private fun EmptyPractice(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(stringResource(R.string.practice_empty_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.practice_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        OutlinedButton(onClick = onClose) { Text(stringResource(R.string.practice_back)) }
    }
}
